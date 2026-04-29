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

    @Nullable
    MapState<String, MemoryObjectImpl.MemoryItem> getSensoryMemState() {
        return sensoryMemState;
    }

    @Nullable
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
