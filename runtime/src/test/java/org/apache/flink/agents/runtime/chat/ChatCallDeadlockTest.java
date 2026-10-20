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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperator;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperatorFactory;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chat side of GitHub issue #1213. Awaiting a chat response used to be dispatched to an
 * async-pool worker, which then blocked there for the whole call. The chat subgraph the caller is
 * waiting for runs its own {@code chat_model_action} and {@code tool_call_action} through {@code
 * durableExecuteAsync}, so it needs a worker from that same finite pool to make progress. With
 * {@code num-async-threads=1} the caller holds the only worker the subgraph needs — a self-deadlock
 * — and nested or concurrent chat calls exhaust any larger pool the same way.
 *
 * <p>The wait must instead yield the caller's continuation and hold no worker. These tests time out
 * under the broken behavior and complete promptly once fixed.
 */
@EnabledForJreRange(min = JRE.JAVA_21)
public class ChatCallDeadlockTest {

    private static final AtomicInteger MODEL_CALLS = new AtomicInteger();
    private static final AtomicInteger TOOL_CALLS = new AtomicInteger();

    @BeforeEach
    void resetCounters() {
        MODEL_CALLS.set(0);
        TOOL_CALLS.set(0);
    }

    /**
     * Trivial provider: it returns immediately, but reaching it still costs an async-pool worker
     * because {@code chat.async} and {@code tool-call.async} keep their defaults. Under the
     * deadlock that worker is never granted.
     */
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
        public ChatResult chat(
                List<ChatMessage> messages, Map<String, Object> args, Map<String, Object> params) {
            MODEL_CALLS.incrementAndGet();
            String text = messages.get(0).getText();
            if ("tool".equals(text) && messages.size() == 1) {
                return new ChatResult(
                        ChatMessage.assistant(
                                List.of(
                                        new ToolCallBlock(
                                                "tool-1", "echo", Map.of("value", "hello")))));
            }
            return new ChatResult(ChatMessage.assistant("answer:" + text));
        }
    }

    public static String echo(@ToolParam(name = "value") String value) {
        TOOL_CALLS.incrementAndGet();
        return value;
    }

    @SuppressWarnings("unused")
    public static void caller(Event event, RunnerContext ctx) throws Exception {
        String input = String.valueOf(InputEvent.fromEvent(event).getInput());
        ctx.getShortTermMemory().set("caller", "visible");
        ChatResult response = ctx.chat("model", List.of(ChatMessage.user(input))).await();
        ctx.sendEvent(new OutputEvent(response.getText()));
    }

    /**
     * One model turn: the caller's await must not hold the only worker, or {@code
     * chat_model_action} can never run its durable async call and this test times out.
     */
    @Test
    @Timeout(30)
    void singleAsyncThreadDoesNotDeadlockOnChatModelCall() throws Exception {
        assertThat(run("hello")).containsExactly("answer:hello");
        assertThat(MODEL_CALLS.get()).isEqualTo(1);
        assertThat(TOOL_CALLS.get()).isZero();
    }

    /**
     * A model/tool/model loop multiplies the workers the subgraph needs while the caller's single
     * wait is still in flight, so both built-in actions must be served without the caller holding
     * one.
     */
    @Test
    @Timeout(30)
    void singleAsyncThreadDoesNotDeadlockOnChatToolLoop() throws Exception {
        assertThat(run("tool")).containsExactly("answer:tool");
        assertThat(MODEL_CALLS.get()).isEqualTo(2);
        assertThat(TOOL_CALLS.get()).isEqualTo(1);
    }

    // --- helpers ---

    /**
     * A plan with a single async worker. {@code chat.async} and {@code tool-call.async} stay at
     * their defaults, so the chat subgraph genuinely competes with the caller for that one worker.
     */
    private static AgentPlan plan() throws Exception {
        Agent agent = new Agent();
        agent.addResource(
                "model",
                ResourceType.CHAT_MODEL,
                ResourceDescriptor.Builder.newBuilder(Model.class.getName()).build());
        agent.addResource(
                "echo",
                ResourceType.TOOL,
                Tool.fromMethod(ChatCallDeadlockTest.class.getMethod("echo", String.class)));
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                ChatCallDeadlockTest.class.getMethod("caller", Event.class, RunnerContext.class));
        AgentPlan plan = new AgentPlan(agent);
        plan.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 1);
        return plan;
    }

    private static List<Object> run(String input) throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<String, String, Object> harness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(plan(), true),
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
