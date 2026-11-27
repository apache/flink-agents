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
import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelConnection;
import org.apache.flink.agents.api.embedding.model.EmbeddingResult;
import org.apache.flink.agents.api.embedding.model.EmbeddingTokenUsage;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateSerde;
import org.apache.flink.agents.runtime.actionstate.InMemoryActionStateStore;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.runtime.state.hashmap.HashMapStateBackend;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.mailbox.Mail;
import org.apache.flink.streaming.runtime.tasks.mailbox.TaskMailbox;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Replays completed embedding calls after a checkpoint taken while their action is suspended. */
@Timeout(30)
public class EmbeddingResultRecoveryTest {
    private static final AtomicInteger MODEL_CALLS = new AtomicInteger();
    private static volatile boolean tailStarted;
    private static CountDownLatch tailGate;
    private static boolean async;
    private static boolean batch;
    private static boolean empty;
    private static Long tokens;

    public static class Model extends BaseEmbeddingModelConnection {
        public Model(ResourceDescriptor descriptor, ResourceContext context) {
            super(descriptor, context);
        }

        @Override
        public EmbeddingResult<List<float[]>> embed(
                List<? extends DataContentBlock> blocks, Map<String, Object> parameters) {
            MODEL_CALLS.incrementAndGet();
            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < blocks.size(); i++) {
                vectors.add(new float[] {i + 0.25f, -2.5f});
            }
            return new EmbeddingResult<>(
                    vectors, tokens == null ? null : new EmbeddingTokenUsage(tokens, tokens));
        }
    }

    public static void caller(Event event, RunnerContext ctx) throws Exception {
        BaseEmbeddingModelConnection model =
                (BaseEmbeddingModelConnection)
                        ctx.getResource("model", ResourceType.EMBEDDING_MODEL_CONNECTION);
        DurableCallable<EmbeddingResult<?>> call =
                new DurableCallable<>() {
                    @Override
                    public String getId() {
                        return "embed";
                    }

                    @Override
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    public Class<EmbeddingResult<?>> getResultClass() {
                        return (Class) EmbeddingResult.class;
                    }

                    @Override
                    public EmbeddingResult<?> call() {
                        return batch
                                ? model.embed(
                                        empty
                                                ? List.of()
                                                : List.of(new TextBlock("a"), new TextBlock("b")),
                                        Map.of())
                                : model.embed("a", Map.of());
                    }
                };
        EmbeddingResult<?> result =
                async ? ctx.durableExecuteAsync(call).await() : ctx.durableExecute(call);
        if (batch) {
            List<?> vectors = (List<?>) result.getEmbeddings();
            assertThat(vectors).hasSize(empty ? 0 : 2);
            for (int i = 0; i < vectors.size(); i++) {
                assertThat((float[]) vectors.get(i)).containsExactly(i + 0.25f, -2.5f);
            }
        } else {
            assertThat((float[]) result.getEmbeddings()).containsExactly(0.25f, -2.5f);
        }
        if (tokens == null) {
            assertThat(result.getTokenUsage()).isNull();
        } else {
            assertThat(result.getTokenUsage().getPromptTokens()).isEqualTo(tokens);
            assertThat(result.getTokenUsage().getTotalTokens()).isEqualTo(tokens);
        }
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
        ctx.sendEvent(new OutputEvent("done"));
    }

    static Stream<Arguments> cases() {
        return Stream.of(false, true)
                .flatMap(
                        async ->
                                Stream.of(false, true)
                                        .flatMap(
                                                batch ->
                                                        Stream.of(false, true)
                                                                .filter(empty -> batch || !empty)
                                                                .flatMap(
                                                                        empty ->
                                                                                Stream.of(
                                                                                                null,
                                                                                                0L,
                                                                                                7L)
                                                                                        .map(
                                                                                                tokens ->
                                                                                                        Arguments
                                                                                                                .of(
                                                                                                                        async,
                                                                                                                        batch,
                                                                                                                        empty,
                                                                                                                        tokens)))));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void restoresResultWithoutCallingModelAgain(
            boolean asyncCall, boolean batchCall, boolean emptyResult, Long tokenCount)
            throws Exception {
        async = asyncCall;
        batch = batchCall;
        empty = emptyResult;
        tokens = tokenCount;
        MODEL_CALLS.set(0);
        tailStarted = false;
        tailGate = new CountDownLatch(1);
        Agent agent = new Agent();
        agent.addResource(
                "model",
                ResourceType.EMBEDDING_MODEL_CONNECTION,
                ResourceDescriptor.Builder.newBuilder(Model.class.getName()).build());
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                EmbeddingResultRecoveryTest.class.getMethod(
                        "caller", Event.class, RunnerContext.class));
        AgentPlan plan = new AgentPlan(agent);
        plan.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 2);
        InMemoryActionStateStore store = new InMemoryActionStateStore(false);
        InMemoryActionStateStore saved = new InMemoryActionStateStore(false);
        OperatorSubtaskState snapshot;
        try (KeyedOneInputStreamOperatorTestHarness<String, String, Object> original =
                harness(plan, store)) {
            original.open();
            original.processElement(new StreamRecord<>("run"));
            ActionExecutionOperator<?, ?> operator =
                    (ActionExecutionOperator<?, ?>) original.getOperator();
            while (!tailStarted) {
                Optional<Mail> mail = original.getTaskMailbox().tryTake(TaskMailbox.MIN_PRIORITY);
                if (mail.isPresent()) {
                    mail.get().run();
                } else {
                    Thread.sleep(1);
                }
            }
            assertThat(MODEL_CALLS.get()).isEqualTo(1);
            snapshot = original.snapshot(1, 1);
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
            tailGate.countDown();
            operator.waitInFlightEventsFinished();
            assertThat(original.getRecordOutput())
                    .extracting(StreamRecord::getValue)
                    .containsExactly("done");
        } finally {
            tailGate.countDown();
        }
        try (KeyedOneInputStreamOperatorTestHarness<String, String, Object> restored =
                harness(plan, saved)) {
            restored.initializeState(snapshot);
            restored.open();
            ((ActionExecutionOperator<?, ?>) restored.getOperator()).waitInFlightEventsFinished();
            assertThat(restored.getRecordOutput())
                    .extracting(StreamRecord::getValue)
                    .containsExactly("done");
            assertThat(MODEL_CALLS.get()).isEqualTo(1);
        }
    }

    private static KeyedOneInputStreamOperatorTestHarness<String, String, Object> harness(
            AgentPlan plan, InMemoryActionStateStore store) throws Exception {
        KeyedOneInputStreamOperatorTestHarness<String, String, Object> harness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(plan, true, store),
                        (KeySelector<String, String>) value -> "key",
                        TypeInformation.of(String.class));
        harness.setStateBackend(new HashMapStateBackend());
        return harness;
    }
}
