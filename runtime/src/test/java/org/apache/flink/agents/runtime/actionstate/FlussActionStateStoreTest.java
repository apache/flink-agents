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
import org.apache.flink.agents.plan.actions.Action;
import org.apache.fluss.client.Connection;
import org.apache.fluss.client.table.Table;
import org.apache.fluss.client.table.writer.AppendWriter;
import org.apache.fluss.row.InternalRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link FlussActionStateStore} store-level behavior. */
public class FlussActionStateStoreTest {

    private static final String TEST_KEY = "test-key";

    private AppendWriter mockWriter;
    private FlussActionStateStore store;
    private Action testAction;
    private Event testEvent;
    private ActionState testActionState;
    private Map<String, ActionState> actionStates;

    @BeforeEach
    void setUp() throws Exception {
        mockWriter = mock(AppendWriter.class);
        when(mockWriter.append(any(InternalRow.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        actionStates = new HashMap<>();
        store =
                new FlussActionStateStore(
                        actionStates, mock(Connection.class), mock(Table.class), mockWriter);

        testAction = new NoOpAction("test-action");
        testEvent = new InputEvent("test data");
        testActionState = new ActionState(testEvent);
    }

    @Test
    void testPutActionState() throws Exception {
        store.put(TEST_KEY, 1L, testAction, testEvent, testActionState);

        verify(mockWriter).append(any(InternalRow.class));

        String stateKey = ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent);
        assertThat(actionStates).containsKey(stateKey);
        assertThat(actionStates.get(stateKey)).isEqualTo(testActionState);
    }

    @Test
    void testPutActionStateWriterFailure() throws Exception {
        when(mockWriter.append(any(InternalRow.class)))
                .thenReturn(CompletableFuture.failedFuture(new IOException("connection lost")));

        FlussActionStateStore failStore =
                new FlussActionStateStore(
                        actionStates, mock(Connection.class), mock(Table.class), mockWriter);

        assertThatThrownBy(
                        () -> failStore.put(TEST_KEY, 1L, testAction, testEvent, testActionState))
                .isInstanceOf(Exception.class);

        // Cache should NOT be updated on write failure
        String stateKey = ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent);
        assertThat(actionStates).doesNotContainKey(stateKey);
    }

    @Test
    void testGetTriggersDivergenceCleanup() throws Exception {
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent), testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 2L, testAction, testEvent), testActionState);
        // diverge: same key+seqNum, different action
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 2L, new NoOpAction("test-2"), testEvent),
                testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 3L, testAction, testEvent), testActionState);

        store.get(TEST_KEY, 2L, new NoOpAction("test-1"), testEvent);

        // Divergence detected at seqNum 2 → removeIf clears seqNum > 2
        assertThat(store.get(TEST_KEY, 1L, testAction, testEvent)).isNotNull();
        assertThat(store.get(TEST_KEY, 2L, testAction, testEvent)).isNotNull();
        assertThat(store.get(TEST_KEY, 3L, testAction, testEvent)).isNull();
    }

    // ==================== rebuildState tests ====================

    @Test
    void testRebuildStateSkipsOnEmptyMarkers() throws Exception {
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent), testActionState);

        store.rebuildState(Collections.emptyList());

        // Empty markers → rebuild is skipped, cache is NOT cleared
        assertThat(actionStates).isNotEmpty();
    }

    @Test
    void testRebuildStateSkipsOnNonMapMarker() throws Exception {
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent), testActionState);

        // A non-Map marker is ignored, resulting in empty bucketStartOffsets.
        // Note: rebuildState clears the cache before checking offsets,
        // so the cache will be empty even though no actual rebuild occurs.
        store.rebuildState(List.of("invalid-marker"));

        assertThat(actionStates).isEmpty();
    }

    @Test
    void testRebuildStateSkipsOnEmptyBucketOffsets() throws Exception {
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent), testActionState);

        // Empty map marker → no valid bucket offsets.
        // Same as above: cache is cleared before the early-return check.
        store.rebuildState(List.of(Map.of()));

        assertThat(actionStates).isEmpty();
    }

    /** Contract: both the table and the connection are closed when neither close fails. */
    @Test
    void testCloseClosesResources() throws Exception {
        Table mockTable = mock(Table.class);
        Connection mockConnection = mock(Connection.class);

        FlussActionStateStore closeableStore =
                new FlussActionStateStore(actionStates, mockConnection, mockTable, mockWriter);

        closeableStore.close();

        verify(mockTable).close();
        verify(mockConnection).close();
    }

    /**
     * Contract: a recorded table-close failure survives a clean connection close — the table's
     * exception still reaches the caller, and the connection is closed.
     */
    @Test
    void testCloseClosesConnectionWhenTableCloseFails() throws Exception {
        Table failingTable = mock(Table.class);
        Connection mockConnection = mock(Connection.class);
        RuntimeException tableFailure = new RuntimeException("table close failed");
        doThrow(tableFailure).when(failingTable).close();

        FlussActionStateStore closeableStore =
                new FlussActionStateStore(actionStates, mockConnection, failingTable, mockWriter);

        assertThat(catchThrowable(closeableStore::close)).isSameAs(tableFailure);

        verify(mockConnection).close();
    }

    /**
     * Contract: when both closes fail, the table's exception is the one thrown and the connection's
     * is attached to it as a suppressed exception, so neither failure is lost.
     */
    @Test
    void testCloseKeepsTableFailureWhenBothCloseFail() throws Exception {
        Table failingTable = mock(Table.class);
        Connection failingConnection = mock(Connection.class);
        IOException tableFailure = new IOException("table close failed");
        IOException connectionFailure = new IOException("connection close failed");
        doThrow(tableFailure).when(failingTable).close();
        doThrow(connectionFailure).when(failingConnection).close();

        FlussActionStateStore closeableStore =
                new FlussActionStateStore(
                        actionStates, failingConnection, failingTable, mockWriter);

        Throwable thrown = catchThrowable(closeableStore::close);

        assertThat(thrown).isSameAs(tableFailure);
        assertThat(thrown.getSuppressed()).containsExactly(connectionFailure);
    }

    /**
     * Contract: when only the connection close fails, its exception reaches the caller unchanged,
     * with nothing attached as suppressed.
     */
    @Test
    void testCloseThrowsConnectionFailureWhenOnlyConnectionCloseFails() throws Exception {
        Table mockTable = mock(Table.class);
        Connection failingConnection = mock(Connection.class);
        RuntimeException connectionFailure = new RuntimeException("connection close failed");
        doThrow(connectionFailure).when(failingConnection).close();

        FlussActionStateStore closeableStore =
                new FlussActionStateStore(actionStates, failingConnection, mockTable, mockWriter);

        Throwable thrown = catchThrowable(closeableStore::close);

        assertThat(thrown).isSameAs(connectionFailure);
        assertThat(thrown.getSuppressed()).isEmpty();
    }

    /**
     * Contract: when closing the table throws a non-{@code Exception} {@code Throwable} and the
     * connection close then fails too, the throwable stays the failure the caller sees, the
     * connection is still closed, and the connection's failure is attached as suppressed.
     */
    @Test
    void testCloseKeepsTableErrorWhenConnectionCloseAlsoFails() throws Exception {
        Table failingTable = mock(Table.class);
        Connection failingConnection = mock(Connection.class);
        NoClassDefFoundError tableFailure = new NoClassDefFoundError("simulated teardown failure");
        RuntimeException connectionFailure = new RuntimeException("connection close failed");
        doThrow(tableFailure).when(failingTable).close();
        doThrow(connectionFailure).when(failingConnection).close();

        FlussActionStateStore closeableStore =
                new FlussActionStateStore(
                        actionStates, failingConnection, failingTable, mockWriter);

        Throwable thrown = catchThrowable(closeableStore::close);

        assertThat(thrown).isSameAs(tableFailure);
        assertThat(thrown.getSuppressed()).containsExactly(connectionFailure);
        verify(failingConnection).close();
    }

    /**
     * Contract: when the table close fails and the connection close then throws a non-{@code
     * Exception} {@code Throwable}, the table's exception stays the failure the caller sees and the
     * connection's throwable is attached as suppressed.
     */
    @Test
    void testCloseKeepsTableFailureWhenConnectionCloseThrowsError() throws Exception {
        Table failingTable = mock(Table.class);
        Connection failingConnection = mock(Connection.class);
        IOException tableFailure = new IOException("table close failed");
        NoClassDefFoundError connectionFailure =
                new NoClassDefFoundError("simulated teardown failure");
        doThrow(tableFailure).when(failingTable).close();
        doThrow(connectionFailure).when(failingConnection).close();

        FlussActionStateStore closeableStore =
                new FlussActionStateStore(
                        actionStates, failingConnection, failingTable, mockWriter);

        Throwable thrown = catchThrowable(closeableStore::close);

        assertThat(thrown).isSameAs(tableFailure);
        assertThat(thrown.getSuppressed()).containsExactly(connectionFailure);
    }
}
