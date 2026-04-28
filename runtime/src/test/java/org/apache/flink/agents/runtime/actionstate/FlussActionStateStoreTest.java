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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

        testAction = new TestAction("test-action");
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
    void testGetNonExistentActionState() throws Exception {
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent), testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 2L, testAction, testEvent), testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 3L, testAction, testEvent), testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 4L, testAction, testEvent), testActionState);

        // Request a key with a different action triggers divergence cleanup
        store.get(TEST_KEY, 2L, new TestAction("test-1"), testEvent);

        assertThat(store.get(TEST_KEY, 1L, testAction, testEvent)).isNotNull();
        assertThat(store.get(TEST_KEY, 2L, testAction, testEvent)).isNotNull();
        assertThat(store.get(TEST_KEY, 3L, testAction, testEvent)).isNull();
        assertThat(store.get(TEST_KEY, 4L, testAction, testEvent)).isNull();
    }

    @Test
    void testGetActionStateWithDiverge() throws Exception {
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent), testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 2L, testAction, testEvent), testActionState);
        // diverge: same key+seqNum, different action
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 2L, new TestAction("test-2"), testEvent),
                testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 3L, testAction, testEvent), testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 4L, testAction, testEvent), testActionState);

        store.get(TEST_KEY, 2L, testAction, testEvent);

        assertThat(store.get(TEST_KEY, 1L, testAction, testEvent)).isNotNull();
        assertThat(store.get(TEST_KEY, 2L, testAction, testEvent)).isNotNull();
        assertThat(store.get(TEST_KEY, 3L, testAction, testEvent)).isNull();
        assertThat(store.get(TEST_KEY, 4L, testAction, testEvent)).isNull();
    }

    @Test
    void testPruneState() throws Exception {
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent), testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 2L, testAction, testEvent), testActionState);
        actionStates.put(
                ActionStateUtil.generateKey(TEST_KEY, 3L, testAction, testEvent), testActionState);

        assertThat(store.get(TEST_KEY, 1L, testAction, testEvent)).isNotNull();
        assertThat(store.get(TEST_KEY, 2L, testAction, testEvent)).isNotNull();
        assertThat(store.get(TEST_KEY, 3L, testAction, testEvent)).isNotNull();

        // Prune states up to sequence number 2
        store.pruneState(TEST_KEY, 2L);

        assertThat(
                        actionStates.get(
                                ActionStateUtil.generateKey(TEST_KEY, 1L, testAction, testEvent)))
                .isNull();
        assertThat(
                        actionStates.get(
                                ActionStateUtil.generateKey(TEST_KEY, 2L, testAction, testEvent)))
                .isNull();
        assertThat(store.get(TEST_KEY, 3L, testAction, testEvent)).isNotNull();
    }

    @Test
    void testActionStateUpdates() throws Exception {
        store.put(TEST_KEY, 1L, testAction, testEvent, testActionState);

        // Update the same key with a new state
        InputEvent updatedEvent = new InputEvent("updated data");
        ActionState updatedState = new ActionState(updatedEvent);
        store.put(TEST_KEY, 1L, testAction, testEvent, updatedState);

        ActionState retrieved = store.get(TEST_KEY, 1L, testAction, testEvent);
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getTaskEvent()).isEqualTo(updatedEvent);
    }

    @Test
    void testRebuildStateWithEmptyMarkers() throws Exception {
        store.put(TEST_KEY, 1L, testAction, testEvent, testActionState);

        // rebuildState with empty markers should skip rebuild and preserve existing cache
        store.rebuildState(Collections.emptyList());

        assertThat(store.get(TEST_KEY, 1L, testAction, testEvent)).isNotNull();
    }
}
