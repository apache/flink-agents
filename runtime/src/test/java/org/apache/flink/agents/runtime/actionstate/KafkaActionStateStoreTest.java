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
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.producer.MockProducer;
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
        testAction = new TestAction("test-action");
        testEvent = new InputEvent("test data");
        testActionState = new ActionState(testEvent);
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

        actionStateStore.get(TEST_KEY, 2L, new TestAction("test-1"), testEvent);

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
                ActionStateUtil.generateKey(TEST_KEY, 2L, new TestAction("test-2"), testEvent),
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
                    new ConsumerRecord<>(TEST_TOPIC, 0, i++, "key", new ActionState()));
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

    private static class TestAction extends Action {

        public static void doNothing(Event event, RunnerContext context) {
            // No operation
        }

        public TestAction(String name) throws Exception {
            super(
                    name,
                    new JavaFunction(
                            TestAction.class.getName(),
                            "doNothing",
                            new Class[] {Event.class, RunnerContext.class}),
                    List.of(InputEvent.class.getName()));
        }
    }
}
