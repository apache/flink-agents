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
package org.apache.flink.agents.runtime.operator;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.DurableFuture;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolParameterSource;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateSerde;
import org.apache.flink.agents.runtime.actionstate.InMemoryActionStateStore;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.mailbox.TaskMailbox;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledForJreRange(min = JRE.JAVA_21)
@Timeout(20)
public class ChatCallTest {
    private static final AtomicInteger MODEL_CALLS = new AtomicInteger();
    private static final AtomicInteger TOOL_CALLS = new AtomicInteger();
    private static final List<String> ORDER = new ArrayList<>();
    private static volatile boolean tailStarted;
    private static CountDownLatch tailGate;

    @BeforeEach
    void reset() {
        MODEL_CALLS.set(0);
        TOOL_CALLS.set(0);
        ORDER.clear();
        tailStarted = false;
        tailGate = new CountDownLatch(1);
    }

    public static class Model extends BaseChatModelSetup {
        public Model(ResourceDescriptor descriptor, ResourceContext ctx) {
            super(descriptor, ctx);
        }

        @Override
        public void open() {}

        @Override
        public Map<String, Object> getParameters() {
            return Map.of();
        }

        @Override
        public ChatMessage chat(
                List<ChatMessage> messages, Map<String, Object> args, Map<String, Object> params) {
            MODEL_CALLS.incrementAndGet();
            String text = messages.get(0).getText();
            if ("fail".equals(text)) {
                throw new IllegalArgumentException("provider refused");
            }
            if ("fatal".equals(text)) {
                throw new AssertionError("provider fatal");
            }
            if ("tool".equals(text) && messages.size() == 1) {
                return ChatMessage.assistant(
                        "",
                        List.of(
                                Map.of(
                                        "id",
                                        "tool-1",
                                        "type",
                                        "function",
                                        "function",
                                        Map.of(
                                                "name",
                                                "echo",
                                                "arguments",
                                                Map.of("value", "hello")))));
            }
            ORDER.add("model");
            return ChatMessage.assistant("answer:" + text);
        }
    }

    public static String echo(
            @ToolParam(name = "value") String value,
            @ToolParam(
                            name = "caller",
                            injected = true,
                            source = ToolParameterSource.SHORT_TERM_MEMORY)
                    String caller) {
        assertThat(caller).isEqualTo("visible");
        TOOL_CALLS.incrementAndGet();
        return value;
    }

    public static void caller(Event event, RunnerContext ctx) throws Exception {
        String input = String.valueOf(InputEvent.fromEvent(event).getInput());
        ctx.getShortTermMemory().set("caller", "visible");
        ctx.sendEvent(new Event("ordinary"));
        ctx.chat("model", List.of(ChatMessage.user("unused")));
        DurableFuture<ChatMessage> call = ctx.chat("model", List.of(ChatMessage.user(input)));
        try {
            ChatMessage response = call.await();
            assertThat(call.await()).isSameAs(response);
            ctx.sendEvent(new OutputEvent(response.getText()));
        } catch (ChatResponseEvent.ChatResponseException error) {
            org.assertj.core.api.Assertions.assertThatThrownBy(call::await)
                    .isInstanceOf(ChatResponseEvent.ChatResponseException.class)
                    .hasMessageContaining("provider refused");
            ctx.sendEvent(new OutputEvent("caught:" + error.getMessage()));
        }
        ORDER.add("caller-finished");
    }

    public static void ordinary(Event event, RunnerContext ctx) {
        ORDER.add("ordinary");
    }

    public static void leaked(Event event, RunnerContext ctx) {
        throw new AssertionError("Private chat event reached user action: " + event.getType());
    }

    public static void recoveryCaller(Event event, RunnerContext ctx) throws Exception {
        ctx.getShortTermMemory().set("caller", "visible");
        String prompt = "tool".equals(InputEvent.fromEvent(event).getInput()) ? "tool" : "hello";
        ChatMessage response = ctx.chat("model", List.of(ChatMessage.user(prompt))).await();
        ctx.durableExecuteAsync(
                        new DurableCallable<String>() {
                            @Override
                            public String getId() {
                                return "tail";
                            }

                            @Override
                            public Class<String> getResultClass() {
                                return String.class;
                            }

                            @Override
                            public String call() throws Exception {
                                tailStarted = true;
                                tailGate.await();
                                return "done";
                            }
                        })
                .await();
        ctx.sendEvent(new OutputEvent(response.getText()));
    }

