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

package org.apache.flink.agents.api.subagent;

/**
 * Handle for one sub-agent invocation, identified by the {@code (sessionId, callId)} pair that keys
 * the invocation.
 */
public abstract class SubagentFuture {

    private final String sessionId;
    private final String callId;

    protected SubagentFuture(String sessionId, String callId) {
        this.sessionId = sessionId;
        this.callId = callId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getCallId() {
        return callId;
    }

    /** Whether the invocation has reached a terminal state. */
    public abstract boolean isDone();

    /**
     * Resolves the invocation, waiting until it reaches a terminal state. Failures converge into a
     * failed {@link SubagentResult} rather than a separately reported exceptional completion.
     */
    public abstract SubagentResult await() throws Exception;

    /** Requests cancellation of the invocation. */
    public void cancel() {}

    /** Groups this handle with others to be resolved together through {@link SubagentFutures}. */
    public abstract SubagentFutures combine(SubagentFuture... others);
}
