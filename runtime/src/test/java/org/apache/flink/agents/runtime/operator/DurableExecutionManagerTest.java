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
package org.apache.flink.agents.runtime.operator;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.InMemoryActionStateStore;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.runtime.state.KeyedStateBackend;
import org.apache.flink.runtime.state.KeyedStateFunction;
import org.apache.flink.runtime.state.OperatorStateBackend;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Contract tests for {@link DurableExecutionManager}. */
class DurableExecutionManagerTest {

    @Test
    void noStoreModeMakesAllMaybeOperationsNoOp() throws Exception {
        DurableExecutionManager dem = new DurableExecutionManager(null);
        // No ACTION_STATE_STORE_BACKEND set → no default store should be created.
        dem.maybeInitActionStateStore(new AgentConfiguration());

        assertThat(dem.hasDurableStore()).isFalse();
        assertThat(dem.getActionStateStore()).isNull();

        Action action = TestActions.noopAction();
        Event event = new InputEvent(0L);

        // Every maybe* method must be a silent no-op.
        assertThat(dem.maybeGetActionState("k", 0L, action, event)).isNull();
        dem.maybeInitActionState("k", 0L, action, event);
        dem.maybePruneState("k", 0L);
        dem.notifyCheckpointComplete(1L);
        dem.snapshotRecoveryMarker();
        dem.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void noStoreModeSnapshotAndNotifyKeepCheckpointMapEmpty() throws Exception {
        DurableExecutionManager dem = new DurableExecutionManager(null);
        KeyedStateBackend<Object> backend = mock(KeyedStateBackend.class);

        // Cycle 1: snapshot + notify with null store. The snapshot-side guard must short-circuit
        // before any backend access, and the cleanup-side guard must leave the map untouched.
        dem.snapshotLastCompletedSequenceNumbers(backend, 1L);
        assertThat(dem.getCheckpointIdToSeqNums()).isEmpty();
        verifyNoInteractions(backend);
        dem.notifyCheckpointComplete(1L);
        assertThat(dem.getCheckpointIdToSeqNums()).isEmpty();

        // Cycle 2: confirm the invariant holds across multiple checkpoints.
        dem.snapshotLastCompletedSequenceNumbers(backend, 2L);
        assertThat(dem.getCheckpointIdToSeqNums()).isEmpty();
        verifyNoInteractions(backend);
        dem.notifyCheckpointComplete(2L);
        assertThat(dem.getCheckpointIdToSeqNums()).isEmpty();

        dem.close();
    }

    @Test
    void withInjectedStorePersistsTaskResult() throws Exception {
        InMemoryActionStateStore store = new InMemoryActionStateStore(false);
        DurableExecutionManager dem = new DurableExecutionManager(store);

        assertThat(dem.hasDurableStore()).isTrue();
        assertThat(dem.getActionStateStore()).isSameAs(store);

        Action action = TestActions.noopAction();
        Event event = new InputEvent(42L);
        String key = "key-1";
        long seq = 0L;

        // First call seeds an initial ActionState in the store.
        dem.maybeInitActionState(key, seq, action, event);
        assertThat(store.getKeyedActionStates()).containsKey(key);
        assertThat(dem.maybeGetActionState(key, seq, action, event)).isNotNull();

        // Build a finished task result with one output event; verify persist folds it into state.
        Event outEvent = new OutputEvent(99L);
        RunnerContextImpl context = mock(RunnerContextImpl.class);
        when(context.getSensoryMemoryUpdates()).thenReturn(List.of());
        when(context.getShortTermMemoryUpdates()).thenReturn(List.of());

        ActionTask.ActionTaskResult finishedResult = mock(ActionTask.ActionTaskResult.class);
        when(finishedResult.isFinished()).thenReturn(true);
        when(finishedResult.getOutputEvents()).thenReturn(List.of(outEvent));

        dem.maybePersistTaskResult(key, seq, action, event, context, finishedResult);

        ActionState persisted = dem.maybeGetActionState(key, seq, action, event);
        assertThat(persisted).isNotNull();
        assertThat(persisted.getOutputEvents()).contains(outEvent);
        assertThat(persisted.isCompleted()).isTrue();
        verify(context).clearDurableExecutionContext();

        dem.close();
    }

    /**
     * Verifies {@code snapshotLastCompletedSequenceNumbers} captures the per-key sequence numbers
     * returned by the keyed state backend and records them under the given checkpoint id.
     */
    @Test
    @SuppressWarnings("unchecked")
    void snapshotCapturesKeySequenceNumbers() throws Exception {
        InMemoryActionStateStore store = new InMemoryActionStateStore(false);
        DurableExecutionManager dem = new DurableExecutionManager(store);

        KeyedStateBackend<Object> backend = mock(KeyedStateBackend.class);
        doAnswer(
                        invocation -> {
                            KeyedStateFunction<Object, ValueState<Long>> function =
                                    invocation.getArgument(3);
                            ValueState<Long> state1 = mock(ValueState.class);
                            when(state1.value()).thenReturn(10L);
                            ValueState<Long> state2 = mock(ValueState.class);
                            when(state2.value()).thenReturn(20L);
                            function.process("k1", state1);
                            function.process("k2", state2);
                            return null;
                        })
                .when(backend)
                .applyToAllKeys(any(), any(), any(), any());

        dem.snapshotLastCompletedSequenceNumbers(backend, 1000L);

        assertThat(dem.getCheckpointIdToSeqNums())
                .containsOnlyKeys(1000L)
                .extractingByKey(1000L)
                .asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.map(
                                Object.class, Long.class))
                .containsEntry("k1", 10L)
                .containsEntry("k2", 20L)
                .hasSize(2);

        dem.close();
    }

