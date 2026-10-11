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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
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
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_ACTION_STATE_TOPIC;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_ACTION_STATE_TOPIC_NUM_PARTITIONS;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_BOOTSTRAP_SERVERS;
import static org.apache.flink.agents.runtime.actionstate.ActionStateTestUtils.createKeyEncoder;
import static org.apache.kafka.clients.consumer.internals.AutoOffsetResetStrategy.EARLIEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** End-to-end recovery test for a queued action with a persisted durable call result. */
public class KafkaActionStateStoreRecoveryTest {

    private static final String TOPIC = "test-action-state-recovery";
    private static final int MAX_PARALLELISM = 128;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final AtomicInteger EXTERNAL_CALLS = new AtomicInteger();
    private static final AtomicBoolean FAIL_AFTER_DURABLE_CALL = new AtomicBoolean();

    public static void durableCall(Event event, RunnerContext context) throws Exception {
        Long input = (Long) InputEvent.fromEvent(event).getInput();
        Long result =
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
                                return callExternal(input);
                            }
                        });
        if (FAIL_AFTER_DURABLE_CALL.get()) {
            throw new IllegalStateException("action failed after persisting its durable result");
        }
        context.sendEvent(new OutputEvent(result));
    }

    private static Long callExternal(Long input) {
        EXTERNAL_CALLS.incrementAndGet();
        return input * 10;
    }

    private static AgentPlan plan() throws Exception {
        Action action =
                new Action(
                        "durableCall",
                        new JavaFunction(
                                KafkaActionStateStoreRecoveryTest.class,
                                "durableCall",
                                new Class<?>[] {Event.class, RunnerContext.class}),
                        Collections.singletonList(InputEvent.EVENT_TYPE));
        Map<String, Action> actions = new HashMap<>();
        actions.put(action.getName(), action);
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentExecutionOptions.PARALLEL_EXECUTION_ENABLED, false);
        return new AgentPlan(actions, new HashMap<>(), config);
    }

    @Test
    void queuedActionUsesRecoveredDurableResultAfterRestore() throws Exception {
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
                        consumerWithLiveEndOffsets(producer),
                        TOPIC,
                        createKeyEncoder(MAX_PARALLELISM));

        EXTERNAL_CALLS.set(0);
        AgentPlan agentPlan = plan();
        Action action = agentPlan.getActions().values().iterator().next();
        OperatorSubtaskState checkpoint;

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true, store),
                        (KeySelector<Long, Long>) value -> 1L,
                        TypeInformation.of(Long.class),
                        MAX_PARALLELISM,
                        1,
                        0)) {
            harness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) harness.getOperator();

            harness.processElement(new StreamRecord<>(3L));
            operator.waitInFlightEventsFinished();
            assertThat(harness.getOutput())
                    .singleElement()
                    .isInstanceOfSatisfying(
                            StreamRecord.class,
                            record -> assertThat(record.getValue()).isEqualTo(30L));
            harness.getOutput().clear();

            InputEvent inputEvent = new InputEvent(7L);
            ActionState pendingState = new ActionState(inputEvent);
            pendingState.addCallResult(
                    new CallResult("multiply", OBJECT_MAPPER.writeValueAsBytes(callExternal(7L))));
            store.put(1L, 1L, action, inputEvent, pendingState);

            harness.processElement(new StreamRecord<>(7L));
            assertThat(harness.getOutput()).isEmpty();
            checkpoint = harness.snapshot(1L, 1L);
            assertThat(EXTERNAL_CALLS.get()).isEqualTo(2);
        }

        MockConsumer<String, ActionState> recoveryConsumer = recoveryConsumer(producer.history());
        try (KafkaActionStateStore recoveredStore =
                        new KafkaActionStateStore(
                                new HashMap<>(),
                                new AgentConfiguration(),
                                new MockProducer<>(
                                        true,
                                        new ActionStateKeyPartitioner(),
                                        new StringSerializer(),
                                        new ActionStateKafkaSeder()),
                                recoveryConsumer,
                                TOPIC,
                                createKeyEncoder(MAX_PARALLELISM));
                KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> restored =
                        new KeyedOneInputStreamOperatorTestHarness<>(
                                new ActionExecutionOperatorFactory<>(
                                        agentPlan, true, recoveredStore),
                                (KeySelector<Long, Long>) value -> 1L,
                                TypeInformation.of(Long.class),
                                MAX_PARALLELISM,
                                1,
                                0)) {
            restored.initializeState(checkpoint);
            restored.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) restored.getOperator();
            operator.waitInFlightEventsFinished();

            assertThat(restored.getOutput())
                    .singleElement()
                    .isInstanceOfSatisfying(
                            StreamRecord.class,
                            record -> assertThat(record.getValue()).isEqualTo(70L));
            assertThat(EXTERNAL_CALLS.get()).isEqualTo(2);
        }
    }

    @Test
    void queuedActionReusesResultFromPreviousAttemptWithKafka() throws Exception {
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the Kafka recovery integration test");

        EXTERNAL_CALLS.set(0);
        try (KafkaContainer kafka =
                new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"))) {
            kafka.start();
            AgentConfiguration configuration = new AgentConfiguration();
            configuration.set(KAFKA_BOOTSTRAP_SERVERS, kafka.getBootstrapServers());
            configuration.set(KAFKA_ACTION_STATE_TOPIC, TOPIC);
            configuration.set(KAFKA_ACTION_STATE_TOPIC_NUM_PARTITIONS, 1);
            AgentPlan agentPlan = plan();
            OperatorSubtaskState queuedCheckpoint;
            OperatorSubtaskState pendingResultCheckpoint;

            // The first attempt saves its result after the checkpoint, then fails mid-action.
            try (KafkaActionStateStore store =
                            new KafkaActionStateStore(
                                    configuration, createKeyEncoder(MAX_PARALLELISM));
                    KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                            kafkaHarness(agentPlan, store)) {
                harness.open();
                harness.processElement(new StreamRecord<>(7L));
                queuedCheckpoint = harness.snapshot(1L, 1L);
                ActionExecutionOperator<Long, Object> operator =
                        (ActionExecutionOperator<Long, Object>) harness.getOperator();
                FAIL_AFTER_DURABLE_CALL.set(true);
                assertThatThrownBy(operator::waitInFlightEventsFinished)
                        .hasRootCauseMessage("action failed after persisting its durable result");
                assertThat(EXTERNAL_CALLS.get()).isEqualTo(1);
                assertThat(harness.getOutput()).isEmpty();
            } finally {
                FAIL_AFTER_DURABLE_CALL.set(false);
            }

            // Restore the queued action and take another checkpoint before dispatching it.
            // Its durable result now precedes the Kafka end offset captured by this checkpoint.
            try (KafkaActionStateStore store =
                            new KafkaActionStateStore(
                                    configuration, createKeyEncoder(MAX_PARALLELISM));
                    KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                            kafkaHarness(agentPlan, store)) {
                harness.initializeState(queuedCheckpoint);
                harness.open();
                pendingResultCheckpoint = harness.snapshot(2L, 2L);
                assertThat(EXTERNAL_CALLS.get()).isEqualTo(1);
                assertThat(harness.getOutput()).isEmpty();
            }

            try (KafkaActionStateStore store =
                            new KafkaActionStateStore(
                                    configuration, createKeyEncoder(MAX_PARALLELISM));
                    KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                            kafkaHarness(agentPlan, store)) {
                harness.initializeState(pendingResultCheckpoint);
                harness.open();
                ActionExecutionOperator<Long, Object> operator =
                        (ActionExecutionOperator<Long, Object>) harness.getOperator();
                operator.waitInFlightEventsFinished();

                assertThat(harness.getOutput())
                        .singleElement()
                        .isInstanceOfSatisfying(
                                StreamRecord.class,
                                record -> assertThat(record.getValue()).isEqualTo(70L));
                assertThat(EXTERNAL_CALLS.get()).isEqualTo(1);
            }
        }
    }

    private static KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> kafkaHarness(
            AgentPlan agentPlan, KafkaActionStateStore store) throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<>(
                new ActionExecutionOperatorFactory<>(agentPlan, true, store),
                (KeySelector<Long, Long>) value -> 1L,
                TypeInformation.of(Long.class),
                MAX_PARALLELISM,
                1,
                0);
    }

    /**
     * Emulates a real Kafka broker: the store's consumer reads end offsets from the same topic the
     * producer writes, so a marker taken mid-run reflects exactly the records already flushed.
     */
    private static MockConsumer<String, ActionState> consumerWithLiveEndOffsets(
            MockProducer<String, ActionState> producer) {
        return new MockConsumer<>(EARLIEST.name()) {
            {
                updatePartitions(TOPIC, List.of(new PartitionInfo(TOPIC, 0, null, null, null)));
            }

            @Override
            public synchronized Map<TopicPartition, Long> endOffsets(
                    Collection<TopicPartition> partitions) {
                Map<TopicPartition, Long> result = new HashMap<>();
                for (TopicPartition topicPartition : partitions) {
                    result.put(topicPartition, (long) producer.history().size());
                }
                return result;
            }
        };
    }

    private static MockConsumer<String, ActionState> recoveryConsumer(
            List<ProducerRecord<String, ActionState>> history) {
        MockConsumer<String, ActionState> consumer = new MockConsumer<>(EARLIEST.name());
        consumer.updatePartitions(TOPIC, List.of(new PartitionInfo(TOPIC, 0, null, null, null)));
        consumer.assign(List.of(new TopicPartition(TOPIC, 0)));
        consumer.updateBeginningOffsets(Map.of(new TopicPartition(TOPIC, 0), 0L));
        consumer.updateEndOffsets(Map.of(new TopicPartition(TOPIC, 0), (long) history.size()));
        for (int i = 0; i < history.size(); i++) {
            ProducerRecord<String, ActionState> record = history.get(i);
            consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, i, record.key(), record.value()));
        }
        return consumer;
    }
}
