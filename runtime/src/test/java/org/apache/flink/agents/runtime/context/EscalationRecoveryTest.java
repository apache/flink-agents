/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.agents.runtime.context;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.TokenUsage;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.chat.model.routing.Escalation;
import org.apache.flink.agents.api.chat.model.routing.QualityContext;
import org.apache.flink.agents.api.chat.model.routing.QualitySignal;
import org.apache.flink.agents.api.chat.model.routing.QualitySignals;
import org.apache.flink.agents.api.chat.model.routing.QualityVerdict;
import org.apache.flink.agents.api.decision.BaseDecisionModelSetup;
import org.apache.flink.agents.api.decision.DecisionRequest;
import org.apache.flink.agents.api.decision.DecisionResult;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ModelRoutingEvent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.ChatModelAction;
import org.apache.flink.agents.plan.routing.ModelRouter;
import org.apache.flink.agents.runtime.ResourceCache;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateSerde;
import org.apache.flink.agents.runtime.actionstate.CallResult;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.async.ContinuationContext;
import org.apache.flink.agents.runtime.memory.CachedMemoryStore;
import org.apache.flink.agents.runtime.memory.ForTestMemoryMapState;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.agents.runtime.utils.RuntimeIdentity;
import org.apache.flink.api.common.serialization.SerializerConfigImpl;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.apache.flink.agents.api.chat.model.routing.ModelRouter.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies quality-triggered escalation against the runtime's durable journal and recovery
 * serializer. A tiered router without a strategy journals one escalated request as four records:
 * {@code route:router}, {@code chat:router:small}, the verdict ({@code escalate:router:small} for a
 * decision-model signal, {@code escalate:router:small:0} for a custom signal) and {@code
 * chat:router:big}. The tests kill the worker right after the verdict record is persisted and check
 * that recovery replays the stored verdict instead of asking the verifier (or the custom signal)
 * again, calls the stronger tier exactly once, and reports the same {@code model_routing} block as
 * an uninterrupted run; a journal of all four records replays without any provider call.
 */
class EscalationRecoveryTest {
    private static final String FAILURE = "simulated worker failure after persistence";
    private static final long SMALL_PROMPT_TOKENS = 30L;
    private static final long SMALL_COMPLETION_TOKENS = 12L;

    /** Escalates the literal answer {@code draft}; counts its evaluations across instances. */
    public static final class DraftSignal implements QualitySignal {
        static final AtomicInteger EVALUATIONS = new AtomicInteger();

        @Override
        public QualityVerdict evaluate(QualityContext context) {
            EVALUATIONS.incrementAndGet();
            return "draft".equals(context.getResponse().getText())
                    ? QualityVerdict.escalate("answer is a draft")
                    : QualityVerdict.accept();
        }
    }

    private static final Escalation.Signal DECISION_SIGNAL =
            QualitySignals.decision("verifier", 0.7);
    private static final Escalation.Signal CUSTOM_SIGNAL = QualitySignals.custom(DraftSignal.class);

    private final AtomicReference<byte[]> persisted = new AtomicReference<>();
    private final AtomicInteger verifierCalls = new AtomicInteger();
    private final AtomicInteger smallCalls = new AtomicInteger();
    private final AtomicInteger bigCalls = new AtomicInteger();
    private ContinuationActionExecutor executor;
    private final ChatRequestEvent request =
            new ChatRequestEvent(
                    "router", List.of(new ChatMessage(MessageRole.USER, "write some sql")));

    @BeforeEach
    void resetCounters() {
        DraftSignal.EVALUATIONS.set(0);
    }

    @AfterEach
    void closeExecutor() {
        // A routed request must not leak an interrupt onto the calling thread.
        assertThat(Thread.interrupted()).as("interrupt leaked by the action").isFalse();
        if (executor != null) {
            executor.close();
        }
    }

