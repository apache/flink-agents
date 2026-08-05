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
 * Driving handle for one sub-agent invocation, carrying the {@code (sessionId, callId)} identity
 * that keys the invocation's durable state.
 *
 * <p>The handle is a driver of the invocation's lifecycle, not a passive view: resolving it issues
 * the request when it has not been issued yet, and its wait releases the mailbox so other work can
 * proceed in between. Its heap state does not survive a failover; the identity is the only basis
 * for rebuilding it through replay.
 *
 * <p>Returned by {@link Subagent#submit}. The invocation is always deferred: the request is issued
 * when the handle is resolved; several handles can be grouped through {@link #combine} for a
 * batched resolve in submission order.
 *
 * <p>Abstract data structure; the implementations live in the runtime layer.
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
     * failed {@link Result} rather than a separately reported exceptional completion.
     *
     * <p>The wait releases the mailbox through stackful suspension, so other work (such as the
     * actions of a sub-agent this invocation drives) proceeds in between. Environments without
     * stackful suspension fail fast rather than blocking the mailbox; resolving is await-only.
     */
    public abstract Result await() throws Exception;

    /**
     * Requests cancellation of the invocation. The default implementation does nothing; the
     * cancellation semantics are defined by the concrete implementation.
     */
    public void cancel() {}

    /** Groups this handle with others for a batched resolve through {@link SubagentFutures}. */
    public abstract SubagentFutures combine(SubagentFuture... others);
}
