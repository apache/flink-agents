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
import org.apache.flink.runtime.state.KeyedStateBackend;
import org.apache.flink.runtime.state.OperatorStateBackend;
import org.apache.flink.runtime.state.VoidNamespace;
import org.apache.flink.runtime.state.VoidNamespaceSerializer;
import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.apache.flink.agents.runtime.utils.StateUtil.*;

/**
 * Manages all Flink state objects used by the {@link ActionExecutionOperator}: keyed states for
 * memory, action tasks, pending input events, sequence numbers, and operator states for processing
 * keys and recovery markers.
 */
class OperatorStateManager {

    static final String RECOVERY_MARKER_STATE_NAME = "recoveryMarker";
    static final String MESSAGE_SEQUENCE_NUMBER_STATE_NAME = "messageSequenceNumber";
    static final String PENDING_INPUT_EVENT_STATE_NAME = "pendingInputEvents";

    private MapState<String, MemoryObjectImpl.MemoryItem> sensoryMemState;
    private MapState<String, MemoryObjectImpl.MemoryItem> shortTermMemState;
    private ListState<ActionTask> actionTasksKState;
    private ListState<Event> pendingInputEventsKState;
    private ListState<Object> currentProcessingKeysOpState;
    private ValueState<Long> sequenceNumberKState;
    private ListState<Object> recoveryMarkerOpState;

    // --- State Initialization ---

    /**
     * Initializes all Flink state objects. Should be called from the operator's {@code open()}
     * method.
     */
    void initializeStates(
            StreamingRuntimeContext runtimeContext,
            OperatorStateBackend operatorStateBackend,
            boolean hasActionStateStore)
            throws Exception {
        sensoryMemState =
                runtimeContext.getMapState(
                        new MapStateDescriptor<>(
                                "sensoryMemory",
                                TypeInformation.of(String.class),
                                TypeInformation.of(MemoryObjectImpl.MemoryItem.class)));

        shortTermMemState =
                runtimeContext.getMapState(
                        new MapStateDescriptor<>(
                                "shortTermMemory",
                                TypeInformation.of(String.class),
                                TypeInformation.of(MemoryObjectImpl.MemoryItem.class)));

        if (hasActionStateStore) {
            recoveryMarkerOpState =
                    operatorStateBackend.getUnionListState(
                            new ListStateDescriptor<>(
                                    RECOVERY_MARKER_STATE_NAME, TypeInformation.of(Object.class)));
        }

        sequenceNumberKState =
                runtimeContext.getState(
                        new ValueStateDescriptor<>(MESSAGE_SEQUENCE_NUMBER_STATE_NAME, Long.class));

        actionTasksKState =
                runtimeContext.getListState(
                        new ListStateDescriptor<>(
                                "actionTasks", TypeInformation.of(ActionTask.class)));
        pendingInputEventsKState =
                runtimeContext.getListState(
                        new ListStateDescriptor<>(
                                PENDING_INPUT_EVENT_STATE_NAME, TypeInformation.of(Event.class)));

        // We use UnionList here to ensure that the task can access all keys after parallelism
        // modifications.
        currentProcessingKeysOpState =
                operatorStateBackend.getUnionListState(
                        new ListStateDescriptor<>(
                                "currentProcessingKeys", TypeInformation.of(Object.class)));
    }

    // --- Memory State Accessors ---

    MapState<String, MemoryObjectImpl.MemoryItem> getSensoryMemState() {
        return sensoryMemState;
    }

    MapState<String, MemoryObjectImpl.MemoryItem> getShortTermMemState() {
        return shortTermMemState;
    }

    // --- Action Task State ---

    ActionTask pollNextActionTask() throws Exception {
        return pollFromListState(actionTasksKState);
    }

    void addActionTask(ActionTask task) throws Exception {
        actionTasksKState.add(task);
    }

    boolean hasMoreActionTasks() throws Exception {
        return listStateNotEmpty(actionTasksKState);
    }

    // --- Pending Input Events ---

    Event pollNextPendingInputEvent() throws Exception {
        return pollFromListState(pendingInputEventsKState);
    }

    void addPendingInputEvent(Event event) throws Exception {
        pendingInputEventsKState.add(event);
    }

    // --- Processing Key Tracking ---

    void addProcessingKey(Object key) throws Exception {
        currentProcessingKeysOpState.add(key);
    }

    int removeProcessingKey(Object key) throws Exception {
        return removeFromListState(currentProcessingKeysOpState, key);
    }

    Iterable<Object> getProcessingKeys() throws Exception {
        return currentProcessingKeysOpState.get();
    }

    boolean hasProcessingKeys() throws Exception {
        return listStateNotEmpty(currentProcessingKeysOpState);
    }

    // --- Sequence Number ---

    void initOrIncSequenceNumber() throws Exception {
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

    // --- Recovery Marker ---

    /**
     * Updates the recovery marker operator state. Only valid when the action state store is enabled
     * (i.e., recoveryMarkerOpState was initialized).
     */
    void updateRecoveryMarker(Object marker) throws Exception {
        recoveryMarkerOpState.update(List.of(marker));
    }

    /**
     * Collects recovery markers from operator state during state initialization.
     *
     * @return list of recovery markers
     */
    List<Object> collectRecoveryMarkers(
            OperatorStateBackend operatorStateBackend, boolean hasActionStateStore)
            throws Exception {
        List<Object> markers = new ArrayList<>();
        if (hasActionStateStore) {
            ListState<Object> recoveryMarkerState =
                    operatorStateBackend.getUnionListState(
                            new ListStateDescriptor<>(
                                    RECOVERY_MARKER_STATE_NAME, TypeInformation.of(Object.class)));
            Iterable<Object> recoveryMarkers = recoveryMarkerState.get();
            if (recoveryMarkers != null) {
                recoveryMarkers.forEach(markers::add);
            }
        }
        return markers;
    }

    /**
     * Iterates over all keys that have pending input events and passes each key to the given
     * consumer. Used during recovery to re-register pending keys with the segmented queue.
     */
    void forEachPendingInputEventKey(
            KeyedStateBackend<?> keyedStateBackend, Consumer<Object> action) throws Exception {
        keyedStateBackend.applyToAllKeys(
                VoidNamespace.INSTANCE,
                VoidNamespaceSerializer.INSTANCE,
                new ListStateDescriptor<>(
                        PENDING_INPUT_EVENT_STATE_NAME, TypeInformation.of(Event.class)),
                (key, state) -> state.get().forEach(event -> action.accept(key)));
    }

    /**
     * Snapshots sequence numbers for all keys. Returns a map of key to sequence number for the
     * given checkpoint.
     */
    Map<Object, Long> snapshotSequenceNumbers(KeyedStateBackend<?> keyedStateBackend)
            throws Exception {
        HashMap<Object, Long> keyToSeqNum = new HashMap<>();
        keyedStateBackend.applyToAllKeys(
                VoidNamespace.INSTANCE,
                VoidNamespaceSerializer.INSTANCE,
                new ValueStateDescriptor<>(MESSAGE_SEQUENCE_NUMBER_STATE_NAME, Long.class),
                (key, state) -> keyToSeqNum.put(key, state.value()));
        return keyToSeqNum;
    }
}
