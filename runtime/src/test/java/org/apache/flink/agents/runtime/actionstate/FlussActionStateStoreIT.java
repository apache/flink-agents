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
import org.apache.fluss.client.admin.Admin;
import org.apache.fluss.metadata.TableInfo;
import org.apache.fluss.metadata.TablePath;
import org.apache.fluss.server.testutils.FlussClusterExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.apache.flink.agents.api.configuration.AgentConfigOptions.FLUSS_ACTION_STATE_DATABASE;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.FLUSS_ACTION_STATE_TABLE;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.FLUSS_ACTION_STATE_TABLE_BUCKETS;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.FLUSS_BOOTSTRAP_SERVERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

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

        testAction = new TestAction("test-action");
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

        // Delete may take time to be applied from changelog to KV store
        waitUntilDeleted(() -> store.get(TEST_KEY, 1L, testAction, testEvent));
        waitUntilDeleted(() -> store.get(TEST_KEY, 2L, testAction, testEvent));
        assertThat(store.get(TEST_KEY, 3L, testAction, testEvent)).isNotNull();
    }

    @Test
    void testPruneMultipleAgentKeys() throws Exception {
        String keyA = "agent-key-a";
        String keyB = "agent-key-b";

        store.put(keyA, 1L, testAction, testEvent, new ActionState(testEvent));
        store.put(keyA, 2L, testAction, testEvent, new ActionState(testEvent));
        store.put(keyB, 1L, testAction, testEvent, new ActionState(testEvent));
        store.put(keyB, 2L, testAction, testEvent, new ActionState(testEvent));

        // Prune only keyA up to seqNum 2
        store.pruneState(keyA, 2L);

        // keyA entries pruned (may take time to propagate)
        waitUntilDeleted(() -> store.get(keyA, 1L, testAction, testEvent));
        waitUntilDeleted(() -> store.get(keyA, 2L, testAction, testEvent));
        // keyB entries unaffected
        assertThat(store.get(keyB, 1L, testAction, testEvent)).isNotNull();
        assertThat(store.get(keyB, 2L, testAction, testEvent)).isNotNull();
    }

    // ==================== Fluss-Specific Behavior ====================

    @Test
    void testRecoveryMarkerReturnsNull() {
        Object marker = store.getRecoveryMarker();
        assertThat(marker).isNull();
    }

    @Test
    void testRebuildStateNoOp() throws Exception {
        store.put(TEST_KEY, 1L, testAction, testEvent, new ActionState(testEvent));

        // rebuildState should complete without error
        store.rebuildState(Collections.emptyList());

        // Data should still be accessible
        assertThat(store.get(TEST_KEY, 1L, testAction, testEvent)).isNotNull();
    }

    // ==================== Durable Execution Patterns ====================

    @Test
    void testCompletionOnlyFlow() throws Exception {
        ActionState state = new ActionState(testEvent);
        CallResult successResult =
                new CallResult(
                        "myFunction",
                        "argsDigest123",
                        "result-payload".getBytes(StandardCharsets.UTF_8));
        state.addCallResult(successResult);

        store.put(TEST_KEY, 1L, testAction, testEvent, state);
        ActionState retrieved = store.get(TEST_KEY, 1L, testAction, testEvent);

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getCallResults()).hasSize(1);
        CallResult retrievedResult = retrieved.getCallResult(0);
        assertThat(retrievedResult.getFunctionId()).isEqualTo("myFunction");
        assertThat(retrievedResult.getArgsDigest()).isEqualTo("argsDigest123");
        assertThat(retrievedResult.isSuccess()).isTrue();
        assertThat(retrievedResult.getResultPayload())
                .isEqualTo("result-payload".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void testReconcilePendingPersistence() throws Exception {
        // Simulate Reconcile pattern: write PENDING state, close store, reopen, verify PENDING
        // survives
        ActionState state = new ActionState(testEvent);
        CallResult pending = CallResult.pending("sideEffectFn", "digest456");
        state.addCallResult(pending);

        store.put(TEST_KEY, 1L, testAction, testEvent, state);
        store.close();

        // Create a new store instance (simulating recovery)
        FlussActionStateStore recoveredStore =
                new FlussActionStateStore(createAgentConfiguration());
        try {
            ActionState recovered = recoveredStore.get(TEST_KEY, 1L, testAction, testEvent);

            assertThat(recovered).isNotNull();
            assertThat(recovered.getCallResults()).hasSize(1);
            CallResult recoveredResult = recovered.getCallResult(0);
            assertThat(recoveredResult.isPending()).isTrue();
            assertThat(recoveredResult.getFunctionId()).isEqualTo("sideEffectFn");
            assertThat(recoveredResult.getArgsDigest()).isEqualTo("digest456");
        } finally {
            recoveredStore.close();
        }
    }

    @Test
    void testPruneWorksAfterRecovery() throws Exception {
        // This tests the critical bug fix: after close+reopen, the in-memory
        // agentKeyToStateKeys mapping is empty. The get() method must rebuild
        // this mapping so that pruneState() can still delete old records.
        store.put(TEST_KEY, 1L, testAction, testEvent, new ActionState(testEvent));
        store.put(TEST_KEY, 2L, testAction, testEvent, new ActionState(testEvent));
        store.put(TEST_KEY, 3L, testAction, testEvent, new ActionState(testEvent));
        store.close();

        // Simulate recovery: new store instance, mapping is empty
        FlussActionStateStore recoveredStore =
                new FlussActionStateStore(createAgentConfiguration());
        try {
            // get() calls rebuild the mapping
            assertThat(recoveredStore.get(TEST_KEY, 1L, testAction, testEvent)).isNotNull();
            assertThat(recoveredStore.get(TEST_KEY, 2L, testAction, testEvent)).isNotNull();
            assertThat(recoveredStore.get(TEST_KEY, 3L, testAction, testEvent)).isNotNull();

            // pruneState should now work because get() rebuilt the mapping
            recoveredStore.pruneState(TEST_KEY, 2L);

            waitUntilDeleted(() -> recoveredStore.get(TEST_KEY, 1L, testAction, testEvent));
            waitUntilDeleted(() -> recoveredStore.get(TEST_KEY, 2L, testAction, testEvent));
            assertThat(recoveredStore.get(TEST_KEY, 3L, testAction, testEvent)).isNotNull();
        } finally {
            recoveredStore.close();
        }
    }

    @Test
    void testMultipleCallResults() throws Exception {
        ActionState state = new ActionState(testEvent);
        state.addCallResult(
                new CallResult("fn1", "d1", "ok".getBytes(StandardCharsets.UTF_8))); // SUCCEEDED
        state.addCallResult(CallResult.pending("fn2", "d2")); // PENDING
        state.addCallResult(
                new CallResult(
                        "fn3", "d3", null, "error".getBytes(StandardCharsets.UTF_8))); // FAILED

        store.put(TEST_KEY, 1L, testAction, testEvent, state);
        ActionState retrieved = store.get(TEST_KEY, 1L, testAction, testEvent);

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getCallResults()).hasSize(3);
        assertThat(retrieved.getCallResult(0).isSuccess()).isTrue();
        assertThat(retrieved.getCallResult(1).isPending()).isTrue();
        assertThat(retrieved.getCallResult(2).isFailure()).isTrue();
    }

    @Test
    void testCompletedAction() throws Exception {
        ActionState state = new ActionState(testEvent);
        state.addCallResult(new CallResult("fn1", "d1", "result".getBytes(StandardCharsets.UTF_8)));
        state.markCompleted();

        store.put(TEST_KEY, 1L, testAction, testEvent, state);
        ActionState retrieved = store.get(TEST_KEY, 1L, testAction, testEvent);

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.isCompleted()).isTrue();
        assertThat(retrieved.getCallResults()).isEmpty();
    }

    // ==================== Infrastructure ====================

    @Test
    void testIdempotentTableCreation() throws Exception {
        // Creating a second store with the same config should succeed
        // (table already exists, createTable with ignoreIfExists=true)
        FlussActionStateStore secondStore = new FlussActionStateStore(createAgentConfiguration());
        try {
            secondStore.put(TEST_KEY, 1L, testAction, testEvent, new ActionState(testEvent));
            assertThat(secondStore.get(TEST_KEY, 1L, testAction, testEvent)).isNotNull();
        } finally {
            secondStore.close();
        }
    }

    @Test
    void testCloseIdempotent() throws Exception {
        store.close();
        assertThatNoException().isThrownBy(() -> store.close());
        // Set to null to prevent double-close in tearDown
        store = null;
    }

    // ==================== Serialization ====================

    @Test
    void testFullSerializationRoundTrip() throws Exception {
        InputEvent taskEvent = new InputEvent("full-test-data");
        ActionState state = new ActionState(taskEvent);
        state.addEvent(new InputEvent("output-event-1"));
        state.addEvent(new InputEvent("output-event-2"));
        state.addCallResult(
                new CallResult("fn1", "digest1", "payload1".getBytes(StandardCharsets.UTF_8)));
        state.addCallResult(CallResult.pending("fn2", "digest2"));

        store.put(TEST_KEY, 1L, testAction, testEvent, state);
        ActionState retrieved = store.get(TEST_KEY, 1L, testAction, testEvent);

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getTaskEvent()).isEqualTo(taskEvent);
        assertThat(retrieved.getOutputEvents()).hasSize(2);
        assertThat(retrieved.getCallResults()).hasSize(2);
        assertThat(retrieved.getCallResult(0).isSuccess()).isTrue();
        assertThat(retrieved.getCallResult(1).isPending()).isTrue();
        assertThat(retrieved.isCompleted()).isFalse();
    }

    // ==================== Helpers ====================

    private AgentConfiguration createAgentConfiguration() {
        AgentConfiguration config = new AgentConfiguration();
        config.set(FLUSS_BOOTSTRAP_SERVERS, FLUSS_CLUSTER.getBootstrapServers());
        config.set(FLUSS_ACTION_STATE_DATABASE, TEST_DATABASE);
        config.set(FLUSS_ACTION_STATE_TABLE, TEST_TABLE);
        config.set(FLUSS_ACTION_STATE_TABLE_BUCKETS, 1);
        return config;
    }

    private void waitForTableReady() throws Exception {
        TablePath tablePath = TablePath.of(TEST_DATABASE, TEST_TABLE);
        try (Admin admin =
                org.apache.fluss.client.ConnectionFactory.createConnection(
                                FLUSS_CLUSTER.getClientConfig())
                        .getAdmin()) {
            TableInfo tableInfo = admin.getTableInfo(tablePath).get();
            FLUSS_CLUSTER.waitUntilTableReady(tableInfo.getTableId());
        }
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

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /**
     * Polls until the supplier returns null, with a timeout. Fluss KV deletes may take time to
     * propagate from the changelog to the RocksDB KV store.
     */
    private static void waitUntilDeleted(ThrowingSupplier<Object> supplier) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (supplier.get() == null) {
                return;
            }
            Thread.sleep(200);
        }
        assertThat(supplier.get()).isNull();
    }
}
