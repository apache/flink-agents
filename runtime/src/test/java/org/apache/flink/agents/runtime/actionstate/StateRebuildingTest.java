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
import org.apache.flink.agents.plan.Action;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for state rebuilding functionality in {@link KafkaActionStateStore}. */
public class StateRebuildingTest {

    private static final String TEST_TOPIC = "test-rebuild-topic";
    private static final String TEST_KEY = "test-rebuild-key";
    private static final String OTHER_KEY = "other-key";

    private MockProducer<String, String> mockProducer;
    private MockConsumer<String, String> mockConsumer;
    private KafkaActionStateStore actionStateStore;
    private Action testAction;
    private Event testEvent;

    @BeforeEach
    void setUp() {
        mockProducer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        mockConsumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);

        Map<String, ActionState> actionStates = new HashMap<>();
        actionStateStore =
                new KafkaActionStateStore(actionStates, mockProducer, mockConsumer, TEST_TOPIC);

        testAction =
                new Action(
                        "testAction",
                        new JavaFunction("TestClass", "testMethod"),
                        Collections.emptyList());
        testEvent = new InputEvent("test data");

        // Set up mock consumer
        TopicPartition partition = new TopicPartition(TEST_TOPIC, 0);
        mockConsumer.assign(Collections.singletonList(partition));
        mockConsumer.updateBeginningOffsets(Collections.singletonMap(partition, 0L));
        mockConsumer.updateEndOffsets(Collections.singletonMap(partition, 0L));
    }

    @Test
    void testRebuildStateWithEmptyTopic() {
        // Arrange - no records in topic
        mockConsumer.schedulePollTask(
                () -> {
                    // Return empty records
                });

        // Act
        assertDoesNotThrow(() -> actionStateStore.rebuildState(TEST_KEY));

        // Assert
        assertNull(actionStateStore.get(TEST_KEY, testAction, testEvent));
    }

    @Test
    void testRebuildStateWithMatchingKey() throws Exception {
        // Arrange
        ActionState testState = new ActionState(testEvent);
        String stateKey = ActionStateUtil.generateKey(TEST_KEY, testAction, testEvent);

        // Create a serialized record manually (simulating what would come from Kafka)
        String recordValue =
                "{\"key\":\""
                        + TEST_KEY
                        + "\",\"actionName\":\"testAction\",\"eventType\":\""
                        + testEvent.getClass().getName()
                        + "\",\"actionState\":"
                        + "{\"taskEvent\":{\"input\":\"test data\"},\"memoryUpdates\":[],\"outputEvents\":[],\"generatedActionTask\":null}}";

        TopicPartition partition = new TopicPartition(TEST_TOPIC, 0);
        mockConsumer.updateEndOffsets(Collections.singletonMap(partition, 1L));

        // Schedule the poll to return our test record
        mockConsumer.schedulePollTask(
                () -> {
                    mockConsumer.addRecord(
                            new ConsumerRecord<>(TEST_TOPIC, 0, 0L, stateKey, recordValue));
                });

        // Act
        actionStateStore.rebuildState(TEST_KEY);

        // Assert
        ActionState retrieved = actionStateStore.get(TEST_KEY, testAction, testEvent);
        assertNotNull(retrieved);
        // Verify recovery marker was updated
        Object recoveryMarker = actionStateStore.getRecoveryMarker();
        assertNotNull(recoveryMarker);
        assertTrue(recoveryMarker instanceof Map);
        assertFalse(((Map<?, ?>) recoveryMarker).isEmpty());
    }

    @Test
    void testRebuildStateWithNonMatchingKey() throws Exception {
        // Arrange
        String otherStateKey = ActionStateUtil.generateKey(OTHER_KEY, testAction, testEvent);
        String recordValue =
                "{\"key\":\""
                        + OTHER_KEY
                        + "\",\"actionName\":\"testAction\",\"eventType\":\""
                        + testEvent.getClass().getName()
                        + "\",\"actionState\":"
                        + "{\"taskEvent\":{\"input\":\"other data\"},\"memoryUpdates\":[],\"outputEvents\":[],\"generatedActionTask\":null}}";

        TopicPartition partition = new TopicPartition(TEST_TOPIC, 0);
        mockConsumer.updateEndOffsets(Collections.singletonMap(partition, 1L));

        mockConsumer.schedulePollTask(
                () -> {
                    mockConsumer.addRecord(
                            new ConsumerRecord<>(TEST_TOPIC, 0, 0L, otherStateKey, recordValue));
                });

        // Act
        actionStateStore.rebuildState(TEST_KEY);

        // Assert - should not have rebuilt state for non-matching key
        assertNull(actionStateStore.get(TEST_KEY, testAction, testEvent));
        assertNull(actionStateStore.get(OTHER_KEY, testAction, testEvent));
    }

    @Test
    void testRebuildStateWithMultipleRecords() throws Exception {
        // Arrange
        Action action1 =
                new Action(
                        "action1", new JavaFunction("Class1", "method1"), Collections.emptyList());
        Action action2 =
                new Action(
                        "action2", new JavaFunction("Class2", "method2"), Collections.emptyList());
        Event event1 = new InputEvent("data1");
        Event event2 = new InputEvent("data2");

        String stateKey1 = ActionStateUtil.generateKey(TEST_KEY, action1, event1);
        String stateKey2 = ActionStateUtil.generateKey(TEST_KEY, action2, event2);
        String otherKey = ActionStateUtil.generateKey(OTHER_KEY, action1, event1);

        String recordValue1 =
                "{\"key\":\""
                        + TEST_KEY
                        + "\",\"actionName\":\"action1\",\"eventType\":\""
                        + event1.getClass().getName()
                        + "\",\"actionState\":"
                        + "{\"taskEvent\":{\"input\":\"data1\"},\"memoryUpdates\":[],\"outputEvents\":[],\"generatedActionTask\":null}}";

        String recordValue2 =
                "{\"key\":\""
                        + TEST_KEY
                        + "\",\"actionName\":\"action2\",\"eventType\":\""
                        + event2.getClass().getName()
                        + "\",\"actionState\":"
                        + "{\"taskEvent\":{\"input\":\"data2\"},\"memoryUpdates\":[],\"outputEvents\":[],\"generatedActionTask\":null}}";

        String recordValue3 =
                "{\"key\":\""
                        + OTHER_KEY
                        + "\",\"actionName\":\"action1\",\"eventType\":\""
                        + event1.getClass().getName()
                        + "\",\"actionState\":"
                        + "{\"taskEvent\":{\"input\":\"other data\"},\"memoryUpdates\":[],\"outputEvents\":[],\"generatedActionTask\":null}}";

        TopicPartition partition = new TopicPartition(TEST_TOPIC, 0);
        mockConsumer.updateEndOffsets(Collections.singletonMap(partition, 3L));

        mockConsumer.schedulePollTask(
                () -> {
                    mockConsumer.addRecord(
                            new ConsumerRecord<>(TEST_TOPIC, 0, 0L, stateKey1, recordValue1));
                    mockConsumer.addRecord(
                            new ConsumerRecord<>(TEST_TOPIC, 0, 1L, stateKey2, recordValue2));
                    mockConsumer.addRecord(
                            new ConsumerRecord<>(TEST_TOPIC, 0, 2L, otherKey, recordValue3));
                });

        // Act
        actionStateStore.rebuildState(TEST_KEY);

        // Assert - should only rebuild states for TEST_KEY
        assertNotNull(actionStateStore.get(TEST_KEY, action1, event1));
        assertNotNull(actionStateStore.get(TEST_KEY, action2, event2));
        assertNull(actionStateStore.get(OTHER_KEY, action1, event1));
    }

    @Test
    void testRebuildStateWithInvalidJson() throws Exception {
        // Arrange
        String stateKey = ActionStateUtil.generateKey(TEST_KEY, testAction, testEvent);
        String invalidRecordValue = "invalid-json-content";

        TopicPartition partition = new TopicPartition(TEST_TOPIC, 0);
        mockConsumer.updateEndOffsets(Collections.singletonMap(partition, 1L));

        mockConsumer.schedulePollTask(
                () -> {
                    mockConsumer.addRecord(
                            new ConsumerRecord<>(TEST_TOPIC, 0, 0L, stateKey, invalidRecordValue));
                });

        // Act & Assert - should not throw exception, just log warning
        assertDoesNotThrow(() -> actionStateStore.rebuildState(TEST_KEY));
        assertNull(actionStateStore.get(TEST_KEY, testAction, testEvent));
    }

    @Test
    void testRebuildStateTimeout() {
        // Arrange - consumer that never returns records
        mockConsumer.schedulePollTask(
                () -> {
                    // Simulate slow/no response by not adding any records
                    try {
                        Thread.sleep(100); // Small delay to simulate some processing
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });

        // Act & Assert - should complete within reasonable time due to timeout
        long startTime = System.currentTimeMillis();
        assertDoesNotThrow(() -> actionStateStore.rebuildState(TEST_KEY));
        long endTime = System.currentTimeMillis();

        // Should complete quickly due to timeout mechanism
        assertTrue(
                endTime - startTime
                        < 35000); // Less than 35 seconds (timeout is 30 seconds + buffer)
    }
}