    /**
     * Verifies {@code notifyCheckpointComplete} prunes every captured key for the notified
     * checkpoint, leaves entries captured for other checkpoints intact, and removes the notified
     * checkpoint's map entry. Combines the multi-key (loop body) and multi-checkpoint (selectivity)
     * concerns into one stronger test.
     */
    @Test
    void notifyPrunesNotifiedCheckpointAndPreservesOthers() throws Exception {
        InMemoryActionStateStore store = new InMemoryActionStateStore(true);
        DurableExecutionManager dem = new DurableExecutionManager(store);

        Action action = TestActions.noopAction();
        // Seed the store with state for three keys.
        dem.maybeInitActionState("k1", 10L, action, new InputEvent(1L));
        dem.maybeInitActionState("k2", 20L, action, new InputEvent(2L));
        dem.maybeInitActionState("k3", 30L, action, new InputEvent(3L));
        assertThat(store.getKeyedActionStates()).containsKeys("k1", "k2", "k3");

        // Record captured sequence numbers: checkpoint 1000 covers {k1, k2}, checkpoint 1001
        // covers {k3}. Use the @VisibleForTesting seam to install the snapshot directly.
        Map<Object, Long> ckpt1000 = new HashMap<>();
        ckpt1000.put("k1", 10L);
        ckpt1000.put("k2", 20L);
        Map<Object, Long> ckpt1001 = new HashMap<>();
        ckpt1001.put("k3", 30L);
        dem.getCheckpointIdToSeqNums().put(1000L, ckpt1000);
        dem.getCheckpointIdToSeqNums().put(1001L, ckpt1001);

        dem.notifyCheckpointComplete(1000L);

        // InMemoryActionStateStore.pruneState (lines 67–71) ignores the seqNum and removes the
        // whole key. The notified checkpoint's keys (k1, k2) are pruned; k3 — captured under a
        // different checkpoint — must remain. The notified checkpoint's map entry is removed;
        // entries for other checkpoints stay.
        assertThat(store.getKeyedActionStates())
                .doesNotContainKey("k1")
                .doesNotContainKey("k2")
                .containsKey("k3");
        assertThat(dem.getCheckpointIdToSeqNums()).doesNotContainKey(1000L).containsKey(1001L);

        dem.close();
    }

    /**
     * Verifies {@code handleRecovery} reads markers from the operator state backend and forwards
     * them to {@code store.rebuildState(...)}.
     */
    @Test
    @SuppressWarnings("unchecked")
    void handleRecoveryCallsRebuildState() throws Exception {
        InMemoryActionStateStore spyStore = spy(new InMemoryActionStateStore(true));
        DurableExecutionManager dem = new DurableExecutionManager(spyStore);

        OperatorStateBackend opBackend = mock(OperatorStateBackend.class);
        ListState<Object> markerState = mock(ListState.class);
        when(opBackend.getUnionListState(any(ListStateDescriptor.class))).thenReturn(markerState);
        when(markerState.get()).thenReturn(List.of("test-marker"));

        dem.handleRecovery(opBackend);

        // InMemoryActionStateStore.rebuildState is a no-op (lines 62–64), so state mutation is
        // not observable here — the test verifies the call contract only.
        verify(spyStore).rebuildState(List.of("test-marker"));

        dem.close();
    }
}
