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
package org.apache.flink.agents.runtime.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.TokenUsage;
import org.apache.flink.agents.api.context.DurableFuture;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateSerde;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.async.ContinuationContext;
import org.apache.flink.agents.runtime.context.ActionStatePersister;
import org.apache.flink.agents.runtime.context.JavaRunnerContextImpl;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.agents.runtime.subgraph.SubgraphCallManager;
import org.apache.flink.agents.runtime.subgraph.SubgraphEvent;
import org.apache.flink.agents.runtime.subgraph.SubgraphInvocation;
import org.apache.flink.agents.runtime.utils.DeterministicIdAllocator;
import org.apache.flink.agents.runtime.utils.IdAllocatorSource;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Exercises the chat call through the two bridge methods a polling caller drives it by. */
class ChatCallTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final SubgraphCallManager calls = new SubgraphCallManager();

    /**
     * Stands in for the id counters the context manager installs for one task execution: one
     * allocator per target name over the caller's digest facts, whose ordinals continue for as long
     * as this source stays bound.
     */
    private static void bindChatIds(RunnerContextImpl context, Object key, Event event) {
        Map<String, DeterministicIdAllocator> byTarget = new HashMap<>();
        context.setIdAllocatorSource(
                name ->
                        byTarget.computeIfAbsent(
                                name,
                                target ->
                                        new DeterministicIdAllocator(
                                                key, 1, "action", event, target)));
    }

    /**
     * Moves a context onto a different record. The record key travels with the action context, so
     * this — and not the id counters — is what separates two calls for record-scoped cleanup. The
     * counters are rebound over the same key so the digest facts agree with it.
     */
    private static void switchToRecord(RunnerContextImpl context, String recordKey, Event event) {
        context.switchActionContext(
                "action",
                new RunnerContextImpl.MemoryContext(null, null),
                new ArrayList<>(),
                recordKey,
                recordKey,
                "observation",
                false,
                null);
        bindChatIds(context, recordKey, event);
    }

    @Test
    void persistenceFollowsTheExplicitCallerContext() throws Exception {
        ActionState firstState = new ActionState(new Event("input"));
        ActionState secondState = new ActionState(new Event("input"));
        RunnerContextImpl first = context(firstState, (k, s, a, e, saved) -> {});
        RunnerContextImpl second = context(secondState, (k, s, a, e, saved) -> {});
        bindChatIds(second, "second-key", new Event("input"));
        String firstId = first.prepareChatCall(request());
        String secondId = second.prepareChatCall(request());
        chatCall(first, firstId)
                .dispatch(
                        ChatResponseEvent.success(
                                UUID.randomUUID(), new ChatResult(ChatMessage.assistant("first"))));
        chatCall(second, secondId)
                .dispatch(
                        ChatResponseEvent.success(
                                UUID.randomUUID(),
                                new ChatResult(ChatMessage.assistant("second"))));

        assertThat(firstState.getCallResults()).isEmpty();
        assertThat(secondState.getCallResults()).isEmpty();
        assertThat(response(second.tryCompleteChatCall(secondId)).getResponse().getText())
                .isEqualTo("second");
        assertThat(secondState.getCallResults()).hasSize(1);
        assertThat(firstState.getCallResults()).isEmpty();
        assertThat(response(first.tryCompleteChatCall(firstId)).getResponse().getText())
                .isEqualTo("first");
        assertThat(firstState.getCallResults()).hasSize(1);
        assertThatThrownBy(() -> chatCall(first, firstId))
                .hasMessageContaining("Missing subgraph call");
        assertThatThrownBy(() -> chatCall(second, secondId))
                .hasMessageContaining("Missing subgraph call");
    }

    @Test
    void recordCleanupPreservesOtherContextsCalls() throws Exception {
        RunnerContextImpl first =
                context(new ActionState(new Event("input")), (k, s, a, e, saved) -> {});
        RunnerContextImpl second =
                context(new ActionState(new Event("input")), (k, s, a, e, saved) -> {});
        switchToRecord(second, "second-key", new Event("input"));
        String firstId = first.prepareChatCall(request());
        String secondId = second.prepareChatCall(request());
        calls.finishRecord("key");
        assertThatThrownBy(() -> chatCall(first, firstId))
                .hasMessageContaining("Missing subgraph call");
        assertThat(chatCall(second, secondId).getId()).isEqualTo(secondId);
        calls.clear();
        assertThatThrownBy(() -> chatCall(second, secondId))
                .hasMessageContaining("Missing subgraph call");
    }

    @Test
    void bootstrapDelegateKeepsTheCallersRequestId() throws Exception {
        RunnerContextImpl context =
                context(new ActionState(new Event("input")), (k, seq, action, event, saved) -> {});
        ChatRequestEvent request =
                new ChatRequestEvent("model", List.of(ChatMessage.user("hello")));
        context.prepareChatCall(MAPPER.writeValueAsString(request));

        SubgraphEvent bootstrap = (SubgraphEvent) context.getPendingEvents().get(0);
        // The response names the request the caller holds, so the delegate keeps its id ...
        assertThat(bootstrap.getDelegate().getId()).isEqualTo(request.getId());
        // ... but that id is not replay-stable, so no attribute may key state on it.
        assertThat(bootstrap.getAttributes()).doesNotContainKey("delegate_id");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void terminalResponseReplaysWithoutBootstrap(boolean failed) throws Exception {
        ActionState state = new ActionState(new Event("input"));
        RunnerContextImpl context = context(state, (k, seq, action, event, saved) -> {});
        String id = context.prepareChatCall(request());
        UUID requestId = UUID.randomUUID();
        ChatResult result =
                new ChatResult(
                        ChatMessage.assistant("answer"),
                        "test-model",
                        "response-1",
                        new TokenUsage(11L, 7L),
                        "stop",
                        Map.of("provider", "test"));
        ChatResponseEvent response =
                failed
                        ? ChatResponseEvent.failed(requestId, "provider refused", 2, 3)
                        : ChatResponseEvent.success(requestId, result);
        chatCall(context, id).dispatch(response);
        context.tryCompleteChatCall(id);
        assertThat(state.getCallResults()).hasSize(1);
        assertThat(state.getCallResults().get(0).isSuccess()).isTrue();

        RunnerContextImpl restored =
                context(
                        ActionStateSerde.deserialize(ActionStateSerde.serialize(state)),
                        (k, seq, action, event, saved) -> {
                            throw new AssertionError("replay persisted again");
                        });
        String replayId = restored.prepareChatCall(request());
        assertThat(replayId).isEqualTo(id);
        assertThat(restored.getPendingEvents()).isEmpty();
        ChatResponseEvent replay = response(restored.tryCompleteChatCall(replayId));
        assertThat(replay.getStatus()).isEqualTo(response.getStatus());
        assertThat(replay.getRequestId()).isEqualTo(response.getRequestId());
        if (failed) {
            assertThatThrownBy(replay::getResponse)
                    .isInstanceOf(ChatResponseEvent.ChatResponseException.class)
                    .hasMessage("provider refused");
        } else {
            assertThat(replay.getResponse()).isEqualTo(result);
        }
    }

    @Test
    void pendingPollAndOrdinaryEventsDoNotConsumeDurableSlots() throws Exception {
        ActionState state = new ActionState(new Event("input"));
        RunnerContextImpl context = context(state, (k, seq, action, event, saved) -> {});
        Event ordinary = new Event("ordinary");
        context.sendEvent(ordinary);
        String id = context.prepareChatCall(request());
        assertThat(context.tryCompleteChatCall(id)).isNull();
        assertThat(state.getCallResults()).isEmpty();
        List<Event> dispatched = context.drainEventsAtActionYield(42L);
        assertThat(dispatched).hasSize(1);
        assertThat(dispatched.get(0)).isInstanceOf(SubgraphEvent.class);
        assertThat(context.getPendingEvents()).containsExactly(ordinary);
        assertThat(chatCall(context, id).isDone()).isFalse();
    }

    @Test
    void persistenceFailurePropagatesInsteadOfBecomingChatFailure() throws Exception {
        RunnerContextImpl context =
                context(
                        new ActionState(new Event("input")),
                        (k, seq, action, event, saved) -> {
                            throw new IllegalStateException("state unavailable");
                        });
        String id = context.prepareChatCall(request());
        SubgraphInvocation<ChatResponseEvent> call = chatCall(context, id);
        call.dispatch(
                ChatResponseEvent.success(
                        UUID.randomUUID(), new ChatResult(ChatMessage.assistant("answer"))));
        assertThatThrownBy(() -> context.tryCompleteChatCall(id)).hasMessage("state unavailable");
        assertThat(call.awaitResult().isSuccess()).isTrue();
    }

    @Test
    void duplicateRecordedResponseIsRejected() throws Exception {
        RunnerContextImpl context =
                context(new ActionState(new Event("input")), (k, s, a, e, saved) -> {});
        SubgraphInvocation<ChatResponseEvent> call =
                chatCall(context, context.prepareChatCall(request()));
        ChatResponseEvent response =
                ChatResponseEvent.success(
                        UUID.randomUUID(), new ChatResult(ChatMessage.assistant("answer")));
        call.installRecorded(response);
        assertThatThrownBy(() -> call.installRecorded(response)).hasMessageContaining("Duplicate");
    }

    @Test
    void chatHandleRejectsGatherWithoutStartingCall() {
        JavaRunnerContextImpl context = javaContext();
        DurableFuture<ChatResult> future = context.chat("model", List.of());
        assertThat(future).isInstanceOf(ChatDurableFuture.class);
        assertThatThrownBy(() -> context.gather(List.of(future)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gather only accepts");
        assertThat(context.getPendingEvents()).isEmpty();
    }

    @Test
    void clearingActiveChatCallRestoresOrdinaryEventRouting() throws Exception {
        RunnerContextImpl context =
                context(new ActionState(new Event("input")), (k, s, a, e, saved) -> {});
        IdAllocatorSource callerIds = context.getIdAllocatorSource();
        SubgraphInvocation<ChatResponseEvent> call =
                chatCall(context, context.prepareChatCall(request()));
        context.getPendingEvents().clear();
        context.setSubgraphInvocation(call);
        context.sendEvent(new ChatRequestEvent("model", List.of()));
        assertThat(context.getPendingEvents()).singleElement().isInstanceOf(SubgraphEvent.class);
        context.getPendingEvents().clear();
        context.setIdAllocatorSource(callerIds);
        context.setSubgraphInvocation(null);
        ChatRequestEvent ordinary = new ChatRequestEvent("model", List.of());
        context.sendEvent(ordinary);
        assertThat(context.getPendingEvents()).containsExactly(ordinary);
        assertThat(context.drainEventsAtActionYield(null)).isEmpty();
    }

    private JavaRunnerContextImpl javaContext() {
        JavaRunnerContextImpl context =
                new JavaRunnerContextImpl(
                        metrics(),
                        () -> {},
                        new AgentPlan(new HashMap<>(), new HashMap<>()),
                        null,
                        "job",
                        null);
        bindChatIds(context, "key", new Event("input"));
        return context;
    }

    @Test
    @EnabledForJreRange(max = JRE.JAVA_17)
    void javaWithoutContinuationFailsBeforeDispatch() throws Exception {
        ContinuationActionExecutor executor = new ContinuationActionExecutor(1);
        try {
            JavaRunnerContextImpl context =
                    new JavaRunnerContextImpl(
                            metrics(),
                            () -> {},
                            new AgentPlan(new HashMap<>(), new HashMap<>()),
                            null,
                            "job",
                            executor);
            context.setContinuationContext(new ContinuationContext());
            assertThatThrownBy(() -> context.chat("model", List.of()).await())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("JDK 21");
            assertThat(context.getPendingEvents()).isEmpty();
        } finally {
            executor.close();
        }
    }

    private RunnerContextImpl context(ActionState state, ActionStatePersister persister) {
        Event input = new Event("input");
        RunnerContextImpl context =
                new RunnerContextImpl(
                        metrics(),
                        () -> {},
                        new AgentPlan(new HashMap<>(), new HashMap<>()),
                        null,
                        "job");
        context.switchActionContext(
                "action",
                new RunnerContextImpl.MemoryContext(null, null),
                new ArrayList<>(),
                "key",
                "key",
                "observation",
                false,
                null);
        context.setSubgraphCalls(calls);
        bindChatIds(context, "key", input);
        context.setDurableExecutionContext(
                new RunnerContextImpl.DurableExecutionContext(
                        "key", 1, mock(Action.class), input, state, persister));
        return context;
    }

    @SuppressWarnings("unchecked")
    private static SubgraphInvocation<ChatResponseEvent> chatCall(
            RunnerContextImpl context, String id) {
        return (SubgraphInvocation<ChatResponseEvent>) context.getSubgraphCalls().get(id);
    }

    private static ChatResponseEvent response(String json) throws Exception {
        return (ChatResponseEvent) Event.fromJson(json);
    }

    private static String request() throws Exception {
        return MAPPER.writeValueAsString(
                new ChatRequestEvent("model", List.of(ChatMessage.user("hello"))));
    }

    private static FlinkAgentsMetricGroupImpl metrics() {
        return new FlinkAgentsMetricGroupImpl(
                UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup());
    }
}
