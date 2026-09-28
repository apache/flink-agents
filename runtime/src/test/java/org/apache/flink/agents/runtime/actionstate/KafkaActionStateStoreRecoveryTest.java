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
package org.apache.flink.agents.runtime.actionstate;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperator;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperatorFactory;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.flink.util.ExceptionUtils;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.apache.flink.agents.runtime.actionstate.ActionStateTestUtils.createKeyEncoder;
import static org.apache.kafka.clients.consumer.internals.AutoOffsetResetStrategy.EARLIEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * End-to-end recovery test for a pending durable call result with a Kafka-backed store.
 *
 * <p>An action records a durable call, takes a real Flink checkpoint while the action is still
 * unfinished, then fails. Recovery must rebuild the pending result from the checkpoint's recovery
 * marker instead of skipping it, otherwise the durable supplier runs a second time. See
 * https://github.com/apache/flink-agents/issues/1158.
 */
public class KafkaActionStateStoreRecoveryTest {

    private static final String TOPIC = "test-action-state-recovery";
    private static final int MAX_PARALLELISM = 128;

    private static final AtomicInteger EXTERNAL_CALLS = new AtomicInteger();
    private static final AtomicReference<KeyedOneInputStreamOperatorTestHarness<Long, Long, Object>>
            HARNESS = new AtomicReference<>();
    private static final AtomicReference<OperatorSubtaskState> CHECKPOINT = new AtomicReference<>();

    /**
     * Runs a real durable call, takes a real checkpoint while the action is still in flight, then
     * fails without completing.
     */
    public static void durableCallThenCheckpointThenFail(Event event, RunnerContext context)
            throws Exception {
        Long input = (Long) InputEvent.fromEvent(event).getInput();
        context.durableExecute(
                new DurableCallable<Long>() {
                    @Override
                    public String getId() {
                        return "multiply";
                    }

                    @Override
                    public Class<Long> getResultClass() {
                        return Long.class;
                    }

                    @Override
                    public Long call() {
                        EXTERNAL_CALLS.incrementAndGet();
                        return input * 10;
                    }
                });
        if (CHECKPOINT.get() == null) {
            CHECKPOINT.set(HARNESS.get().snapshot(1L, 1L));
        }
        throw new IllegalStateException("action fails while its durable result is pending");
    }

    private static AgentPlan plan() {
        try {
            Action action =
                    new Action(
                            "durableCallThenCheckpointThenFail",
                            new JavaFunction(
                                    KafkaActionStateStoreRecoveryTest.class,
                                    "durableCallThenCheckpointThenFail",
                                    new Class<?>[] {Event.class, RunnerContext.class}),
                            Collections.singletonList(InputEvent.EVENT_TYPE));
            Map<String, Action> actions = new HashMap<>();
            actions.put(action.getName(), action);
            return new AgentPlan(actions);
        } catch (Exception e) {
            ExceptionUtils.rethrow(e);
        }
        return null;
    }

    @Test
    void pendingDurableResultSurvivesRecoveryFromCheckpointTakenMidAction() throws Exception {
        MockProducer<String, ActionState> producer =
                new MockProducer<>(
                        true,
                        new ActionStateKeyPartitioner(),
                        new StringSerializer(),
                        new ActionStateKafkaSeder());
        KafkaActionStateStore store =
                new KafkaActionStateStore(
                        new HashMap<>(),
                        new AgentConfiguration(),
                        producer,
                        consumerWithPartition(3L),
                        TOPIC,
                        createKeyEncoder(MAX_PARALLELISM));

        EXTERNAL_CALLS.set(0);
        OperatorSubtaskState checkpoint;

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(plan(), true, store),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class),
                        MAX_PARALLELISM,
                        1,
                        0)) {
            harness.open();
            HARNESS.set(harness);
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) harness.getOperator();

            Throwable failure =
                    catchThrowable(
                            () -> {
                                harness.processElement(new StreamRecord<>(7L));
                                operator.waitInFlightEventsFinished();
                            });
            assertThat(failure).isNotNull();
            checkpoint = CHECKPOINT.get();
            assertThat(checkpoint).isNotNull();
            assertThat(EXTERNAL_CALLS.get()).isEqualTo(1);
        }

        // Recovery: a restarted task reads the checkpoint's recovery marker into a fresh store and
        // must find the pending durable result that was recorded before the checkpoint.
        MockConsumer<String, ActionState> recoveryConsumer =
                recoveryConsumer(producer.history());
        Action action = plan().getActions().values().iterator().next();
        try (KafkaActionStateStore recoveredStore =
                        new KafkaActionStateStore(
                                new HashMap<>(),
                                new AgentConfiguration(),
                                null,
                                recoveryConsumer,
                                TOPIC,
                                createKeyEncoder(MAX_PARALLELISM));
                KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> restored =
                        new KeyedOneInputStreamOperatorTestHarness<>(
                                new ActionExecutionOperatorFactory<>(plan(), true, recoveredStore),
                                (KeySelector<Long, Long>) value -> value,
                                TypeInformation.of(Long.class),
                                MAX_PARALLELISM,
                                1,
                                0)) {
            restored.initializeState(checkpoint);
            restored.open();

            ActionState recovered = recoveredStore.get(7L, 0L, action, new InputEvent(7L));
            assertThat(recovered)
                    .as(
                            "Recovery must replay the durable result recorded before the checkpoint")
                    .isNotNull();
            assertThat(recovered.isCompleted()).isFalse();
            assertThat(recovered.getCallResults()).hasSize(1);
            CallResult result = recovered.getCallResults().get(0);
            assertThat(result.matches("multiply", "")).isTrue();
            assertThat(result.isPending()).isFalse();
            assertThat(result.isSuccess()).isTrue();
            assertThat(EXTERNAL_CALLS.get()).isEqualTo(1);
        }
    }

    private static MockConsumer<String, ActionState> consumerWithPartition(long endOffset) {
        MockConsumer<String, ActionState> consumer = new MockConsumer<>(EARLIEST.name());
        consumer.updatePartitions(
                TOPIC, List.of(new PartitionInfo(TOPIC, 0, null, null, null)));
        consumer.updateEndOffsets(Map.of(new TopicPartition(TOPIC, 0), endOffset));
        return consumer;
    }

    private static MockConsumer<String, ActionState> recoveryConsumer(
            List<ProducerRecord<String, ActionState>> history) {
        MockConsumer<String, ActionState> consumer = new MockConsumer<>(EARLIEST.name());
        consumer.assign(List.of(new TopicPartition(TOPIC, 0)));
        consumer.updateBeginningOffsets(Map.of(new TopicPartition(TOPIC, 0), 0L));
        consumer.updateEndOffsets(Map.of(new TopicPartition(TOPIC, 0), (long) history.size()));
        for (int i = 0; i < history.size(); i++) {
            ProducerRecord<String, ActionState> record = history.get(i);
            consumer.addRecord(
                    new ConsumerRecord<>(TOPIC, 0, i, record.key(), record.value()));
        }
        return consumer;
    }
}