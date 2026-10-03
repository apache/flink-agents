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

package org.apache.flink.agents.runtime.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.configuration.Configuration;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.subagent.SubagentFuture;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.CallResult;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.async.ContinuationContext;
import org.apache.flink.agents.runtime.context.JavaRunnerContextImpl;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Exercises admission and durable reservation through the actual continuation batch executor. */
@EnabledForJreRange(min = JRE.JAVA_21)
class InternalSubagentBatchTest {
    private ContinuationActionExecutor executor;
    private JavaRunnerContextImpl context;
    private ActionState state;
    private final List<String> started = new ArrayList<>();
    private final Map<String, InternalSubagentCallStatus> calls = new HashMap<>();
    private int reservationsBeforeFirstStart;
    private int prepared;

    @BeforeEach
    void setUp() {
        executor = new ContinuationActionExecutor(1);
        state = new ActionState(null);
        context = newContext(state);
        assertThat(context.getConfig().get(AgentExecutionOptions.SUBAGENT_PARALLELISM))
                .isEqualTo(16);
        assertThat(context.getConfig().get(AgentExecutionOptions.SUBAGENT_MAX_BATCH_SIZE))
                .isEqualTo(1024);
        ((Configuration) context.getConfig()).set(AgentExecutionOptions.SUBAGENT_PARALLELISM, 2);
        // Internal scopes must not inherit the unrelated tool-call deadline or concurrency cap.
        ((Configuration) context.getConfig()).set(AgentExecutionOptions.TOOL_CALL_PARALLELISM, 1);
        ((Configuration) context.getConfig())
                .set(AgentExecutionOptions.TOOL_CALL_BATCH_TIMEOUT_MS, 1L);
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void reservesEverySlotBeforeStartingAndRefillsOnOutOfOrderCompletion() throws Exception {
        InternalSubagentSetup firstSetup = setup();
        InternalSubagentSetup secondSetup = setup();
        SubagentFuture first = future(context, firstSetup, "a");
        SubagentFuture second = future(context, secondSetup, "b");
        SubagentFuture third = future(context, firstSetup, "c");
        AtomicReference<List<SubagentResult>> result = new AtomicReference<>();
        Runnable action =
                unchecked(
                        () -> {
                            result.set(first.combine(second, third).awaitAll());
                            return null;
                        });

        assertThat(started).isEmpty();
        assertThat(run(action)).isFalse();
        assertThat(started).containsExactly("a", "b");
        assertThat(reservationsBeforeFirstStart).isEqualTo(3);
        assertThat(state.getCallResults()).allMatch(CallResult::isPending);

        calls.get("b").accumulateOutput("second");
        calls.get("b").completeAction();
        assertThat(run(action)).isFalse();
        assertThat(started).containsExactly("a", "b", "c");
        assertThat(state.getCallResults().get(0).isPending()).isTrue();
        assertThat(state.getCallResults().get(1).isSuccess()).isTrue();
        assertThat(state.getCallResults().get(2).isPending()).isTrue();

        calls.get("c").failAction(new IllegalArgumentException("child failure"));
        calls.get("c").completeAction();
        calls.get("a").accumulateOutput("first");
        calls.get("a").completeAction();
        assertThat(run(action)).isTrue();
        assertThat(result.get()).hasSize(3);
        assertThat(result.get().get(0).getResult()).isEqualTo(List.of("first"));
        assertThat(result.get().get(1).getResult()).isEqualTo(List.of("second"));
        assertThat(result.get().get(2).getErrorMessage()).contains("child failure");
        assertThat(first.isDone()).isTrue();
        assertThat(second.await()).isSameAs(result.get().get(1));
        assertThat(context.getDurableExecutionContext().getCurrentCallIndex()).isEqualTo(3);
    }

    @Test
    void replaySkipsTerminalChildrenAndRetainsFixedSlotIndexes() throws Exception {
        state.addCallResult(
                new CallResult(
                        "session#a",
                        "",
                        new ObjectMapper()
                                .writeValueAsBytes(SubagentResult.ok(List.of("cached")))));
        state.addCallResult(CallResult.pending("session#b", ""));
        context = newContext(state);
        InternalSubagentSetup setup = setup();
        SubagentFuture first = future(context, setup, "a");
        SubagentFuture second = future(context, setup, "b");
        AtomicReference<List<SubagentResult>> result = new AtomicReference<>();
        Runnable action =
                unchecked(
                        () -> {
                            result.set(first.combine(second).awaitAll());
                            return null;
                        });

        assertThat(run(action)).isFalse();
        assertThat(started).containsExactly("b");
        calls.get("b").completeAction();
        assertThat(run(action)).isTrue();
        assertThat(result.get().get(0).getResult()).isEqualTo(List.of("cached"));
        assertThat(state.getCallResults()).hasSize(2).allMatch(CallResult::isSuccess);
    }

    @Test
    void rejectsInvalidGroupsBeforePreparingOrReserving() {
        InternalSubagentSetup setup = setup();
        SubagentFuture first = future(context, setup, "a");
        SubagentFuture second = future(context, setup, "b");
        assertThatThrownBy(() -> first.combine(first).awaitAll())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same");
        SubagentFuture foreign = future(newContext(new ActionState(null)), setup, "foreign");
        assertThatThrownBy(() -> first.combine(foreign).awaitAll())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("context");
        ((Configuration) context.getConfig()).set(AgentExecutionOptions.SUBAGENT_MAX_BATCH_SIZE, 1);
        assertThatThrownBy(() -> first.combine(second).awaitAll())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-batch-size");
        ((Configuration) context.getConfig()).set(AgentExecutionOptions.SUBAGENT_MAX_BATCH_SIZE, 0);
        assertThatThrownBy(() -> first.combine(second).awaitAll())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        ((Configuration) context.getConfig())
                .set(AgentExecutionOptions.SUBAGENT_MAX_BATCH_SIZE, 10);
        ((Configuration) context.getConfig()).set(AgentExecutionOptions.SUBAGENT_PARALLELISM, 0);
        assertThatThrownBy(() -> first.combine(second).awaitAll())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThat(prepared).isZero();
        assertThat(started).isEmpty();
        assertThat(state.getCallResults()).isEmpty();
    }

    @Test
    void bootstrapFailurePropagatesAndLeavesItsSlotPending() {
        InternalSubagentSetup setup = setup();
        doAnswer(
                        invocation -> {
                            throw new IllegalStateException("mailbox failure");
                        })
                .when(setup)
                .bootstrap(any(), anyString(), anyString(), any());
        SubagentFuture future = future(context, setup, "failure");
        SubagentFuture queued = future(context, setup, "queued");
        ((Configuration) context.getConfig()).set(AgentExecutionOptions.SUBAGENT_PARALLELISM, 1);
        assertThatThrownBy(() -> run(unchecked(() -> future.combine(queued).awaitAll())))
                .hasRootCauseMessage("mailbox failure");
        verify(setup, times(1)).bootstrap(any(), anyString(), anyString(), any());
        assertThat(state.getCallResults()).hasSize(2).allMatch(CallResult::isPending);
        assertThat(future.isDone()).isFalse();
    }

    @Test
    void failedCallRetainsItsConcurrencySlotUntilAllActionsAndEventsFinish() {
        InternalSubagentCallStatus status =
                new InternalSubagentCallStatus("call", "scope", "session", null);
        status.addTriggeredActions(2);
        status.emitEvent();
        status.failAction(new IllegalArgumentException("child failure"));
        assertThat(status.isDone()).isFalse();
        status.completeAction();
        status.completeAction();
        assertThat(status.isDone()).isFalse();
        status.markEmittedEventDispatched();
        assertThat(status.isDone()).isTrue();
        assertThatThrownBy(() -> status.getResponseFuture().join())
                .hasRootCauseMessage("child failure");
    }

    private InternalSubagentSetup setup() {
        InternalSubagentSetup setup = mock(InternalSubagentSetup.class);
        doAnswer(
                        invocation -> {
                            String callId = invocation.getArgument(2);
                            if (started.isEmpty()) {
                                reservationsBeforeFirstStart = state.getCallResults().size();
                            }
                            // A completed earlier slot must be persisted before its replacement is
                            // admitted.
                            if (callId.equals("c")) {
                                assertThat(state.getCallResults().get(1).isSuccess()).isTrue();
                            }
                            started.add(callId);
                            InternalSubagentCallStatus status =
                                    new InternalSubagentCallStatus(
                                            callId, "scope", "session", setup);
                            status.addTriggeredActions(1);
                            calls.put(callId, status);
                            return null;
                        })
                .when(setup)
                .bootstrap(any(), anyString(), anyString(), any());
        when(setup.getCallStatus(anyString(), anyString()))
                .thenAnswer(invocation -> calls.get(invocation.getArgument(1)));
        return setup;
    }

    private SubagentFuture future(RunnerContext owner, InternalSubagentSetup setup, String callId) {
        return new DeferredSubagentFuture(
                "session",
                callId,
                owner,
                null,
                () -> {
                    prepared++;
                    return new InternalSubagentCallable(setup, owner, callId, "session", callId);
                });
    }

    private JavaRunnerContextImpl newContext(ActionState actionState) {
        JavaRunnerContextImpl result =
                new JavaRunnerContextImpl(
                        new FlinkAgentsMetricGroupImpl(
                                UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup()),
                        () -> {},
                        new AgentPlan(new HashMap<>(), new HashMap<>()),
                        null,
                        "test",
                        executor);
        result.setContinuationContext(new ContinuationContext());
        result.setDurableExecutionContext(
                new RunnerContextImpl.DurableExecutionContext(
                        "key",
                        1L,
                        mock(Action.class),
                        mock(Event.class),
                        actionState,
                        (key, sequence, action, event, persisted) -> {}));
        return result;
    }

    private boolean run(Runnable action) {
        return executor.executeAction(context.getContinuationContext(), action);
    }

    private static Runnable unchecked(java.util.concurrent.Callable<?> action) {
        return () -> {
            try {
                action.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }
}