    /**
     * The worker dies right after the verifier's verdict is journaled and before the stronger tier
     * is called. Recovery replays the stored small answer and the stored verdict without calling
     * either provider again, calls big once, and the response carries the same routing block as an
     * uninterrupted run.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restoreAfterTheVerdictRecordReplaysItWithoutAskingTheVerifierAgain(boolean async)
            throws Exception {
        Map<String, Object> expected = uninterruptedRoutingBlock(async, DECISION_SIGNAL);

        RunnerContextImpl first = context(new ActionState(request), async, 3, DECISION_SIGNAL);
        assertThatThrownBy(() -> runAction(request, first)).hasMessageContaining(FAILURE);
        assertThat(verifierCalls).hasValue(1);
        assertThat(smallCalls).hasValue(1);
        assertThat(bigCalls).hasValue(0);
        assertOnlyTheInitialRoutingDecisionWasEmitted(first);

        ActionState restored = ActionStateSerde.deserialize(persisted.get());
        assertThat(recordIds(restored))
                .containsExactly("route:router", "chat:router:small", "escalate:router:small");
        RunnerContextImpl replay = context(restored, async, Integer.MAX_VALUE, DECISION_SIGNAL);
        runAction(restored.getTaskEvent(), replay);

        assertThat(verifierCalls).hasValue(1);
        assertThat(smallCalls).hasValue(1);
        assertThat(bigCalls).hasValue(1);
        assertEscalated(replay, expected, "decision_model:verifier", 0.3);
    }

    /** A completed escalated request replays end to end without a provider or verifier call. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replaysACompletedEscalatedRequestWithoutAnyProviderCall(boolean async) throws Exception {
        RunnerContextImpl first =
                context(new ActionState(request), async, Integer.MAX_VALUE, DECISION_SIGNAL);
        runAction(request, first);
        assertThat(verifierCalls).hasValue(1);
        assertThat(smallCalls).hasValue(1);
        assertThat(bigCalls).hasValue(1);
        Map<String, Object> expected = routingBlock(first);
        assertEscalated(first, expected, "decision_model:verifier", 0.3);

        ActionState restored = ActionStateSerde.deserialize(persisted.get());
        assertThat(recordIds(restored))
                .containsExactly(
                        "route:router",
                        "chat:router:small",
                        "escalate:router:small",
                        "chat:router:big");
        RunnerContextImpl replay = context(restored, async, Integer.MAX_VALUE, DECISION_SIGNAL);
        runAction(restored.getTaskEvent(), replay);

        assertThat(verifierCalls).hasValue(1);
        assertThat(smallCalls).hasValue(1);
        assertThat(bigCalls).hasValue(1);
        assertEscalated(replay, expected, "decision_model:verifier", 0.3);
    }

    /**
     * The custom signal's verdict is journaled under {@code escalate:router:small:0}. After a crash
     * right behind that record the signal is not evaluated again, big answers once, and the block
     * matches the uninterrupted run; the completed journal then replays with no call at all.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restoreAfterACustomVerdictRecordReplaysItWithoutEvaluatingTheSignalAgain(boolean async)
            throws Exception {
        Map<String, Object> expected = uninterruptedRoutingBlock(async, CUSTOM_SIGNAL);
        assertThat(DraftSignal.EVALUATIONS).hasValue(1);
        DraftSignal.EVALUATIONS.set(0);

        RunnerContextImpl first = context(new ActionState(request), async, 3, CUSTOM_SIGNAL);
        assertThatThrownBy(() -> runAction(request, first)).hasMessageContaining(FAILURE);
        assertThat(DraftSignal.EVALUATIONS).hasValue(1);
        assertThat(smallCalls).hasValue(1);
        assertThat(bigCalls).hasValue(0);
        assertOnlyTheInitialRoutingDecisionWasEmitted(first);

        ActionState restored = ActionStateSerde.deserialize(persisted.get());
        assertThat(recordIds(restored))
                .containsExactly("route:router", "chat:router:small", "escalate:router:small:0");
        RunnerContextImpl replay = context(restored, async, Integer.MAX_VALUE, CUSTOM_SIGNAL);
        runAction(restored.getTaskEvent(), replay);

        assertThat(DraftSignal.EVALUATIONS).hasValue(1);
        assertThat(verifierCalls).hasValue(0);
        assertThat(smallCalls).hasValue(1);
        assertThat(bigCalls).hasValue(1);
        String signal = "custom:" + DraftSignal.class.getName();
        assertEscalated(replay, expected, signal, null);

        ActionState completed = ActionStateSerde.deserialize(persisted.get());
        assertThat(recordIds(completed))
                .containsExactly(
                        "route:router",
                        "chat:router:small",
                        "escalate:router:small:0",
                        "chat:router:big");
        RunnerContextImpl fullReplay = context(completed, async, Integer.MAX_VALUE, CUSTOM_SIGNAL);
        runAction(completed.getTaskEvent(), fullReplay);
        assertThat(DraftSignal.EVALUATIONS).hasValue(1);
        assertThat(smallCalls).hasValue(1);
        assertThat(bigCalls).hasValue(1);
        assertEscalated(fullReplay, expected, signal, null);
    }

    /** Runs the request to completion on a fresh journal and returns its routing block. */
    private Map<String, Object> uninterruptedRoutingBlock(boolean async, Escalation.Signal signal)
            throws Exception {
        RunnerContextImpl context =
                context(new ActionState(request), async, Integer.MAX_VALUE, signal);
        runAction(request, context);
        assertThat(smallCalls).hasValue(1);
        assertThat(bigCalls).hasValue(1);
        Map<String, Object> block = routingBlock(context);
        verifierCalls.set(0);
        smallCalls.set(0);
        bigCalls.set(0);
        persisted.set(null);
        return block;
    }

