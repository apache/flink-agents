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
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.kafka.clients.consumer.internals.AutoOffsetResetStrategy.EARLIEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link KafkaActionStateStore}. */
public class KafkaActionStateStoreTest {

    private static final String TEST_TOPIC = "test-action-state";
    private static final String TEST_KEY = "test-key";

    private MockProducer<String, ActionState> mockProducer;
    private MockConsumer<String, ActionState> mockConsumer;
    private KafkaActionStateStore actionStateStore;
    private Action testAction;
    private Event testEvent;
    private ActionState testActionState;
    private Map<String, ActionState> actionStates;

    @BeforeEach
    void setUp() throws Exception {
        mockProducer =
                new MockProducer<>(
                        true,
                        new ActionStateKeyPartitioner(),
                        new StringSerializer(),
                        new ActionStateKafkaSeder());
        mockConsumer = new MockConsumer<>(EARLIEST.name());
        mockConsumer.assign(
                List.of(new TopicPartition(TEST_TOPIC, 0), new TopicPartition(TEST_TOPIC, 1)));
        actionStates = new HashMap<>();
        actionStateStore =
                new KafkaActionStateStore(
                        actionStates,
                        new AgentConfiguration(),
                        mockProducer,
                        mockConsumer,
                        TEST_TOPIC);

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
        return new KafkaActionStateStore(states, config, producer, mockConsumer, TEST_TOPIC);
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
        assertThat(record.key()).startsWith(TEST_KEY + "_1");
        assertNotNull(record.value());
        assertThat(record.value()).isEqualTo(testActionState);
    }

