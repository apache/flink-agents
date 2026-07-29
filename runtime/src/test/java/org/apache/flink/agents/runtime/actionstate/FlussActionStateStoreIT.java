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
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.fluss.client.admin.Admin;
import org.apache.fluss.metadata.TableInfo;
import org.apache.fluss.metadata.TablePath;
import org.apache.fluss.server.testutils.FlussClusterExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.apache.flink.agents.api.configuration.AgentConfigOptions.FLUSS_ACTION_STATE_DATABASE;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.FLUSS_ACTION_STATE_TABLE;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.FLUSS_ACTION_STATE_TABLE_BUCKETS;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.FLUSS_BOOTSTRAP_SERVERS;
import static org.assertj.core.api.Assertions.assertThat;

/** Integration tests for {@link FlussActionStateStore} against an embedded Fluss cluster. */
public class FlussActionStateStoreIT {

    private static final String TEST_DATABASE = "test_flink_agents";
    private static final String TEST_TABLE = "action_state_it";
    private static final String TEST_KEY = "test-key";

    @RegisterExtension
    static final FlussClusterExtension FLUSS_CLUSTER =
            FlussClusterExtension.builder().setNumOfTabletServers(1).build();

    private FlussActionStateStore store;
    private Action testAction;
    private Event testEvent;

