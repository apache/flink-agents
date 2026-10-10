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

import org.apache.flink.agents.api.trace.TraceRecord;

/**
 * Observes the creation, start, and outcome of calls made within an Action, such as LLM requests,
 * parser invocations, Tool calls, and Subagent calls.
 *
 * <p>Each callback receives one complete observation of a call. Its context identifies the call and
 * the containing Action execution.
 *
 * <p>Invariants a listener may rely on, and must not break:
 *
 * <ul>
 *   <li>Callbacks for an operator are serialized by the runtime's execution context. They may run
 *       on the mailbox thread or on a worker holding the execution lock.
 *   <li>An exception thrown by a listener is logged and swallowed, so reporting never fails the
 *       reporting component and never starves the remaining listeners.
 *   <li>The call's identity and relationship to its containing Action are populated before the
 *       callback.
 *   <li>The same TraceRecord is shared with other listeners. Its metadata, attributes, and nested
 *       payload values must be treated as read-only.
 * </ul>
 */
@FunctionalInterface
public interface ComponentExecutionListener {

    /**
     * Receives one report about a call made within an Action.
     *
     * @param record the call's identity, relationships, timestamp, status, and observation details
     */
    void onExecutionReported(TraceRecord record);
}
