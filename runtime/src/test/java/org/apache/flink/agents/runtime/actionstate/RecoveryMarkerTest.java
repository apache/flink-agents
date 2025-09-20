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

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for recovery marker functionality in {@link KafkaActionStateStore}. */
public class RecoveryMarkerTest {

    private KafkaActionStateStore actionStateStore;

    @BeforeEach
    void setUp() {
        actionStateStore = new KafkaActionStateStore();
    }

    @Test
    void testInitialRecoveryMarker() {
        Object marker = actionStateStore.getRecoveryMarker();
        assertNotNull(marker);
        assertTrue(marker instanceof Map);
        assertTrue(((Map<?, ?>) marker).isEmpty());
    }

    @Test
    void testSetRecoveryMarker() {
        // Arrange
        Map<TopicPartition, Long> testMarker = new HashMap<>();
        TopicPartition partition1 = new TopicPartition("topic1", 0);
        TopicPartition partition2 = new TopicPartition("topic1", 1);
        TopicPartition partition3 = new TopicPartition("topic2", 0);

        testMarker.put(partition1, 100L);
        testMarker.put(partition2, 200L);
        testMarker.put(partition3, 300L);

        // Act
        actionStateStore.setRecoveryMarker(testMarker);

        // Assert
        Object retrievedMarker = actionStateStore.getRecoveryMarker();
        assertNotNull(retrievedMarker);
        assertTrue(retrievedMarker instanceof Map);

        @SuppressWarnings("unchecked")
        Map<TopicPartition, Long> retrievedMap = (Map<TopicPartition, Long>) retrievedMarker;
        assertEquals(3, retrievedMap.size());
        assertEquals(100L, retrievedMap.get(partition1));
        assertEquals(200L, retrievedMap.get(partition2));
        assertEquals(300L, retrievedMap.get(partition3));
    }

    @Test
    void testSetRecoveryMarkerWithInvalidType() {
        // Act & Assert - should not throw exception, just ignore invalid type
        assertDoesNotThrow(() -> actionStateStore.setRecoveryMarker("invalid-type"));

        Object marker = actionStateStore.getRecoveryMarker();
        assertTrue(((Map<?, ?>) marker).isEmpty());
    }

    @Test
    void testSetRecoveryMarkerOverwritesPrevious() {
        // Arrange
        Map<TopicPartition, Long> firstMarker = new HashMap<>();
        firstMarker.put(new TopicPartition("topic1", 0), 100L);

        Map<TopicPartition, Long> secondMarker = new HashMap<>();
        secondMarker.put(new TopicPartition("topic2", 0), 200L);

        // Act
        actionStateStore.setRecoveryMarker(firstMarker);
        actionStateStore.setRecoveryMarker(secondMarker);

        // Assert
        @SuppressWarnings("unchecked")
        Map<TopicPartition, Long> retrievedMap =
                (Map<TopicPartition, Long>) actionStateStore.getRecoveryMarker();
        assertEquals(1, retrievedMap.size());
        assertEquals(200L, retrievedMap.get(new TopicPartition("topic2", 0)));
        assertNull(retrievedMap.get(new TopicPartition("topic1", 0)));
    }

    @Test
    void testRecoveryMarkerImmutability() {
        // Arrange
        Map<TopicPartition, Long> testMarker = new HashMap<>();
        TopicPartition partition = new TopicPartition("topic1", 0);
        testMarker.put(partition, 100L);

        actionStateStore.setRecoveryMarker(testMarker);

        // Act - modify the original map
        testMarker.put(new TopicPartition("topic2", 0), 200L);

        // Assert - internal state should not be affected
        @SuppressWarnings("unchecked")
        Map<TopicPartition, Long> retrievedMap =
                (Map<TopicPartition, Long>) actionStateStore.getRecoveryMarker();
        assertEquals(1, retrievedMap.size());
        assertEquals(100L, retrievedMap.get(partition));

        // Act - modify the retrieved map
        retrievedMap.put(new TopicPartition("topic3", 0), 300L);

        // Assert - internal state should not be affected
        @SuppressWarnings("unchecked")
        Map<TopicPartition, Long> retrievedMap2 =
                (Map<TopicPartition, Long>) actionStateStore.getRecoveryMarker();
        assertEquals(1, retrievedMap2.size());
        assertEquals(100L, retrievedMap2.get(partition));
    }

    @Test
    void testRecoveryMarkerAfterCleanup() {
        // Arrange
        Map<TopicPartition, Long> testMarker = new HashMap<>();
        testMarker.put(new TopicPartition("topic1", 0), 100L);
        actionStateStore.setRecoveryMarker(testMarker);

        // Act
        actionStateStore.cleanUpState();

        // Assert
        Object marker = actionStateStore.getRecoveryMarker();
        assertTrue(((Map<?, ?>) marker).isEmpty());
    }
}
