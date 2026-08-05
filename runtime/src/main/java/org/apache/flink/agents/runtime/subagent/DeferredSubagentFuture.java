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
import org.apache.flink.agents.api.subagent.SubagentFutures;

import javax.annotation.Nullable;

import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/**
 * Deferred handle: the request is prepared when the handle is resolved. Resolving prepares the
 * request through {@code preparedSupplier} (mailbox-confined, so it runs on the resolving thread)
 * and then waits through durable execution of the prepared {@link DurableCallable} itself.
 *
 * <p>The {@link PendingSubagentCallRegistry} of the owning base records the handle until it is
 * resolved, so a caller that drops the handle fails instead of silently skipping the invocation.
 *
 * <p>Cancelling a handle whose request has not been prepared yet discards the request: {@link
 * #prepare()} and {@link #await()} throw {@link CancellationException}, and nothing is sent for the
 * cancelled handle. The request was never prepared, so cancellation has an unambiguous meaning.
 */
public final class DeferredSubagentFuture extends SubagentFuture {

    private final RunnerContext ctx;
    @Nullable private final PendingSubagentCallRegistry registry;
    private final Supplier<DurableCallable<Result>> preparedSupplier;

    @Nullable private DurableCallable<Result> prepared;
    private boolean done;
    private boolean cancelled;
    @Nullable private Result value;

    public DeferredSubagentFuture(
            String sessionId,
            String callId,
            RunnerContext ctx,
            @Nullable PendingSubagentCallRegistry registry,
            Supplier<DurableCallable<Result>> preparedSupplier) {
        super(sessionId, callId);
        this.ctx = ctx;
        this.registry = registry;
        this.preparedSupplier = preparedSupplier;
        if (registry != null) {
            registry.trackPendingSubagentCall(identity());
        }
    }

    /** Prepares the request if it has not been prepared yet; must run on the mailbox thread. */
    DurableCallable<Result> prepare() {
        if (cancelled) {
            throw new CancellationException("Sub-agent call cancelled: " + identity());
        }
        if (prepared == null) {
            prepared = preparedSupplier.get();
        }
        return prepared;
    }

    /**
     * Runs the prepared request through durable execution and records the outcome. Mailbox releases
     * happen inside the durable execution itself.
     */
    void execute() {
        try {
            complete(ctx.durableExecuteAsync(prepare()));
        } catch (Exception e) {
            // Failures converge into a failed Result; the handle has no separate
            // exceptional completion.
            complete(Result.error(e));
        }
    }

    /**
     * Cancels before the request is prepared: the request is discarded and resolving the handle
     * fails. An already resolved handle ignores the cancellation request.
     */
    @Override
    public void cancel() {
        if (done) {
            return;
        }
        cancelled = true;
        if (registry != null) {
            registry.untrackPendingSubagentCall(identity());
        }
    }

    private String identity() {
        return getSessionId() + "#" + getCallId();
    }

    /** Records the outcome produced by a batched wait. */
    private void complete(Result outcome) {
        this.value = outcome;
        this.done = true;
        if (registry != null) {
            registry.untrackPendingSubagentCall(identity());
        }
    }

    @Override
    public boolean isDone() {
        return done || cancelled;
    }

    @Override
    public Result await() throws Exception {
        if (cancelled) {
            throw new CancellationException("Sub-agent call cancelled: " + identity());
        }
        if (!done) {
            execute();
        }
        return value;
    }

    @Override
    public SubagentFutures combine(SubagentFuture... others) {
        return new SubagentFutureGroup(this, others);
    }
}
