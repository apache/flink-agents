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

package org.apache.flink.agents.runtime.subagent;

import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.subagent.SubagentFuture;
import org.apache.flink.agents.api.subagent.SubagentResult;

/**
 * Framework-level deferred execution mode for sub-agent setups: {@code submit} registers the
 * invocation and returns a deferred handle without sending anything; the actual request is issued
 * lazily when the handle is first resolved, and runs through one durable async callable keyed by a
 * failover-reproducible id, so the invocation participates in the task's durable execution.
 */
public abstract class BaseDeferredSubagentSetup extends BaseSubagentSetup {

    /** Registers the invocation and returns its deferred handle. */
    @Override
    public SubagentFuture submit(RunnerContext ctx, Object prompt, String sessionId, String callId)
            throws Exception {
        return new DeferredSubagentFuture(
                sessionId,
                callId,
                ctx,
                currentTaskRegistry(),
                () -> prepare(ctx, prompt, sessionId, callId));
    }

    /**
     * Prepares one invocation and returns the {@link DurableCallable} running it. Both ids are
     * already assigned; the durable id MUST be derived solely from the {@code (sessionId, callId)}
     * pair so it is reproducible after failover.
     *
     * <p>Called exactly once per invocation, when the deferred handle is first resolved, on the
     * mailbox thread. Implementations may therefore perform the mailbox-confined part of issuing
     * the request here; the returned callable's {@link DurableCallable#call()} carries only the
     * part that runs off the mailbox thread.
     *
     * <p>The callable folds its own comprehensible failures into the returned {@link
     * SubagentResult}; an exception escaping {@link DurableCallable#call()} is a system-level
     * failure that propagates and fails the action.
     *
     * <p>Skipping the reconciler on the returned callable has a cost: a crash between the call
     * landing and its result being persisted re-invokes the sub-agent on replay, possibly
     * duplicating external side effects.
     */
    protected abstract DurableCallable<SubagentResult> prepare(
            RunnerContext ctx, Object prompt, String sessionId, String callId);
}
