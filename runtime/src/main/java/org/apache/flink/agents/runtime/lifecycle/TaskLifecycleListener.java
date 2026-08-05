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
 * Observes the per-record and per-task lifecycle of {@code ActionExecutionOperator}.
 *
 * <p>The operator broadcasts these events to every listener registered on it. All callbacks run on
 * the mailbox thread. Every method has a default no-op implementation so listeners only override
 * the events they care about.
 *
 * <p>Event pairing semantics:
 *
 * <ul>
 *   <li>{@code onRecordStart}/{@code onRecordFinished} bracket the processing of one input record
 *       for a key: start fires when the first task of the record is created, finished fires once
 *       every task spawned by that record has completed. A record that triggers no actions emits
 *       neither callback.
 *   <li>{@code onTaskPrepared} fires each time a task's runner context is wired up (including
 *       re-preparation of a suspended task); {@code onTaskFinished} fires when a task terminates;
 *       {@code onTaskTransferred} fires instead when a non-finished task hands its contexts over to
 *       the task it generated.
 * </ul>
 */
public interface TaskLifecycleListener {

    /**
     * The first action task of the input record of {@code key} has just been created and is about
     * to be prepared.
     *
     * @param key the Flink key of the input record starting processing.
     */
    default void onRecordStart(Object key) {}

    /**
     * A task's runner context has been wired up and the task is ready to run. Fires on every
     * preparation, including re-preparation of a suspended or resumed task.
     *
     * @param task the prepared action task.
     */
    default void onTaskPrepared(ActionTask task) {}

    /**
     * A non-finished task handed its per-task contexts to the task it generated. Listeners that
     * keep per-task bookkeeping must move their entries from {@code from} to {@code to} so the
     * continuation keeps its state (e.g. id ordinals continue instead of restarting).
     *
     * @param from the finishing task whose contexts were transferred.
     * @param to the generated task that inherited the contexts.
     */
    default void onTaskTransferred(ActionTask from, ActionTask to) {}

    /**
     * A task reached its terminal state. Fires after the task has been invoked and its contexts
     * record has been removed, and before the record-level completion bookkeeping runs.
     *
     * @param task the finished action task.
     */
    default void onTaskFinished(ActionTask task) {}

    /**
     * Every task spawned by the input record of {@code key} has completed and the record is fully
     * processed. Implementations must make their per-record cleanup idempotent: after a failover
     * replay the notification may not be delivered again for records that completed before the
     * snapshot.
     *
     * @param key the Flink key of the finished input record.
     */
    default void onRecordFinished(Object key) {}
}
