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
import org.apache.flink.api.common.state.ValueStateDescriptor;
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

    @Test
    void notifyAbortedRemovesEntryWithoutPruning() throws Exception {
        // Use a cleanup-enabled in-memory store so pruning would be observable if it
        // (incorrectly) fired on abort. doCleanup=true makes pruneState remove the keyed entry
        // from getKeyedActionStates().
        InMemoryActionStateStore store = new InMemoryActionStateStore(true);
        DurableExecutionManager dem = new DurableExecutionManager(store);

        Action action = TestActions.noopAction();
        Event event = new InputEvent(1L);
        String key = "key-a";
        long seq = 7L;
        long checkpointId = 1L;

        // Seed durable state for the key/seq pair.
        dem.maybeInitActionState(key, seq, action, event);
        assertThat(store.getKeyedActionStates()).containsKey(key);

        // Snapshot the per-key sequence number for this checkpoint. Stub the backend so that
        // applyToAllKeys invokes the provided KeyedStateFunction once with our seeded key and a
        // ValueState that returns the seeded sequence number.
        @SuppressWarnings("unchecked")
        KeyedStateBackend<Object> backend = mock(KeyedStateBackend.class);
        stubApplyToAllKeysSingle(backend, key, seq);
        dem.snapshotLastCompletedSequenceNumbers(backend, checkpointId);
        assertThat(dem.getCheckpointIdToSeqNums()).containsKey(checkpointId);

        // Abort the checkpoint.
        dem.notifyCheckpointAborted(checkpointId);

        // The in-memory tracking entry is released.
        assertThat(dem.getCheckpointIdToSeqNums()).doesNotContainKey(checkpointId);
        // The durable state was NOT pruned — the key is still present in the store.
        assertThat(store.getKeyedActionStates()).containsKey(key);

        dem.close();
    }

    @Test
    void completedAndAbortedInterleavedKeepsInFlightEntries() throws Exception {
        // doCleanup=true so a completed checkpoint's pruneState calls are observable as a
        // disappearance from getKeyedActionStates().
        InMemoryActionStateStore store = new InMemoryActionStateStore(true);
        DurableExecutionManager dem = new DurableExecutionManager(store);

        Action action = TestActions.noopAction();
        Event eventA = new InputEvent(1L);
        Event eventB = new InputEvent(2L);
        Event eventC = new InputEvent(3L);
        String keyA = "key-a";
        String keyB = "key-b";
        String keyC = "key-c";

        // Seed durable state for three distinct keys, one per upcoming checkpoint.
        dem.maybeInitActionState(keyA, 10L, action, eventA);
        dem.maybeInitActionState(keyB, 20L, action, eventB);
        dem.maybeInitActionState(keyC, 30L, action, eventC);
        assertThat(store.getKeyedActionStates()).containsKeys(keyA, keyB, keyC);

        // Snapshot three checkpoints. Each snapshot is stubbed to record exactly one key with its
        // seeded sequence number, so the per-checkpoint map is fully deterministic.
        @SuppressWarnings("unchecked")
        KeyedStateBackend<Object> backendA = mock(KeyedStateBackend.class);
        stubApplyToAllKeysSingle(backendA, keyA, 10L);
        dem.snapshotLastCompletedSequenceNumbers(backendA, 1L);

        @SuppressWarnings("unchecked")
        KeyedStateBackend<Object> backendB = mock(KeyedStateBackend.class);
        stubApplyToAllKeysSingle(backendB, keyB, 20L);
        dem.snapshotLastCompletedSequenceNumbers(backendB, 2L);

        @SuppressWarnings("unchecked")
        KeyedStateBackend<Object> backendC = mock(KeyedStateBackend.class);
        stubApplyToAllKeysSingle(backendC, keyC, 30L);
        dem.snapshotLastCompletedSequenceNumbers(backendC, 3L);

        assertThat(dem.getCheckpointIdToSeqNums()).containsKeys(1L, 2L, 3L);

        // Complete checkpoint 1 (prunes keyA) and abort checkpoint 2 (releases entry without
        // pruning keyB). Checkpoint 3 stays in-flight.
        dem.notifyCheckpointComplete(1L);
        dem.notifyCheckpointAborted(2L);

        // Only the in-flight checkpoint entry remains.
        assertThat(dem.getCheckpointIdToSeqNums()).containsOnlyKeys(3L);

        // Completed checkpoint's durable state was pruned away.
        assertThat(store.getKeyedActionStates()).doesNotContainKey(keyA);
        // Aborted checkpoint's durable state is untouched.
        assertThat(store.getKeyedActionStates()).containsKey(keyB);
        // In-flight checkpoint's durable state is untouched.
        assertThat(store.getKeyedActionStates()).containsKey(keyC);

        dem.close();
    }

    @Test
    void noStoreModeNotifyCheckpointAbortedIsNoOp() {
        DurableExecutionManager dem = new DurableExecutionManager(null);

        // Should not throw and should not populate any per-checkpoint map entries.
        dem.notifyCheckpointAborted(42L);

        assertThat(dem.getCheckpointIdToSeqNums()).isEmpty();
    }

    /**
     * Stubs {@code backend.applyToAllKeys(...)} to invoke the supplied {@link KeyedStateFunction}
     * exactly once with the given key and a {@link ValueState} mock that returns the given sequence
     * number. This mirrors the per-key iteration shape used by {@link
     * DurableExecutionManager#snapshotLastCompletedSequenceNumbers}.
     */
    @SuppressWarnings("unchecked")
    private static void stubApplyToAllKeysSingle(
            KeyedStateBackend<Object> backend, Object key, long sequenceNumber) throws Exception {
        ValueState<Long> valueState = mock(ValueState.class);
        when(valueState.value()).thenReturn(sequenceNumber);
        doAnswer(
                        invocation -> {
                            KeyedStateFunction<Object, ValueState<Long>> function =
                                    invocation.getArgument(3);
                            function.process(key, valueState);
                            return null;
                        })
                .when(backend)
                .applyToAllKeys(any(), any(), any(ValueStateDescriptor.class), any());
    }
}