    @Test
    void testGetNonExistentActionState() throws Exception {
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent), testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 2L, testAction, testEvent), testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 3L, testAction, testEvent), testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 4L, testAction, testEvent), testActionState);

        actionStateStore.get(TEST_KEY, 2L, new NoOpAction("test-1"), testEvent);

        assertNotNull(actionStateStore.get(TEST_KEY, 1L, testAction, testEvent));
        assertNotNull(actionStateStore.get(TEST_KEY, 2L, testAction, testEvent));
        assertNull(actionStateStore.get(TEST_KEY, 3L, testAction, testEvent));
        assertNull(actionStateStore.get(TEST_KEY, 4L, testAction, testEvent));
    }

    @Test
    void testGetActionStateWithDiverge() throws Exception {
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent), testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 2L, testAction, testEvent), testActionState);
        // diverge here
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 2L, new NoOpAction("test-2"), testEvent),
                testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 3L, testAction, testEvent), testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 4L, testAction, testEvent), testActionState);

        actionStateStore.get(TEST_KEY, 2L, testAction, testEvent);

        assertNotNull(actionStateStore.get(TEST_KEY, 1L, testAction, testEvent));
        assertNotNull(actionStateStore.get(TEST_KEY, 2L, testAction, testEvent));
        assertNull(actionStateStore.get(TEST_KEY, 3L, testAction, testEvent));
        assertNull(actionStateStore.get(TEST_KEY, 4L, testAction, testEvent));
    }

    @Test
    void testRecoveryMarker() throws Exception {
        // Test getting initial recovery marker
        Object initialMarker = actionStateStore.getRecoveryMarker();
        assertNotNull(initialMarker);
        assertTrue(initialMarker instanceof Map);
        assertTrue(((Map<?, ?>) initialMarker).isEmpty());

        mockConsumer.updatePartitions(
                TEST_TOPIC,
                List.of(
                        new PartitionInfo(TEST_TOPIC, 0, null, null, null),
                        new PartitionInfo(TEST_TOPIC, 1, null, null, null)));
        mockConsumer.updateEndOffsets(
                Map.of(
                        new TopicPartition(TEST_TOPIC, 0),
                        5L,
                        new TopicPartition(TEST_TOPIC, 1),
                        3L));
        for (int i = 0; i < 5; i++) {
            mockConsumer.addRecord(
                    new ConsumerRecord<>(
                            TEST_TOPIC,
                            0,
                            i++,
                            "key",
                            new ActionState(null, null, null, null, null, false)));
        }
        // Test getting recovery marker after putting state
        Object secondMarker = actionStateStore.getRecoveryMarker();
        assertTrue(initialMarker instanceof Map);
        assertFalse(((Map<?, ?>) secondMarker).isEmpty());
        assertThat((Map<Integer, Long>) secondMarker).containsEntry(0, 5L);
        assertThat((Map<Integer, Long>) secondMarker).containsEntry(1, 3L);
    }

    @Test
    void testPruneState() throws Exception {
        // Arrange
        actionStateStore = tombstoneEnabledStore(actionStates, mockProducer);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent), testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 2L, testAction, testEvent), testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 3L, testAction, testEvent), testActionState);

        // Verify all states exist
        assertNotNull(actionStateStore.get(TEST_KEY, 1L, testAction, testEvent));
        assertNotNull(actionStateStore.get(TEST_KEY, 2L, testAction, testEvent));
        assertNotNull(actionStateStore.get(TEST_KEY, 3L, testAction, testEvent));

        // Act - prune states up to sequence number 2
        actionStateStore.pruneState(TEST_KEY, 2L);

        // Assert - states 1 and 2 should be pruned, state 3 should remain
        assertNull(
                actionStates.get(ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent)));
        assertNull(
                actionStates.get(ActionStateUtil.generateKey(TEST_KEY, 2L, testAction, testEvent)));
        assertNotNull(actionStateStore.get(TEST_KEY, 3L, testAction, testEvent));

        // Assert - tombstones should have been sent to Kafka
        var history = mockProducer.history();
        assertThat(history).hasSize(2);
        for (ProducerRecord<String, ActionState> record : history) {
            assertThat(record.topic()).isEqualTo(TEST_TOPIC);
            assertThat(record.key()).startsWith(TEST_KEY + "_");
            assertThat(record.value()).isNull();
        }
    }

    @Test
    void testPruneStateSendsTombstonesWithCorrectKeys() throws Exception {
        // Arrange
        actionStateStore = tombstoneEnabledStore(actionStates, mockProducer);
        String key1 = ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent);
        String key2 = ActionStateUtil.generateKey(TEST_KEY, 2L, testAction, testEvent);
        String key3 = ActionStateUtil.generateKey(TEST_KEY, 3L, testAction, testEvent);
        actionStates.put(key1, testActionState);
        actionStates.put(key2, testActionState);
        actionStates.put(key3, testActionState);

        // Act
        actionStateStore.pruneState(TEST_KEY, 2L);

        // Assert - exactly keys for seqNum 1 and 2 appear as tombstones
        var history = mockProducer.history();
        assertThat(history).extracting(ProducerRecord::key).containsExactlyInAnyOrder(key1, key2);
        assertThat(history).extracting(ProducerRecord::value).containsOnlyNulls();
    }

    @Test
    void testPruneStateDoesNotPruneOtherKeysWithMatchingPrefix() throws Exception {
        // Arrange - agent key "a" seq 1 yields state key "a_1_<uuid>_<uuid>", which is a
        // prefix match for pruning agent key "a_1"
        actionStateStore = tombstoneEnabledStore(actionStates, mockProducer);
        String otherKeyState = ActionStateUtil.generateKey("a", 1L, testAction, testEvent);
        actionStates.put(otherKeyState, testActionState);

        // Act - prune a DIFFERENT agent key whose name collides with "a"'s key prefix
        actionStateStore.pruneState("a_1", 10L);

        // Assert - agent key "a"'s state is untouched and no tombstones were sent
        assertThat(actionStates).containsKey(otherKeyState);
        assertThat(mockProducer.history()).isEmpty();
    }

    @Test
    void testPruneStateEvictsCacheEvenWhenTombstoneSendFails() throws Exception {
        // Arrange - the next send() will fail asynchronously (e.g. broker unavailable)
        actionStateStore = tombstoneEnabledStore(actionStates, mockProducer);
        String stateKey = ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent);
        actionStates.put(stateKey, testActionState);
        mockProducer.errorNext(new RuntimeException("simulated broker failure"));

        // Act - should not throw despite the async send failure
        actionStateStore.pruneState(TEST_KEY, 1L);

        // Assert - in-memory entry is still evicted regardless of tombstone delivery
        assertThat(actionStates).doesNotContainKey(stateKey);
    }

    @Test
    void testPruneStateSkipsUnparseableKeys() throws Exception {
        // Arrange - a state key with the right prefix but the wrong number of parts, which
        // ActionStateUtil.parseKey cannot split into exactly 4 parts
        actionStateStore = tombstoneEnabledStore(actionStates, mockProducer);
        String malformedKey = TEST_KEY + "_1_onlythreeparts";
        actionStates.put(malformedKey, testActionState);

        // Act - should not throw despite the unparseable key
        actionStateStore.pruneState(TEST_KEY, 10L);

        // Assert - the unparseable entry is retained, and no tombstone was sent for it
        assertThat(actionStates).containsKey(malformedKey);
        assertThat(mockProducer.history()).isEmpty();
    }

    @Test
    void testPruneStateNoTombstonesByDefault() throws Exception {
        // Arrange - setUp store uses a default AgentConfiguration (tombstones disabled)
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent), testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 2L, testAction, testEvent), testActionState);

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
                ActionStateUtil.generateKey("other-key", 1L, testAction, testEvent),
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
                ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent), testActionState);

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
        assertThat(record.key()).startsWith(TEST_KEY + "_1");
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

        actionStateStore.rebuildState(recoveryMarkers);

        // Assert - only the state up to the recovery marker should be restored
        assertThat(
                        actionStates.get(
                                ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent)))
                .isEqualTo(testActionState);
        assertThat(
                        actionStates.get(
                                ActionStateUtil.generateKey(TEST_KEY, 2L, testAction, testEvent)))
                .isEqualTo(secondState);
        assertThat(
                        actionStates.get(
                                ActionStateUtil.generateKey(TEST_KEY, 3L, testAction, testEvent)))
                .isEqualTo(thirdState);
    }

    @Test
    void testRebuildStateRemovesTombstonedKeys() throws Exception {
        // Arrange - two state records followed by a tombstone for the first key
        List<Object> recoveryMarkers = List.of(Map.of(0, 0L));
        String key1 = ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent);
        String key2 = ActionStateUtil.generateKey(TEST_KEY, 2L, testAction, testEvent);
        mockConsumer.addRecord(new ConsumerRecord<>(TEST_TOPIC, 0, 0L, key1, testActionState));
        mockConsumer.addRecord(new ConsumerRecord<>(TEST_TOPIC, 0, 1L, key2, testActionState));
        mockConsumer.addRecord(new ConsumerRecord<>(TEST_TOPIC, 0, 2L, key1, null));

        // Act
        actionStateStore.rebuildState(recoveryMarkers);

        // Assert - the tombstoned key is removed, the other key is restored
        assertThat(actionStates).doesNotContainKey(key1);
        assertThat(actionStates.get(key2)).isEqualTo(testActionState);
    }
}
