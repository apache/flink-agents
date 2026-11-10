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
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateSerde;
import org.apache.flink.agents.runtime.actionstate.InMemoryActionStateStore;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.runtime.state.hashmap.HashMapStateBackend;
import org.apache.flink.state.rocksdb.EmbeddedRocksDBStateBackend;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.mailbox.Mail;
import org.apache.flink.streaming.runtime.tasks.mailbox.TaskMailbox;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Recovery after chat completes, while its caller is still awaiting another external call. */
@Timeout(30)
public class ChatMemoryIsolationTest {
    private static final AtomicInteger MODEL_CALLS = new AtomicInteger();
    private static volatile boolean tailStarted;
    private static CountDownLatch tailGate;

    public static class Model extends ChatCallTest.Model {
        public Model(ResourceDescriptor descriptor, ResourceContext context) {
            super(descriptor, context);
        }

        @Override
        public ChatResult chat(
                List<ChatMessage> messages, Map<String, Object> args, Map<String, Object> params) {
            MODEL_CALLS.incrementAndGet();
            return super.chat(messages, args, params);
        }
    }

    public static void caller(Event event, RunnerContext ctx) throws Exception {
        if ("seed".equals(InputEvent.fromEvent(event).getInput())) {
            ctx.getShortTermMemory().set("counter", 0);
            ctx.getShortTermMemory().set("caller", "old");
            return;
        }
        int counter = ((Number) ctx.getShortTermMemory().get("counter").getValue()).intValue();
        ctx.getShortTermMemory().set("counter", counter + 1);
        ctx.getShortTermMemory().set("caller", "visible");
        ctx.getSensoryMemory().set("caller", "private");
        assertThat(ctx.chat("model", List.of(ChatMessage.user("tool"))).await().getText())
                .isEqualTo("answer:tool");
        // The tool loop's bookkeeping must stay inside chat, including its root field list.
        assertThat(ctx.getSensoryMemory().getFields()).containsOnlyKeys("caller");
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
        ctx.sendEvent(new OutputEvent(ctx.getShortTermMemory().get("counter").getValue()));
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void checkpointDoesNotIncludeUnfinishedCallerWrites(boolean rocksDb, boolean durable)
            throws Exception {
        MODEL_CALLS.set(0);
        tailStarted = false;
        tailGate = new CountDownLatch(1);
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
                ChatMemoryIsolationTest.class.getMethod(
                        "caller", Event.class, RunnerContext.class));
        AgentPlan plan = new AgentPlan(agent);
        plan.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 2);
        InMemoryActionStateStore store = durable ? new InMemoryActionStateStore(false) : null;
        InMemoryActionStateStore saved = durable ? new InMemoryActionStateStore(false) : null;
        OperatorSubtaskState snapshot;
        try (KeyedOneInputStreamOperatorTestHarness<String, String, Object> original =
                harness(plan, rocksDb, store)) {
            original.open();
            original.processElement(new StreamRecord<>("seed"));
            ActionExecutionOperator<?, ?> operator =
                    (ActionExecutionOperator<?, ?>) original.getOperator();
            operator.waitInFlightEventsFinished();
            original.processElement(new StreamRecord<>("run"));
            while (!tailStarted) {
                Optional<Mail> mail = original.getTaskMailbox().tryTake(TaskMailbox.MIN_PRIORITY);
                if (mail.isPresent()) {
                    mail.get().run();
                } else {
                    Thread.sleep(1);
                }
            }
            assertThat(MODEL_CALLS.get()).isEqualTo(2);
            snapshot = original.snapshot(1, 1);
            operator.setCurrentKey("key");
            assertThat(
                            operator.getOperatorStateManager()
                                    .getShortTermMemState()
                                    .get("counter")
                                    .getValue())
                    .isEqualTo(0);
            if (durable) {
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
            }
            tailGate.countDown();
            operator.waitInFlightEventsFinished();
            assertThat(original.getRecordOutput())
                    .extracting(StreamRecord::getValue)
                    .containsExactly(1);
        } finally {
            tailGate.countDown();
        }
        try (KeyedOneInputStreamOperatorTestHarness<String, String, Object> restored =
                harness(plan, rocksDb, saved)) {
            restored.initializeState(snapshot);
            restored.open();
            ((ActionExecutionOperator<?, ?>) restored.getOperator()).waitInFlightEventsFinished();
            assertThat(restored.getRecordOutput())
                    .extracting(StreamRecord::getValue)
                    .containsExactly(1);
            assertThat(MODEL_CALLS.get()).isEqualTo(durable ? 2 : 4);
        }
    }

    private static KeyedOneInputStreamOperatorTestHarness<String, String, Object> harness(
            AgentPlan plan, boolean rocksDb, InMemoryActionStateStore store) throws Exception {
        KeyedOneInputStreamOperatorTestHarness<String, String, Object> harness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(plan, true, store),
                        (KeySelector<String, String>) value -> "key",
                        TypeInformation.of(String.class));
        harness.setStateBackend(
                rocksDb ? new EmbeddedRocksDBStateBackend() : new HashMapStateBackend());
        return harness;
    }
}
