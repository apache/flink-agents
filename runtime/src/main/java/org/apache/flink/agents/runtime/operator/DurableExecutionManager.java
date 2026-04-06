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
import org.apache.flink.agents.api.context.MemoryUpdate;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateStore;
import org.apache.flink.agents.runtime.actionstate.KafkaActionStateStore;
import org.apache.flink.agents.runtime.async.ContinuationContext;
import org.apache.flink.agents.runtime.context.ActionStatePersister;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.runtime.state.OperatorStateBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.flink.agents.api.configuration.AgentConfigOptions.ACTION_STATE_STORE_BACKEND;
import static org.apache.flink.agents.runtime.actionstate.ActionStateStore.BackendType.KAFKA;

class DurableExecutionManager implements ActionStatePersister, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DurableExecutionManager.class);

    private static final String RECOVERY_MARKER_STATE_NAME = "recoveryMarker";

    private ActionStateStore actionStateStore;
    private transient ListState<Object> recoveryMarkerOpState;
    private final Map<Long, Map<Object, Long>> checkpointIdToSeqNums;

    private final Map<ActionTask, RunnerContextImpl.DurableExecutionContext>
            actionTaskDurableContexts;
    private final Map<ActionTask, ContinuationContext> continuationContexts;
    private final Map<ActionTask, String> pythonAwaitableRefs;

    DurableExecutionManager(@Nullable ActionStateStore actionStateStore) {
        this.actionStateStore = actionStateStore;
        this.checkpointIdToSeqNums = new HashMap<>();
        this.actionTaskDurableContexts = new HashMap<>();
        this.continuationContexts = new HashMap<>();
        this.pythonAwaitableRefs = new HashMap<>();
    }

    void maybeInitActionStateStore(AgentConfiguration config) {
        if (actionStateStore == null
                && KAFKA.getType().equalsIgnoreCase(config.get(ACTION_STATE_STORE_BACKEND))) {
            LOG.info("Using Kafka as backend of action state store.");
            actionStateStore = new KafkaActionStateStore(config);
        }
    }

    boolean hasDurableStore() {
        return actionStateStore != null;
    }

    void initRecoveryMarkerState(OperatorStateBackend operatorStateBackend) throws Exception {
        if (actionStateStore != null) {
            recoveryMarkerOpState =
                    operatorStateBackend.getUnionListState(
                            new ListStateDescriptor<>(
                                    RECOVERY_MARKER_STATE_NAME, TypeInformation.of(Object.class)));
        }
    }

    // Note: Re-creates the union list state descriptor here because handleRecovery() is called
    // from initializeState() which runs BEFORE open(), so recoveryMarkerOpState is not yet
    // initialized. The descriptor name matches exactly, so Flink returns the same state.
    void handleRecovery(OperatorStateBackend operatorStateBackend) throws Exception {
        if (actionStateStore != null) {
            List<Object> markers = new ArrayList<>();
            ListState<Object> markerState =
                    operatorStateBackend.getUnionListState(
                            new ListStateDescriptor<>(
                                    RECOVERY_MARKER_STATE_NAME, TypeInformation.of(Object.class)));
            Iterable<Object> recoveryMarkers = markerState.get();
            if (recoveryMarkers != null) {
                recoveryMarkers.forEach(markers::add);
            }
            LOG.info("Rebuilding action state from {} recovery markers", markers.size());
            actionStateStore.rebuildState(markers);
        }
    }

    ActionState maybeGetActionState(Object key, long sequenceNum, Action action, Event event)
            throws Exception {
        return actionStateStore == null
                ? null
                : actionStateStore.get(key.toString(), sequenceNum, action, event);
    }

    void maybeInitActionState(Object key, long sequenceNum, Action action, Event event)
            throws Exception {
        if (actionStateStore != null) {
            if (actionStateStore.get(key, sequenceNum, action, event) == null) {
                actionStateStore.put(key, sequenceNum, action, event, new ActionState(event));
            }
        }
    }

    void maybePersistTaskResult(
            Object key,
            long sequenceNum,
            Action action,
            Event event,
            RunnerContextImpl context,
            ActionTask.ActionTaskResult actionTaskResult)
            throws Exception {
        if (actionStateStore == null) {
            return;
        }

        if (!actionTaskResult.isFinished()) {
            return;
        }

        ActionState actionState = actionStateStore.get(key, sequenceNum, action, event);

        for (MemoryUpdate memoryUpdate : context.getSensoryMemoryUpdates()) {
            actionState.addSensoryMemoryUpdate(memoryUpdate);
        }

        for (MemoryUpdate memoryUpdate : context.getShortTermMemoryUpdates()) {
            actionState.addShortTermMemoryUpdate(memoryUpdate);
        }

        for (Event outputEvent : actionTaskResult.getOutputEvents()) {
            actionState.addEvent(outputEvent);
        }

        actionState.markCompleted();

        actionStateStore.put(key, sequenceNum, action, event, actionState);

        context.clearDurableExecutionContext();
    }

    void setupDurableExecutionContext(ActionTask actionTask, ActionState actionState, long seqNum) {
        if (actionStateStore == null) {
            return;
        }

        RunnerContextImpl.DurableExecutionContext durableContext;
        if (actionTaskDurableContexts.containsKey(actionTask)) {
            durableContext = actionTaskDurableContexts.get(actionTask);
        } else {
            durableContext =
                    new RunnerContextImpl.DurableExecutionContext(
                            actionTask.getKey(),
                            seqNum,
                            actionTask.action,
                            actionTask.event,
                            actionState,
                            this);
        }

        actionTask.getRunnerContext().setDurableExecutionContext(durableContext);
    }

    @Override
    public void persist(
            Object key, long sequenceNumber, Action action, Event event, ActionState actionState) {
        try {
            actionStateStore.put(key, sequenceNumber, action, event, actionState);
        } catch (Exception e) {
            LOG.error("Failed to persist ActionState", e);
            throw new RuntimeException("Failed to persist ActionState", e);
        }
    }

    void maybePruneState(Object key, long sequenceNum) throws Exception {
        if (actionStateStore != null) {
            actionStateStore.pruneState(key, sequenceNum);
        }
    }

    void notifyCheckpointComplete(long checkpointId) {
        if (actionStateStore != null) {
            Map<Object, Long> keyToSeqNum =
                    checkpointIdToSeqNums.getOrDefault(checkpointId, new HashMap<>());
            for (Map.Entry<Object, Long> entry : keyToSeqNum.entrySet()) {
                actionStateStore.pruneState(entry.getKey(), entry.getValue());
            }
            checkpointIdToSeqNums.remove(checkpointId);
        }
    }

    void snapshotRecoveryMarker() throws Exception {
        if (actionStateStore != null) {
            Object recoveryMarker = actionStateStore.getRecoveryMarker();
            if (recoveryMarker != null) {
                recoveryMarkerOpState.update(List.of(recoveryMarker));
            }
        }
    }

    void recordCheckpointSequenceNumbers(long checkpointId, Map<Object, Long> seqNums) {
        checkpointIdToSeqNums.put(checkpointId, seqNums);
    }

    // --- Context map accessors ---

    RunnerContextImpl.DurableExecutionContext getDurableContext(ActionTask actionTask) {
        return actionTaskDurableContexts.get(actionTask);
    }

    void putDurableContext(
            ActionTask actionTask, RunnerContextImpl.DurableExecutionContext context) {
        actionTaskDurableContexts.put(actionTask, context);
    }

    void removeDurableContext(ActionTask actionTask) {
        actionTaskDurableContexts.remove(actionTask);
    }

    ContinuationContext getContinuationContext(ActionTask actionTask) {
        return continuationContexts.get(actionTask);
    }

    void putContinuationContext(ActionTask actionTask, ContinuationContext context) {
        continuationContexts.put(actionTask, context);
    }

    void removeContinuationContext(ActionTask actionTask) {
        continuationContexts.remove(actionTask);
    }

    String getPythonAwaitableRef(ActionTask actionTask) {
        return pythonAwaitableRefs.get(actionTask);
    }

    void putPythonAwaitableRef(ActionTask actionTask, String ref) {
        pythonAwaitableRefs.put(actionTask, ref);
    }

    void removePythonAwaitableRef(ActionTask actionTask) {
        pythonAwaitableRefs.remove(actionTask);
    }

    boolean hasDurableContext(ActionTask actionTask) {
        return actionTaskDurableContexts.containsKey(actionTask);
    }

    boolean hasContinuationContext(ActionTask actionTask) {
        return continuationContexts.containsKey(actionTask);
    }

    @VisibleForTesting
    ActionStateStore getActionStateStore() {
        return actionStateStore;
    }

    @Override
    public void close() throws Exception {
        if (actionStateStore != null) {
            actionStateStore.close();
        }
    }
}
