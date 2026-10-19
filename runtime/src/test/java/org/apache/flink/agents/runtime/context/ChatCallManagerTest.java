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
import org.apache.flink.agents.runtime.chat.ChatCallManager;
import org.apache.flink.agents.runtime.chat.ChatCallOwner;
import org.apache.flink.agents.runtime.chat.ChatContext;
import org.apache.flink.agents.runtime.chat.ChatInvocation;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.agents.runtime.subgraph.SubgraphEvent;
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

class ChatCallManagerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ChatCallManager manager = new ChatCallManager();

    @Test
    void sharedManagerUsesTheExplicitCallerForPersistence() throws Exception {
        ActionState firstState = new ActionState(new Event("input"));
        ActionState secondState = new ActionState(new Event("input"));
        RunnerContextImpl first = context(firstState, (k, s, a, e, saved) -> {});
        RunnerContextImpl second = context(secondState, (k, s, a, e, saved) -> {});
        second.setChatContext(
                new ChatContext(
                        manager, new ChatCallOwner("second-key", 1, "action", new Event("input"))));
        String firstId = manager.prepareCall(first, request());
        String secondId = manager.prepareCall(second, request());
        ChatInvocation firstCall = manager.get(firstId);
        ChatInvocation secondCall = manager.get(secondId);
        firstCall.complete(
                ChatResponseEvent.success(
                        firstCall.getRequestId(), new ChatResult(ChatMessage.assistant("first"))));
        secondCall.complete(
                ChatResponseEvent.success(
                        secondCall.getRequestId(),
                        new ChatResult(ChatMessage.assistant("second"))));

        assertThat(firstState.getCallResults()).isEmpty();
        assertThat(secondState.getCallResults()).isEmpty();
        assertThat(
                        ((ChatResponseEvent)
                                        Event.fromJson(manager.tryCompleteCall(second, secondId)))
                                .getResponse()
                                .getText())
                .isEqualTo("second");
        assertThat(secondState.getCallResults()).hasSize(1);
        assertThat(firstState.getCallResults()).isEmpty();
        assertThat(
                        ((ChatResponseEvent)
                                        Event.fromJson(manager.tryCompleteCall(first, firstId)))
                                .getResponse()
                                .getText())
                .isEqualTo("first");
        assertThat(firstState.getCallResults()).hasSize(1);
        assertThatThrownBy(() -> manager.get(firstId))
                .hasMessageContaining("Missing subgraph call");
        assertThatThrownBy(() -> manager.get(secondId))
                .hasMessageContaining("Missing subgraph call");
    }

    @Test
    void recordCleanupPreservesOtherContextsCalls() throws Exception {
        RunnerContextImpl first =
                context(new ActionState(new Event("input")), (k, s, a, e, saved) -> {});
        RunnerContextImpl second =
                context(new ActionState(new Event("input")), (k, s, a, e, saved) -> {});
        second.setChatContext(
                new ChatContext(
                        manager, new ChatCallOwner("second-key", 1, "action", new Event("input"))));
        String firstId = manager.prepareCall(first, request());
        String secondId = manager.prepareCall(second, request());
        manager.finishRecord("key");
        assertThatThrownBy(() -> manager.get(firstId))
                .hasMessageContaining("Missing subgraph call");
        assertThat(manager.get(secondId).getId()).isEqualTo(secondId);
        manager.clear();
        assertThatThrownBy(() -> manager.get(secondId))
                .hasMessageContaining("Missing subgraph call");
    }

    @Test
    void replayedBootstrapKeepsRequestId() throws Exception {
        RunnerContextImpl original =
                context(new ActionState(new Event("input")), (k, seq, action, event, saved) -> {});
        String id = manager.prepareCall(original, request());
        SubgraphEvent bootstrap = (SubgraphEvent) original.getPendingEvents().get(0);
        UUID requestId = bootstrap.getDelegate().getId();
        assertThat(manager.get(id).getRequestId()).isEqualTo(requestId);
        manager.clear();

        RunnerContextImpl restored =
                context(new ActionState(new Event("input")), (k, seq, action, event, saved) -> {});
        String replayId = manager.prepareCall(restored, request());
        SubgraphEvent replay = (SubgraphEvent) restored.getPendingEvents().get(0);
        assertThat(replayId).isEqualTo(id);
        assertThat(replay.getDelegate().getId()).isEqualTo(requestId);
        assertThat(manager.get(replayId).getRequestId()).isEqualTo(requestId);
        assertThat(replay.getAttributes()).doesNotContainKey("delegate_id");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void terminalResponseReplaysWithoutBootstrap(boolean failed) throws Exception {
        ActionState state = new ActionState(new Event("input"));
        RunnerContextImpl context = context(state, (k, seq, action, event, saved) -> {});
        String id = manager.prepareCall(context, request());
        ChatInvocation call = manager.get(id);
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
                        ? ChatResponseEvent.failed(call.getRequestId(), "provider refused", 2, 3)
                        : ChatResponseEvent.success(call.getRequestId(), result);
        call.complete(response);
        manager.tryCompleteCall(context, id);
        assertThat(state.getCallResults()).hasSize(1);
        assertThat(state.getCallResults().get(0).isSuccess()).isTrue();

        RunnerContextImpl restored =
                context(
                        ActionStateSerde.deserialize(ActionStateSerde.serialize(state)),
                        (k, seq, action, event, saved) -> {
                            throw new AssertionError("replay persisted again");
                        });
        String replayId = manager.prepareCall(restored, request());
        assertThat(replayId).isEqualTo(id);
        assertThat(restored.getPendingEvents()).isEmpty();
        ChatResponseEvent replay =
                (ChatResponseEvent) Event.fromJson(manager.tryCompleteCall(restored, replayId));
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
        String id = manager.prepareCall(context, request());
        assertThat(manager.tryCompleteCall(context, id)).isNull();
        assertThat(state.getCallResults()).isEmpty();
        List<Event> dispatched = context.drainEventsAtActionYield(42L);
        assertThat(dispatched).hasSize(1);
        assertThat(dispatched.get(0)).isInstanceOf(SubgraphEvent.class);
        assertThat(context.getPendingEvents()).containsExactly(ordinary);
        assertThat(manager.get(id).isDone()).isFalse();
    }

    @Test
    void persistenceFailurePropagatesInsteadOfBecomingChatFailure() throws Exception {
        RunnerContextImpl context =
                context(
                        new ActionState(new Event("input")),
                        (k, seq, action, event, saved) -> {
                            throw new IllegalStateException("state unavailable");
                        });
        String id = manager.prepareCall(context, request());
        ChatInvocation call = manager.get(id);
        call.complete(
                ChatResponseEvent.success(
                        call.getRequestId(), new ChatResult(ChatMessage.assistant("answer"))));
        assertThatThrownBy(() -> manager.tryCompleteCall(context, id))
                .hasMessage("state unavailable");
        assertThat(call.awaitResponse().isSuccess()).isTrue();
    }

    @Test
    void mismatchedAndDuplicateResponsesAreRejected() throws Exception {
        RunnerContextImpl context =
                context(new ActionState(new Event("input")), (k, s, a, e, saved) -> {});
        ChatInvocation call = manager.get(manager.prepareCall(context, request()));
        assertThatThrownBy(
                        () ->
                                call.complete(
                                        ChatResponseEvent.success(
                                                UUID.randomUUID(),
                                                new ChatResult(ChatMessage.assistant("wrong")))))
                .hasMessageContaining("does not match");
        ChatResponseEvent response =
                ChatResponseEvent.success(
                        call.getRequestId(), new ChatResult(ChatMessage.assistant("answer")));
        call.complete(response);
        assertThatThrownBy(() -> call.complete(response)).hasMessageContaining("Duplicate");
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
        ChatContext callerContext = context.getChatContext();
        ChatCallManager chats = callerContext.getManager();
        ChatInvocation call = manager.get(chats.prepareCall(context, request()));
        context.getPendingEvents().clear();
        context.setSubgraphInvocation(call);
        context.sendEvent(new ChatRequestEvent("model", List.of()));
        assertThat(context.getPendingEvents()).singleElement().isInstanceOf(SubgraphEvent.class);
        context.getPendingEvents().clear();
        context.setChatContext(callerContext);
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
        context.setChatContext(
                new ChatContext(
                        manager, new ChatCallOwner("key", 1, "action", new Event("input"))));
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
            context.setChatContext(
                    new ChatContext(
                            manager, new ChatCallOwner("key", 1, "action", new Event("input"))));
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
                "observation",
                false,
                null);
        context.setChatContext(
                new ChatContext(manager, new ChatCallOwner("key", 1, "action", input)));
        context.setDurableExecutionContext(
                new RunnerContextImpl.DurableExecutionContext(
                        "key", 1, mock(Action.class), input, state, persister));
        return context;
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
