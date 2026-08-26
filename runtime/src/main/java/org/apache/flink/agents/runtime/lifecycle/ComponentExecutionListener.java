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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.trace.ExecutionLifecycleEvents;

import java.util.Map;

/**
 * Observes component executions reported from within an action, at LLM, parser, and tool
 * granularity.
 *
 * <p>A component reports its lifecycle as a status event rather than as one callback per outcome,
 * so a listener that only cares about a subset matches on the event type and ignores the rest.
 *
 * <p>Invariants a listener may rely on, and must not break:
 *
 * <ul>
 *   <li>The callback runs on the mailbox thread, so a listener needs no synchronization of its own.
 *   <li>An exception thrown by a listener is logged and swallowed, so reporting never fails the
 *       reporting component and never starves the remaining listeners.
 *   <li>The event carries the lifecycle status only; the reporting component is identified by the
 *       entity triple, which repeats on every report of the same execution.
 *   <li>The event instance is shared with every other listener, so a listener must treat it as
 *       read-only.
 * </ul>
 */
@FunctionalInterface
public interface ComponentExecutionListener {

    /**
     * A component execution reported a lifecycle event.
     *
     * @param entityType the component entity type, one of {@code
     *     org.apache.flink.agents.api.trace.ExecutionReporter.EntityTypes}.
     * @param entityName the component entity name.
     * @param entityMetadata the entity metadata reported with the execution.
     * @param event the lifecycle event, one of those produced by {@link ExecutionLifecycleEvents}.
     */
    void onComponentExecution(
            String entityType, String entityName, Map<String, Object> entityMetadata, Event event);
}
