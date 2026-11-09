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
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.subagent.SubagentFuture;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;

/**
 * Framework-level deferred execution mode for sub-agent setups: {@code submit} registers the
 * invocation and returns a deferred handle without sending anything; the actual request is issued
 * lazily when the handle is first resolved, and runs through one durable async callable keyed by a
 * failover-reproducible id, so the invocation participates in the task's durable execution.
 */
public abstract class BaseDeferredSubagentSetup extends BaseSubagentSetup {

    /** Descriptor-based construction, forwarding to the sub-agent base. */
    protected BaseDeferredSubagentSetup(
            ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
    }

    /** Registers the invocation and returns its deferred handle. */
    @Override
    public SubagentFuture submit(RunnerContext ctx, Object prompt, String sessionId, String callId)
            throws Exception {
        return new SubagentFutureImpl(
                sessionId,
                callId,
                ctx,
                () ->
                        ((RunnerContextImpl) ctx)
                                .durableExecuteAsync(
                                        sessionId + "#" + callId,
                                        SubagentResult.class,
                                        () -> prepare(ctx, prompt, sessionId, callId)),
                currentTaskRegistry(),
                () -> false,
                () -> {});
    }

    /**
     * Prepares one invocation and returns the {@link DurableCallable} running it. Both ids are
     * already assigned; the callable must use {@code sessionId + "#" + callId} as its durable id
     * and {@code SubagentResult.class} as its result class.
     *
     * <p>Called on the mailbox thread when the handle is first resolved, after reserving its
     * durable slot. Terminal replay skips this method; recovery of a pending call may invoke it
     * again. Ordinary preparation exceptions are recorded as the call's failure. Implementations
     * may therefore perform the mailbox-confined part of issuing the request here; the returned
     * callable's {@link DurableCallable#call()} then carries the wait on an async-pool worker off
     * the mailbox thread.
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
