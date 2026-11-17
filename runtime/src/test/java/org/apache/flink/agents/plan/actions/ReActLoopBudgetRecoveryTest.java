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
package org.apache.flink.agents.plan.actions;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.agents.ReActLoopBudget;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.TokenUsage;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.chat.model.routing.ModelRouter;
import org.apache.flink.agents.api.chat.model.routing.Strategies;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateSerde;
import org.apache.flink.agents.runtime.actionstate.CallResult;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.memory.IsolatedCachedMemoryStore;
import org.apache.flink.agents.runtime.memory.MemoryObjectImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Exercises action admission with real serialized durable results and memory update journals. */
class ReActLoopBudgetRecoveryTest {
    private final UUID requestId = UUID.randomUUID();
    private final BaseChatModelSetup model = mock(BaseChatModelSetup.class);
    private final List<Event> events = new ArrayList<>();
    private final List<Map<String, Object>> chargedStates = new ArrayList<>();
    private byte[] persisted;

    private static final class Crash extends Error {}

    @SuppressWarnings("unchecked")
    private RunnerContextImpl context(
            ActionState journal, Map<String, Object> state, boolean crashAfterPersistence)
            throws Exception {
        AgentPlan plan = new AgentPlan(new Agent());
        plan.getConfig().set(AgentExecutionOptions.CHAT_ASYNC, false);
        plan.getConfig().set(AgentExecutionOptions.MAX_RETRIES, 0);
        RunnerContextImpl ctx = spy(new RunnerContextImpl(null, () -> {}, plan, null, "test"));
        MemoryObject memory =
                spy(
                        new MemoryObjectImpl(
                                MemoryObject.MemoryType.SENSORY,
                                new IsolatedCachedMemoryStore(),
                                "",
                                new ArrayList<>()));
        doReturn(memory).when(ctx).getSensoryMemory();
        doReturn(null).when(ctx).getActionMetricGroup();
        doReturn(model).when(ctx).getResource(anyString(), eq(ResourceType.CHAT_MODEL));
        doAnswer(
                        i -> {
                            events.add(i.getArgument(0));
                            return null;
                        })
                .when(ctx)
                .sendEvent(any());
        doAnswer(
                        i -> {
                            if (ReActLoopBudgetUtils.LOOP_BUDGET_CONTEXT.equals(i.getArgument(0))) {
                                Map<?, ?> contexts = i.getArgument(1);
                                if (contexts.containsKey(requestId)) {
                                    chargedStates.add(
                                            new HashMap<>(
                                                    (Map<String, Object>) contexts.get(requestId)));
                                }
                            }
                            return i.callRealMethod();
                        })
                .when(memory)
                .set(anyString(), any());
        if (state != null) ReActLoopBudgetUtils.saveBudgetState(memory, requestId, state);
        ctx.setDurableExecutionContext(
                new RunnerContextImpl.DurableExecutionContext(
                        "key",
                        1L,
                        mock(Action.class),
                        new Event("chat"),
                        journal,
                        (key, seq, action, event, saved) -> {
                            persisted = ActionStateSerde.serialize(saved);
                            if (crashAfterPersistence) throw new Crash();
                        }));
        return ctx;
    }

    private void run(RunnerContextImpl ctx) throws Exception {
        ChatModelAction.chat(
                requestId, "model", List.of(ChatMessage.user("hello")), Map.of(), null, ctx);
    }