    /**
     * Before the crash the action had journaled the route and emitted its routing decision (source
     * {@code default}: a tiered router without a strategy starts on the first tier) and nothing
     * else: no escalation event, no response.
     */
    private static void assertOnlyTheInitialRoutingDecisionWasEmitted(RunnerContextImpl context) {
        assertThat(context.getPendingEvents()).hasSize(1);
        Event only = context.getPendingEvents().get(0);
        assertThat(only.getType()).isEqualTo(ModelRoutingEvent.EVENT_TYPE);
        ModelRoutingEvent routing = ModelRoutingEvent.fromEvent(only);
        assertThat(routing.getSelectedModel()).isEqualTo("small");
        assertThat(routing.getDecisionSource()).isEqualTo(ModelRoutingEvent.SOURCE_DEFAULT);
    }

    private static List<String> recordIds(ActionState state) {
        return state.getCallResults().stream()
                .map(CallResult::getFunctionId)
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> routingBlock(RunnerContextImpl context) {
        ChatResponseEvent response =
                event(context, ChatResponseEvent.EVENT_TYPE, ChatResponseEvent::fromEvent);
        assertThat(response.isFailed()).isFalse();
        return (Map<String, Object>) response.getAttr("model_routing");
    }

    /**
     * The response answered by big after one hop from small, with the routing block equal to {@code
     * expected} and the escalation event carrying the signal, the origin tier and the discarded
     * tokens of small's answer.
     */
    @SuppressWarnings("unchecked")
    private void assertEscalated(
            RunnerContextImpl context, Map<String, Object> expected, String signal, Double score) {
        ChatResponseEvent response =
                event(context, ChatResponseEvent.EVENT_TYPE, ChatResponseEvent::fromEvent);
        assertThat(response.getRequestId()).isEqualTo(request.getId());
        assertThat(response.isFailed()).isFalse();
        assertThat(response.getResponse().getText()).isEqualTo("final");

        Map<String, Object> routing = (Map<String, Object>) response.getAttr("model_routing");
        assertThat(routing).isEqualTo(expected);
        assertThat(routing)
                .containsEntry("selected_model", "small")
                .containsEntry("final_model", "big")
                .containsEntry(
                        ModelRoutingEvent.DECISION_SOURCE_KEY, ModelRoutingEvent.SOURCE_ESCALATION)
                .containsEntry("escalation_attempted", true)
                .containsEntry("fallback_attempted", false)
                .containsEntry("escalated_tokens", SMALL_PROMPT_TOKENS + SMALL_COMPLETION_TOKENS);
        List<Map<String, Object>> hops = (List<Map<String, Object>>) routing.get("escalations");
        assertThat(hops).hasSize(1);
        assertThat(hops.get(0))
                .containsEntry("from", "small")
                .containsEntry("to", "big")
                .containsEntry("signal", signal)
                .containsEntry("discarded_tokens", SMALL_PROMPT_TOKENS + SMALL_COMPLETION_TOKENS);
        if (score != null) {
            assertThat(hops.get(0)).containsEntry("score", score);
        }

        List<ModelRoutingEvent> escalations =
                context.getPendingEvents().stream()
                        .filter(e -> ModelRoutingEvent.EVENT_TYPE.equals(e.getType()))
                        .map(ModelRoutingEvent::fromEvent)
                        .filter(
                                e ->
                                        ModelRoutingEvent.SOURCE_ESCALATION.equals(
                                                e.getDecisionSource()))
                        .collect(Collectors.toList());
        assertThat(escalations).hasSize(1);
        ModelRoutingEvent escalation = escalations.get(0);
        assertThat(escalation.getRequestId()).isEqualTo(request.getId());
        assertThat(escalation.getSelectedModel()).isEqualTo("big");
        assertThat(escalation.getScore()).isEqualTo(score);
        assertThat(escalation.getMetadata())
                .containsEntry("signal", signal)
                .containsEntry("escalated_from", "small")
                .containsEntry("discarded_tokens", SMALL_PROMPT_TOKENS + SMALL_COMPLETION_TOKENS);
    }

    private static <T> T event(RunnerContextImpl context, String type, Function<Event, T> read) {
        return context.getPendingEvents().stream()
                .filter(e -> type.equals(e.getType()))
                .map(read)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no event of type " + type));
    }

    /** A verifier that rates every answer 0.3, below the 0.7 minimum. */
    private BaseDecisionModelSetup verifier() {
        BaseDecisionModelSetup model = mock(BaseDecisionModelSetup.class);
        when(model.supportsAsyncExecution()).thenReturn(true);
        when(model.decide(any(), anyMap()))
                .thenAnswer(
                        invocation -> {
                            verifierCalls.incrementAndGet();
                            DecisionRequest request = invocation.getArgument(0);
                            String question = request.getQuestions().keySet().iterator().next();
                            return new DecisionResult(
                                    Map.of(question, new DecisionResult.PredicateAnswer(0.3)),
                                    "fake-verifier",
                                    new DecisionResult.TokenUsage(5L, 1L, 6L),
                                    Map.of());
                        });
        return model;
    }

    private BaseChatModelSetup chatModel(
            String text, AtomicInteger calls, long promptTokens, long completionTokens) {
        return new BaseChatModelSetup(new ResourceDescriptor("fake", Map.of()), null) {
            @Override
            public Map<String, Object> getParameters() {
                return Map.of();
            }

            @Override
            public boolean willApplyNativeStructuredOutput(Object outputSchema) {
                return false;
            }

            @Override
            public ChatResult chat(
                    List<ChatMessage> messages,
                    Map<String, Object> promptArgs,
                    Map<String, Object> modelParams) {
                calls.incrementAndGet();
                return new ChatResult(
                        new ChatMessage(MessageRole.ASSISTANT, text),
                        "fake-" + text,
                        null,
                        new TokenUsage(promptTokens, completionTokens),
                        "stop",
                        Map.of());
            }
        };
    }

    private RunnerContextImpl context(
            ActionState state, boolean async, int failAfterCallResults, Escalation.Signal signal)
            throws Exception {
        AgentPlan plan = new AgentPlan(Map.of());
        plan.getConfig().set(AgentExecutionOptions.DECISION_ASYNC, async);
        plan.getConfig().set(AgentExecutionOptions.MAX_RETRIES, 1);
        plan.getConfig().set(AgentExecutionOptions.RETRY_WAIT_INTERVAL, Duration.ZERO);
        ModelRouter router =
                new ModelRouter(
                        of("small", "big")
                                .escalation(Escalation.tiers("small", "big").when(signal))
                                .build(),
                        null);
        // Build the stubs first: stubbing one mock while another stubbing is open is a misuse.
        BaseDecisionModelSetup verifier = verifier();
        BaseChatModelSetup small =
                chatModel("draft", smallCalls, SMALL_PROMPT_TOKENS, SMALL_COMPLETION_TOKENS);
        BaseChatModelSetup big = chatModel("final", bigCalls, 50L, 20L);
        ResourceCache resources = mock(ResourceCache.class);
        when(resources.hasResource("router", ResourceType.MODEL_ROUTER)).thenReturn(true);
        when(resources.getResource("router", ResourceType.MODEL_ROUTER)).thenReturn(router);
        when(resources.getResource("verifier", ResourceType.DECISION_MODEL)).thenReturn(verifier);
        when(resources.getResource("small", ResourceType.CHAT_MODEL)).thenReturn(small);
        when(resources.getResource("big", ResourceType.CHAT_MODEL)).thenReturn(big);
        FlinkAgentsMetricGroupImpl metrics =
                new FlinkAgentsMetricGroupImpl(
                        UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup());
        RunnerContextImpl context;
        if (async) {
            assumeTrue(Runtime.version().feature() >= 21, "async recovery needs JDK 21+");
            assertThat(ContinuationActionExecutor.isContinuationSupported()).isTrue();
            if (executor == null) {
                executor = new ContinuationActionExecutor(1);
            }
            JavaRunnerContextImpl asyncContext =
                    new JavaRunnerContextImpl(
                            metrics,
                            () -> {},
                            plan,
                            resources,
                            "test-job",
                            executor,
                            TypeInformation.of(Event.class)
                                    .createSerializer(new SerializerConfigImpl()));
            asyncContext.setContinuationContext(new ContinuationContext());
            context = asyncContext;
        } else {
            context =
                    new RunnerContextImpl(
                            metrics,
                            () -> {},
                            plan,
                            resources,
                            "test-job",
                            TypeInformation.of(Event.class)
                                    .createSerializer(new SerializerConfigImpl()));
        }
        context.setOutputIdentities(new RuntimeIdentity.OutputIdentityAllocator("test-execution"));
        context.actionName = "chat_model_action";
        // The chat action reads and writes sensory memory; back it with in-memory stores.
        context.memoryContext =
                new RunnerContextImpl.MemoryContext(
                        new CachedMemoryStore(new ForTestMemoryMapState<>()),
                        new CachedMemoryStore(new ForTestMemoryMapState<>()));
        context.durableExecutionContext =
                new RunnerContextImpl.DurableExecutionContext(
                        "test-key",
                        1L,
                        ChatModelAction.getChatModelAction(),
                        state.getTaskEvent(),
                        state,
                        (key, sequence, action, event, current) -> {
                            persisted.set(ActionStateSerde.serialize(current));
                            if (current.getCallResultCount() == failAfterCallResults) {
                                throw new IllegalStateException(FAILURE);
                            }
                        });
        return context;
    }

    private void runAction(Event event, RunnerContextImpl context) throws Exception {
        if (!(context instanceof JavaRunnerContextImpl)) {
            ChatModelAction.processChatRequestOrToolResponse(event, context);
            return;
        }
        JavaRunnerContextImpl asyncContext = (JavaRunnerContextImpl) context;
        AtomicReference<Exception> failure = new AtomicReference<>();
        Runnable action =
                () -> {
                    try {
                        ChatModelAction.processChatRequestOrToolResponse(event, context);
                    } catch (Exception error) {
                        failure.set(error);
                    }
                };
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!executor.executeAction(asyncContext.getContinuationContext(), action)) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.yield();
        }
        if (failure.get() != null) {
            throw failure.get();
        }
    }
}
