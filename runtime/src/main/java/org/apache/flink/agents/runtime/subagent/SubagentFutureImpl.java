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

import org.apache.flink.agents.api.context.AsyncFuture;
import org.apache.flink.agents.api.context.Outcome;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.subagent.SubagentFuture;
import org.apache.flink.agents.api.subagent.SubagentFutures;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.runtime.context.AsyncFutureImpl;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.context.SchedulableFuture;

import javax.annotation.Nullable;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Subagent identity/management facade; callable and graph execution share existing futures. */
public class SubagentFutureImpl extends SubagentFuture
        implements SchedulableFuture<SubagentResult> {
    private final RunnerContext context;
    private final Supplier<AsyncFuture<SubagentResult>> factory;
    @Nullable private final PendingSubagentCallRegistry registry;
    private final BooleanSupplier probe;
    private final Runnable remoteCancel;
    private AsyncFuture<SubagentResult> delegate;
    private boolean released;
    private boolean cancelled;
    private boolean resolved;
    private boolean remoteCancelled;

    private void cancelRemote() {
        if (!remoteCancelled) {
            remoteCancelled = true;
            remoteCancel.run();
        }
    }

    public SubagentFutureImpl(
            String sessionId,
            String callId,
            RunnerContext context,
            Supplier<AsyncFuture<SubagentResult>> factory,
            @Nullable PendingSubagentCallRegistry registry,
            BooleanSupplier probe,
            Runnable remoteCancel) {
        super(sessionId, callId);
        this.context = context;
        this.factory = factory;
        this.registry = registry;
        this.probe = probe;
        this.remoteCancel = remoteCancel;
        if (registry != null) {
            registry.trackPendingSubagentCall(identity());
        }
    }

    @Override
    public RunnerContext getOwnerContext() {
        return context;
    }

    private String identity() {
        return getSessionId() + "#" + getCallId();
    }

    private AsyncFuture<SubagentResult> delegate() {
        if (cancelled) {
            throw new CancellationException("Sub-agent call cancelled: " + identity());
        }
        if (delegate == null) {
            delegate = factory.get();
        }
        return delegate;
    }

    private SchedulableFuture<SubagentResult> schedulable() throws Exception {
        try {
            if (delegate == null) {
                delegate();
            }
            return AsyncFutureImpl.requireSchedulable(delegate, context);
        } catch (Exception | Error failure) {
            close();
            throw failure;
        }
    }

    @Override
    public Object getCallIdentity() throws Exception {
        return schedulable().getCallIdentity();
    }

    @Override
    public void validate(RunnerContextImpl owner) throws Exception {
        SchedulableFuture.super.validate(owner);
        if (cancelled) {
            throw new CancellationException("Sub-agent call cancelled: " + identity());
        }
        schedulable().validate(owner);
    }

    @Override
    public Outcome<SubagentResult> prepare(RunnerContextImpl owner) throws Exception {
        Outcome<SubagentResult> result = schedulable().prepare(owner);
        resolved = result != null;
        return result;
    }

    @Override
    public CompletableFuture<?> start() throws Exception {
        return schedulable().start();
    }

    @Override
    public Outcome<SubagentResult> finish(TimeoutException timeout) throws Exception {
        Outcome<SubagentResult> result = schedulable().finish(timeout);
        resolved = true;
        return result;
    }

    @Override
    public void preventStart() {
        if (delegate instanceof SchedulableFuture) {
            ((SchedulableFuture<?>) delegate).preventStart();
        }
    }

    @Override
    public boolean isDone() {
        return cancelled || released || resolved || probe.getAsBoolean();
    }

    @Override
    public SubagentResult await() throws Exception {
        try {
            SubagentResult result = resolved ? delegate.await() : delegate().await();
            resolved = true;
            return result;
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        if (released) {
            return;
        }
        released = true;
        try {
            if (delegate instanceof SchedulableFuture) {
                ((SchedulableFuture<?>) delegate).close();
            }
        } finally {
            if (registry != null) {
                registry.untrackPendingSubagentCall(identity());
            }
        }
    }

    @Override
    public void cancel() {
        if (cancelled || released || resolved) {
            return;
        }
        cancelled = true;
        try {
            if (delegate instanceof SchedulableFuture) {
                ((SchedulableFuture<?>) delegate).cancel();
            }
            cancelRemote();
        } finally {
            close();
        }
    }

    @Override
    public SubagentFutures combine(SubagentFuture... others) {
        return new SubagentFutureGroup(this, others);
    }
}
