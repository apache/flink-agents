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
package org.apache.flink.agents.resource.test;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.agents.ReActLoopBudget;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.TokenUsage;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.resource.PythonResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateSerde;
import org.apache.flink.agents.runtime.actionstate.ActionStateStore;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperator;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperatorFactory;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs real Python actions/resources through Pemja and the checkpointable operator. */
@EnabledForJreRange(min = JRE.JAVA_21)
@Timeout(60)
public class ReActLoopBudgetCrossLanguageTest {
    private static final AtomicInteger NORMAL_CALLS = new AtomicInteger();
    private static final AtomicInteger CLOSING_CALLS = new AtomicInteger();
    private static final AtomicInteger TOOL_CALLS = new AtomicInteger();

    @BeforeEach
    void reset() {
        NORMAL_CALLS.set(0);
        CLOSING_CALLS.set(0);
        TOOL_CALLS.set(0);
    }

    public static class Connection extends BaseChatModelConnection {
        public Connection(ResourceDescriptor descriptor, ResourceContext ctx) {
            super(descriptor, ctx);
        }

        @Override
        public ChatResult chat(
                List<ChatMessage> messages, List<Tool> tools, Map<String, Object> params) {
            if (tools.isEmpty()) {
                CLOSING_CALLS.incrementAndGet();
                assertThat(params).doesNotContainKey("tool_choice");
                assertThat(messages.get(messages.size() - 1).getText())
                        .contains("Do not call tools");
                return new ChatResult(ChatMessage.assistant("closed"));
            }
            NORMAL_CALLS.incrementAndGet();
            assertThat(params).containsEntry("tool_choice", "required");
            return new ChatResult(
                    ChatMessage.assistant(List.of(new ToolCallBlock("call", "echo", Map.of()))),
                    null,
                    null,
                    new TokenUsage(3L, 2L),
                    null,
                    Map.of());
        }
    }

    public static class Setup extends BaseChatModelSetup {
        public Setup(ResourceDescriptor descriptor, ResourceContext ctx) {
            super(descriptor, ctx);
        }

        @Override
        public Map<String, Object> getParameters() {
            return new HashMap<>(Map.of("tool_choice", "required"));
        }
    }

    public static String echo() {
        TOOL_CALLS.incrementAndGet();
        return "tool result";
    }

    public static void request(Event event, RunnerContext ctx) {
        String[] args = InputEvent.fromEvent(event).getInput().toString().split(":");
        ReActLoopBudget.Builder builder =
                ReActLoopBudget.builder().mode(ReActLoopBudget.BudgetMode.valueOf(args[0]));
        if (args[1].startsWith("turn")) builder.maxTurns(args[1].endsWith("-replay") ? 2 : 1);
        if (args[1].startsWith("token")) builder.maxTokens(args[1].endsWith("-replay") ? 10L : 5L);
        builder.maxTime(Duration.ofMillis(args[1].equals("time") ? 1000 : 60000));
        ctx.sendEvent(
                new ChatRequestEvent(
                        "model",
                        List.of(ChatMessage.user("hello")),
                        null,
                        null,
                        null,
                        builder.build()));
    }

    public static void receive(Event event, RunnerContext ctx) {
        ChatResponseEvent response = ChatResponseEvent.fromEvent(event);
        ctx.sendEvent(
                new OutputEvent(
                        response.isSuccess()
                                ? response.getResponse().getText()
                                : response.getError()));
    }

