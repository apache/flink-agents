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

import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.subagent.Result;
import org.apache.flink.agents.api.subagent.SubagentFuture;
import org.apache.flink.agents.api.subagent.SubagentFutures;
import org.apache.flink.agents.runtime.subagent.BaseAsyncSubagentSetup.RunStatus;

import javax.annotation.Nullable;

import java.util.concurrent.CancellationException;

/**
 * The sub side of an async-job invocation: the run was already started by the durable POST of
 * {@code submit}, so the handle only subscribes to it. {@link #isDone()} probes the status directly
 * (not durable); {@link #await()} waits through the durable await composition; {@link #cancel()}
 * propagates the cancellation through the setup's hook, and a cancelled {@code await} fails as a
 * {@link CancellationException}.
 *
 * <p>The handle records itself in the owning base's per-task registry, so dropping it without
 * collecting the outcome fails the action instead of silently losing the result.
 */
final class AsyncSubagentFuture extends SubagentFuture {

    private final BaseAsyncSubagentSetup setup;
    private final RunnerContext ctx;
    @Nullable private final PendingSubagentCallRegistry registry;

    private boolean consumed;
    private boolean cancelled;
    @Nullable private Result value;

    AsyncSubagentFuture(
            BaseAsyncSubagentSetup setup,
            RunnerContext ctx,
            String sessionId,
            String callId,
            @Nullable PendingSubagentCallRegistry registry) {
        super(sessionId, callId);
        this.setup = setup;
        this.ctx = ctx;
        this.registry = registry;
        if (registry != null) {
            registry.trackPendingSubagentCall(identity());
        }
    }

    /**
     * Probes the remote status directly; not durable, so a failover replay may probe a different
     * number of times than the original execution.
     */
    @Override
    public boolean isDone() {
        if (consumed || cancelled) {
            return true;
        }
        try {
            RunStatus probe = setup.queryStatus(getSessionId(), getCallId());
            return probe.getState() == RunStatus.State.COMPLETED
                    || probe.getState() == RunStatus.State.FAILED;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Waits for the run through the durable await composition. A cancelled handle fails as a {@link
     * CancellationException}.
     */
    @Override
    public Result await() throws Exception {
        if (cancelled) {
            throw new CancellationException(
                    "Sub-agent call cancelled: " + getSessionId() + "#" + getCallId());
        }
        if (!consumed) {
            value = ctx.durableExecuteAsync(setup.awaitResult(ctx, getSessionId(), getCallId()));
            consumed = true;
            if (registry != null) {
                registry.untrackPendingSubagentCall(identity());
            }
        }
        return value;
    }

    /**
     * Propagates the cancellation through the setup's hook; not durable, so a failover replay
     * (which creates a fresh handle) may propagate it again; remote cancellations must be
     * idempotent. A repeated cancel on the same handle and a cancel after the resolve are local
     * no-ops. A cancelled {@link #await()} fails as a {@link CancellationException}; a hook failure
     * propagates and fails the action.
     */
    @Override
    public void cancel() {
        if (consumed || cancelled) {
            return;
        }
        setup.cancelRequest(ctx, getSessionId(), getCallId());
        cancelled = true;
        if (registry != null) {
            registry.untrackPendingSubagentCall(identity());
        }
    }

    private String identity() {
        return getSessionId() + "#" + getCallId();
    }

    @Override
    public SubagentFutures combine(SubagentFuture... others) {
        return new SubagentFutureGroup(this, others);
    }
}
