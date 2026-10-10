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
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.ChatModelConnection;
import org.apache.flink.agents.api.annotation.ChatModelSetup;
import org.apache.flink.agents.api.annotation.Tool;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.context.DurableFuture;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent.ChatResponseException;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.tools.ToolParameterSource;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs Java chat calls in real Flink jobs against the packaged continuation runtime. A
 * deterministic connection exercises the built-in model/tool loop without a network service.
 * Per-prompt counters detect accidental re-execution; key-specific output and injected memory
 * detect misrouting. Both synchronous and asynchronous built-in actions must preserve the same
 * caller-facing behavior.
 */
@EnabledForJreRange(min = JRE.JAVA_21)
@Timeout(60)
class ChatCallIntegrationTest {

    @BeforeAll
    static void usesPackagedContinuationExecutor() {
        assertThat(ContinuationActionExecutor.isContinuationSupported()).isTrue();
        assertThat(
                        ContinuationActionExecutor.class
                                .getResource("ContinuationActionExecutor.class")
                                .toString())
                .startsWith("jar:")
                .contains("!/META-INF/versions/21/");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void toolLoopsKeepMultipleKeysAndSequentialCallsIsolated(boolean async) throws Exception {
        assertThat(run(async, "alpha", "beta"))
                .containsExactlyInAnyOrder(
                        "answer:alpha:1:alpha|answer:alpha:2:alpha",
                        "answer:beta:1:beta|answer:beta:2:beta",
                        "ordinary:alpha",
                        "ordinary:beta");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedResponseIsCatchableAndDoesNotPreventTheNextCall(boolean async) throws Exception {
        assertThat(run(async, "failure"))
                .containsExactlyInAnyOrder("caught:answer:recovered:failure", "ordinary:failure");
    }

    private static List<String> run(boolean async, String... inputs) throws Exception {
        Configuration configuration = new Configuration();
        configuration.setString("restart-strategy.type", "disable");
        StreamExecutionEnvironment env =
                StreamExecutionEnvironment.getExecutionEnvironment(configuration);
        env.setParallelism(1);
        AgentsExecutionEnvironment agents = AgentsExecutionEnvironment.getExecutionEnvironment(env);
        // At most two callers wait at once; leave workers for their model/tool actions as well.
        agents.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 4);
        agents.getConfig().set(AgentExecutionOptions.CHAT_ASYNC, async);
        agents.getConfig().set(AgentExecutionOptions.TOOL_CALL_ASYNC, async);
        agents.getConfig().set(AgentExecutionOptions.MAX_RETRIES, 0);
        DataStream<Object> output =
                agents.fromDataStream(env.fromElements(inputs), new InputKeySelector())
                        .apply(new ChatAgent())
                        .toDataStream();
        List<String> actual = new ArrayList<>();
        try (CloseableIterator<Object> results = output.collectAsync()) {
            agents.execute();
            results.forEachRemaining(value -> actual.add(String.valueOf(value)));
        }
        return actual;
    }

    public static class InputKeySelector implements KeySelector<String, String> {
        @Override
        public String getKey(String value) {
            return value;
        }
    }

    /** Public for resource and action discovery from the serialized agent plan. */
    public static class ChatAgent extends Agent {
        @ChatModelConnection
        public static ResourceDescriptor connection() {
            return ResourceDescriptor.Builder.newBuilder(CountingConnection.class.getName())
                    .build();
        }

        @ChatModelSetup
        public static ResourceDescriptor model() {
            return ResourceDescriptor.Builder.newBuilder(TestModel.class.getName())
                    .addInitialArgument("connection", "connection")
                    .addInitialArgument("model", "deterministic")
                    .addInitialArgument("tools", List.of("echo"))
                    .build();
        }

        @Tool(description = "Echo the value with the calling action's memory.")
        public static String echo(
                @ToolParam(name = "value") String value,
                @ToolParam(
                                name = "caller",
                                injected = true,
                                source = ToolParameterSource.SHORT_TERM_MEMORY)
                        String caller) {
            return value + ":" + caller;
        }

        @Action(EventType.InputEvent)
        public static void call(Event event, RunnerContext ctx) throws Exception {
            String key = String.valueOf(InputEvent.fromEvent(event).getInput());
            ctx.getShortTermMemory().set("caller", key);
            ctx.sendEvent(new Event("ordinary"));
            ctx.chat("model", List.of(ChatMessage.user("unused")));
            String result;
            if ("failure".equals(key)) {
                DurableFuture<ChatMessage> failed =
                        ctx.chat("model", List.of(ChatMessage.user("fail")));
                for (int i = 0; i < 2; i++) {
                    assertThatThrownBy(failed::await)
                            .isInstanceOf(ChatResponseException.class)
                            .hasMessageContaining("provider refused");
                }
                result =
                        "caught:"
                                + ctx.chat("model", List.of(ChatMessage.user("recovered")))
                                        .await()
                                        .getText();
            } else {
                List<ChatMessage> messages = new ArrayList<>();
                messages.add(ChatMessage.user(key + ":1"));
                DurableFuture<ChatMessage> first = ctx.chat("model", messages);
                messages.clear(); // The future owns the request snapshot, not this mutable list.
                ChatMessage response = first.await();
                assertThat(first.await()).isSameAs(response);
                ChatMessage second =
                        ctx.chat(
                                        new ChatRequestEvent(
                                                "model", List.of(ChatMessage.user(key + ":2"))))
                                .await();
                result = response.getText() + "|" + second.getText();
            }
            assertThat(ctx.getShortTermMemory().get("caller").getValue()).isEqualTo(key);
            ctx.getShortTermMemory().set("finished", true);
            ctx.sendEvent(new OutputEvent(result));
        }

        @Action("ordinary")
        public static void ordinary(Event event, RunnerContext ctx) throws Exception {
            assertThat(ctx.getShortTermMemory().get("finished").getValue()).isEqualTo(true);
            ctx.sendEvent(
                    new OutputEvent(
                            "ordinary:" + ctx.getShortTermMemory().get("caller").getValue()));
        }

        @Action({
            EventType.ChatRequestEvent,
            EventType.ChatResponseEvent,
            EventType.ToolRequestEvent,
            EventType.ToolResponseEvent
        })
        public static void rejectLeakedEvent(Event event, RunnerContext ctx) {
            throw new AssertionError(
                    "Private chat event reached a user action: " + event.getType());
        }
    }

    public static class TestModel extends BaseChatModelSetup {
        public TestModel(ResourceDescriptor descriptor, ResourceContext context) {
            super(descriptor, context);
        }

        @Override
        public Map<String, Object> getParameters() {
            return new HashMap<>();
        }
    }

    public static class CountingConnection extends BaseChatModelConnection {
        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        public CountingConnection(ResourceDescriptor descriptor, ResourceContext context) {
            super(descriptor, context);
        }

        @Override
        public ChatMessage chat(
                List<ChatMessage> messages,
                List<org.apache.flink.agents.api.tools.Tool> tools,
                Map<String, Object> params) {
            String prompt = messages.get(0).getText();
            assertThat(prompt).isNotEqualTo("unused");
            int attempt =
                    calls.computeIfAbsent(prompt, ignored -> new AtomicInteger()).incrementAndGet();
            ChatMessage last = messages.get(messages.size() - 1);
            if (last.getRole() == MessageRole.TOOL) {
                assertThat(attempt).isEqualTo(2);
                return ChatMessage.assistant("answer:" + last.getText());
            }
            assertThat(attempt).isEqualTo(1);
            if ("fail".equals(prompt)) {
                throw new IllegalArgumentException("provider refused");
            }
            return ChatMessage.assistant(
                    "",
                    List.of(
                            Map.of(
                                    "id",
                                    "tool-" + prompt,
                                    "type",
                                    "function",
                                    "function",
                                    Map.of("name", "echo", "arguments", Map.of("value", prompt)))));
        }
    }
}
