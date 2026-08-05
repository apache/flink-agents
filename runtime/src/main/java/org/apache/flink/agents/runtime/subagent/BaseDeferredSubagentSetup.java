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
import org.apache.flink.agents.api.subagent.Result;
import org.apache.flink.agents.api.subagent.SubagentFuture;

/**
 * Framework-level deferred execution mode for sub-agent setups: invocations run through one async
 * callable, issued lazily through a deferred handle. The mode is not tied to external services; an
 * internal sub-agent drives its child plan through the same shape.
 *
 * <p>{@link #submit} always returns a deferred handle built on {@link DeferredSubagentFuture}: the
 * request is prepared when the handle is resolved. Implementations only provide the terminal {@link
 * #prepare}, which supplies the {@link DurableCallable} running the invocation; the handle feeds it
 * to durable execution itself.
 *
 * <p>The short {@code submit} forms are inherited from {@link BaseSubagentSetup}: the base assigns
 * the missing ids deterministically from the executing task before the deferred handle is created.
 * The dropped-handle safety net is built in: the handle records itself in the base's per-task
 * registry, and a handle left unresolved when the action finishes fails the action.
 */
public abstract class BaseDeferredSubagentSetup extends BaseSubagentSetup {

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
     * Prepares one invocation and returns the {@link DurableCallable} running it: the stable
     * durable id, the callable running the off-mailbox part, and the optional recovery reconciler.
     * Both ids are already assigned; the durable id MUST be derived solely from the {@code
     * (sessionId, callId)} pair so it is reproducible after failover.
     *
     * <p>Called exactly once per invocation, when the deferred handle is first resolved, on the
     * mailbox thread. Implementations may therefore perform the mailbox-confined part of issuing
     * the request here (an internal sub-agent sends its call event); the returned callable's {@link
     * DurableCallable#call()} carries only the part that runs off the mailbox thread.
     *
     * <p>Implementations that recover an in-flight invocation after failover supply the reconciler
     * on the returned callable; the identity is the only handle that survives a restart.
     */
    protected abstract DurableCallable<Result> prepare(
            RunnerContext ctx, Object prompt, String sessionId, String callId);
}
