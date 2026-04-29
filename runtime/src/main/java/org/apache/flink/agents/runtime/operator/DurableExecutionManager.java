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

/**
 * Owns the durable-execution side of {@link ActionExecutionOperator}: the optional {@link
 * ActionStateStore}, the recovery-marker operator state, the per-checkpoint sequence-number map,
 * and the per-{@link ActionTask} {@link RunnerContextImpl.DurableExecutionContext} map.
 *
 * <p>Durable mode is optional. If no {@link ActionStateStore} is configured (and none is
 * pre-injected via the constructor), all {@code maybe*} methods are no-ops and {@link
 * #hasDurableStore()} returns {@code false}. This lets the operator stay agnostic of whether
 * durable execution is enabled.
 *
 * <p>Lifecycle: instantiated in the operator constructor. {@link
 * #maybeInitActionStateStore(AgentConfiguration)} runs from BOTH the operator's {@code
 * initializeState()} and {@code open()} — recovery requires the store to be configured before
 * {@link #handleRecovery(OperatorStateBackend)} reads from it, and the {@code open()} call ensures
 * the store is also available on the normal (non-recovery) path. The method creates a default
 * Kafka-backed store when one was not pre-injected, and is idempotent on the second call. {@link
 * #handleRecovery(OperatorStateBackend)} runs from the operator's {@code initializeState()} during
 * recovery. {@link #initRecoveryMarkerState(OperatorStateBackend)} runs from the operator's {@code
 * open()}. {@link #close()} closes the underlying store.
 *
 * <p>Design constraint: package-private; no manager-to-manager held references. Cross-cutting data
 * flows via method parameters. In particular, {@link
 * ActionTaskContextManager#transferContexts(ActionTask, ActionTask, DurableExecutionManager)}
 * accepts this manager as a parameter so that the durable-context map can stay encapsulated here.
 */
class DurableExecutionManager implements ActionStatePersister, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DurableExecutionManager.class);

    private static final String RECOVERY_MARKER_STATE_NAME = "recoveryMarker";

    private ActionStateStore actionStateStore;
    private transient ListState<Object> recoveryMarkerOpState;
    private final Map<Long, Map<Object, Long>> checkpointIdToSeqNums;

    private final Map<ActionTask, RunnerContextImpl.DurableExecutionContext>
            actionTaskDurableContexts;

    /**
     * @param actionStateStore an optional pre-injected store, primarily for tests. When {@code
     *     null}, {@link #maybeInitActionStateStore(AgentConfiguration)} may create a default store
     *     based on configuration; otherwise durable execution is disabled.
     */
    DurableExecutionManager(@Nullable ActionStateStore actionStateStore) {
        this.actionStateStore = actionStateStore;
        this.checkpointIdToSeqNums = new HashMap<>();
        this.actionTaskDurableContexts = new HashMap<>();
    }

    /**
     * Lazily creates a default {@link ActionStateStore} from configuration if none was
     * pre-injected.
     *
     * <p>Only creates a store when this manager was constructed without one and the configuration
     * selects a recognized backend (currently Kafka). Otherwise this is a no-op, which leaves
     * durable execution disabled.
     *
     * @param config the agent configuration carrying the backend selection.
     */
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

    /**
     * Replays recovery markers from the operator's union-list state to rebuild durable action
     * state.
     *
     * <p>Called from the operator's {@code initializeState()}, which runs before {@code open()}.
     * This means {@link #recoveryMarkerOpState} is not yet initialized, so the union-list state
     * descriptor is re-created here using the same descriptor name — Flink returns the same
     * underlying state. No-op when durable execution is disabled.
     *
     * @param operatorStateBackend the operator state backend used to obtain the recovery-marker
     *     union-list state.
     */
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

    @Nullable
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

    /**
     * Persists the result of a finished {@link ActionTask} to the durable store.
     *
     * <p>No-op when no store is configured or when the task did not finish (e.g. it suspended on a
     * continuation). On finish, accumulates the task's memory updates and emitted output events
     * into the {@link ActionState}, marks it completed, persists it, and clears the in-context
     * durable bookkeeping.
     *
     * @param key the key under which the action ran.
     * @param sequenceNum the per-key message sequence number.
     * @param action the action being persisted.
     * @param event the input event that triggered this action.
     * @param context the runner context whose memory updates will be folded into the action state.
     * @param actionTaskResult the result of running the action task.
     */
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

    /**
     * Wires a {@link RunnerContextImpl.DurableExecutionContext} onto the given action task's runner
     * context.
     *
     * <p>Returns immediately when no durable store is configured. Otherwise reuses an existing
     * {@link RunnerContextImpl.DurableExecutionContext} held in the per-task map (i.e. when
     * resuming a continuation), or creates a fresh one bound to this manager so that nested
     * persists route back through {@link #persist}.
     *
     * @param actionTask the action task to attach the context to.
     * @param actionState the action state for this (key, sequenceNum, action, event).
     * @param seqNum the per-key sequence number.
     */
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

    /**
     * Prunes durable state for all per-key sequence numbers that were captured at the time of the
     * given checkpoint.
     *
     * <p>The mapping from checkpoint id to per-key sequence numbers must have been recorded earlier
     * via {@link #recordCheckpointSequenceNumbers}. After pruning, the entry for that checkpoint is
     * removed. No-op when durable execution is disabled.
     *
     * @param checkpointId the id of the completed checkpoint.
     */
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

    /**
     * Records the per-key sequence numbers observed when snapshotting the given checkpoint.
     *
     * <p>The recorded mapping is consulted later by {@link #notifyCheckpointComplete(long)} to
     * prune durable state up to the sequence number that was committed by that checkpoint.
     *
     * @param checkpointId the checkpoint being snapshotted.
     * @param seqNums the per-key sequence numbers captured during the snapshot.
     */
    void recordCheckpointSequenceNumbers(long checkpointId, Map<Object, Long> seqNums) {
        checkpointIdToSeqNums.put(checkpointId, seqNums);
    }

    // --- Durable execution context map accessors ---

    @Nullable
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

    boolean hasDurableContext(ActionTask actionTask) {
        return actionTaskDurableContexts.containsKey(actionTask);
    }

    @VisibleForTesting
    @Nullable
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
