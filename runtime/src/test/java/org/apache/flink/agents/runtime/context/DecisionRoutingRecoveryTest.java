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
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.chat.model.routing.ModelRouter;
import org.apache.flink.agents.api.chat.model.routing.Strategies;
import org.apache.flink.agents.api.decision.BaseDecisionModelSetup;
import org.apache.flink.agents.api.decision.DecisionResult;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ModelRoutingEvent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.ChatModelAction;
import org.apache.flink.agents.runtime.ResourceCache;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateSerde;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.async.ContinuationContext;
import org.apache.flink.agents.runtime.memory.CachedMemoryStore;
import org.apache.flink.agents.runtime.memory.ForTestMemoryMapState;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies decision-model routing against the runtime's durable journal and recovery serializer:
 * the stored {@code decide:<router>} outcomes (a failed attempt and the choice) survive
 * serialization and are replayed positionally when the worker dies before the {@code
 * route:<router>} record exists.
 */
class DecisionRoutingRecoveryTest {
    private static final String FAILURE = "simulated worker failure after persistence";

    private final AtomicReference<byte[]> persisted = new AtomicReference<>();
    private final AtomicInteger decisionCalls = new AtomicInteger();
    private final AtomicInteger chatCalls = new AtomicInteger();
    private ContinuationActionExecutor executor;
    private final ChatRequestEvent request =
            new ChatRequestEvent(
                    "router", List.of(new ChatMessage(MessageRole.USER, "write some sql")));
    private final DecisionResult choice =
            new DecisionResult(
                    Map.of(
                            "model",
                            new DecisionResult.ChoiceAnswer(
                                    "big", Map.of("small", 0.2, "big", 0.8), 0.7)),
                    "fake-model",
                    new DecisionResult.TokenUsage(10L, 2L, 12L),
                    Map.of());

    @AfterEach
    void closeExecutor() {
        // A routed request must not leak an interrupt onto the calling thread.
        assertThat(Thread.interrupted()).as("interrupt leaked by the action").isFalse();
        if (executor != null) {
            executor.close();
        }
    }

