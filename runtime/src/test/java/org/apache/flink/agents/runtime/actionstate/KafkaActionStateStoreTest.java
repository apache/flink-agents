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
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.kafka.clients.consumer.internals.AutoOffsetResetStrategy.EARLIEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Unit tests for {@link KafkaActionStateStore}. */
public class KafkaActionStateStoreTest {

    private static final String TEST_TOPIC = "test-action-state";
    private static final String TEST_KEY = "test-key";
    private static final int MAX_PARALLELISM = 128;

    private MockProducer<String, ActionState> mockProducer;
    private MockConsumer<String, ActionState> mockConsumer;
    private KafkaActionStateStore actionStateStore;
    private Action testAction;
    private Event testEvent;
    private ActionState testActionState;
    private Map<String, ActionState> actionStates;

    @Test
    void testRejectsBlankCleanupControlTopicBeforeConnecting() {
        AgentConfiguration configuration = new AgentConfiguration();
        configuration.set(AgentConfigOptions.KAFKA_ACTION_STATE_CLEANUP_CONTROL_TOPIC, "  ");

        assertThatThrownBy(() -> new KafkaActionStateStore(configuration, MAX_PARALLELISM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Kafka action-state cleanup control topic must not be blank");
    }

    @Test
    void testRejectsTombstonesWithCleanupControlTopicBeforeConnecting() {
        AgentConfiguration configuration = new AgentConfiguration();
        configuration.set(
                AgentConfigOptions.KAFKA_ACTION_STATE_CLEANUP_CONTROL_TOPIC, "control-topic");
        configuration.set(AgentConfigOptions.KAFKA_ACTION_STATE_TOMBSTONE_ENABLED, true);

        assertThatThrownBy(() -> new KafkaActionStateStore(configuration, MAX_PARALLELISM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tombstones cannot be enabled");
    }

    @BeforeEach
    void setUp() throws Exception {
        mockProducer =
                new MockProducer<>(
                        true,
                        new ActionStateKeyPartitioner(),
                        new StringSerializer(),
                        new ActionStateKafkaSeder());
        mockConsumer =
                new MockConsumer<String, ActionState>(EARLIEST.name()) {
                    @Override
                    public synchronized void commitSync() {
                        throw new AssertionError(
                                "Action-state replay must not commit consumer-group offsets");
                    }
                };
        mockConsumer.updatePartitions(
                TEST_TOPIC,
                List.of(
                        new PartitionInfo(TEST_TOPIC, 0, null, null, null),
                        new PartitionInfo(TEST_TOPIC, 1, null, null, null)));
        mockConsumer.updateBeginningOffsets(
                Map.of(
                        new TopicPartition(TEST_TOPIC, 0),
                        0L,
                        new TopicPartition(TEST_TOPIC, 1),
                        0L));
        mockConsumer.updateEndOffsets(
                Map.of(
                        new TopicPartition(TEST_TOPIC, 0),
                        0L,
                        new TopicPartition(TEST_TOPIC, 1),
                        0L));
        mockConsumer.assign(
                List.of(new TopicPartition(TEST_TOPIC, 0), new TopicPartition(TEST_TOPIC, 1)));
        actionStates = new HashMap<>();
        actionStateStore =
                new KafkaActionStateStore(
                        actionStates,
                        new AgentConfiguration(),
                        mockProducer,
                        mockConsumer,
                        TEST_TOPIC,
                        MAX_PARALLELISM);

        // Create test objects
        testAction = new NoOpAction("test-action");
        testEvent = new InputEvent("test data");
        testActionState = new ActionState(testEvent);
    }

    /** Builds a store sharing this test's mock consumer but with tombstone emission enabled. */
    private KafkaActionStateStore tombstoneEnabledStore(
            Map<String, ActionState> states, Producer<String, ActionState> producer) {
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentConfigOptions.KAFKA_ACTION_STATE_TOMBSTONE_ENABLED, true);
        return new KafkaActionStateStore(
                states, config, producer, mockConsumer, TEST_TOPIC, MAX_PARALLELISM);
    }

    @Test
    void testPutActionState() throws Exception {
        // Act
        actionStateStore.put(TEST_KEY, 1L, testAction, testEvent, testActionState);

        // Assert - Check state
        var history = mockProducer.history();
        assertEquals(1, history.size());
        var record = history.get(0);
        assertEquals(TEST_TOPIC, record.topic());
        assertThat(ActionStateUtil.matchesBusinessKeyAndSeqNum(record.key(), TEST_KEY, 1L))
                .isTrue();
        assertNotNull(record.value());
        assertThat(record.value()).isEqualTo(testActionState);
    }

    @Test
    void testGetNonExistentActionState() throws Exception {
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent, MAX_PARALLELISM),
                testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 2L, testAction, testEvent, MAX_PARALLELISM),
                testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 3L, testAction, testEvent, MAX_PARALLELISM),
                testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 4L, testAction, testEvent, MAX_PARALLELISM),
                testActionState);

        actionStateStore.get(TEST_KEY, 2L, new NoOpAction("test-1"), testEvent);

        assertNotNull(actionStateStore.get(TEST_KEY, 1L, testAction, testEvent));
        assertNotNull(actionStateStore.get(TEST_KEY, 2L, testAction, testEvent));
        assertNull(actionStateStore.get(TEST_KEY, 3L, testAction, testEvent));
        assertNull(actionStateStore.get(TEST_KEY, 4L, testAction, testEvent));
    }

    @Test
    void testGetActionStateWithDiverge() throws Exception {
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent, MAX_PARALLELISM),
                testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 2L, testAction, testEvent, MAX_PARALLELISM),
                testActionState);
        // diverge here
        actionStates.put(
                ActionStateUtil.generateKey(
                        TEST_KEY, 2L, new NoOpAction("test-2"), testEvent, MAX_PARALLELISM),
                testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 3L, testAction, testEvent, MAX_PARALLELISM),
                testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 4L, testAction, testEvent, MAX_PARALLELISM),
                testActionState);

        actionStateStore.get(TEST_KEY, 2L, testAction, testEvent);

        assertNotNull(actionStateStore.get(TEST_KEY, 1L, testAction, testEvent));
        assertNotNull(actionStateStore.get(TEST_KEY, 2L, testAction, testEvent));
        assertNull(actionStateStore.get(TEST_KEY, 3L, testAction, testEvent));
        assertNull(actionStateStore.get(TEST_KEY, 4L, testAction, testEvent));
    }

    @Test
    void testGetCleansFutureStateForKeyContainingUnderscore() throws Exception {
        String flinkKey = "user_123";
        String stateKey =
                ActionStateUtil.generateKey(flinkKey, 3L, testAction, testEvent, MAX_PARALLELISM);
        actionStates.put(stateKey, testActionState);

        assertThat(actionStateStore.get(flinkKey, 1L, testAction, testEvent)).isNull();
        assertThat(actionStates).doesNotContainKey(stateKey);
    }

    @Test
    void testRecoveryMarker() throws Exception {
        // Test getting initial recovery marker
        Object initialMarker = actionStateStore.getRecoveryMarker();
        assertThat(initialMarker).isInstanceOf(KafkaActionStateRecoveryMarker.class);
        KafkaActionStateRecoveryMarker initialRecoveryMarker =
                (KafkaActionStateRecoveryMarker) initialMarker;
        assertThat(initialRecoveryMarker.getSchemaVersion())
                .isEqualTo(KafkaActionStateRecoveryMarker.CURRENT_SCHEMA_VERSION);
        assertThat(initialRecoveryMarker.getTopic()).isEqualTo(TEST_TOPIC);
        assertThat(initialRecoveryMarker.getTopicId()).isEqualTo("test-topic-id:" + TEST_TOPIC);
        assertThat(initialRecoveryMarker.getOffsets()).containsOnly(entry(0, 0L), entry(1, 0L));

        mockConsumer.updateEndOffsets(
                Map.of(
                        new TopicPartition(TEST_TOPIC, 0),
                        5L,
                        new TopicPartition(TEST_TOPIC, 1),
                        3L));
        // Test getting recovery marker after putting state
        Object secondMarker = actionStateStore.getRecoveryMarker();
        assertThat(secondMarker).isInstanceOf(KafkaActionStateRecoveryMarker.class);
        assertThat(((KafkaActionStateRecoveryMarker) secondMarker).getOffsets())
                .containsOnly(entry(0, 5L), entry(1, 3L));
    }

    @Test
    void testRebuildConsumerDisablesOffsetResetAndAutoCommit() {
        Properties consumerProperties = actionStateStore.createConsumerProp();

        assertThat(consumerProperties)
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none")
                .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    }

    @Test
    void testPruneState() throws Exception {
        // Arrange
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent, MAX_PARALLELISM),
                testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 2L, testAction, testEvent, MAX_PARALLELISM),
                testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 3L, testAction, testEvent, MAX_PARALLELISM),
                testActionState);

        // Verify all states exist
        assertNotNull(actionStateStore.get(TEST_KEY, 1L, testAction, testEvent));
        assertNotNull(actionStateStore.get(TEST_KEY, 2L, testAction, testEvent));
        assertNotNull(actionStateStore.get(TEST_KEY, 3L, testAction, testEvent));

        // Act - prune states up to sequence number 2
        actionStateStore.pruneState(TEST_KEY, 2L);

        // Assert - states 1 and 2 should be pruned, state 3 should remain
        assertNull(
                actionStates.get(
                        ActionStateUtil.generateKey(
                                TEST_KEY, 1L, testAction, testEvent, MAX_PARALLELISM)));
        assertNull(
                actionStates.get(
                        ActionStateUtil.generateKey(
                                TEST_KEY, 2L, testAction, testEvent, MAX_PARALLELISM)));
        assertNotNull(actionStateStore.get(TEST_KEY, 3L, testAction, testEvent));
    }

    @Test
    void testPruneStateSendsTombstonesWithCorrectKeys() throws Exception {
        // Arrange
        actionStateStore = tombstoneEnabledStore(actionStates, mockProducer);
        String key1 =
                ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent, MAX_PARALLELISM);
        String key2 =
                ActionStateUtil.generateKey(TEST_KEY, 2L, testAction, testEvent, MAX_PARALLELISM);
        String key3 =
                ActionStateUtil.generateKey(TEST_KEY, 3L, testAction, testEvent, MAX_PARALLELISM);
        actionStates.put(key1, testActionState);
        actionStates.put(key2, testActionState);
        actionStates.put(key3, testActionState);

        // Act
        actionStateStore.pruneState(TEST_KEY, 2L);

        // Assert - exactly keys for seqNum 1 and 2 appear as tombstones
        var history = mockProducer.history();
        assertThat(history).extracting(ProducerRecord::topic).containsOnly(TEST_TOPIC);
        assertThat(history).extracting(ProducerRecord::key).containsExactlyInAnyOrder(key1, key2);
        assertThat(history).extracting(ProducerRecord::value).containsOnlyNulls();
    }

    @Test
    void testPruneStateEvictsCacheEvenWhenTombstoneSendFails() throws Exception {
        // Arrange - a producer whose send() completes its callback with an exception, exercising
        // the async failure path (mockProducer's autoComplete=true completes sends successfully
        // before errorNext() can take effect, so a dedicated producer is needed here)
        AtomicBoolean failureCallbackInvoked = new AtomicBoolean();
        MockProducer<String, ActionState> failingProducer =
                new MockProducer<>(
                        false,
                        new ActionStateKeyPartitioner(),
                        new StringSerializer(),
                        new ActionStateKafkaSeder()) {
                    @Override
                    public synchronized Future<RecordMetadata> send(
                            ProducerRecord<String, ActionState> record, Callback callback) {
                        assertThat(callback).isNotNull();
                        Future<RecordMetadata> future =
                                super.send(
                                        record,
                                        (metadata, exception) -> {
                                            failureCallbackInvoked.set(exception != null);
                                            callback.onCompletion(metadata, exception);
                                        });
                        assertThat(errorNext(new RuntimeException("simulated broker failure")))
                                .isTrue();
                        return future;
                    }
                };
        actionStateStore = tombstoneEnabledStore(actionStates, failingProducer);
        String stateKey =
                ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent, MAX_PARALLELISM);
        actionStates.put(stateKey, testActionState);

        TestAppender appender = new TestAppender("KafkaActionStateStoreTestAppender");
        appender.start();
        LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);
        Configuration loggerConfiguration = loggerContext.getConfiguration();
        String loggerName = KafkaActionStateStore.class.getName();
        LoggerConfig loggerConfig = loggerConfiguration.getLoggerConfig(loggerName);
        boolean addedLoggerConfig = !loggerConfig.getName().equals(loggerName);
        if (addedLoggerConfig) {
            loggerConfig = new LoggerConfig(loggerName, Level.WARN, false);
            loggerConfiguration.addLogger(loggerName, loggerConfig);
        }
        loggerConfig.addAppender(appender, Level.WARN, null);
        loggerContext.updateLoggers();

        try {
            // Act - should not throw despite the async send failure
            actionStateStore.pruneState(TEST_KEY, 1L);

            // Assert - the failure is reported and the in-memory entry is still evicted
            assertThat(failureCallbackInvoked).isTrue();
            assertThat(appender.getMessages())
                    .anyMatch(message -> message.contains("Failed to send tombstone record"));
            assertThat(actionStates).doesNotContainKey(stateKey);
        } finally {
            loggerConfig.removeAppender(appender.getName());
            if (addedLoggerConfig) {
                loggerConfiguration.removeLogger(loggerName);
            }
            appender.stop();
            loggerContext.updateLoggers();
        }
    }

    @Test
    void testPruneStateSupportsKeysContainingUnderscore() throws Exception {
        actionStateStore = tombstoneEnabledStore(actionStates, mockProducer);
        String agentKey = "user_123";
        String stateKey =
                ActionStateUtil.generateKey(agentKey, 1L, testAction, testEvent, MAX_PARALLELISM);
        actionStates.put(stateKey, testActionState);

        actionStateStore.pruneState(agentKey, 10L);

        assertThat(actionStates).doesNotContainKey(stateKey);
        assertThat(mockProducer.history())
                .extracting(ProducerRecord::key)
                .containsExactly(stateKey);
        assertThat(mockProducer.history()).extracting(ProducerRecord::value).containsOnlyNulls();
    }

    @Test
    void testPruneStateNoTombstonesByDefault() throws Exception {
        // Arrange - setUp store uses a default AgentConfiguration (tombstones disabled)
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent, MAX_PARALLELISM),
                testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 2L, testAction, testEvent, MAX_PARALLELISM),
                testActionState);

        // Act
        actionStateStore.pruneState(TEST_KEY, 2L);

        // Assert - no tombstones sent, but in-memory entries are still evicted
        assertThat(mockProducer.history()).isEmpty();
        assertThat(actionStates).isEmpty();
    }

    @Test
    void testPruneStateNoMatchingKeys() throws Exception {
        // Arrange - add states for a different key
        actionStateStore = tombstoneEnabledStore(actionStates, mockProducer);
        actionStates.put(
                ActionStateUtil.generateKey(
                        "other-key", 1L, testAction, testEvent, MAX_PARALLELISM),
                testActionState);

        // Act
        actionStateStore.pruneState(TEST_KEY, 2L);

        // Assert - no tombstones sent, other key's state remains
        assertThat(mockProducer.history()).isEmpty();
        assertThat(actionStates).hasSize(1);
    }

    @Test
    void testPruneStateWithNullProducer() throws Exception {
        // Arrange - tombstones enabled but producer is null
        Map<String, ActionState> localStates = new HashMap<>();
        KafkaActionStateStore nullProducerStore = tombstoneEnabledStore(localStates, null);
        localStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent, MAX_PARALLELISM),
                testActionState);

        // Act - should not throw
        nullProducerStore.pruneState(TEST_KEY, 1L);

        // Assert - in-memory removal still works
        assertThat(localStates).isEmpty();
    }

    @Test
    void testActionStateUpdates() throws Exception {
        // Arrange
        actionStateStore.put(TEST_KEY, 1L, testAction, testEvent, testActionState);

        // Modify the action state
        testActionState.addEvent(new InputEvent("additional event"));

        // Act - update the same action state
        actionStateStore.put(TEST_KEY, 1L, testAction, testEvent, testActionState);

        // Assert
        var history = mockProducer.history();
        assertEquals(2, history.size());
        var record = history.get(0);
        assertEquals(TEST_TOPIC, record.topic());
        assertThat(ActionStateUtil.matchesBusinessKeyAndSeqNum(record.key(), TEST_KEY, 1L))
                .isTrue();
        assertNotNull(record.value());
        assertThat(record.value()).isEqualTo(testActionState);
    }

    @Test
    void testRebuildState() throws Exception {
        // Arrange
        List<Object> recoveryMarkers = List.of(Map.of(0, 0L, 1, 0L));

        assertThat(actionStates).isEmpty();

        actionStateStore.put(TEST_KEY, 1L, testAction, testEvent, testActionState);
        ActionState secondState = new ActionState(new InputEvent("second event"));
        actionStateStore.put(TEST_KEY, 2L, testAction, testEvent, secondState);
        ActionState thirdState = new ActionState(new InputEvent("third event"));
        actionStateStore.put(TEST_KEY, 3L, testAction, testEvent, thirdState);

        long i = 0L;
        for (ProducerRecord<String, ActionState> record : mockProducer.history()) {
            mockConsumer.addRecord(
                    new ConsumerRecord<>(record.topic(), 0, i++, record.key(), record.value()));
        }
        mockConsumer.updateEndOffsets(
                Map.of(
                        new TopicPartition(TEST_TOPIC, 0),
                        i,
                        new TopicPartition(TEST_TOPIC, 1),
                        0L));
        actionStates.clear();

        actionStateStore.rebuildState(recoveryMarkers);

        // Assert - only the state up to the recovery marker should be restored
        assertThat(
                        actionStates.get(
                                ActionStateUtil.generateKey(
                                        TEST_KEY, 1L, testAction, testEvent, MAX_PARALLELISM)))
                .isEqualTo(testActionState);
        assertThat(
                        actionStates.get(
                                ActionStateUtil.generateKey(
                                        TEST_KEY, 2L, testAction, testEvent, MAX_PARALLELISM)))
                .isEqualTo(secondState);
        assertThat(
                        actionStates.get(
                                ActionStateUtil.generateKey(
                                        TEST_KEY, 3L, testAction, testEvent, MAX_PARALLELISM)))
                .isEqualTo(thirdState);
    }

    @Test
    void testRebuildStateFromVersionedMarker() throws Exception {
        String stateKey =
                ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent, MAX_PARALLELISM);
        mockConsumer.addRecord(new ConsumerRecord<>(TEST_TOPIC, 0, 0L, stateKey, testActionState));
        mockConsumer.updateEndOffsets(
                Map.of(
                        new TopicPartition(TEST_TOPIC, 0),
                        1L,
                        new TopicPartition(TEST_TOPIC, 1),
                        0L));
        KafkaActionStateRecoveryMarker marker =
                new KafkaActionStateRecoveryMarker(
                        TEST_TOPIC, "test-topic-id:" + TEST_TOPIC, Map.of(0, 0L, 1, 0L));

        actionStateStore.rebuildState(List.of(marker));

        assertThat(actionStates).containsEntry(stateKey, testActionState);
    }

    @Test
    void testRebuildStateContinuesAfterEmptyPollAndStopsAtCapturedEnd() throws Exception {
        String includedKey =
                ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent, MAX_PARALLELISM);
        String laterKey =
                ActionStateUtil.generateKey(TEST_KEY, 2L, testAction, testEvent, MAX_PARALLELISM);
        mockConsumer.updateEndOffsets(
                Map.of(
                        new TopicPartition(TEST_TOPIC, 0),
                        1L,
                        new TopicPartition(TEST_TOPIC, 1),
                        0L));
        mockConsumer.schedulePollTask(() -> {});
        mockConsumer.schedulePollTask(
                () -> {
                    mockConsumer.addRecord(
                            new ConsumerRecord<>(TEST_TOPIC, 0, 0L, includedKey, testActionState));
                    mockConsumer.addRecord(
                            new ConsumerRecord<>(TEST_TOPIC, 0, 1L, laterKey, testActionState));
                });

        actionStateStore.rebuildState(List.of(Map.of(0, 0L, 1, 0L)));

        assertThat(actionStates)
                .containsEntry(includedKey, testActionState)
                .doesNotContainKey(laterKey);
    }

    @Test
    void testRebuildStateRejectsUnavailableEarlierOffset() {
        mockConsumer.updateBeginningOffsets(
                Map.of(
                        new TopicPartition(TEST_TOPIC, 0),
                        5L,
                        new TopicPartition(TEST_TOPIC, 1),
                        0L));
        mockConsumer.updateEndOffsets(
                Map.of(
                        new TopicPartition(TEST_TOPIC, 0),
                        10L,
                        new TopicPartition(TEST_TOPIC, 1),
                        0L));

        RuntimeException error =
                assertThrows(
                        RuntimeException.class,
                        () -> actionStateStore.rebuildState(List.of(Map.of(0, 4L, 1, 0L))));

        assertThat(error)
                .hasRootCauseMessage(
                        "Cannot rebuild Kafka action state for test-action-state-0: requested offset 4 is outside the available range [5, 10]");
    }

    @Test
    void testRebuildStateRejectsOffsetBeyondEnd() {
        mockConsumer.updateEndOffsets(
                Map.of(
                        new TopicPartition(TEST_TOPIC, 0),
                        10L,
                        new TopicPartition(TEST_TOPIC, 1),
                        0L));

        RuntimeException error =
                assertThrows(
                        RuntimeException.class,
                        () -> actionStateStore.rebuildState(List.of(Map.of(0, 11L, 1, 0L))));

        assertThat(error)
                .hasRootCauseMessage(
                        "Cannot rebuild Kafka action state for test-action-state-0: requested offset 11 is outside the available range [0, 10]");
    }

    @Test
    void testRebuildStateRejectsRecreatedTopic() {
        KafkaActionStateRecoveryMarker marker =
                new KafkaActionStateRecoveryMarker(
                        TEST_TOPIC, "old-topic-id", Map.of(0, 0L, 1, 0L));

        RuntimeException error =
                assertThrows(
                        RuntimeException.class,
                        () -> actionStateStore.rebuildState(List.of(marker)));

        assertThat(error)
                .hasRootCauseMessage(
                        "Kafka action-state topic test-action-state has ID test-topic-id:test-action-state, but the recovery marker expects old-topic-id; the topic may have been recreated");
    }

    @Test
    void testRebuildStateRejectsUnsupportedMarkerSchema() {
        KafkaActionStateRecoveryMarker marker =
                new KafkaActionStateRecoveryMarker(
                        99, TEST_TOPIC, "test-topic-id:" + TEST_TOPIC, Map.of(0, 0L, 1, 0L));

        RuntimeException error =
                assertThrows(
                        RuntimeException.class,
                        () -> actionStateStore.rebuildState(List.of(marker)));

        assertThat(error)
                .hasRootCauseMessage(
                        "Unsupported Kafka action-state recovery marker schema 99, expected 1");
    }

    @Test
    void testRebuildStateRejectsMixedLegacyAndVersionedMarkers() {
        KafkaActionStateRecoveryMarker marker =
                new KafkaActionStateRecoveryMarker(
                        TEST_TOPIC, "test-topic-id:" + TEST_TOPIC, Map.of(0, 0L, 1, 0L));

        RuntimeException error =
                assertThrows(
                        RuntimeException.class,
                        () -> actionStateStore.rebuildState(List.of(marker, Map.of(0, 0L, 1, 0L))));

        assertThat(error)
                .hasRootCauseMessage(
                        "Cannot restore from a mixture of versioned and legacy Kafka recovery markers");
    }

    @Test
    void testRebuildStateRejectsChangedPartitionSet() {
        RuntimeException error =
                assertThrows(
                        RuntimeException.class,
                        () -> actionStateStore.rebuildState(List.of(Map.of(0, 0L))));

        assertThat(error)
                .hasRootCauseMessage(
                        "Kafka action-state recovery marker contains partitions [0], but topic test-action-state currently has partitions [0, 1]");
    }

    @Test
    void testRecoveryMarkerRejectsTopicPartitionChange() {
        mockConsumer.updatePartitions(
                TEST_TOPIC,
                List.of(
                        new PartitionInfo(TEST_TOPIC, 0, null, null, null),
                        new PartitionInfo(TEST_TOPIC, 1, null, null, null),
                        new PartitionInfo(TEST_TOPIC, 2, null, null, null)));

        RuntimeException error =
                assertThrows(RuntimeException.class, actionStateStore::getRecoveryMarker);

        assertThat(error)
                .hasRootCauseMessage(
                        "Kafka action-state topic test-action-state changed while the job was running; expected ID test-topic-id:test-action-state and partitions [0, 1] but found ID test-topic-id:test-action-state and partitions [0, 1, 2]");
    }

    @Test
    void testRebuildStateRefreshesTopicMetadataBeforeReplay() {
        KafkaActionStateRecoveryMarker marker =
                new KafkaActionStateRecoveryMarker(
                        TEST_TOPIC, "test-topic-id:" + TEST_TOPIC, Map.of(0, 0L, 1, 0L));
        mockConsumer.updatePartitions(
                TEST_TOPIC,
                List.of(
                        new PartitionInfo(TEST_TOPIC, 0, null, null, null),
                        new PartitionInfo(TEST_TOPIC, 1, null, null, null),
                        new PartitionInfo(TEST_TOPIC, 2, null, null, null)));

        RuntimeException error =
                assertThrows(
                        RuntimeException.class,
                        () -> actionStateStore.rebuildState(List.of(marker)));

        assertThat(error)
                .hasRootCauseMessage(
                        "Kafka action-state topic test-action-state changed while the job was running; expected ID test-topic-id:test-action-state and partitions [0, 1] but found ID test-topic-id:test-action-state and partitions [0, 1, 2]");
    }

    @Test
    void testRebuildStateRechecksTopicMetadataImmediatelyBeforeReadingOffsets() {
        AtomicInteger metadataLoads = new AtomicInteger();
        MockConsumer<String, ActionState> changingConsumer =
                new MockConsumer<String, ActionState>(EARLIEST.name()) {
                    @Override
                    public synchronized List<PartitionInfo> partitionsFor(String topic) {
                        if (metadataLoads.incrementAndGet() == 3) {
                            updatePartitions(
                                    topic,
                                    List.of(
                                            new PartitionInfo(topic, 0, null, null, null),
                                            new PartitionInfo(topic, 1, null, null, null),
                                            new PartitionInfo(topic, 2, null, null, null)));
                        }
                        return super.partitionsFor(topic);
                    }
                };
        changingConsumer.updatePartitions(
                TEST_TOPIC,
                List.of(
                        new PartitionInfo(TEST_TOPIC, 0, null, null, null),
                        new PartitionInfo(TEST_TOPIC, 1, null, null, null)));
        KafkaActionStateStore store =
                new KafkaActionStateStore(
                        new HashMap<>(),
                        new AgentConfiguration(),
                        mockProducer,
                        changingConsumer,
                        TEST_TOPIC,
                        MAX_PARALLELISM);
        KafkaActionStateRecoveryMarker marker =
                new KafkaActionStateRecoveryMarker(
                        TEST_TOPIC, "test-topic-id:" + TEST_TOPIC, Map.of(0, 0L, 1, 0L));

        RuntimeException error =
                assertThrows(RuntimeException.class, () -> store.rebuildState(List.of(marker)));

        assertThat(error)
                .hasRootCauseMessage(
                        "Kafka action-state topic test-action-state changed while the job was running; expected ID test-topic-id:test-action-state and partitions [0, 1] but found ID test-topic-id:test-action-state and partitions [0, 1, 2]");
    }

    @Test
    void testRebuildStateRejectsMarkerOlderThanCommittedCleanupBoundary() {
        KafkaActionStateCleanupPlan plan =
                KafkaActionStateCleanupPlan.fromRecoveryMarkers(
                        "checkpoint-42",
                        List.of(
                                new KafkaActionStateRecoveryMarker(
                                        TEST_TOPIC,
                                        "test-topic-id:" + TEST_TOPIC,
                                        Map.of(0, 5L, 1, 0L))));
        KafkaActionStateStore cleanupAwareStore = cleanupAwareStore(committedCoordinator(plan));
        KafkaActionStateRecoveryMarker oldMarker =
                new KafkaActionStateRecoveryMarker(
                        TEST_TOPIC, "test-topic-id:" + TEST_TOPIC, Map.of(0, 4L, 1, 0L));

        RuntimeException error =
                assertThrows(
                        RuntimeException.class,
                        () -> cleanupAwareStore.rebuildState(List.of(oldMarker)));

        assertThat(error)
                .hasRootCauseMessage(
                        "Cannot restore Kafka action state for test-action-state-0 from offset 4 because the committed cleanup boundary is 5");
    }

    @Test
    void testRebuildStateRejectsLegacyMarkerWhenCleanupBoundaryIsConfigured() {
        KafkaActionStateCleanupPlan plan =
                KafkaActionStateCleanupPlan.fromRecoveryMarkers(
                        "checkpoint-42",
                        List.of(
                                new KafkaActionStateRecoveryMarker(
                                        TEST_TOPIC,
                                        "test-topic-id:" + TEST_TOPIC,
                                        Map.of(0, 0L, 1, 0L))));
        KafkaActionStateStore cleanupAwareStore = cleanupAwareStore(committedCoordinator(plan));

        RuntimeException error =
                assertThrows(
                        RuntimeException.class,
                        () -> cleanupAwareStore.rebuildState(List.of(Map.of(0, 0L, 1, 0L))));

        assertThat(error)
                .hasRootCauseMessage(
                        "Checkpoint-aligned cleanup requires versioned Kafka recovery markers");
    }

    @Test
    void testRebuildStateRejectsMissingMarkerAfterCleanupWasCommitted() {
        KafkaActionStateCleanupPlan plan =
                KafkaActionStateCleanupPlan.fromRecoveryMarkers(
                        "checkpoint-42",
                        List.of(
                                new KafkaActionStateRecoveryMarker(
                                        TEST_TOPIC,
                                        "test-topic-id:" + TEST_TOPIC,
                                        Map.of(0, 5L, 1, 0L))));
        KafkaActionStateStore cleanupAwareStore = cleanupAwareStore(committedCoordinator(plan));

        RuntimeException error =
                assertThrows(
                        RuntimeException.class,
                        () -> cleanupAwareStore.rebuildState(Collections.emptyList()));

        assertThat(error)
                .hasRootCauseMessage(
                        "Cannot initialize Kafka action state without a recovery marker because cleanup boundary {0=5, 1=0} is committed");
    }

    @Test
    void testRebuildStateRemovesTombstonedKeys() throws Exception {
        // Arrange - two state records followed by a tombstone for the first key
        List<Object> recoveryMarkers = List.of(Map.of(0, 0L, 1, 0L));
        String key1 =
                ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent, MAX_PARALLELISM);
        String key2 =
                ActionStateUtil.generateKey(TEST_KEY, 2L, testAction, testEvent, MAX_PARALLELISM);
        mockConsumer.addRecord(new ConsumerRecord<>(TEST_TOPIC, 0, 0L, key1, testActionState));
        mockConsumer.addRecord(new ConsumerRecord<>(TEST_TOPIC, 0, 1L, key2, testActionState));
        mockConsumer.addRecord(new ConsumerRecord<>(TEST_TOPIC, 0, 2L, key1, null));
        mockConsumer.updateEndOffsets(
                Map.of(
                        new TopicPartition(TEST_TOPIC, 0),
                        3L,
                        new TopicPartition(TEST_TOPIC, 1),
                        0L));

        actionStateStore.rebuildState(recoveryMarkers);
        assertThat(actionStates).doesNotContainKey(key1);
        assertThat(actionStates.get(key2)).isEqualTo(testActionState);
    }

    private static class TestAppender extends AbstractAppender {

        private final List<String> messages = Collections.synchronizedList(new ArrayList<>());

        private TestAppender(String name) {
            super(name, null, PatternLayout.newBuilder().withPattern("%msg").build(), true, null);
        }

        @Override
        public void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }

        private List<String> getMessages() {
            return messages;
        }
    }
    /**
     * After recovery, only the keys accepted by the ownership filter should enter the in-memory
     * cache. Here key "A" is owned and "B" is foreign, so "B" must be skipped while "A" is kept.
     */
    @Test
    void testRebuildStateFiltersForeignKeys() throws Exception {
        String keyA = "A";
        String keyB = "B";
        String stateKeyA =
                ActionStateUtil.generateKey(keyA, 1L, testAction, testEvent, MAX_PARALLELISM);
        String stateKeyB =
                ActionStateUtil.generateKey(keyB, 1L, testAction, testEvent, MAX_PARALLELISM);

        long offset = 0L;
        mockConsumer.addRecord(
                new ConsumerRecord<>(TEST_TOPIC, 0, offset++, stateKeyA, testActionState));
        mockConsumer.addRecord(
                new ConsumerRecord<>(TEST_TOPIC, 0, offset++, stateKeyB, testActionState));
        mockConsumer.updateEndOffsets(
                Map.of(
                        new TopicPartition(TEST_TOPIC, 0),
                        offset,
                        new TopicPartition(TEST_TOPIC, 1),
                        0L));

        List<Object> recoveryMarkers = List.of(Map.of(0, 0L, 1, 0L));

        int ownedKeyGroup = ActionStateUtil.parseKeyGroup(stateKeyA);
        actionStateStore.setOwnershipFilter(kg -> kg == ownedKeyGroup);
        actionStateStore.rebuildState(recoveryMarkers);

        assertThat(actionStates).containsKey(stateKeyA);
        assertThat(actionStates).doesNotContainKey(stateKeyB);
        assertThat(actionStateStore.get(keyA, 1L, testAction, testEvent))
                .isEqualTo(testActionState);
        assertThat(actionStateStore.get(keyB, 1L, testAction, testEvent)).isNull();
    }

    /**
     * When no ownership filter is set, rebuildState retains every key — the original behavior is
     * preserved (important for the in-memory and test backends).
     */
    @Test
    void testRebuildStateKeepsAllKeysWhenNoFilter() throws Exception {
        String stateKeyA =
                ActionStateUtil.generateKey("A", 1L, testAction, testEvent, MAX_PARALLELISM);
        String stateKeyB =
                ActionStateUtil.generateKey("B", 1L, testAction, testEvent, MAX_PARALLELISM);

        long offset = 0L;
        mockConsumer.addRecord(
                new ConsumerRecord<>(TEST_TOPIC, 0, offset++, stateKeyA, testActionState));
        mockConsumer.addRecord(
                new ConsumerRecord<>(TEST_TOPIC, 0, offset++, stateKeyB, testActionState));
        mockConsumer.updateEndOffsets(
                Map.of(
                        new TopicPartition(TEST_TOPIC, 0),
                        offset,
                        new TopicPartition(TEST_TOPIC, 1),
                        0L));

        List<Object> recoveryMarkers = List.of(Map.of(0, 0L, 1, 0L));

        actionStateStore.rebuildState(recoveryMarkers);

        assertThat(actionStates).containsKey(stateKeyA);
        assertThat(actionStates).containsKey(stateKeyB);
    }

    /**
     * Regression test for cross-key pruning: a numeric business key must not match another record's
     * sequence-number segment. Here business key 1 at seqNum 5 collides, on substring matching,
     * with pruning business key 5 — segment-exact matching must keep it.
     */
    @Test
    void testPruneStateDoesNotCrossNumericKeyAndSeqNum() throws Exception {
        String keyOneAtSeqFive =
                ActionStateUtil.generateKey(1L, 5L, testAction, testEvent, MAX_PARALLELISM);
        String keyFiveAtSeqThree =
                ActionStateUtil.generateKey(5L, 3L, testAction, testEvent, MAX_PARALLELISM);
        actionStates.put(keyOneAtSeqFive, testActionState);
        actionStates.put(keyFiveAtSeqThree, testActionState);

        actionStateStore.pruneState(5L, 10L);

        // Key 5's record (seqNum 3 <= 10) is pruned; key 1's record must survive even though its
        // seqNum segment ("_5_") textually contains the pruned business key.
        assertThat(actionStates).containsKey(keyOneAtSeqFive);
        assertThat(actionStates).doesNotContainKey(keyFiveAtSeqThree);
    }

    /**
     * The divergence cleanup inside {@code get()} must also be scoped to the requested business
     * key: a cache miss for one key must not evict another key's newer states.
     */
    @Test
    void testGetCleanupIsScopedToRequestedKey() throws Exception {
        String otherKeyNewerState =
                ActionStateUtil.generateKey(
                        "other-key", 9L, testAction, testEvent, MAX_PARALLELISM);
        actionStates.put(otherKeyNewerState, testActionState);

        // Cache miss for TEST_KEY at seqNum 1 triggers cleanup of states with seqNum > 1.
        assertNull(actionStateStore.get(TEST_KEY, 1L, testAction, testEvent));

        assertThat(actionStates).containsKey(otherKeyNewerState);
    }

    /**
     * Records whose composite state key is not in the current format — including records written
     * before the format change and otherwise malformed keys — cannot be attributed to a key-group
     * and are dropped during rebuild rather than retained in every subtask. This closes the
     * orphan-state leak; the project does not preserve pre-format durable state.
     */
    @Test
    void testRebuildStateDropsUnrecognizedFormatKeys() throws Exception {
        String legacyKey = TEST_KEY + "_1_event-uuid_action-uuid";
        String malformedKey = "malformed-key";
        String stateKeyA =
                ActionStateUtil.generateKey("A", 1L, testAction, testEvent, MAX_PARALLELISM);

        long offset = 0L;
        mockConsumer.addRecord(
                new ConsumerRecord<>(TEST_TOPIC, 0, offset++, legacyKey, testActionState));
        mockConsumer.addRecord(
                new ConsumerRecord<>(TEST_TOPIC, 0, offset++, malformedKey, testActionState));
        mockConsumer.addRecord(
                new ConsumerRecord<>(TEST_TOPIC, 0, offset++, stateKeyA, testActionState));
        mockConsumer.updateEndOffsets(
                Map.of(
                        new TopicPartition(TEST_TOPIC, 0),
                        offset,
                        new TopicPartition(TEST_TOPIC, 1),
                        0L));

        List<Object> recoveryMarkers = List.of(Map.of(0, 0L, 1, 0L));

        int ownedKeyGroup = ActionStateUtil.parseKeyGroup(stateKeyA);
        actionStateStore.setOwnershipFilter(kg -> kg == ownedKeyGroup);
        actionStateStore.rebuildState(recoveryMarkers);

        assertThat(actionStates).containsKey(stateKeyA);
        assertThat(actionStates).doesNotContainKey(legacyKey);
        assertThat(actionStates).doesNotContainKey(malformedKey);
    }

    /**
     * A well-formed (5-segment) key whose key-group segment is not numeric cannot be attributed to
     * a key-group and is dropped during rebuild.
     */
    @Test
    void testRebuildStateDropsKeyWithUnparsableKeyGroup() throws Exception {
        String unparseableGroupKey = "not-a-number_1_event-uuid_action-uuid_bkey";
        String stateKeyA =
                ActionStateUtil.generateKey("A", 1L, testAction, testEvent, MAX_PARALLELISM);

        long offset = 0L;
        mockConsumer.addRecord(
                new ConsumerRecord<>(
                        TEST_TOPIC, 0, offset++, unparseableGroupKey, testActionState));
        mockConsumer.addRecord(
                new ConsumerRecord<>(TEST_TOPIC, 0, offset++, stateKeyA, testActionState));
        mockConsumer.updateEndOffsets(
                Map.of(
                        new TopicPartition(TEST_TOPIC, 0),
                        offset,
                        new TopicPartition(TEST_TOPIC, 1),
                        0L));

        List<Object> recoveryMarkers = List.of(Map.of(0, 0L, 1, 0L));

        int ownedKeyGroup = ActionStateUtil.parseKeyGroup(stateKeyA);
        actionStateStore.setOwnershipFilter(kg -> kg == ownedKeyGroup);
        actionStateStore.rebuildState(recoveryMarkers);

        assertThat(actionStates).containsKey(stateKeyA);
        assertThat(actionStates).doesNotContainKey(unparseableGroupKey);
    }

    private KafkaActionStateStore cleanupAwareStore(
            KafkaActionStateCleanupCoordinator coordinator) {
        return new KafkaActionStateStore(
                actionStates,
                new AgentConfiguration(),
                mockProducer,
                mockConsumer,
                TEST_TOPIC,
                MAX_PARALLELISM,
                coordinator);
    }

    private static KafkaActionStateCleanupCoordinator committedCoordinator(
            KafkaActionStateCleanupPlan plan) {
        KafkaActionStateCleanupCoordinator.Operation operation =
                KafkaActionStateCleanupCoordinator.Operation.committed(plan);
        return new KafkaActionStateCleanupCoordinator(
                new KafkaActionStateCleanupCoordinator.Transport() {
                    @Override
                    public KafkaActionStateCleanupCoordinator.TopicMetadata describeTopic(
                            String topic) {
                        return new KafkaActionStateCleanupCoordinator.TopicMetadata(
                                plan.getTopicId(), Set.copyOf(plan.getOffsets().keySet()));
                    }

                    @Override
                    public Map<String, KafkaActionStateCleanupCoordinator.Operation>
                            readOperations() {
                        return Map.of(plan.getPlanId(), operation);
                    }

                    @Override
                    public void append(KafkaActionStateCleanupCoordinator.Operation value) {}

                    @Override
                    public void validateOffsetsAvailable(
                            String topic, String topicId, Map<Integer, Long> offsets) {}

                    @Override
                    public void deleteBefore(
                            String topic, String topicId, Map<Integer, Long> offsets) {}

                    @Override
                    public void close() {}
                });
    }
    /** Contract: the consumer is closed even when closing the producer throws. */
    @Test
    @SuppressWarnings("unchecked")
    void testCloseClosesConsumerWhenProducerCloseFails() {
        Producer<String, ActionState> failingProducer = mock(Producer.class);
        Consumer<String, ActionState> consumer = mock(Consumer.class);
        doThrow(new RuntimeException("producer close failed")).when(failingProducer).close();

        KafkaActionStateStore store =
                new KafkaActionStateStore(
                        actionStates,
                        new AgentConfiguration(),
                        failingProducer,
                        consumer,
                        TEST_TOPIC,
                        MAX_PARALLELISM);

        assertThrows(RuntimeException.class, store::close);

        verify(consumer).close();
    }

    /**
     * Contract: when both closes fail, the producer's exception is the one thrown and the
     * consumer's is attached to it as a suppressed exception, so neither failure is lost.
     */
    @Test
    @SuppressWarnings("unchecked")
    void testCloseKeepsProducerFailureWhenBothCloseFail() {
        Producer<String, ActionState> failingProducer = mock(Producer.class);
        Consumer<String, ActionState> failingConsumer = mock(Consumer.class);
        RuntimeException producerFailure = new RuntimeException("producer close failed");
        RuntimeException consumerFailure = new RuntimeException("consumer close failed");
        doThrow(producerFailure).when(failingProducer).close();
        doThrow(consumerFailure).when(failingConsumer).close();

        KafkaActionStateStore store =
                new KafkaActionStateStore(
                        actionStates,
                        new AgentConfiguration(),
                        failingProducer,
                        failingConsumer,
                        TEST_TOPIC,
                        MAX_PARALLELISM);

        RuntimeException thrown = assertThrows(RuntimeException.class, store::close);

        assertThat(thrown).isSameAs(producerFailure);
        assertThat(thrown.getSuppressed()).containsExactly(consumerFailure);
    }

    /**
     * Contract: when only the consumer close fails, its exception reaches the caller unchanged,
     * with nothing attached as suppressed.
     */
    @Test
    @SuppressWarnings("unchecked")
    void testCloseThrowsConsumerFailureWhenOnlyConsumerCloseFails() {
        Producer<String, ActionState> producer = mock(Producer.class);
        Consumer<String, ActionState> failingConsumer = mock(Consumer.class);
        RuntimeException consumerFailure = new RuntimeException("consumer close failed");
        doThrow(consumerFailure).when(failingConsumer).close();

        KafkaActionStateStore store =
                new KafkaActionStateStore(
                        actionStates,
                        new AgentConfiguration(),
                        producer,
                        failingConsumer,
                        TEST_TOPIC,
                        MAX_PARALLELISM);

        RuntimeException thrown = assertThrows(RuntimeException.class, store::close);

        assertThat(thrown).isSameAs(consumerFailure);
        assertThat(thrown.getSuppressed()).isEmpty();
    }

    /**
     * Contract: when closing the producer throws a non-{@code Exception} {@code Throwable}, the
     * consumer is still closed and the throwable reaches the caller unchanged.
     */
    @Test
    @SuppressWarnings("unchecked")
    void testCloseClosesConsumerWhenProducerCloseThrowsError() {
        Producer<String, ActionState> failingProducer = mock(Producer.class);
        Consumer<String, ActionState> consumer = mock(Consumer.class);
        NoClassDefFoundError producerFailure =
                new NoClassDefFoundError("simulated teardown failure");
        doThrow(producerFailure).when(failingProducer).close();

        KafkaActionStateStore store =
                new KafkaActionStateStore(
                        actionStates,
                        new AgentConfiguration(),
                        failingProducer,
                        consumer,
                        TEST_TOPIC,
                        MAX_PARALLELISM);

        assertThat(catchThrowable(store::close)).isSameAs(producerFailure);

        verify(consumer).close();
    }

    /**
     * Contract: when the producer close fails and the consumer close then throws a non-{@code
     * Exception} {@code Throwable}, the producer's exception stays the failure the caller sees and
     * the consumer's throwable is attached as suppressed.
     */
    @Test
    @SuppressWarnings("unchecked")
    void testCloseKeepsProducerFailureWhenConsumerCloseThrowsError() {
        Producer<String, ActionState> failingProducer = mock(Producer.class);
        Consumer<String, ActionState> failingConsumer = mock(Consumer.class);
        RuntimeException producerFailure = new RuntimeException("producer close failed");
        NoClassDefFoundError consumerFailure =
                new NoClassDefFoundError("simulated teardown failure");
        doThrow(producerFailure).when(failingProducer).close();
        doThrow(consumerFailure).when(failingConsumer).close();

        KafkaActionStateStore store =
                new KafkaActionStateStore(
                        actionStates,
                        new AgentConfiguration(),
                        failingProducer,
                        failingConsumer,
                        TEST_TOPIC,
                        MAX_PARALLELISM);

        Throwable thrown = catchThrowable(store::close);

        assertThat(thrown).isSameAs(producerFailure);
        assertThat(thrown.getSuppressed()).containsExactly(consumerFailure);
    }

    /** Contract: both the producer and the consumer are closed when neither close fails. */
    @Test
    void testCloseClosesProducerAndConsumer() throws Exception {
        actionStateStore.close();

        assertThat(mockProducer.closed()).isTrue();
        assertThat(mockConsumer.closed()).isTrue();
    }
}
