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
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateStore;
import org.apache.flink.agents.runtime.actionstate.KafkaActionStateStore;
import org.apache.flink.agents.runtime.context.ActionStatePersister;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.flink.agents.api.configuration.AgentConfigOptions.ACTION_STATE_STORE_BACKEND;
import static org.apache.flink.agents.runtime.actionstate.ActionStateStore.BackendType.KAFKA;

/**
 * Manages durable execution state including the {@link ActionStateStore}, action state persistence,
 * recovery markers, and checkpoint-based state pruning.
 *
 * <p>When no {@link ActionStateStore} is configured (the common case for simple agents), all
 * methods are no-ops. This class implements {@link ActionStatePersister} so it can be passed to
 * {@link RunnerContextImpl.DurableExecutionContext} for fine-grained call-level recovery.
 */
class DurableExecutionManager implements ActionStatePersister {

    private static final Logger LOG = LoggerFactory.getLogger(DurableExecutionManager.class);

    private ActionStateStore actionStateStore;
    private final Map<Long, Map<Object, Long>> checkpointIdToSeqNums;

    DurableExecutionManager(ActionStateStore actionStateStore) {
        this.actionStateStore = actionStateStore;
        this.checkpointIdToSeqNums = new HashMap<>();
    }

    ActionStateStore getActionStateStore() {
        return actionStateStore;
    }

    /** Initializes the Kafka action state store if configured but not yet created. */
    void maybeInitActionStateStore(AgentPlan agentPlan) {
        if (actionStateStore == null
                && KAFKA.getType()
                        .equalsIgnoreCase(agentPlan.getConfig().get(ACTION_STATE_STORE_BACKEND))) {
            LOG.info("Using Kafka as backend of action state store.");
            actionStateStore = new KafkaActionStateStore(agentPlan.getConfig());
        }
    }

    /** Rebuilds state from recovery markers during state initialization. */
    void rebuildStateFromMarkers(List<Object> markers) throws Exception {
        if (actionStateStore != null) {
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
        if (actionStateStore == null || !actionTaskResult.isFinished()) {
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

    /**
     * Sets up the durable execution context for fine-grained recovery.
     *
     * <p>This method initializes the runner context with a {@link
     * RunnerContextImpl.DurableExecutionContext}, which enables execute/execute_async calls to:
     *
     * <ul>
     *   <li>Skip re-execution for already completed calls during recovery
     *   <li>Persist CallRecords after each code block completion
     * </ul>
     */
    void setupDurableExecutionContext(
            ActionTask actionTask,
            ActionState actionState,
            long sequenceNumber,
            Map<ActionTask, RunnerContextImpl.DurableExecutionContext> actionTaskDurableContexts) {
        if (actionStateStore == null) {
            return;
        }

        RunnerContextImpl.DurableExecutionContext durableContext =
                actionTaskDurableContexts.get(actionTask);
        if (durableContext == null) {
            durableContext =
                    new RunnerContextImpl.DurableExecutionContext(
                            actionTask.getKey(),
                            sequenceNumber,
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

    /** Records sequence numbers per key at snapshot time for later pruning. */
    void recordCheckpointSeqNums(long checkpointId, Map<Object, Long> keyToSeqNum) {
        checkpointIdToSeqNums.put(checkpointId, keyToSeqNum);
    }

    /** Prunes state for completed checkpoints. */
    void notifyCheckpointComplete(long checkpointId) throws Exception {
        Map<Object, Long> keyToSeqNum = checkpointIdToSeqNums.remove(checkpointId);
        if (actionStateStore != null && keyToSeqNum != null) {
            for (Map.Entry<Object, Long> entry : keyToSeqNum.entrySet()) {
                actionStateStore.pruneState(entry.getKey(), entry.getValue());
            }
        }
    }

    /** Gets recovery marker from the action state store, if available. */
    Object getRecoveryMarker() {
        return actionStateStore != null ? actionStateStore.getRecoveryMarker() : null;
    }

    void close() throws Exception {
        if (actionStateStore != null) {
            actionStateStore.close();
        }
    }
}