    /**
     * The worker dies right after the second decision attempt is journaled (first attempt failed,
     * retry succeeded) and before the route record. Recovery replays both stored outcomes without
     * calling the provider, routes on the stored choice and answers with the selected model.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restoreBetweenTheDecisionCallAndTheRouteRecordReplaysTheStoredChoice(boolean async)
            throws Exception {
        RunnerContextImpl first = context(new ActionState(request), async, 2);
        assertThatThrownBy(() -> runAction(request, first)).hasMessageContaining(FAILURE);
        assertThat(decisionCalls).hasValue(2);
        assertThat(chatCalls).hasValue(0);
        assertThat(first.getPendingEvents()).isEmpty();

        ActionState restored = ActionStateSerde.deserialize(persisted.get());
        assertThat(restored.getCallResultCount()).isEqualTo(2);
        RunnerContextImpl replay = context(restored, async, Integer.MAX_VALUE);
        runAction(restored.getTaskEvent(), replay);

        assertThat(decisionCalls).hasValue(2);
        assertThat(chatCalls).hasValue(1);
        assertRouted(replay, 1);
    }

    /** A completed routed request replays end to end without a provider or chat call. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replaysACompletedRoutedRequestWithoutAnotherCall(boolean async) throws Exception {
        RunnerContextImpl first = context(new ActionState(request), async, Integer.MAX_VALUE);
        runAction(request, first);
        assertThat(decisionCalls).hasValue(2);
        assertThat(chatCalls).hasValue(1);
        assertRouted(first, 1);

        ActionState restored = ActionStateSerde.deserialize(persisted.get());
        assertThat(restored.getCallResultCount()).isEqualTo(4);
        RunnerContextImpl replay = context(restored, async, Integer.MAX_VALUE);
        runAction(restored.getTaskEvent(), replay);

        assertThat(decisionCalls).hasValue(2);
        assertThat(chatCalls).hasValue(1);
        assertRouted(replay, 1);
    }

    private void assertRouted(RunnerContextImpl context, int retryCount) {
        ModelRoutingEvent routing =
                event(context, ModelRoutingEvent.EVENT_TYPE, ModelRoutingEvent::fromEvent);
        assertThat(routing.getRequestId()).isEqualTo(request.getId());
        assertThat(routing.getSelectedModel()).isEqualTo("big");
        assertThat(routing.getDecisionSource()).isEqualTo(ModelRoutingEvent.SOURCE_DECISION_MODEL);
        assertThat(routing.getScore()).isEqualTo(0.8);
        assertThat(routing.getMetadata())
                .containsEntry("decision_probabilities", Map.of("small", 0.2, "big", 0.8))
                .containsEntry("provider_confidence", 0.7)
                // Same types fresh and after the JSON round trip of the route record.
                .containsEntry("decision_input_tokens", 10)
                .containsEntry("decision_output_tokens", 2);
        ChatResponseEvent response =
                event(context, ChatResponseEvent.EVENT_TYPE, ChatResponseEvent::fromEvent);
        assertThat(response.getRequestId()).isEqualTo(request.getId());
        assertThat(response.isFailed()).isFalse();
        assertThat(response.getResponse().getText()).isEqualTo("answer");
        assertThat(response.getRetryCount()).isEqualTo(retryCount);
    }

    private static <T> T event(RunnerContextImpl context, String type, Function<Event, T> read) {
        return context.getPendingEvents().stream()
                .filter(e -> type.equals(e.getType()))
                .map(read)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no event of type " + type));
    }

    private BaseDecisionModelSetup decisionModel() {
        BaseDecisionModelSetup model = mock(BaseDecisionModelSetup.class);
        when(model.supportsAsyncExecution()).thenReturn(true);
        when(model.decide(any(), anyMap()))
                .thenAnswer(
                        invocation -> {
                            if (decisionCalls.incrementAndGet() == 1) {
                                throw new IllegalArgumentException("provider unavailable");
                            }
                            return choice;
                        });
        return model;
    }

    private BaseChatModelSetup chatModel() {
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
                chatCalls.incrementAndGet();
                return new ChatResult(new ChatMessage(MessageRole.ASSISTANT, "answer"));
            }
        };
    }

    private RunnerContextImpl context(ActionState state, boolean async, int failAfterCallResults)
            throws Exception {
        AgentPlan plan = new AgentPlan(Map.of());
        plan.getConfig().set(AgentExecutionOptions.DECISION_ASYNC, async);
        plan.getConfig().set(AgentExecutionOptions.MAX_RETRIES, 1);
        plan.getConfig().set(AgentExecutionOptions.RETRY_WAIT_INTERVAL, 0);
        ModelRouter router =
                new ModelRouter(
                        ModelRouter.of("small", "big")
                                .describe("small", "short factual questions")
                                .describe("big", "code and sql")
                                .strategy(Strategies.decision("dm"))
                                .defaultModel("small")
                                .build(),
                        null);
        // Build the stubs first: stubbing one mock while another stubbing is open is a misuse.
        BaseDecisionModelSetup decisionModel = decisionModel();
        BaseChatModelSetup small = chatModel();
        BaseChatModelSetup big = chatModel();
        ResourceCache resources = mock(ResourceCache.class);
        when(resources.hasResource("router", ResourceType.MODEL_ROUTER)).thenReturn(true);
        when(resources.getResource("router", ResourceType.MODEL_ROUTER)).thenReturn(router);
        when(resources.getResource("dm", ResourceType.DECISION_MODEL)).thenReturn(decisionModel);
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
                            metrics, () -> {}, plan, resources, "test-job", executor);
            asyncContext.setContinuationContext(new ContinuationContext());
            context = asyncContext;
        } else {
            context = new RunnerContextImpl(metrics, () -> {}, plan, resources, "test-job");
        }
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
