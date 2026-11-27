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
package org.apache.flink.agents.integration.test;

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
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises per-request budgets in a Java Flink job. */
@Timeout(60)
public class ReActLoopBudgetE2ETest {
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
            if (messages.stream().anyMatch(message -> "hello:time".equals(message.getText()))) {
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
        switch (args[1]) {
            case "turn":
                builder.maxTurns(1);
                break;
            case "token":
                builder.maxTokens(5L);
                break;
            case "time":
                builder.maxTime(Duration.ofSeconds(10));
                break;
            default:
                throw new IllegalArgumentException("Unknown budget: " + args[1]);
        }
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

    private static Agent agent() throws Exception {
        Agent agent = new Agent();
        agent.addResource(
                "connection",
                ResourceType.CHAT_MODEL_CONNECTION,
                ResourceDescriptor.Builder.newBuilder(Connection.class.getName()).build());
        agent.addResource(
                "echo",
                ResourceType.TOOL,
                Tool.fromMethod(ReActLoopBudgetE2ETest.class.getMethod("echo")));
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder(Setup.class.getName())
                        .addInitialArgument("connection", "connection")
                        .addInitialArgument("model", "local")
                        .addInitialArgument("tools", List.of("echo"))
                        .build();
        agent.addResource("model", ResourceType.CHAT_MODEL, descriptor);
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                ReActLoopBudgetE2ETest.class.getMethod(
                        "request", Event.class, RunnerContext.class));
        agent.addAction(
                new String[] {ChatResponseEvent.EVENT_TYPE},
                ReActLoopBudgetE2ETest.class.getMethod(
                        "receive", Event.class, RunnerContext.class));
        return agent;
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
                agents.fromDataStream(env.fromElements(mode + ":" + limit), new InputKey())
                        .apply(agent())
                        .toDataStream();
        List<Object> results = new ArrayList<>();
        try (CloseableIterator<Object> iterator = output.collectAsync()) {
            agents.execute();
            iterator.forEachRemaining(results::add);
        }
        assertThat(results).hasSize(1);
        if (mode.equals("SOFT")) {
            assertThat(results.get(0)).isEqualTo("closed");
        } else {
            assertThat(results.get(0).toString())
                    .contains(limit.toUpperCase(java.util.Locale.ROOT) + "_BUDGET_EXCEEDED");
        }
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
}