    private static AgentPlan plan(boolean python) throws Exception {
        Agent agent = new Agent();
        agent.addResource(
                "connection",
                ResourceType.CHAT_MODEL_CONNECTION,
                ResourceDescriptor.Builder.newBuilder(Connection.class.getName()).build());
        agent.addResource(
                "echo",
                ResourceType.TOOL,
                Tool.fromMethod(ReActLoopBudgetCrossLanguageTest.class.getMethod("echo")));
        ResourceDescriptor descriptor =
                python
                        ? ResourceDescriptor.Builder.newBuilder(Setup.class.getName())
                                .addInitialArgument("connection", "connection")
                                .addInitialArgument("model", "local")
                                .addInitialArgument("tools", List.of("echo"))
                                .build()
                        : PythonResourceDescriptor.Builder.newBuilder(
                                        "flink_agents.runtime.tests.budget_bridge_fixtures.BudgetModelSetup")
                                .addInitialArgument("connection", "connection")
                                .addInitialArgument("model", "local")
                                .addInitialArgument("tools", List.of("echo"))
                                .build();
        agent.addResource("model", ResourceType.CHAT_MODEL, descriptor);
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                ReActLoopBudgetCrossLanguageTest.class.getMethod(
                        "request", Event.class, RunnerContext.class));
        agent.addAction(
                new String[] {ChatResponseEvent.EVENT_TYPE},
                ReActLoopBudgetCrossLanguageTest.class.getMethod(
                        "receive", Event.class, RunnerContext.class));
        AgentPlan plan = new AgentPlan(agent);
        if (python) {
            Map<String, Action> actions = new HashMap<>(plan.getActions());
            actions.put(
                    "chat_model_action",
                    new Action(
                            "chat_model_action",
                            new PythonFunction(
                                    "flink_agents.plan.actions.chat_model_action",
                                    "process_chat_request_or_tool_response"),
                            List.of(ChatRequestEvent.EVENT_TYPE, ToolResponseEvent.EVENT_TYPE)));
            plan = new AgentPlan(actions, plan.getResourceProviders(), plan.getConfig());
        }
        plan.getConfig().set(AgentExecutionOptions.CHAT_ASYNC, false);
        plan.getConfig().set(AgentExecutionOptions.TOOL_CALL_ASYNC, false);
        plan.getConfig().set(AgentExecutionOptions.MAX_RETRIES, 0);
        return plan;
    }

    private static KeyedOneInputStreamOperatorTestHarness<String, String, Object> harness(
            AgentPlan plan, SerializedStore store) throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<>(
                new ActionExecutionOperatorFactory<>(plan, true, store),
                (KeySelector<String, String>) value -> value,
                TypeInformation.of(String.class));
    }

    @ParameterizedTest
    @CsvSource({
        "false,HARD,turn",
        "false,SOFT,turn",
        "true,HARD,turn",
        "true,SOFT,turn",
        "false,HARD,token",
        "false,SOFT,token",
        "true,HARD,token",
        "true,SOFT,token"
    })
    void budgetCrossesTheModelBridge(boolean python, String mode, String limit) throws Exception {
        try (var h = harness(plan(python), new SerializedStore())) {
            h.open();
            h.processElement(new StreamRecord<>(mode + ":" + limit));
            ((ActionExecutionOperator<?, ?>) h.getOperator()).waitInFlightEventsFinished();
            assertThat(NORMAL_CALLS.get()).as("output: %s", h.getRecordOutput()).isEqualTo(1);
            assertThat(TOOL_CALLS.get()).isEqualTo(1);
            assertResult(h, mode, limit);
        }
    }

    private static void assertResult(
            KeyedOneInputStreamOperatorTestHarness<String, String, Object> h,
            String mode,
            String limit) {
        assertThat(h.getRecordOutput()).hasSize(1);
        String output = h.getRecordOutput().iterator().next().getValue().toString();
        if (mode.equals("SOFT")) {
            assertThat(output).isEqualTo("closed");
            assertThat(CLOSING_CALLS.get()).isEqualTo(1);
        } else {
            assertThat(output).contains(limit.toUpperCase() + "_BUDGET_EXCEEDED");
            assertThat(CLOSING_CALLS.get()).isZero();
        }
    }

    /** A fresh copy on every write models the durable storage boundary. */
    private static class SerializedStore implements ActionStateStore {
        private final Map<Object, Map<String, ActionState>> states = new HashMap<>();

        private static String id(long seq, Action action, Event event) {
            return seq + ":" + action.getName() + ":" + event.getId();
        }

        @Override
        public void put(Object key, long seq, Action action, Event event, ActionState state)
                throws IOException {
            states.computeIfAbsent(key, ignored -> new HashMap<>())
                    .put(
                            id(seq, action, event),
                            ActionStateSerde.deserialize(ActionStateSerde.serialize(state)));
        }

        @Override
        public ActionState get(Object key, long seq, Action action, Event event)
                throws IOException {
            return states.getOrDefault(key, Map.of()).get(id(seq, action, event));
        }

        Map<Object, Map<String, ActionState>> getKeyedActionStates() {
            return states;
        }

        @Override
        public void rebuildState(List<Object> markers) {}

        @Override
        public void pruneState(Object key, long seq) {}

        @Override
        public void close() {}
    }

    /** Persist serialized copies, then fail after the first model result is durable. */
    private static class CrashStore extends SerializedStore {
        private byte[] startPayload;
        private boolean crashed;

        @Override
        public void put(Object key, long seq, Action action, Event event, ActionState state)
                throws IOException {
            super.put(key, seq, action, event, state);
            if (!state.isCompleted()
                    && state.getCallResults().stream()
                            .anyMatch(
                                    call ->
                                            call.isSuccess()
                                                    && call.getFunctionId().equals("chat"))) {
                assertThat(state.getSensoryMemoryUpdates()).isEmpty();
                startPayload =
                        state.getCallResults().stream()
                                .filter(
                                        call ->
                                                call.getFunctionId()
                                                        .equals("react-loop:budget-start"))
                                .findFirst()
                                .orElseThrow()
                                .getResultPayload();
                crashed = true;
                throw new IOException("crash after persisted model result");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"HARD,time", "SOFT,time", "HARD,turn", "SOFT,turn", "HARD,token", "SOFT,token"})
    void pythonBudgetRecoversAfterPersistedResponse(String mode, String limit) throws Exception {
        AgentPlan plan = plan(true);
        CrashStore store = new CrashStore();
        OperatorSubtaskState checkpoint;
        try (var original = harness(plan, store)) {
            original.open();
            original.processElement(
                    new StreamRecord<>(
                            mode + ":" + limit + (limit.equals("time") ? "" : "-replay")));
            checkpoint = original.snapshot(1, 1);
            assertThatThrownBy(
                            () ->
                                    ((ActionExecutionOperator<?, ?>) original.getOperator())
                                            .waitInFlightEventsFinished())
                    .hasStackTraceContaining("crash after persisted model result");
            assertThat(store.crashed).isTrue();
            assertThat(NORMAL_CALLS.get()).isEqualTo(1);
            assertThat(original.getRecordOutput()).isEmpty();
        }
        // Emulate downtime only for the time-budget cases; no runtime state is patched.
        if (limit.equals("time")) Thread.sleep(1100);
        boolean[] replayedStart = {false};
        SerializedStore restoredStore =
                new SerializedStore() {
                    @Override
                    public ActionState get(Object key, long seq, Action action, Event event)
                            throws IOException {
                        ActionState state = super.get(key, seq, action, event);
                        if (state != null) {
                            state.getCallResults().stream()
                                    .filter(
                                            call ->
                                                    call.getFunctionId()
                                                            .equals("react-loop:budget-start"))
                                    .forEach(
                                            call -> {
                                                assertThat(call.getResultPayload())
                                                        .isEqualTo(store.startPayload);
                                                replayedStart[0] = true;
                                            });
                        }
                        return state;
                    }
                };
        store.getKeyedActionStates()
                .forEach(
                        (key, states) -> {
                            Map<String, ActionState> copies = new HashMap<>();
                            states.forEach(
                                    (id, state) ->
                                            copies.put(
                                                    id,
                                                    ActionStateSerde.deserialize(
                                                            ActionStateSerde.serialize(state))));
                            restoredStore.getKeyedActionStates().put(key, copies);
                        });
        try (var restored = harness(plan, restoredStore)) {
            restored.initializeState(checkpoint);
            restored.open();
            ((ActionExecutionOperator<?, ?>) restored.getOperator()).waitInFlightEventsFinished();
            // With two turns / ten tokens, one cached five-token result and one fresh
            // result exhaust the budget. Missing or double charging changes this count.
            assertThat(NORMAL_CALLS.get()).isEqualTo(limit.equals("time") ? 1 : 2);
            assertThat(TOOL_CALLS.get()).isEqualTo(limit.equals("time") ? 0 : 2);
            assertResult(restored, mode, limit);
            // Completed actions compact their durable call records. Check the record as
            // the restored runtime reads it, before completion clears the journal.
            assertThat(replayedStart[0]).isTrue();
        }
    }
}
