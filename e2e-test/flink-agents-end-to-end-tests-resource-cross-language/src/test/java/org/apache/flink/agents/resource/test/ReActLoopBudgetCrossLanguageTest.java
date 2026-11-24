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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.AgentsExecutionEnvironment;
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
import org.apache.flink.agents.api.utils.JacksonMapperFactory;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateKeyEncoder;
import org.apache.flink.agents.runtime.actionstate.ActionStateSerde;
import org.apache.flink.agents.runtime.actionstate.ActionStateStore;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperatorFactory;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.base.StringSerializer;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import pemja.core.PythonInterpreter;
import pemja.core.PythonInterpreterConfig;

import java.io.IOException;
import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs real Python actions/resources through Pemja in Flink jobs. */
@Timeout(60)
public class ReActLoopBudgetCrossLanguageTest {

    private static final ObjectMapper MAPPER = JacksonMapperFactory.builderWithJavaTime().build();

    private static final AtomicInteger NORMAL_CALLS = new AtomicInteger();
    private static final AtomicInteger CLOSING_CALLS = new AtomicInteger();
    private static final AtomicInteger TOOL_CALLS = new AtomicInteger();

    @BeforeEach
    void reset() {
        NORMAL_CALLS.set(0);
        CLOSING_CALLS.set(0);
        TOOL_CALLS.set(0);
        RecoveryStore.STATES.clear();
        RecoveryStore.CRASHED.set(false);
        RecoveryStore.READ_PERSISTED_START.set(false);
        RecoveryStore.startPayload = null;
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 60001L, 31536000000L, 34560000001L, 63072000000L, 86399999999999999L})
    void budgetJsonRoundTripsThroughPython(long millis) throws Exception {
        var config =
                PythonInterpreterConfig.newBuilder()
                        .setPythonExec(System.getenv().getOrDefault("PYTHON_EXECUTABLE", "python3"))
                        .build();
        try (var interpreter = new PythonInterpreter(config)) {
            interpreter.exec("from flink_agents.api.react_loop_budget import ReActLoopBudget");
            for (var mode : ReActLoopBudget.BudgetMode.values()) {
                var budget =
                        ReActLoopBudget.builder()
                                .maxTurns(2)
                                .maxTokens(100L)
                                .maxTime(Duration.ofMillis(millis))
                                .mode(mode)
                                .build();
                interpreter.set("budget_json", MAPPER.writeValueAsString(budget));
                interpreter.exec(
                        "wire_json = ReActLoopBudget.model_validate_json(budget_json).model_dump_json()");
                var restored =
                        MAPPER.readValue(
                                (String) interpreter.get("wire_json"), ReActLoopBudget.class);
                assertThat(restored.getMaxTime()).isEqualTo(budget.getMaxTime());
                assertThat(restored.getMaxTurns()).isEqualTo(budget.getMaxTurns());
                assertThat(restored.getMaxTokens()).isEqualTo(budget.getMaxTokens());
                assertThat(restored.getMode()).isEqualTo(budget.getMode());
            }
        }
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
            if (messages.stream().anyMatch(message -> "hello:time-job".equals(message.getText()))) {
                try {
                    Thread.sleep(11000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
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
        builder.maxTime(Duration.ofMillis(args[1].equals("time-job") ? 10000 : 60000));
        ctx.sendEvent(
                new ChatRequestEvent(
                        "model",
                        List.of(ChatMessage.user("hello:" + args[1])),
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

    private static Agent agent(boolean javaModel) throws Exception {
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
                javaModel
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
        return agent;
    }

    private static AgentPlan plan(boolean python) throws Exception {
        AgentPlan plan = new AgentPlan(agent(python));
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

    @ParameterizedTest
    @CsvSource({"HARD,turn", "SOFT,turn", "HARD,token", "SOFT,token", "HARD,time", "SOFT,time"})
    void budgetsRunThroughPublicApiInFlinkJob(String mode, String limit) throws Exception {
        Configuration config = new Configuration();
        config.setString("restart-strategy.type", "disable");
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(config);
        env.setParallelism(1);
        AgentsExecutionEnvironment agents = AgentsExecutionEnvironment.getExecutionEnvironment(env);
        agents.getConfig().set(AgentExecutionOptions.CHAT_ASYNC, false);
        agents.getConfig().set(AgentExecutionOptions.TOOL_CALL_ASYNC, false);
        agents.getConfig().set(AgentExecutionOptions.MAX_RETRIES, 0);
        var output =
                agents.fromDataStream(
                                env.fromElements(
                                        mode + ":" + (limit.equals("time") ? "time-job" : limit)),
                                new InputKey())
                        .apply(agent(false))
                        .toDataStream();
        List<Object> results = new ArrayList<>();
        try (CloseableIterator<Object> iterator = output.collectAsync()) {
            agents.execute();
            iterator.forEachRemaining(results::add);
        }
        assertThat(results).hasSize(1);
        if (mode.equals("SOFT")) assertThat(results.get(0)).isEqualTo("closed");
        else
            assertThat(results.get(0).toString())
                    .contains(limit.toUpperCase() + "_BUDGET_EXCEEDED");
        assertThat(NORMAL_CALLS.get()).isEqualTo(1);
        assertThat(TOOL_CALLS.get()).isEqualTo(1);
        assertThat(CLOSING_CALLS.get()).isEqualTo(mode.equals("SOFT") ? 1 : 0);
    }

    public static class InputKey implements KeySelector<String, String> {
        @Override
        public String getKey(String value) {
            return value;
        }
    }

    @ParameterizedTest
    @CsvSource({
        "true,HARD,time", "false,SOFT,time",
        "true,SOFT,turn", "false,HARD,turn",
        "true,HARD,token", "false,SOFT,token"
    })
    void budgetRecoversAfterJobRestart(boolean python, String mode, String limit) throws Exception {
        Configuration config = new Configuration();
        config.setString("restart-strategy.type", "fixed-delay");
        config.setString("restart-strategy.fixed-delay.attempts", "1");
        config.setString("restart-strategy.fixed-delay.delay", "10 ms");
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(config);
        env.setParallelism(1);
        String input = mode + ":" + (limit.equals("time") ? "time-job" : limit + "-replay");
        var output =
                env.fromElements(input)
                        .keyBy(new InputKey())
                        .transform(
                                "budget recovery",
                                TypeInformation.of(Object.class),
                                new ActionExecutionOperatorFactory<String, Object>(
                                        plan(python), true, new RecoveryStore()));
        List<Object> results = new ArrayList<>();
        try (CloseableIterator<Object> iterator = output.collectAsync()) {
            env.execute();
            iterator.forEachRemaining(results::add);
        }
        assertThat(RecoveryStore.CRASHED).isTrue();
        assertThat(RecoveryStore.READ_PERSISTED_START).isTrue();
        assertThat(results).hasSize(1);
        if (mode.equals("SOFT")) assertThat(results.get(0)).isEqualTo("closed");
        else
            assertThat(results.get(0).toString())
                    .contains(limit.toUpperCase() + "_BUDGET_EXCEEDED");
        assertThat(NORMAL_CALLS.get()).isEqualTo(limit.equals("time") ? 1 : 2);
        assertThat(TOOL_CALLS.get()).isEqualTo(limit.equals("time") ? 1 : 2);
        assertThat(CLOSING_CALLS.get()).isEqualTo(mode.equals("SOFT") ? 1 : 0);
    }

    /** Serialized backend bytes survive task restarts in the local MiniCluster. */
    private static class RecoveryStore implements ActionStateStore, Serializable {
        private static final Map<String, byte[]> STATES = new ConcurrentHashMap<>();
        private static final AtomicBoolean CRASHED = new AtomicBoolean();
        private static final AtomicBoolean READ_PERSISTED_START = new AtomicBoolean();
        private static final ActionStateKeyEncoder KEYS =
                new ActionStateKeyEncoder(128, StringSerializer.INSTANCE);
        private static byte[] startPayload;

        @Override
        public void put(Object key, long seq, Action action, Event event, ActionState state)
                throws IOException {
            STATES.put(
                    KEYS.generateKey(key, seq, action, event), ActionStateSerde.serialize(state));
            if (!state.isCompleted()
                    && state.getCallResults().stream()
                            .anyMatch(
                                    call -> call.isSuccess() && call.getFunctionId().equals("chat"))
                    && CRASHED.compareAndSet(false, true)) {
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
                throw new IOException("crash after persisted model result");
            }
        }

        @Override
        public ActionState get(Object key, long seq, Action action, Event event)
                throws IOException {
            byte[] bytes = STATES.get(KEYS.generateKey(key, seq, action, event));
            if (bytes == null) return null;
            ActionState state = ActionStateSerde.deserialize(bytes);
            if (CRASHED.get() && !state.isCompleted()) {
                state.getCallResults().stream()
                        .filter(call -> call.getFunctionId().equals("react-loop:budget-start"))
                        .forEach(
                                call -> {
                                    assertThat(call.getResultPayload()).isEqualTo(startPayload);
                                    READ_PERSISTED_START.set(true);
                                });
            }
            return state;
        }

        @Override
        public void rebuildState(List<Object> markers) {}

        @Override
        public void pruneState(Object key, long seq) {}

        @Override
        public void close() {}
    }
}