    public static void subagentCaller(Event event, RunnerContext ctx) throws Exception {
        SubagentSetup child = (SubagentSetup) ctx.getResource("child", ResourceType.AGENT);
        SubagentResult result = child.submit(ctx, "hello").await();
        ctx.sendEvent(new OutputEvent(((List<?>) result.getResult()).get(0)));
    }

    static AgentPlan plan() throws Exception {
        return plan("caller");
    }

    static AgentPlan plan(String caller) throws Exception {
        Agent agent = new Agent();
        agent.addResource(
                "model",
                ResourceType.CHAT_MODEL,
                ResourceDescriptor.Builder.newBuilder(Model.class.getName()).build());
        agent.addResource(
                "echo",
                ResourceType.TOOL,
                Tool.fromMethod(ChatCallTest.class.getMethod("echo", String.class, String.class)));
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                ChatCallTest.class.getMethod(caller, Event.class, RunnerContext.class));
        agent.addAction(
                new String[] {"ordinary"},
                ChatCallTest.class.getMethod("ordinary", Event.class, RunnerContext.class));
        agent.addAction(
                new String[] {
                    ChatRequestEvent.EVENT_TYPE,
                    ChatResponseEvent.EVENT_TYPE,
                    ToolRequestEvent.EVENT_TYPE,
                    ToolResponseEvent.EVENT_TYPE
                },
                ChatCallTest.class.getMethod("leaked", Event.class, RunnerContext.class));
        AgentPlan plan = new AgentPlan(agent);
        // One worker waits for the response, another executes asynchronous model/tool work.
        plan.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 2);
        return plan;
    }

    @Test
    void lazyRepeatedAwaitAndOrdinaryEventIsolation() throws Exception {
        assertThat(run("hello")).containsExactly("answer:hello");
        assertThat(MODEL_CALLS.get()).isEqualTo(1);
        assertThat(ORDER).containsExactly("model", "caller-finished", "ordinary");
    }

    @Test
    void failedChatIsCatchableAndDoesNotFailJob() throws Exception {
        assertThat(run("fail").get(0).toString()).contains("caught:", "provider refused");
        assertThat(ORDER).containsExactly("caller-finished", "ordinary");
    }

    @Test
    void fullChatToolChatLoopWithAsyncWorkerWait() throws Exception {
        assertThat(run("tool")).containsExactly("answer:tool");
        assertThat(MODEL_CALLS.get()).isEqualTo(2);
        assertThat(TOOL_CALLS.get()).isEqualTo(1);
    }

    @Test
    void oneWaitWorkerSupportsSynchronousModelAndToolExecution() throws Exception {
        AgentPlan plan = plan();
        plan.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 1);
        plan.getConfig().set(AgentExecutionOptions.CHAT_ASYNC, false);
        plan.getConfig().set(AgentExecutionOptions.TOOL_CALL_ASYNC, false);
        assertThat(run(plan, "tool")).containsExactly("answer:tool");
        assertThat(MODEL_CALLS.get()).isEqualTo(2);
        assertThat(TOOL_CALLS.get()).isEqualTo(1);
        assertThat(ORDER).containsExactly("model", "caller-finished", "ordinary");
    }

    @Test
    void fatalProviderErrorStillFailsTheJob() {
        assertThatThrownBy(() -> run("fatal")).hasRootCauseMessage("provider fatal");
    }

    @Test
    void chatInsideInternalSubagentUsesChildResources() throws Exception {
        Agent child = new Agent();
        child.addResource(
                "model",
                ResourceType.CHAT_MODEL,
                ResourceDescriptor.Builder.newBuilder(Model.class.getName()).build());
        child.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                ChatCallTest.class.getMethod("caller", Event.class, RunnerContext.class));
        Agent parent = new Agent();
        parent.addResource("child", ResourceType.AGENT, child);
        parent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                ChatCallTest.class.getMethod("subagentCaller", Event.class, RunnerContext.class));
        assertThat(run(new AgentPlan(parent), "hello")).containsExactly("answer:hello");
        assertThat(MODEL_CALLS.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void checkpointRecoveryBeforeDispatchAfterResponseAndAfterAwait(int phase) throws Exception {
        AgentPlan plan = plan("recoveryCaller");
        // Deterministically stop between event dispatches; the caller still uses a continuation.
        plan.getConfig().set(AgentExecutionOptions.CHAT_ASYNC, false);
        plan.getConfig().set(AgentExecutionOptions.TOOL_CALL_ASYNC, false);
        InMemoryActionStateStore store = new InMemoryActionStateStore(false);
        OperatorSubtaskState checkpoint;
        InMemoryActionStateStore saved = new InMemoryActionStateStore(false);
        try (KeyedOneInputStreamOperatorTestHarness<String, String, Object> original =
                harness(plan, store)) {
            original.open();
            original.processElement(new StreamRecord<>(phase == 3 ? "tool" : "key"));
            original.getTaskMailbox().take(TaskMailbox.MIN_PRIORITY).run();
            if (phase >= 1) {
                original.getTaskMailbox().take(TaskMailbox.MIN_PRIORITY).run();
                assertThat(MODEL_CALLS.get()).isEqualTo(1);
            }
            if (phase == 2) {
                while (!tailStarted) {
                    original.getTaskMailbox().take(TaskMailbox.MIN_PRIORITY).run();
                }
            }
            if (phase == 3) {
                while (MODEL_CALLS.get() < 2) {
                    original.getTaskMailbox().take(TaskMailbox.MIN_PRIORITY).run();
                }
                assertThat(TOOL_CALLS.get()).isEqualTo(1);
            }
            checkpoint = original.snapshot(1, 1);
            store.getKeyedActionStates()
                    .forEach(
                            (key, states) -> {
                                Map<String, ActionState> copies = new LinkedHashMap<>();
                                states.forEach(
                                        (id, state) ->
                                                copies.put(
                                                        id,
                                                        ActionStateSerde.deserialize(
                                                                ActionStateSerde.serialize(
                                                                        state))));
                                saved.getKeyedActionStates().put(key, copies);
                            });
        } finally {
            tailGate.countDown();
        }
        try (KeyedOneInputStreamOperatorTestHarness<String, String, Object> restored =
                harness(plan, saved)) {
            restored.initializeState(checkpoint);
            restored.open();
            ((ActionExecutionOperator<?, ?>) restored.getOperator()).waitInFlightEventsFinished();
            assertThat(MODEL_CALLS.get()).isEqualTo(phase == 3 ? 2 : 1);
            assertThat(restored.getRecordOutput()).hasSize(1);
            assertThat(restored.getRecordOutput().iterator().next().getValue())
                    .isEqualTo(phase == 3 ? "answer:tool" : "answer:hello");
            if (phase == 3) {
                assertThat(TOOL_CALLS.get()).isEqualTo(1);
            }
        }
    }

    private static KeyedOneInputStreamOperatorTestHarness<String, String, Object> harness(
            AgentPlan plan, InMemoryActionStateStore store) throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<>(
                new ActionExecutionOperatorFactory<>(plan, true, store),
                (KeySelector<String, String>) value -> value,
                TypeInformation.of(String.class));
    }

    private static List<Object> run(String input) throws Exception {
        return run(plan(), input);
    }

    private static List<Object> run(AgentPlan plan, String input) throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<String, String, Object> harness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(plan, true),
                        (KeySelector<String, String>) value -> value,
                        TypeInformation.of(String.class))) {
            harness.open();
            harness.processElement(new StreamRecord<>(input));
            ((ActionExecutionOperator<?, ?>) harness.getOperator()).waitInFlightEventsFinished();
            List<Object> values = new ArrayList<>();
            for (Object record : harness.getRecordOutput()) {
                values.add(((StreamRecord<?>) record).getValue());
            }
            return values;
        }
    }
}
