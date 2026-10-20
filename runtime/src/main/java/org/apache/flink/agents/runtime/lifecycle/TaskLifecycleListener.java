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

package org.apache.flink.agents.runtime.lifecycle;

import org.apache.flink.agents.runtime.operator.ActionTask;

/**
 * Observes the per-record and per-action lifecycle of {@code ActionExecutionOperator}.
 *
 * <p>Callbacks are serialized within one operator: on the mailbox thread for cooperative execution,
 * or under the execution lock for parallel execution. No fixed thread affinity is guaranteed.
 *
 * <p>Event pairing semantics:
 *
 * <ul>
 *   <li>{@code onRecordStart}/{@code onRecordFinished} bracket the processing of one input record
 *       for a key. The bracket is unconditional: it depends on the record being admitted and
 *       retired, not on whether any action ran, so a record that triggers no actions still emits
 *       both.
 *   <li>{@code onActionPrepared} marks entry into each execution phase after the task's contexts
 *       are bound, not necessarily another invocation of its body. The phase ends with {@code
 *       onActionFinishing} followed by {@code onActionFinished} on normal completion, {@code
 *       onActionReused} when a replay skips an already-completed action, or {@code
 *       onActionTransferred} when the task yields execution. A transfer may retain the same task,
 *       including a worker-to-mailbox handoff.
 *   <li>{@code onActionFailed} reports that processing a task failed. It is not a phase exit and
 *       does not imply an open phase.
 *   <li>{@code onActionStarted} fires at most once per action execution, before the first real
 *       invocation and never on a re-preparation; the gate is checkpointed with the task, so a
 *       failover replay re-emits {@code onRecordStart} for the resumed round but not {@code
 *       onActionStarted}.
 * </ul>
 *
 * <p>Exception contract: the framework allows a listener to inspect state and throw when necessary;
 * a listener that only observes should avoid throwing.
 */
public interface TaskLifecycleListener {

    /**
     * The input record of {@code key} has been admitted for processing and is about to trigger its
     * actions, if it triggers any. Also re-emitted when the task chain of a record that was in
     * flight at snapshot time resumes after a failover, so listeners observe a paired bracket for
     * the replayed round.
     *
     * @param key the Flink key of the input record starting processing.
     */
    default void onRecordStart(Object key) {}

    /**
     * An action task's contexts are fully bound for the execution phase it is entering. Fires again
     * after a handoff or suspension; the phase need not invoke the action body again.
     *
     * @param task the prepared action task.
     */
    default void onActionPrepared(ActionTask task) {}

    /**
     * An action execution is about to run for the first time. Fires at most once per action
     * execution: re-preparations of a suspended or resumed task do not emit it again.
     *
     * @param task the action task whose first invocation is imminent.
     */
    default void onActionStarted(ActionTask task) {}

    /**
     * A task is yielding execution. Listeners unbind it and move per-task bookkeeping from {@code
     * from} to {@code to}, preserving it unchanged when both are the same task. What comes next is
     * {@code onActionPrepared} for a further phase, or {@code onActionFailed}.
     *
     * @param from the task yielding execution.
     * @param to the task retaining the contexts for its next execution phase.
     */
    default void onActionTransferred(ActionTask from, ActionTask to) {}

    /**
     * A task's invocation completed and its contexts record has been removed; fires immediately
     * before its result (including its completed state) is persisted. Not emitted on the
     * replay-reuse path.
     *
     * @param task the completing action task.
     */
    default void onActionFinishing(ActionTask task) {}

    /**
     * A task's invocation finished normally and its result has been persisted, so a later replay of
     * the same action skips the invocation. Marks the end of the normal completion path. Not
     * emitted when the invocation fails.
     *
     * @param task the finished action task.
     */
    default void onActionFinished(ActionTask task) {}

    /**
     * A replayed already-completed action had its persisted result applied and its invocation
     * skipped. This is the sole terminal callback on the reuse path.
     *
     * @param task the reused action task.
     */
    default void onActionReused(ActionTask task) {}

    /**
     * Processing an action task failed. Purely observational: listeners perceive the failure for
     * logging, metrics, or bookkeeping cleanup, but must not compensate or decide on rethrowing.
     *
     * <p>Not a phase exit, and it does not imply an open phase: a task that failed after a transfer
     * arrives here with no {@code onActionPrepared} in between, so cleanup must tolerate a task it
     * already unbound.
     *
     * @param task the failed action task.
     * @param error the task processing failure.
     */
    default void onActionFailed(ActionTask task, Throwable error) {}

    /**
     * Every task spawned by the input record of {@code key} has completed and the record is fully
     * processed; a record that spawned none is retired here as well. Implementations must make
     * their per-record cleanup idempotent: after a failover replay the notification may not be
     * delivered again for records that completed before the snapshot.
     *
     * @param key the Flink key of the finished input record.
     */
    default void onRecordFinished(Object key) {}
}
