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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.plan.exceptions.UncapturableException;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateSerde;
import org.apache.flink.agents.runtime.actionstate.InMemoryActionStateStore;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperator;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperatorFactory;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Framework failures must reach the operator with their original cause, including nested children.
 * Ordinary child failures remain results the parent can handle.
 */
public class InternalSubagentFrameworkFaultTest {
    private static final AtomicInteger CHILD_EXECUTIONS = new AtomicInteger();
    private static final String INJECTED_FAILURE = "injected subagent completion write failure";

    @BeforeEach
    void reset() {
        CHILD_EXECUTIONS.set(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(30)
    void nestedFrameworkFaultRetainsOriginalCause(boolean parallel) throws Exception {
        FaultingStore store = new FaultingStore(true);
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                harness(parallel, store, true)) {
            harness.open();
            Throwable observed =
                    catchThrowable(
                            () -> {
                                harness.processElement(new StreamRecord<>(1L));
                                ((ActionExecutionOperator<?, ?>) harness.getOperator())
                                        .waitInFlightEventsFinished();
                            });

            assertThat(UncapturableException.isUncapturable(observed)).isTrue();
            assertThat(observed)
                    .hasRootCauseInstanceOf(IOException.class)
                    .hasRootCauseMessage(INJECTED_FAILURE);
            assertThat(harness.getRecordOutput()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(30)
    void recoveryReusesCompletedChildAfterItsCallResultWriteFailed(boolean parallel)
            throws Exception {
        FaultingStore store = new FaultingStore(true);
        OperatorSubtaskState checkpoint;
        FaultingStore recoveredStore;
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> first =
                harness(parallel, store, false)) {
            first.open();
            checkpoint = first.snapshot(1L, 1L);
            Throwable failure =
                    catchThrowable(
                            () -> {
                                first.processElement(new StreamRecord<>(1L));
                                ((ActionExecutionOperator<?, ?>) first.getOperator())
                                        .waitInFlightEventsFinished();
                            });
            assertThat(UncapturableException.isUncapturable(failure)).isTrue();
            assertThat(failure)
                    .hasRootCauseInstanceOf(IOException.class)
                    .hasRootCauseMessage(INJECTED_FAILURE);
            assertThat(CHILD_EXECUTIONS.get()).isEqualTo(1);
            recoveredStore = store.copyWithoutFault();
        }
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> restored =
                harness(parallel, recoveredStore, false)) {
            restored.initializeState(checkpoint);
            restored.open();
            restored.processElement(new StreamRecord<>(1L));
            ((ActionExecutionOperator<?, ?>) restored.getOperator()).waitInFlightEventsFinished();
            // The first child's completed ActionState survived; only the second child runs now.
            assertThat(CHILD_EXECUTIONS.get()).isEqualTo(2);
            assertThat(restored.getRecordOutput())
                    .extracting(StreamRecord::getValue)
                    .containsExactly("handled child success");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(30)
    void ordinaryChildFailureStillReturnsAFailedResult(boolean parallel) throws Exception {
        FaultingStore store = new FaultingStore(false);
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                harness(parallel, store, false)) {
            harness.open();
            harness.processElement(new StreamRecord<>(2L));
            ((ActionExecutionOperator<?, ?>) harness.getOperator()).waitInFlightEventsFinished();
            assertThat(CHILD_EXECUTIONS.get()).isEqualTo(2);
            assertThat(harness.getRecordOutput())
                    .extracting(StreamRecord::getValue)
                    .containsExactly("handled child failure");
        }
    }

    private static KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness(
            boolean parallel, FaultingStore store, boolean nested) throws Exception {
        Agent parent = new Agent();
        parent.addResource("child", ResourceType.AGENT, new Child());
        parent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                InternalSubagentFrameworkFaultTest.class.getMethod(
                        "request", Event.class, RunnerContext.class));
        parent.addAction(
                new String[] {ToolResponseEvent.EVENT_TYPE},
                InternalSubagentFrameworkFaultTest.class.getMethod(
                        "receive", Event.class, RunnerContext.class));
        if (nested) {
            Agent outer = new Agent();
            outer.addResource("middle", ResourceType.AGENT, parent);
            outer.addAction(
                    new String[] {InputEvent.EVENT_TYPE},
                    InternalSubagentFrameworkFaultTest.class.getMethod(
                            "callMiddle", Event.class, RunnerContext.class));
            parent = outer;
        }
        AgentConfiguration config = new AgentConfiguration();
        config.set(
                AgentExecutionOptions.PARALLEL_EXECUTION_ENABLED,
                !ContinuationActionExecutor.isContinuationSupported());
        config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, parallel);
        config.set(AgentExecutionOptions.ASYNC_BATCH_PARALLELISM, 2);
        AgentPlan compiled = new AgentPlan(parent, config);
        Map<String, Action> actions = new LinkedHashMap<>(compiled.getActions());
        actions.remove("chat_model_action");
        AgentPlan plan = new AgentPlan(actions, compiled.getResourceProviders(), config);
        return new KeyedOneInputStreamOperatorTestHarness<>(
                new ActionExecutionOperatorFactory<Long, Object>(plan, true, store),
                (KeySelector<Long, Long>) value -> 0L,
                TypeInformation.of(Long.class));
    }

    public static void callMiddle(Event event, RunnerContext ctx) throws Exception {
        SubagentSetup middle = (SubagentSetup) ctx.getResource("middle", ResourceType.AGENT);
        SubagentResult result = middle.submit(ctx, InputEvent.fromEvent(event).getInput()).await();
        ctx.sendEvent(
                new OutputEvent(
                        result.isSuccess() ? "unexpected success" : result.getErrorMessage()));
    }

    public static void request(Event event, RunnerContext ctx) {
        boolean failChild = ((Number) InputEvent.fromEvent(event).getInput()).longValue() == 2;
        ctx.sendEvent(
                new ToolRequestEvent(
                        "model",
                        List.of(
                                new ToolCallBlock(
                                        "call-1", "_subagent_child", Map.of("fail", failChild)),
                                new ToolCallBlock(
                                        "call-2", "_subagent_child", Map.of("fail", failChild)))));
    }

    public static void receive(Event event, RunnerContext ctx) {
        ToolResponseEvent response = ToolResponseEvent.fromEvent(event);
        if (response.getSuccess().values().stream().allMatch(Boolean.TRUE::equals)) {
            ctx.sendEvent(new OutputEvent("handled child success"));
            return;
        }
        for (String call : List.of("call-1", "call-2")) {
            assertThat(response.getSuccess().get(call)).isFalse();
            assertThat(response.getError().get(call)).contains("child rejected");
        }
        ctx.sendEvent(new OutputEvent("handled child failure"));
    }

    public static class Child extends Agent {
        public Child() throws Exception {
            addAction(
                    new String[] {InputEvent.EVENT_TYPE},
                    Child.class.getMethod("execute", Event.class, RunnerContext.class));
        }

        public static void execute(Event event, RunnerContext ctx) {
            CHILD_EXECUTIONS.incrementAndGet();
            Map<?, ?> input = (Map<?, ?>) InputEvent.fromEvent(event).getInput();
            if (Boolean.TRUE.equals(input.get("fail"))) {
                throw new IllegalArgumentException("child rejected");
            }
            ctx.sendEvent(new OutputEvent("child succeeded"));
        }
    }

    static final class FaultingStore extends InMemoryActionStateStore {
        private final boolean inject;

        FaultingStore(boolean inject) {
            super(false);
            this.inject = inject;
        }

        @Override
        public void put(Object key, long seqNum, Action action, Event event, ActionState state)
                throws IOException {
            if (inject
                    && action.getName().equals("tool_call_action")
                    && state.getCallResults().stream()
                            .anyMatch(call -> call.getFunctionId().startsWith("subagent:"))) {
                throw new IOException(INJECTED_FAILURE);
            }
            // Model a committed write, not a shared mutable ActionState reference.
            super.put(key, seqNum, action, event, copy(state));
        }

        @Override
        public ActionState get(Object key, long seqNum, Action action, Event event)
                throws IOException {
            ActionState state = super.get(key, seqNum, action, event);
            return state == null ? null : copy(state);
        }

        private FaultingStore copyWithoutFault() {
            FaultingStore restored = new FaultingStore(false);
            getKeyedActionStates()
                    .forEach(
                            (key, states) -> {
                                Map<String, ActionState> copied = new HashMap<>();
                                states.forEach((id, state) -> copied.put(id, copy(state)));
                                restored.getKeyedActionStates().put(key, copied);
                            });
            return restored;
        }

        private static ActionState copy(ActionState state) {
            return ActionStateSerde.deserialize(ActionStateSerde.serialize(state));
        }
    }
}
