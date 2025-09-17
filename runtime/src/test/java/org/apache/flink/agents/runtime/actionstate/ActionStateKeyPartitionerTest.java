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

import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matcher.*;
import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link ActionStateKeyPartitioner}. */
public class ActionStateKeyPartitionerTest {

    private static final String TEST_TOPIC = "test-action-state-topic";
    private ActionStateKeyPartitioner partitioner;
    private Cluster cluster;

    @BeforeEach
    void setUp() {
        partitioner = new ActionStateKeyPartitioner();

        // Create a mock cluster with 3 partitions
        List<PartitionInfo> partitions = new ArrayList<>();
        Node[] nodes = {
            new Node(0, "broker0", 9092), new Node(1, "broker1", 9092), new Node(2, "broker2", 9092)
        };

        for (int i = 0; i < 3; i++) {
            partitions.add(new PartitionInfo(TEST_TOPIC, i, nodes[i % nodes.length], nodes, nodes));
        }

        cluster =
                new Cluster(
                        "test-cluster",
                        List.of(nodes),
                        partitions,
                        java.util.Collections.emptySet(),
                        java.util.Collections.emptySet());
    }

    @Test
    void testValidKeyPartitioning() {
        String key1 = "1_1_action1_event1";
        String key2 = "456_1_action2_event2";
        String key3 = "789_1_action3_event3";

        int partition1 =
                partitioner.partition(TEST_TOPIC, key1, key1.getBytes(), null, null, cluster);
        int partition2 =
                partitioner.partition(TEST_TOPIC, key2, key2.getBytes(), null, null, cluster);
        int partition3 =
                partitioner.partition(TEST_TOPIC, key3, key3.getBytes(), null, null, cluster);

        // Partitions should be valid (0, 1, or 2)
        assertTrue(partition1 >= 0 && partition1 < 3);
        assertTrue(partition2 >= 0 && partition2 < 3);
        assertTrue(partition3 >= 0 && partition3 < 3);
    }

    @Test
    void testSameKeyFirstPartConsistentPartitioning() {
        // Keys with the same first part should go to the same partition
        String key1 = "123_1_action1_event1";
        String key2 = "123_2_action2_event2";
        String key3 = "123_3_action3_event3";

        int partition1 =
                partitioner.partition(TEST_TOPIC, key1, key1.getBytes(), null, null, cluster);
        int partition2 =
                partitioner.partition(TEST_TOPIC, key2, key2.getBytes(), null, null, cluster);
        int partition3 =
                partitioner.partition(TEST_TOPIC, key3, key3.getBytes(), null, null, cluster);

        // All should go to the same partition since first part is the same
        assertEquals(partition1, partition2);
        assertEquals(partition1, partition3);
    }

    @Test
    void testNullKeyThrowsException() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> partitioner.partition(TEST_TOPIC, null, null, null, null, cluster));
        assertEquals("Key cannot be null", exception.getMessage());
    }

    @Test
    void testNonStringKeyThrowsException() {
        Integer nonStringKey = 12345;

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                partitioner.partition(
                                        TEST_TOPIC, nonStringKey, null, null, null, cluster));
        assertEquals("Key must be a String", exception.getMessage());
    }

    @Test
    void testInvalidKeyFormatThrowsException() {
        // Test keys with less than 3 parts
        String invalidKey1 = "onlyonepart";
        String invalidKey2 = "only_twoparts";

        IllegalArgumentException exception1 =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                partitioner.partition(
                                        TEST_TOPIC,
                                        invalidKey1,
                                        invalidKey1.getBytes(),
                                        null,
                                        null,
                                        cluster));
        assertEquals("Key format is invalid", exception1.getMessage());

        IllegalArgumentException exception2 =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                partitioner.partition(
                                        TEST_TOPIC,
                                        invalidKey2,
                                        invalidKey2.getBytes(),
                                        null,
                                        null,
                                        cluster));
        assertEquals("Key format is invalid", exception2.getMessage());
    }

    @Test
    void testEmptyFirstKeyPartThrowException() {
        String invalidKey = "_1_action_event";
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                partitioner.partition(
                                        TEST_TOPIC, invalidKey, null, null, null, cluster));
        assertEquals("First part of the key cannot be empty", exception.getMessage());
    }

    @Test
    void testKeyWithMoreThanThreePartsIsValid() {
        // Keys with more than 3 parts should still work (only first part matters for partitioning)
        String key = "123_action1_event1_extra_parts_here";

        int partition = partitioner.partition(TEST_TOPIC, key, key.getBytes(), null, null, cluster);

        assertTrue(partition >= 0 && partition < 3);
    }

    @Test
    void testPartitionDistribution() {
        // Test that different first key parts go to potentially different partitions
        Map<Integer, Integer> partitionCounts = new HashMap<>();

        // Generate keys with different first parts
        for (int i = 0; i < 100; i++) {
            String key = "" + i + "_1_action_event";
            int partition =
                    partitioner.partition(TEST_TOPIC, key, key.getBytes(), null, null, cluster);

            partitionCounts.put(partition, partitionCounts.getOrDefault(partition, 0) + 1);
        }

        // Should use at least 2 different partitions (distribution may not be perfectly even)
        assertTrue(
                partitionCounts.size() >= 2,
                "Keys should be distributed across multiple partitions");

        // Each partition count should be reasonable (not all keys on one partition)
        for (int count : partitionCounts.values()) {
            assertTrue(
                    count <= 80,
                    "No single partition should have too many keys (indicating poor distribution)");
        }
    }

    @Test
    void testSinglePartitionCluster() {
        // Create cluster with only 1 partition
        List<PartitionInfo> singlePartition = new ArrayList<>();
        Node node = new Node(0, "broker0", 9092);
        singlePartition.add(
                new PartitionInfo(TEST_TOPIC, 0, node, new Node[] {node}, new Node[] {node}));

        Cluster singlePartitionCluster =
                new Cluster(
                        "test-cluster",
                        List.of(node),
                        singlePartition,
                        java.util.Collections.emptySet(),
                        java.util.Collections.emptySet());

        String key = "123_1_action1_event1";
        int partition =
                partitioner.partition(
                        TEST_TOPIC, key, key.getBytes(), null, null, singlePartitionCluster);

        assertEquals(0, partition, "With single partition, all keys should go to partition 0");
    }

    @Test
    void testHashConsistency() {
        // Same key should always produce the same partition
        String key = "123_1_action1_event1";

        int partition1 =
                partitioner.partition(TEST_TOPIC, key, key.getBytes(), null, null, cluster);
        int partition2 =
                partitioner.partition(TEST_TOPIC, key, key.getBytes(), null, null, cluster);
        int partition3 =
                partitioner.partition(TEST_TOPIC, key, key.getBytes(), null, null, cluster);

        assertEquals(partition1, partition2);
        assertEquals(partition1, partition3);
    }
}
