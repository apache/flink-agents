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
import org.apache.flink.agents.runtime.memory.MemoryObjectImpl;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.runtime.state.KeyGroupRange;
import org.apache.flink.runtime.state.KeyGroupRangeAssignment;
import org.apache.flink.runtime.state.KeyedStateBackend;
import org.apache.flink.runtime.state.KeyedStateFunction;
import org.apache.flink.runtime.state.OperatorStateBackend;
import org.apache.flink.runtime.state.VoidNamespace;
import org.apache.flink.runtime.state.VoidNamespaceSerializer;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

import static org.apache.flink.agents.runtime.utils.StateUtil.*;

/**
 * Owns all Flink state used by {@link ActionExecutionOperator} and exposes a narrow API for
 * accessing it.
 *
 * <p>Owned state:
 *
 * <ul>
 *   <li>Keyed list state of pending {@link ActionTask}s for the current key.
 *   <li>Keyed list state of pending {@link Event}s buffered while another input is processing.
 *   <li>Keyed value state holding the per-key message sequence number.
 *   <li>Keyed map states for sensory and short-term memory.
 *   <li>Operator union-list state of keys currently being processed (used after rescale to recover
 *       in-flight work).
 * </ul>
 *
 * <p>Lifecycle: instantiated by the operator's {@code initializeState()} (the Flink lifecycle runs
 * {@code initializeState} before {@code open}). Both {@link
 * #initializeKeyedStates(org.apache.flink.api.common.functions.RuntimeContext)} and {@link
 * #initializeOperatorStates(OperatorStateBackend)} are invoked later from the operator's {@code
 * open()}. There is no explicit close — the underlying state handles are owned by Flink.
 *
 * <p>Design constraint: package-private; no manager-to-manager held references. Cross-cutting data
 * flows via method parameters (see for example {@link ActionTaskContextManager#transferContexts}
 * which takes a {@link DurableExecutionManager} as an argument rather than holding one).
 */
class OperatorStateManager {

    static final String MESSAGE_SEQUENCE_NUMBER_STATE_NAME = "messageSequenceNumber";
    static final String PENDING_INPUT_EVENT_STATE_NAME = "pendingInputEvents";

    private ListState<ActionTask> actionTasksKState;
    private ListState<Event> pendingInputEventsKState;
    private ListState<Object> currentProcessingKeysOpState;
    private ValueState<Long> sequenceNumberKState;
    private MapState<String, MemoryObjectImpl.MemoryItem> sensoryMemState;
    private MapState<String, MemoryObjectImpl.MemoryItem> shortTermMemState;

    OperatorStateManager() {}

    /**
     * Registers all keyed-state descriptors against the operator's runtime context.
     *
     * <p>Registers: sensory memory map state, short-term memory map state, the per-key message
     * sequence-number value state, the per-key list of pending {@link ActionTask}s, and the per-key
     * list of buffered {@link Event}s. Called from the operator's {@code open()} method.
     *
     * @param runtimeContext the operator's runtime context, used to obtain keyed state handles.
     */
    void initializeKeyedStates(org.apache.flink.api.common.functions.RuntimeContext runtimeContext)
            throws Exception {
        // init sensoryMemState
        MapStateDescriptor<String, MemoryObjectImpl.MemoryItem> sensoryMemStateDescriptor =
                new MapStateDescriptor<>(
                        "sensoryMemory",
                        TypeInformation.of(String.class),
                        TypeInformation.of(MemoryObjectImpl.MemoryItem.class));
        sensoryMemState = runtimeContext.getMapState(sensoryMemStateDescriptor);

        // init shortTermMemState
        MapStateDescriptor<String, MemoryObjectImpl.MemoryItem> shortTermMemStateDescriptor =
                new MapStateDescriptor<>(
                        "shortTermMemory",
                        TypeInformation.of(String.class),
                        TypeInformation.of(MemoryObjectImpl.MemoryItem.class));
        shortTermMemState = runtimeContext.getMapState(shortTermMemStateDescriptor);

        // init sequence number state for per key message ordering
        sequenceNumberKState =
                runtimeContext.getState(
                        new ValueStateDescriptor<>(MESSAGE_SEQUENCE_NUMBER_STATE_NAME, Long.class));

        // init agent processing related state
        actionTasksKState =
                runtimeContext.getListState(
                        new ListStateDescriptor<>(
                                "actionTasks", TypeInformation.of(ActionTask.class)));
        pendingInputEventsKState =
                runtimeContext.getListState(
                        new ListStateDescriptor<>(
                                PENDING_INPUT_EVENT_STATE_NAME, TypeInformation.of(Event.class)));
    }

    /**
     * Registers operator-level (non-keyed) state.
     *
     * <p>Registers the {@code currentProcessingKeys} union-list state. A union-list lets every
     * subtask see all keys after a rescale; the operator filters out keys that do not belong to the
     * current subtask's key-group range during recovery. Called from the operator's {@code open()}
     * method (after {@code super.open()} and after the keyed-state setup).
     *
     * @param operatorStateBackend the operator state backend used to obtain operator state.
     */
    void initializeOperatorStates(OperatorStateBackend operatorStateBackend) throws Exception {
        // We use UnionList here to ensure that the task can access all keys after parallelism
        // modifications.
        // Subsequent steps {@link #tryResumeProcessActionTasks} will then filter out keys that do
        // not belong to the key range of current task.
        currentProcessingKeysOpState =
                operatorStateBackend.getUnionListState(
                        new ListStateDescriptor<>(
                                "currentProcessingKeys", TypeInformation.of(Object.class)));
    }