    private Map<String, Object> state(boolean expired, String mode) {
        Map<String, Object> result =
                new HashMap<>(
                        Map.of(
                                "turns_left",
                                2L,
                                "tokens_left",
                                20L,
                                "max_time_ms",
                                Long.MAX_VALUE,
                                "started_at_ms",
                                0L,
                                "mode",
                                mode));
        if (expired) result.put("max_time_ms", 1L);
        return result;
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOFT", "HARD"})
    void ordinaryResultIsNotReusedAfterTimeExpires(String mode) throws Exception {
        when(model.chat(any(), any(), any()))
                .thenReturn(new ChatResult(ChatMessage.assistant("ordinary")));
        when(model.chatWithoutTools(any(), any(), any()))
                .thenReturn(new ChatResult(ChatMessage.assistant("closing")));
        assertThatThrownBy(() -> run(context(new ActionState(null), state(false, mode), true)))
                .isInstanceOf(Crash.class);
        ActionState recovered = ActionStateSerde.deserialize(persisted);
        // A later conversion result must also be discarded on a branch mismatch.
        recovered.addCallResult(new CallResult("stale:final", new byte[] {1}));
        run(context(recovered, state(true, mode), false));
        verify(model, times(1)).chat(any(), any(), any());
        verify(model, times(mode.equals("SOFT") ? 1 : 0)).chatWithoutTools(any(), any(), any());
        assertThat(events).hasSize(1);
        ChatResponseEvent response = ChatResponseEvent.fromEvent(events.get(0));
        assertThat(response.isFailed()).isEqualTo(mode.equals("HARD"));
        if (mode.equals("SOFT")) {
            assertThat(response.getResponse().getText()).isEqualTo("closing");
            assertThat(recovered.getCallResults()).hasSize(1);
            assertThat(recovered.getCallResult(0).getFunctionId()).endsWith(":budget-final");
        } else assertThat(response.getError()).contains("TIME_BUDGET_EXCEEDED");
    }

    @Test
    void closingResultIsNotReusedWhenRecoveredStateAdmitsOrdinaryInference() throws Exception {
        when(model.chatWithoutTools(any(), any(), any()))
                .thenReturn(new ChatResult(ChatMessage.assistant("closing")));
        when(model.chat(any(), any(), any()))
                .thenReturn(new ChatResult(ChatMessage.assistant("ordinary")));
        assertThatThrownBy(() -> run(context(new ActionState(null), state(true, "SOFT"), true)))
                .isInstanceOf(Crash.class);
        ActionState recovered = ActionStateSerde.deserialize(persisted);
        recovered.addCallResult(new CallResult("stale:final", new byte[] {1}));
        run(context(recovered, state(false, "SOFT"), false));
        verify(model, times(1)).chatWithoutTools(any(), any(), any());
        verify(model, times(1)).chat(any(), any(), any());
        assertThat(events).hasSize(1);
        assertThat(ChatResponseEvent.fromEvent(events.get(0)).getResponse().getText())
                .isEqualTo("ordinary");
        assertThat(recovered.getCallResults()).hasSize(1);
        assertThat(recovered.getCallResult(0).getFunctionId()).doesNotContain("budget-final");
    }

    @Test
    void reusedModelResultReconstructsUsageWithoutCallingProviderAgain() throws Exception {
        ChatResult answer =
                new ChatResult(
                        ChatMessage.assistant("done"),
                        null,
                        null,
                        new TokenUsage(3L, 2L),
                        null,
                        Map.of());
        when(model.chat(any(), any(), any())).thenReturn(answer);
        assertThatThrownBy(() -> run(context(new ActionState(null), state(false, "HARD"), true)))
                .isInstanceOf(Crash.class);
        chargedStates.clear();
        run(context(ActionStateSerde.deserialize(persisted), state(false, "HARD"), false));
        verify(model, times(1)).chat(any(), any(), any());
        assertThat(events).hasSize(1);
        assertThat(chargedStates.get(chargedStates.size() - 1))
                .containsEntry("turns_left", 1L)
                .containsEntry("tokens_left", 15L);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void routedRecoveryRebuildsJudgeAndTargetUsageOnce(int crashAt) throws Exception {
        BaseChatModelSetup judge = mock(BaseChatModelSetup.class);
        when(judge.chat(any(), any(), any()))
                .thenReturn(
                        new ChatResult(
                                ChatMessage.assistant("target"),
                                null,
                                null,
                                new TokenUsage(10L, 5L),
                                null,
                                Map.of()));
        when(model.chat(any(), any(), any()))
                .thenReturn(
                        new ChatResult(
                                ChatMessage.assistant("done"),
                                null,
                                null,
                                new TokenUsage(1L, 1L),
                                null,
                                Map.of()));
        ModelRouter router =
                new ModelRouter(
                        ModelRouter.of("target").strategy(Strategies.llm("judge")).build(), null);
        ChatRequestEvent request = new ChatRequestEvent("router", List.of(ChatMessage.user("hi")));
        ActionState journal = new ActionState(null);
        RunnerContextImpl original = context(journal, null, false);
        configureRouter(original, router, judge);
        ReActLoopBudgetUtils.saveBudgetState(
                original.getSensoryMemory(), request.getId(), state(false, "HARD"));
        AtomicInteger writes = new AtomicInteger();
        original.setDurableExecutionContext(
                new RunnerContextImpl.DurableExecutionContext(
                        "key",
                        1L,
                        mock(Action.class),
                        request,
                        journal,
                        (key, seq, action, event, saved) -> {
                            persisted = ActionStateSerde.serialize(saved);
                            if (writes.incrementAndGet() == crashAt) throw new Crash();
                        }));
        assertThatThrownBy(
                        () -> ChatModelAction.processChatRequestOrToolResponse(request, original))
                .isInstanceOf(Crash.class);
        assertThat(events.stream().filter(ChatResponseEvent.class::isInstance)).isEmpty();
        events.clear();
        ActionState recoveredJournal = ActionStateSerde.deserialize(persisted);
        RunnerContextImpl recovered = context(recoveredJournal, null, false);
        configureRouter(recovered, router, judge);
        ReActLoopBudgetUtils.saveBudgetState(
                recovered.getSensoryMemory(), request.getId(), state(false, "HARD"));
        List<Map<?, ?>> balances = new ArrayList<>();
        MemoryObject recoveredMemory = recovered.getSensoryMemory();
        doAnswer(
                        i -> {
                            if (ReActLoopBudgetUtils.LOOP_BUDGET_CONTEXT.equals(i.getArgument(0))) {
                                Map<?, ?> contexts = i.getArgument(1);
                                if (contexts.containsKey(request.getId()))
                                    balances.add(
                                            new HashMap<>(
                                                    (Map<?, ?>) contexts.get(request.getId())));
                            }
                            return i.callRealMethod();
                        })
                .when(recoveredMemory)
                .set(anyString(), any());
        ChatModelAction.processChatRequestOrToolResponse(request, recovered);
        verify(judge, times(1)).chat(any(), any(), any());
        verify(model, times(1)).chat(any(), any(), any());
        Map<?, ?> balance = balances.get(balances.size() - 1);
        assertThat(balance.get("turns_left")).isEqualTo(1L);
        assertThat(balance.get("tokens_left")).isEqualTo(3L);
        assertThat(recoveredJournal.getCallResults())
                .extracting(CallResult::getFunctionId)
                .containsExactly("judge:router", "route:router", "chat:router:target");
        assertThat(events.stream().filter(ChatResponseEvent.class::isInstance)).hasSize(1);
        ChatResponseEvent response =
                (ChatResponseEvent)
                        events.stream()
                                .filter(ChatResponseEvent.class::isInstance)
                                .findFirst()
                                .orElseThrow();
        assertThat(response.getResponse().getText()).isEqualTo("done");
    }

    private void configureRouter(
            RunnerContextImpl ctx, ModelRouter router, BaseChatModelSetup judge) throws Exception {
        doReturn(true).when(ctx).hasResource("router", ResourceType.MODEL_ROUTER);
        doReturn(router).when(ctx).getResource("router", ResourceType.MODEL_ROUTER);
        doReturn(judge).when(ctx).getResource("judge", ResourceType.CHAT_MODEL);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void firstActionRestoresStartBeforeAdmission(int crashAfter) throws Exception {
        when(model.chat(any(), any(), any()))
                .thenReturn(new ChatResult(ChatMessage.assistant("done")));
        ChatRequestEvent request =
                new ChatRequestEvent(
                        "model",
                        List.of(ChatMessage.user("hi")),
                        null,
                        null,
                        null,
                        ReActLoopBudget.builder()
                                .maxTime(Duration.ofMillis(300))
                                .mode(ReActLoopBudget.BudgetMode.HARD)
                                .build());
        RunnerContextImpl original = context(new ActionState(null), null, false);
        AtomicInteger writes = new AtomicInteger();
        original.setDurableExecutionContext(
                new RunnerContextImpl.DurableExecutionContext(
                        "key",
                        1L,
                        mock(Action.class),
                        request,
                        new ActionState(request),
                        (key, seq, action, event, saved) -> {
                            persisted = ActionStateSerde.serialize(saved);
                            if (writes.incrementAndGet() == crashAfter) throw new Crash();
                        }));
        assertThatThrownBy(
                        () -> ChatModelAction.processChatRequestOrToolResponse(request, original))
                .isInstanceOf(Crash.class);
        ActionState recoveredJournal = ActionStateSerde.deserialize(persisted);
        assertThat(recoveredJournal.getSensoryMemoryUpdates()).isEmpty();
        // Let the original deadline expire while no action memory has been committed.
        Thread.sleep(350);
        RunnerContextImpl recovered = context(recoveredJournal, null, false);
        ChatModelAction.processChatRequestOrToolResponse(request, recovered);
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ChatResponseEvent.class);
        assertThat(((ChatResponseEvent) events.get(0)).getError()).contains("TIME_BUDGET_EXCEEDED");
        verify(model, times(crashAfter - 1)).chat(any(), any(), any());
    }

    @Test
    void initializationReplaysPersistedStartWithoutExtendingDeadline() throws Exception {
        ChatRequestEvent request =
                new ChatRequestEvent(
                        "model",
                        List.of(ChatMessage.user("hi")),
                        null,
                        null,
                        null,
                        ReActLoopBudget.builder().maxTime(Duration.ofMinutes(1)).build());
        RunnerContextImpl original = context(new ActionState(null), null, true);
        assertThatThrownBy(() -> ReActLoopBudgetUtils.initialize(original, request))
                .isInstanceOf(Crash.class);
        ActionState journal = ActionStateSerde.deserialize(persisted);
        long start =
                Long.parseLong(
                        new String(
                                journal.getCallResult(0).getResultPayload(),
                                java.nio.charset.StandardCharsets.UTF_8));
        RunnerContextImpl restored = context(journal, null, false);
        ReActLoopBudgetUtils.initialize(restored, request);
        Map<String, Object> state =
                ReActLoopBudgetUtils.getBudgetState(restored.getSensoryMemory(), request.getId());
        assertThat(state.get("started_at_ms")).isEqualTo(start);
        assertThat(
                        ReActLoopBudgetUtils.tryStartTurn(
                                restored.getSensoryMemory(), request.getId(), start + 60_000))
                .isEqualTo("TIME_BUDGET_EXCEEDED");
        ReActLoopBudgetUtils.initialize(restored, request);
        assertThat(journal.getCallResultCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initializationOnlyCreatesStartTimeWhenNoStateSurvived(boolean saved) throws Exception {
        Map<String, Object> previous = state(true, "HARD");
        RunnerContextImpl ctx = context(new ActionState(null), saved ? previous : null, false);
        ChatRequestEvent request =
                new ChatRequestEvent(
                        "model",
                        List.of(ChatMessage.user("hi")),
                        null,
                        null,
                        null,
                        ReActLoopBudget.builder()
                                .maxTurns(2)
                                .maxTime(Duration.ofMinutes(1))
                                .build());
        // Use the actual request identity for initialization.
        if (saved)
            ReActLoopBudgetUtils.saveBudgetState(ctx.getSensoryMemory(), request.getId(), previous);
        long before = System.currentTimeMillis();
        ReActLoopBudgetUtils.initialize(ctx, request);
        Map<String, Object> restored =
                ReActLoopBudgetUtils.getBudgetState(ctx.getSensoryMemory(), request.getId());
        if (saved) assertThat(restored).isEqualTo(previous);
        else
            assertThat(((Number) restored.get("started_at_ms")).longValue())
                    .isBetween(before, System.currentTimeMillis());
    }
}
