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
package org.apache.flink.agents.runtime.context;

import org.apache.flink.agents.api.context.AsyncFuture;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.DurableFuture;
import org.apache.flink.agents.api.context.Outcome;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.utils.CancellationUtils;
import org.apache.flink.agents.runtime.async.ContinuationNativeCallable;
import org.apache.flink.agents.plan.exceptions.UncapturableException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Shared await behavior and local result state for runtime call handles. */
public abstract class AsyncFutureImpl<T> implements AsyncFuture<T> {
    private final RunnerContextImpl owner;
    private boolean resolving;
    private Outcome<T> outcome;

    protected AsyncFutureImpl(RunnerContextImpl owner) {
        this.owner = owner;
    }

    public final RunnerContextImpl getOwner() {
        return owner;
    }

    public final boolean isDone() {
        return outcome != null;
    }

    public final Outcome<T> getCompletedOutcome() {
        if (outcome == null) {
            throw new IllegalStateException("Async future has not been resolved");
        }
        return outcome;
    }

    public RunnerContextImpl getOwnerContext() {
        return owner;
    }

    protected final void complete(Outcome<T> value) {
        outcome = value;
    }

    protected boolean cacheResolutionFailures() {
        return true;
    }

    protected abstract T resolveValue() throws Exception;

    @Override
    public final T await() throws Exception {
        owner.checkMailboxThread();
        if (isDone()) {
            return value();
        }
        if (resolving) {
            throw new IllegalStateException("An async future cannot await itself recursively");
        }
        resolving = true;
        try {
            T result = resolveValue();
            if (!isDone()) {
                complete(Outcome.success(result));
            }
            return result;
        } catch (Exception e) {
            rethrowFatal(e);
            if (!isDone() && cacheResolutionFailures()) {
                complete(Outcome.failure(e));
            }
            throw e;
        } finally {
            resolving = false;
        }
    }

    protected final T value() throws Exception {
        if (outcome.isFailure()) {
            throw outcome.getError();
        }
        return outcome.getValue();
    }

    public static void rethrowFatal(Throwable failure) throws Exception {
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        if (CancellationUtils.isCancellation(failure)
                || UncapturableException.isUncapturable(failure)) {
            if (CancellationUtils.isInterruption(failure)) {
                Thread.currentThread().interrupt();
            }
            throw (Exception) failure;
        }
    }

    protected static <T> Outcome<T> getOutcome(CompletableFuture<T> future) throws Exception {
        // CompletableFuture.get() unwraps an explicitly completed CompletionException. Capture
        // the original exception as an Outcome before crossing that transport boundary.
        Outcome<T> result;
        try {
            result =
                    future.handle(
                                    (value, failure) -> {
                                        if (failure == null) {
                                            return Outcome.success(value);
                                        }
                                        if (failure instanceof Error) {
                                            throw (Error) failure;
                                        }
                                        return Outcome.<T>failure((Exception) failure);
                                    })
                            .get();
        } catch (ExecutionException e) {
            rethrowFatal(e.getCause());
            throw (Exception) e.getCause();
        }
        if (result.isFailure()) {
            rethrowFatal(result.getError());
        }
        return result;
    }

    public static <T> SchedulableFuture<T> requireSchedulable(
            AsyncFuture<T> future, RunnerContext owner) {
        if (!(future instanceof SchedulableFuture)) {
            throw new IllegalArgumentException("gather only accepts runtime-managed call futures");
        }
        SchedulableFuture<T> participant = (SchedulableFuture<T>) future;
        if (participant.getOwnerContext() != null && participant.getOwnerContext() != owner) {
            throw new IllegalArgumentException(
                    "An async future must be gathered by the context that created it");
        }
        return participant;
    }
}

/** Ordinary callable; durable subclasses reuse the same scheduling lifecycle. */
class SingleAsyncFuture<T> extends AsyncFutureImpl<T> implements SchedulableFuture<T> {
    private final Callable<T> callable;
    private Callable<T> execution;
    private DurableCallSlot<T> slot;
    private CompletableFuture<T> completion;
    private boolean prepared;
    private volatile boolean started;
    private boolean stopped;

