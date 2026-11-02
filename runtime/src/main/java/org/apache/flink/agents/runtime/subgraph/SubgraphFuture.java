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
package org.apache.flink.agents.runtime.subgraph;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.context.DurableFuture;
import org.apache.flink.agents.api.context.Outcome;
import org.apache.flink.agents.plan.exceptions.UncapturableException;
import org.apache.flink.agents.plan.utils.CancellationUtils;
import org.apache.flink.agents.runtime.context.AsyncFutureImpl;
import org.apache.flink.agents.runtime.context.DurableCallSlot;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.context.SchedulableFuture;
import org.apache.flink.agents.runtime.memory.EventAttachmentUtils;
import org.apache.flink.util.function.FunctionWithException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Shared graph execution for Chat and internal subagents; durable state stores the raw response.
 */
public final class SubgraphFuture<R, T> extends AsyncFutureImpl<T>
        implements DurableFuture<T>, SchedulableFuture<T> {
    private final Supplier<SubgraphInvocation<R>> factory;
    private final Object input;
    private final FunctionWithException<R, T, Exception> mapper;
    private SubgraphInvocation<R> invocation;
    private DurableCallSlot<R> slot;
    private boolean started;

    public SubgraphFuture(
            RunnerContextImpl owner,
            Supplier<SubgraphInvocation<R>> factory,
            Object input,
            FunctionWithException<R, T, Exception> mapper) {
        super(owner);
        this.factory = factory;
        this.input = input;
        this.mapper = mapper;
    }

    public SubgraphInvocation<R> getInvocation() {
        return invocation;
    }

    @Override
    protected T resolveValue() throws Exception {
        return getOwner().resolveAsyncFuture(this);
    }

    @Override
    public void validate(RunnerContextImpl context) throws Exception {
        try {
            SchedulableFuture.super.validate(context);
            SubgraphExecution.requireReleasableOperator(getOwner());
        } catch (Exception failure) {
            throw frameworkFault(failure);
        }
    }

    @Override
    public Outcome<T> prepare(RunnerContextImpl context) throws Exception {
        try {
            if (slot != null || isDone()) {
                return isDone() ? getCompletedOutcome() : null;
            }
            invocation = factory.get();
            DurableCallSlot<R> prepared =
                    new DurableCallSlot<>(
                            getOwner(), invocation.getDurableSlotKey(), invocation.resultClass());
            Outcome<R> saved = prepared.prepare();
            slot = prepared;
            if (saved != null) {
                convert(saved);
            }
            return isDone() ? getCompletedOutcome() : null;
        } catch (Exception failure) {
            throw frameworkFault(failure);
        }
    }

    @Override
    public CompletableFuture<?> start() throws Exception {
        try {
            if (isDone()) {
                return CompletableFuture.completedFuture(null);
            }
            if (started) {
                return invocation.completionFuture();
            }
            getOwner().getSubgraphCalls().register(invocation);
            Event delegate = invocation.inputEvent(input);
            EventAttachmentUtils.storeEventAttachments(delegate, getOwner());
            getOwner().addPendingEvent(new SubgraphEvent(invocation.getId(), delegate, true));
            started = true;
            getOwner().dispatchSubgraphBootstrap();
            return invocation.completionFuture();
        } catch (Exception failure) {
            throw frameworkFault(failure);
        }
    }

    @Override
    public Outcome<T> finish(TimeoutException timeout) throws Exception {
        if (isDone()) {
            return getCompletedOutcome();
        }
        if (timeout != null) {
            complete(Outcome.failure(timeout));
            return getCompletedOutcome();
        }
        Outcome<R> result;
        try {
            result = getOutcome(invocation.completionFuture());
            if (result.isFailure()) {
                if (!invocation.isRecordedActionFailure(result.getError())
                        || !invocation.capturesActionFailure(result.getError())) {
                    throw result.getError();
                }
                result = Outcome.success(invocation.captureActionFailure(result.getError()));
            }
            slot.finish(result);
        } catch (Exception failure) {
            throw frameworkFault(failure);
        }
        convert(result);
        return getCompletedOutcome();
    }

    private static Exception frameworkFault(Exception failure) {
        return CancellationUtils.isCancellation(failure)
                ? failure
                : UncapturableException.wrap(failure);
    }

    private void convert(Outcome<R> result) throws Exception {
        if (result.isFailure()) {
            complete(Outcome.failure(result.getError()));
            return;
        }
        try {
            complete(Outcome.success(mapper.apply(result.getValue())));
        } catch (Exception e) {
            rethrowFatal(e);
            complete(Outcome.failure(e));
        }
    }

    @Override
    public void cancel() {
        if (!isDone() && invocation != null) {
            invocation.cancel();
        }
    }

    @Override
    public void close() {
        if (started && !invocation.capturesActionFailures()) {
            getOwner().getSubgraphCalls().remove(invocation.getId());
        }
    }

    @Override
    protected boolean cacheResolutionFailures() {
        return false;
    }
}