    @BeforeEach
    void setUp() throws Exception {
        AgentConfiguration config = createAgentConfiguration();
        store = new FlussActionStateStore(config);

        // Wait for table to be ready in the cluster
        waitForTableReady();

        testAction = new NoOpAction("test-action");
        testEvent = new InputEvent("test-data");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) {
            store.close();
        }
    }

    // ==================== Basic CRUD ====================

    @Test
    void testPutAndGet() throws Exception {
        ActionState state = new ActionState(testEvent);

        store.put(TEST_KEY, 1L, testAction, testEvent, state);
        ActionState retrieved = store.get(TEST_KEY, 1L, testAction, testEvent);

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getTaskEvent()).isEqualTo(testEvent);
        assertThat(retrieved.isCompleted()).isFalse();
    }

    @Test
    void testGetNonExistent() throws Exception {
        ActionState result = store.get(TEST_KEY, 999L, testAction, testEvent);

        assertThat(result).isNull();
    }

    @Test
    void testMultipleSeqNums() throws Exception {
        InputEvent event1 = new InputEvent("data-1");
        InputEvent event2 = new InputEvent("data-2");
        InputEvent event3 = new InputEvent("data-3");
        ActionState state1 = new ActionState(event1);
        ActionState state2 = new ActionState(event2);
        ActionState state3 = new ActionState(event3);

        store.put(TEST_KEY, 1L, testAction, testEvent, state1);
        store.put(TEST_KEY, 2L, testAction, testEvent, state2);
        store.put(TEST_KEY, 3L, testAction, testEvent, state3);

        assertThat(store.get(TEST_KEY, 1L, testAction, testEvent).getTaskEvent()).isEqualTo(event1);
        assertThat(store.get(TEST_KEY, 2L, testAction, testEvent).getTaskEvent()).isEqualTo(event2);
        assertThat(store.get(TEST_KEY, 3L, testAction, testEvent).getTaskEvent()).isEqualTo(event3);
    }

    @Test
    void testUpsertOverwrite() throws Exception {
        ActionState original = new ActionState(new InputEvent("original"));
        store.put(TEST_KEY, 1L, testAction, testEvent, original);

        InputEvent updatedEvent = new InputEvent("updated");
        ActionState updated = new ActionState(updatedEvent);
        store.put(TEST_KEY, 1L, testAction, testEvent, updated);

        ActionState retrieved = store.get(TEST_KEY, 1L, testAction, testEvent);
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getTaskEvent()).isEqualTo(updatedEvent);
    }

    // ==================== Pruning ====================

    @Test
    void testPruneSingleKey() throws Exception {
        store.put(TEST_KEY, 1L, testAction, testEvent, new ActionState(testEvent));
        store.put(TEST_KEY, 2L, testAction, testEvent, new ActionState(testEvent));
        store.put(TEST_KEY, 3L, testAction, testEvent, new ActionState(testEvent));

        store.pruneState(TEST_KEY, 2L);

        // pruneState is synchronous (in-memory eviction)
        // Check surviving entry first: get() with a missing key triggers divergence cleanup
        // that removes entries with higher seqNums (same as Kafka backend behavior).
        assertThat(store.get(TEST_KEY, 3L, testAction, testEvent)).isNotNull();
        assertThat(store.get(TEST_KEY, 1L, testAction, testEvent)).isNull();
        assertThat(store.get(TEST_KEY, 2L, testAction, testEvent)).isNull();
    }

    // ==================== Recovery ====================

    @Test
    @SuppressWarnings("unchecked")
    void testRecoveryMarkerReturnsBucketOffsets() {
        Object marker = store.getRecoveryMarker();
        assertThat(marker).isNotNull();
        assertThat(marker).isInstanceOf(Map.class);
        Map<Integer, Long> bucketOffsets = (Map<Integer, Long>) marker;
        // With 1 bucket configured, we should have exactly 1 entry
        assertThat(bucketOffsets).hasSize(1);
        assertThat(bucketOffsets).containsKey(0);
        assertThat(bucketOffsets.get(0)).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void testRebuildStateWithEmptyMarkersSkipsRebuild() throws Exception {
        store.put(TEST_KEY, 1L, testAction, testEvent, new ActionState(testEvent));

        // rebuildState with empty markers should skip rebuild (aligned with Kafka backend)
        store.rebuildState(Collections.emptyList());

        // The in-memory cache is not cleared when rebuild is skipped,
        // so the state should still be accessible
        assertThat(store.get(TEST_KEY, 1L, testAction, testEvent)).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testRebuildStateWithRecoveryMarkers() throws Exception {
        store.put(TEST_KEY, 1L, testAction, testEvent, new ActionState(testEvent));

        // Capture recovery marker after writing data (simulates checkpoint boundary)
        Object marker = store.getRecoveryMarker();

        // Write more data after the marker (simulates writes between checkpoint and crash)
        store.put(TEST_KEY, 2L, testAction, testEvent, new ActionState(testEvent));

        // Close to ensure all writes are fully committed before recovery
        store.close();

        // Simulate recovery: new store instance
        FlussActionStateStore recoveredStore =
                new FlussActionStateStore(createAgentConfiguration());
        try {
            // Rebuild using the marker; should replay from marker offset to current end
            recoveredStore.rebuildState(List.of(marker));

            // Check surviving entry first: get() with a missing key triggers divergence cleanup
            // that removes entries with higher seqNums (same as Kafka backend behavior).
            // Data written after the marker should be recovered
            assertThat(recoveredStore.get(TEST_KEY, 2L, testAction, testEvent)).isNotNull();
            // Data written before the marker should NOT be in the rebuilt cache
            assertThat(recoveredStore.get(TEST_KEY, 1L, testAction, testEvent)).isNull();
        } finally {
            recoveredStore.close();
            // Prevent double-close in tearDown
            store = null;
        }
    }

    @Test
    void testPruneWorksAfterRecovery() throws Exception {
        // Capture recovery marker BEFORE writing data.
        Object marker = store.getRecoveryMarker();

        store.put(TEST_KEY, 1L, testAction, testEvent, new ActionState(testEvent));
        store.put(TEST_KEY, 2L, testAction, testEvent, new ActionState(testEvent));
        store.put(TEST_KEY, 3L, testAction, testEvent, new ActionState(testEvent));
        store.close();

        // Simulate recovery: new store instance
        FlussActionStateStore recoveredStore =
                new FlussActionStateStore(createAgentConfiguration());
        try {
            // Rebuild state from the log using recovery markers
            recoveredStore.rebuildState(List.of(marker));

            assertThat(recoveredStore.get(TEST_KEY, 1L, testAction, testEvent)).isNotNull();
            assertThat(recoveredStore.get(TEST_KEY, 2L, testAction, testEvent)).isNotNull();
            assertThat(recoveredStore.get(TEST_KEY, 3L, testAction, testEvent)).isNotNull();

            recoveredStore.pruneState(TEST_KEY, 2L);

            // Check surviving entry first (get() divergence cleanup side-effect)
            assertThat(recoveredStore.get(TEST_KEY, 3L, testAction, testEvent)).isNotNull();
            assertThat(recoveredStore.get(TEST_KEY, 1L, testAction, testEvent)).isNull();
            assertThat(recoveredStore.get(TEST_KEY, 2L, testAction, testEvent)).isNull();
        } finally {
            recoveredStore.close();
        }
    }

    // ==================== Multi-bucket ====================

    @Test
    @SuppressWarnings("unchecked")
    void testMultiBucketRecovery() throws Exception {
        // Use a separate database/table with 4 buckets to test multi-bucket scenario
        String multiDb = "test_flink_agents_multi";
        String multiTable = "action_state_multi";
        AgentConfiguration multiConfig = createAgentConfiguration(multiDb, multiTable, 4);
        FlussActionStateStore multiStore = new FlussActionStateStore(multiConfig);
        try {
            waitForTableReady(multiDb, multiTable);

            // Write states with different keys (likely distributed across buckets)
            Action action1 = new NoOpAction("multi-action");
            for (int i = 0; i < 10; i++) {
                String key = "multi-key-" + i;
                multiStore.put(key, 1L, action1, testEvent, new ActionState(testEvent));
            }

            // Verify all states are retrievable
            for (int i = 0; i < 10; i++) {
                String key = "multi-key-" + i;
                assertThat(multiStore.get(key, 1L, action1, testEvent)).isNotNull();
            }

            // Recovery marker should contain all 4 buckets
            Object marker = multiStore.getRecoveryMarker();
            assertThat(marker).isInstanceOf(Map.class);
            Map<Integer, Long> bucketOffsets = (Map<Integer, Long>) marker;
            assertThat(bucketOffsets).hasSize(4);

            // Write more data after marker
            for (int i = 10; i < 15; i++) {
                String key = "multi-key-" + i;
                multiStore.put(key, 1L, action1, testEvent, new ActionState(testEvent));
            }
            multiStore.close();

            // Recover into a new store instance
            FlussActionStateStore recoveredStore = new FlussActionStateStore(multiConfig);
            try {
                recoveredStore.rebuildState(List.of(marker));

                // Data written after marker should be recovered
                for (int i = 10; i < 15; i++) {
                    String key = "multi-key-" + i;
                    assertThat(recoveredStore.get(key, 1L, action1, testEvent)).isNotNull();
                }
            } finally {
                recoveredStore.close();
            }
        } finally {
            multiStore.close();
            // Prevent double-close in tearDown
            store = null;
        }
    }

    // ==================== Helpers ====================

    private AgentConfiguration createAgentConfiguration() {
        return createAgentConfiguration(TEST_DATABASE, TEST_TABLE, 1);
    }

    private AgentConfiguration createAgentConfiguration(
            String database, String table, int buckets) {
        AgentConfiguration config = new AgentConfiguration();
        config.set(FLUSS_BOOTSTRAP_SERVERS, FLUSS_CLUSTER.getBootstrapServers());
        config.set(FLUSS_ACTION_STATE_DATABASE, database);
        config.set(FLUSS_ACTION_STATE_TABLE, table);
        config.set(FLUSS_ACTION_STATE_TABLE_BUCKETS, buckets);
        return config;
    }

    private void waitForTableReady() throws Exception {
        waitForTableReady(TEST_DATABASE, TEST_TABLE);
    }

    private void waitForTableReady(String database, String table) throws Exception {
        TablePath tablePath = TablePath.of(database, table);
        try (org.apache.fluss.client.Connection conn =
                        org.apache.fluss.client.ConnectionFactory.createConnection(
                                FLUSS_CLUSTER.getClientConfig());
                Admin admin = conn.getAdmin()) {
            TableInfo tableInfo = admin.getTableInfo(tablePath).get();
            FLUSS_CLUSTER.waitUntilTableReady(tableInfo.getTableId());
        }
    }
}
