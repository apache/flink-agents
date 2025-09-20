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
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link KafkaActionStateStore}. */
public class KafkaActionStateStoreTest {

    private static final String TEST_TOPIC = "test-action-state";
    private static final String TEST_KEY = "test-key";

    private MockProducer<String, String> mockProducer;
    private MockConsumer<String, String> mockConsumer;
    private KafkaActionStateStore actionStateStore;
    private Action testAction;
    private Event testEvent;
    private ActionState testActionState;

    @BeforeEach
    void setUp() {
        mockProducer = new MockProducer<>();
        mockConsumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);

        Map<String, ActionState> actionStates = new HashMap<>();
        actionStateStore =
                new KafkaActionStateStore(actionStates, mockProducer, mockConsumer, TEST_TOPIC);

        // Create test objects
        testAction =
                new Action(
                        "testAction",
                        new JavaFunction("TestClass", "testMethod"),
                        Collections.emptyList());
        testEvent = new InputEvent("test data");
        testActionState = new ActionState(testEvent);
    }

    @Test
    void testPutActionState() {
        // Act
        actionStateStore.put(TEST_KEY, testAction, testEvent, testActionState);

        // Assert
        // Check in-memory state
        String expectedKey = ActionStateUtil.generateKey(TEST_KEY, testAction, testEvent);
        ActionState retrievedState = actionStateStore.get(TEST_KEY, testAction, testEvent);
        assertNotNull(retrievedState);
        assertEquals(testActionState.getTaskEvent(), retrievedState.getTaskEvent());

        // Check Kafka producer
        assertEquals(1, mockProducer.history().size());
        ProducerRecord<String, String> record = mockProducer.history().get(0);
        assertEquals(TEST_TOPIC, record.topic());
        assertEquals(expectedKey, record.key());
        assertNotNull(record.value());
    }

    @Test
    void testGetActionState() {
        // Arrange
        actionStateStore.put(TEST_KEY, testAction, testEvent, testActionState);

        // Act
        ActionState result = actionStateStore.get(TEST_KEY, testAction, testEvent);

        // Assert
        assertNotNull(result);
        assertEquals(testActionState.getTaskEvent(), result.getTaskEvent());
    }

    @Test
    void testGetNonExistentActionState() {
        // Act
        ActionState result = actionStateStore.get("non-existent-key", testAction, testEvent);

        // Assert
        assertNull(result);
    }

    @Test
    void testRecoveryMarker() {
        // Test getting initial recovery marker
        Object initialMarker = actionStateStore.getRecoveryMarker();
        assertNotNull(initialMarker);
        assertTrue(initialMarker instanceof Map);

        // Test setting recovery marker
        Map<TopicPartition, Long> testMarker = new HashMap<>();
        TopicPartition partition = new TopicPartition(TEST_TOPIC, 0);
        testMarker.put(partition, 100L);

        actionStateStore.setRecoveryMarker(testMarker);

        Object retrievedMarker = actionStateStore.getRecoveryMarker();
        assertNotNull(retrievedMarker);
        assertTrue(retrievedMarker instanceof Map);

        @SuppressWarnings("unchecked")
        Map<TopicPartition, Long> retrievedMap = (Map<TopicPartition, Long>) retrievedMarker;
        assertEquals(1, retrievedMap.size());
        assertEquals(100L, retrievedMap.get(partition));
    }

    @Test
    void testCleanUpState() {
        // Arrange
        actionStateStore.put(TEST_KEY, testAction, testEvent, testActionState);
        assertNotNull(actionStateStore.get(TEST_KEY, testAction, testEvent));

        // Act
        actionStateStore.cleanUpState();

        // Assert
        assertNull(actionStateStore.get(TEST_KEY, testAction, testEvent));
        assertTrue(mockProducer.closed());
        assertTrue(mockConsumer.closed());
    }

    @Test
    void testMultipleActionStates() {
        // Arrange
        Action action1 =
                new Action(
                        "action1", new JavaFunction("Class1", "method1"), Collections.emptyList());
        Action action2 =
                new Action(
                        "action2", new JavaFunction("Class2", "method2"), Collections.emptyList());
        Event event1 = new InputEvent("data1");
        Event event2 = new InputEvent("data2");
        ActionState state1 = new ActionState(event1);
        ActionState state2 = new ActionState(event2);

        // Act
        actionStateStore.put("key1", action1, event1, state1);
        actionStateStore.put("key2", action2, event2, state2);

        // Assert
        assertEquals(
                state1.getTaskEvent(),
                actionStateStore.get("key1", action1, event1).getTaskEvent());
        assertEquals(
                state2.getTaskEvent(),
                actionStateStore.get("key2", action2, event2).getTaskEvent());
        assertEquals(2, mockProducer.history().size());
    }

    @Test
    void testActionStateUpdates() {
        // Arrange
        actionStateStore.put(TEST_KEY, testAction, testEvent, testActionState);

        // Modify the action state
        testActionState.addEvent(new InputEvent("additional event"));

        // Act - update the same action state
        actionStateStore.put(TEST_KEY, testAction, testEvent, testActionState);

        // Assert
        ActionState retrieved = actionStateStore.get(TEST_KEY, testAction, testEvent);
        assertEquals(1, retrieved.getOutputEvents().size());
        assertEquals(
                2, mockProducer.history().size()); // Two puts should result in two Kafka records
    }

    class TestAction {}
}