    /**
     * Advances the per-key message sequence number.
     *
     * <p>If the state has no value for the current key, sets it to {@code 0L}. Otherwise increments
     * the existing value by one. Must be called under a keyed context.
     */
    void initOrIncSequenceNumber() throws Exception {
        // Initialize the sequence number state if it does not exist.
        Long sequenceNumber = sequenceNumberKState.value();
        if (sequenceNumber == null) {
            sequenceNumberKState.update(0L);
        } else {
            sequenceNumberKState.update(sequenceNumber + 1);
        }
    }

    long getSequenceNumber() throws Exception {
        return sequenceNumberKState.value();
    }

    boolean hasMoreActionTasks() throws Exception {
        return listStateNotEmpty(actionTasksKState);
    }

    /**
     * Removes and returns the next pending {@link ActionTask} for the current key.
     *
     * @return the next {@link ActionTask}, or {@code null} if the queue for the current key is
     *     empty.
     */
    @Nullable
    ActionTask pollNextActionTask() throws Exception {
        return pollFromListState(actionTasksKState);
    }

    void addActionTask(ActionTask actionTask) throws Exception {
        actionTasksKState.add(actionTask);
    }

    void addPendingInputEvent(Event event) throws Exception {
        pendingInputEventsKState.add(event);
    }

    /**
     * Removes and returns the next pending input {@link Event} buffered for the current key.
     *
     * @return the next buffered input {@link Event}, or {@code null} if the buffer for the current
     *     key is empty.
     */
    @Nullable
    Event pollNextPendingInputEvent() throws Exception {
        return pollFromListState(pendingInputEventsKState);
    }

    void addProcessingKey(Object key) throws Exception {
        currentProcessingKeysOpState.add(key);
    }

    int removeProcessingKey(Object key) throws Exception {
        return removeFromListState(currentProcessingKeysOpState, key);
    }

    boolean hasProcessingKeys() throws Exception {
        return listStateNotEmpty(currentProcessingKeysOpState);
    }

    Iterable<Object> getProcessingKeys() throws Exception {
        return currentProcessingKeysOpState.get();
    }

    MapState<String, MemoryObjectImpl.MemoryItem> getSensoryMemState() {
        return sensoryMemState;
    }

    MapState<String, MemoryObjectImpl.MemoryItem> getShortTermMemState() {
        return shortTermMemState;
    }

    KeyGroupRange getCurrentSubtaskKeyGroupRange(
            int maxParallelism,
            org.apache.flink.api.common.functions.RuntimeContext runtimeContext) {
        int parallelism = runtimeContext.getTaskInfo().getNumberOfParallelSubtasks();
        int subtaskIndex = runtimeContext.getTaskInfo().getIndexOfThisSubtask();
        return KeyGroupRangeAssignment.computeKeyGroupRangeForOperatorIndex(
                maxParallelism, parallelism, subtaskIndex);
    }

    boolean isKeyOwnedByCurrentSubtask(
            Object key, int maxParallelism, KeyGroupRange currentSubtaskKeyGroupRange) {
        int keyGroup = KeyGroupRangeAssignment.assignToKeyGroup(key, maxParallelism);
        return currentSubtaskKeyGroupRange.contains(keyGroup);
    }

    /**
     * Captures the current per-key sequence numbers across all keys held by the given backend.
     *
     * <p>Invoked during checkpoint snapshotting so the caller can later associate the snapshot's
     * per-key sequence numbers with a checkpoint id (see {@link
     * DurableExecutionManager#recordCheckpointSequenceNumbers}).
     *
     * @param keyedStateBackend the keyed state backend to scan.
     * @return an immutable map snapshot from key to its current sequence number.
     */
    @SuppressWarnings("unchecked")
    Map<Object, Long> snapshotSequenceNumbers(KeyedStateBackend<?> keyedStateBackend)
            throws Exception {
        HashMap<Object, Long> keyToSeqNum = new HashMap<>();
        ((KeyedStateBackend<Object>) keyedStateBackend)
                .applyToAllKeys(
                        VoidNamespace.INSTANCE,
                        VoidNamespaceSerializer.INSTANCE,
                        new ValueStateDescriptor<>(MESSAGE_SEQUENCE_NUMBER_STATE_NAME, Long.class),
                        (key, state) -> keyToSeqNum.put(key, state.value()));
        return keyToSeqNum;
    }

    /**
     * Applies a function to the pending-input-event list state for every key in the backend.
     *
     * <p>Used during recovery to scan all keys that hold buffered input events so the operator can
     * resume processing them after a restore.
     *
     * @param keyedStateBackend the keyed state backend to scan.
     * @param function the function to apply per (key, list-state) pair.
     */
    @SuppressWarnings("unchecked")
    void forEachPendingInputEventKey(
            KeyedStateBackend<?> keyedStateBackend,
            KeyedStateFunction<Object, ListState<Event>> function)
            throws Exception {
        ((KeyedStateBackend<Object>) keyedStateBackend)
                .applyToAllKeys(
                        VoidNamespace.INSTANCE,
                        VoidNamespaceSerializer.INSTANCE,
                        new ListStateDescriptor<>(
                                PENDING_INPUT_EVENT_STATE_NAME, TypeInformation.of(Event.class)),
                        function);
    }
}