    SingleAsyncFuture(RunnerContextImpl owner, Callable<T> callable) {
        super(owner);
        this.callable = callable;
    }

    DurableCallable<T> getDurableCallable() {
        return null;
    }

    @Override
    protected T resolveValue() throws Exception {
        return getOwner().resolveAsyncFuture(this);
    }

    @Override
    public Outcome<T> prepare(RunnerContextImpl context) throws Exception {
        if (prepared || isDone()) {
            return isDone() ? getCompletedOutcome() : null;
        }
        execution = callable;
        DurableCallable<T> durable = getDurableCallable();
        if (durable != null) {
            slot = new DurableCallSlot<>(getOwner(), durable.getId(), durable.getResultClass());
            Outcome<T> recorded = slot.prepare();
            if (recorded != null) {
                complete(recorded);
            }
            if (slot.isRecovering() && durable.reconciler() != null) {
                execution = durable.reconciler();
            }
        }
        prepared = true;
        return isDone() ? getCompletedOutcome() : null;
    }

    @Override
    public CompletableFuture<?> start() {
        if (isDone()) {
            return CompletableFuture.completedFuture(null);
        }
        synchronized (this) {
            stopped = false;
        }
        Callable<T> work =
                () -> {
                    synchronized (this) {
                        if (stopped) {
                            throw new CancellationException("Call stopped before execution");
                        }
                        started = true;
                    }
                    return execution.call();
                };
        if (getDurableCallable() instanceof ContinuationNativeCallable) {
            // These calls yield the caller's continuation and must not run on a pool worker.
            completion = new CompletableFuture<>();
            try {
                completion.complete(work.call());
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
        } else {
            completion = getOwner().submitAsync(work);
        }
        return completion;
    }

    @Override
    public Outcome<T> finish(TimeoutException timeout) throws Exception {
        if (isDone()) {
            return getCompletedOutcome();
        }
        if (timeout != null) {
            Outcome<T> result = Outcome.failure(timeout);
            if (slot != null && started) {
                slot.finish(result);
            }
            complete(result);
        } else {
            Outcome<T> result = getOutcome(completion);
            try {
                if (slot != null) {
                    slot.finish(result);
                }
            } catch (Exception e) {
                rethrowFatal(e);
                result = Outcome.failure(e);
            }
            complete(result);
        }
        return getCompletedOutcome();
    }

    @Override
    public void close() {}

    @Override
    public synchronized void preventStart() {
        stopped = true;
    }

    @Override
    public void cancel() {
        if (isDone()) {
            return;
        }
        preventStart();
        if (completion != null) {
            completion.cancel(true);
        }
    }
}

final class SingleDurableFuture<T> extends SingleAsyncFuture<T> implements DurableFuture<T> {
    private final DurableCallable<T> callable;

    SingleDurableFuture(RunnerContextImpl owner, DurableCallable<T> callable) {
        super(owner, callable::call);
        this.callable = callable;
    }

    @Override
    DurableCallable<T> getDurableCallable() {
        return callable;
    }
}

/** Batch wrapper; the scheduler sees only the common lifecycle, never individual call kinds. */
final class GatherAsyncFuture<T> extends AsyncFutureImpl<List<Outcome<T>>> {
    private final List<SchedulableFuture<T>> futures;

    GatherAsyncFuture(RunnerContextImpl owner, List<? extends AsyncFuture<T>> inputs) {
        super(owner);
        List<SchedulableFuture<T>> calls = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AsyncFuture<T> input : inputs) {
            SchedulableFuture<T> call = requireSchedulable(input, owner);
            try {
                if (!seen.add(call.getCallIdentity())) {
                    throw new IllegalArgumentException("gather rejects duplicate futures");
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
            calls.add(call);
        }
        futures = List.copyOf(calls);
    }

    @Override
    protected List<Outcome<T>> resolveValue() throws Exception {
        return getOwner().resolveAsyncBatch(futures);
    }
}
