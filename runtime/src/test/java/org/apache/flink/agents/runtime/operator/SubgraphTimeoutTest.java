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
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.context.AsyncFuture;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.subgraph.SubgraphCallManager;
import org.apache.flink.agents.runtime.subgraph.SubgraphFuture;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Late graph events traverse the real operator on both continuation and parallel engines. */
@Timeout(30)
public class SubgraphTimeoutTest {
    private static CountDownLatch release;
    private static final AtomicInteger started = new AtomicInteger();
    private static final AtomicInteger finished = new AtomicInteger();
    private static final AtomicInteger successors = new AtomicInteger();
    private static SubgraphCallManager calls;
    private static String invocationId;

    @BeforeEach
    void reset() {
        release = new CountDownLatch(1);
        started.set(0);
        finished.set(0);
        successors.set(0);
        calls = null;
        invocationId = null;
    }

    public static class Model extends BaseChatModelSetup {
        public Model(ResourceDescriptor descriptor, ResourceContext context) {
            super(descriptor, context);
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
            String prompt = messages.get(0).getText();
            awaitCaller(prompt);
            finished.incrementAndGet();
            return new ChatResult(ChatMessage.assistant(prompt));
        }
    }

    public static void caller(Event event, RunnerContext context) throws Exception {
        String input = (String) InputEvent.fromEvent(event).getInput();
        boolean chat = input.startsWith("chat");
        boolean timeout = input.endsWith("timeout");
        calls = ((RunnerContextImpl) context).getSubgraphCalls();
        AsyncFuture<?> future;
        if (chat) {
            future = context.chat("model", List.of(ChatMessage.user(input)));
        } else {
            SubagentSetup child = (SubagentSetup) context.getResource("child", ResourceType.AGENT);
            future = child.submit(context, input, "session", "call");
        }
        try {
            var outcome = context.gather(List.of(future)).await().get(0);
            invocationId =
                    chat ? ((SubgraphFuture<?, ?>) future).getInvocation().getId() : "session#call";
            if (timeout) {
                assertThat(started).hasValue(1);
                assertThat(finished).hasValue(0);
                assertThat(outcome.getError()).isInstanceOf(TimeoutException.class);
                context.sendEvent(new OutputEvent("timeout"));
            } else {
                assertThat(outcome.isSuccess()).isTrue();
                if (chat) {
                    assertThat(((ChatResult) outcome.getValue()).getText()).isEqualTo(input);
                } else {
                    assertThat((List<?>) ((SubagentResult) outcome.getValue()).getResult())
                            .isEqualTo(List.of(input, "successor"));
                }
                context.sendEvent(new OutputEvent("success"));
            }
        } finally {
            // Only let the model/child finish after gather has returned and closed its handles.
            release.countDown();
        }
    }

    public static void child(Event event, RunnerContext context) throws Exception {
        String prompt = (String) InputEvent.fromEvent(event).getInput();
        context.executeAsync(
                        () -> {
                            awaitCaller(prompt);
                            return null;
                        })
                .await();
        finished.incrementAndGet();
        context.sendEvent(new Event("next"));
        context.sendEvent(new OutputEvent(prompt));
    }

    public static void successor(Event event, RunnerContext context) {
        successors.incrementAndGet();
        context.sendEvent(new OutputEvent("successor"));
    }

    public static void leaked(Event event, RunnerContext context) {
        throw new AssertionError("Late graph event leaked into the parent: " + event.getType());
    }

    private static void awaitCaller(String prompt) {
        if (prompt.endsWith("timeout")) {
            started.incrementAndGet();
            try {
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "subagent"})
    void lateEventsAfterGatherTimeoutDoNotFailTheOperator(String kind) throws Exception {
        Agent child = new Agent();
        child.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                SubgraphTimeoutTest.class.getMethod("child", Event.class, RunnerContext.class));
        child.addAction(
                new String[] {"next"},
                SubgraphTimeoutTest.class.getMethod("successor", Event.class, RunnerContext.class));
        Agent parent = new Agent();
        parent.addResource("child", ResourceType.AGENT, child);
        parent.addResource(
                "model",
                ResourceType.CHAT_MODEL,
                ResourceDescriptor.Builder.newBuilder(Model.class.getName()).build());
        parent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                SubgraphTimeoutTest.class.getMethod("caller", Event.class, RunnerContext.class));
        parent.addAction(
                new String[] {"next", ChatRequestEvent.EVENT_TYPE, ChatResponseEvent.EVENT_TYPE},
                SubgraphTimeoutTest.class.getMethod("leaked", Event.class, RunnerContext.class));
        AgentPlan plan = new AgentPlan(parent);
        plan.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 10);
        plan.getConfig().set(AgentExecutionOptions.ASYNC_BATCH_TIMEOUT_MS, 1000L);
        try (KeyedOneInputStreamOperatorTestHarness<String, String, Object> harness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(plan, true),
                        (KeySelector<String, String>) value -> "key",
                        TypeInformation.of(String.class))) {
            harness.open();
            ActionExecutionOperator<?, ?> operator =
                    (ActionExecutionOperator<?, ?>) harness.getOperator();
            harness.processElement(new StreamRecord<>(kind + "-timeout"));
            operator.waitInFlightEventsFinished();
            assertThat(harness.getRecordOutput())
                    .extracting(StreamRecord::getValue)
                    .containsExactly("timeout");
            assertThat(finished).hasValue(1);
            assertThat(successors).hasValue(0);
            assertThat(calls.find(invocationId)).isNull();

            // Reuse the same key (and explicit subagent identity) in a new record.
            harness.processElement(new StreamRecord<>(kind + "-success"));
            operator.waitInFlightEventsFinished();
            assertThat(harness.getRecordOutput())
                    .extracting(StreamRecord::getValue)
                    .containsExactly("timeout", "success");
            assertThat(calls.find(invocationId)).isNull();
            assertThat(finished).hasValue(2);
            assertThat(successors).hasValue("subagent".equals(kind) ? 1 : 0);
        } finally {
            release.countDown();
        }
    }
}
