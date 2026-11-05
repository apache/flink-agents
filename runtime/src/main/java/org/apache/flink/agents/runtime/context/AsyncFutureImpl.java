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
import org.apache.flink.agents.plan.exceptions.UncapturableException;
import org.apache.flink.agents.plan.utils.CancellationUtils;
import org.apache.flink.util.function.FunctionWithException;

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

    /**
     * Maps a call result without adding a durable boundary, preserving runtime batch scheduling.
     */
    public static <S, T> AsyncFuture<T> map(
            RunnerContext owner,
            AsyncFuture<S> future,
            FunctionWithException<S, T, Exception> mapper)
            throws Exception {
        if (!(owner instanceof RunnerContextImpl)) {
            // Other context implementations manage their own waiting and gather behavior.
            return () -> mapper.apply(future.await());
        }
        RunnerContextImpl runtime = (RunnerContextImpl) owner;
        SchedulableFuture<S> source = requireSchedulable(future, runtime);
        return new MappedAsyncFuture<>(runtime, source, mapper);
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

/** Single-call execution, completion collection, and cancellation shared by runtime futures. */
class SingleAsyncFuture<T> extends AsyncFutureImpl<T> implements SchedulableFuture<T> {
    private Callable<T> execution;
    private CompletableFuture<T> completion;
    private volatile boolean started;
    private boolean stopped;

    SingleAsyncFuture(RunnerContextImpl owner, Callable<T> callable) {
        super(owner);
        execution = callable;
    }

    SingleAsyncFuture(RunnerContextImpl owner) {
        super(owner);
    }

    protected final void setExecution(Callable<T> execution) {
        this.execution = execution;
    }

    protected final boolean hasStarted() {
        return started;
    }

    @Override
    protected T resolveValue() throws Exception {
        return getOwner().resolveAsyncFuture(this);
    }

    @Override
    public Outcome<T> prepare(RunnerContextImpl context) throws Exception {
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
        completion = getOwner().submitAsync(work);
        return completion;
    }

    @Override
    public Outcome<T> finish(TimeoutException timeout) throws Exception {
        if (!isDone()) {
            complete(collectOutcome(timeout));
        }
        return getCompletedOutcome();
    }

    /** Reads the outcome without marking the future complete. */
    protected final Outcome<T> collectOutcome(TimeoutException timeout) throws Exception {
        return timeout != null ? Outcome.failure(timeout) : getOutcome(completion);
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
    private final Callable<DurableCallable<T>> preparation;
    private final String id;
    private final Class<T> resultClass;
    private final DurableCallSlot<T> slot;
    private boolean prepared;

    SingleDurableFuture(
            RunnerContextImpl owner,
            String id,
            Class<T> resultClass,
            Callable<DurableCallable<T>> preparation) {
        super(owner);
        this.id = id;
        this.resultClass = resultClass;
        this.preparation = preparation;
        this.slot = new DurableCallSlot<>(owner, id, resultClass);
    }

    @Override
    public Outcome<T> prepare(RunnerContextImpl context) throws Exception {
        if (prepared || isDone()) {
            return isDone() ? getCompletedOutcome() : null;
        }
        Outcome<T> recorded = slot.prepare();
        if (recorded != null) {
            complete(recorded);
        } else {
            try {
                // User preparation runs on the mailbox only after its slot has been reserved.
                setExecution(prepareExecution());
            } catch (Exception e) {
                rethrowFatal(e);
                Outcome<T> failure = Outcome.failure(e);
                slot.finish(failure);
                complete(failure);
            }
        }
        prepared = true;
        return isDone() ? getCompletedOutcome() : null;
    }

    @Override
    public Outcome<T> finish(TimeoutException timeout) throws Exception {
        if (isDone()) {
            return getCompletedOutcome();
        }
        Outcome<T> result = collectOutcome(timeout);
        if (timeout != null) {
            if (hasStarted()) {
                slot.finish(result);
            }
        } else {
            try {
                slot.finish(result);
            } catch (Exception e) {
                rethrowFatal(e);
                result = Outcome.failure(e);
            }
        }
        complete(result);
        return getCompletedOutcome();
    }

    private Callable<T> prepareExecution() throws Exception {
        DurableCallable<T> prepared = preparation.call();
        if (!id.equals(prepared.getId()) || resultClass != prepared.getResultClass()) {
            throw new IllegalArgumentException(
                    "Prepared callable must retain the reserved durable id and result class: "
                            + id);
        }
        Callable<T> reconciler = slot.isRecovering() ? prepared.reconciler() : null;
        return reconciler != null ? reconciler : prepared::call;
    }
}

/** A presentation view; its source alone owns execution and durable recording. */
final class MappedAsyncFuture<S, T> extends AsyncFutureImpl<T> implements SchedulableFuture<T> {
    private final SchedulableFuture<S> source;
    private final FunctionWithException<S, T, Exception> mapper;

    MappedAsyncFuture(
            RunnerContextImpl owner,
            SchedulableFuture<S> source,
            FunctionWithException<S, T, Exception> mapper) {
        super(owner);
        this.source = source;
        this.mapper = mapper;
    }

    @Override
    protected T resolveValue() throws Exception {
        return getOwner().resolveAsyncFuture(this);
    }

    @Override
    public Object getCallIdentity() throws Exception {
        return source.getCallIdentity();
    }

    @Override
    public void validate(RunnerContextImpl context) throws Exception {
        source.validate(context);
    }

    @Override
    public Outcome<T> prepare(RunnerContextImpl context) throws Exception {
        if (!isDone()) {
            Outcome<S> saved = source.prepare(context);
            if (saved != null) {
                mapOutcome(saved);
            }
        }
        return isDone() ? getCompletedOutcome() : null;
    }

    @Override
    public CompletableFuture<?> start() throws Exception {
        return isDone() ? CompletableFuture.completedFuture(null) : source.start();
    }

    @Override
    public Outcome<T> finish(TimeoutException timeout) throws Exception {
        if (!isDone()) {
            mapOutcome(source.finish(timeout));
        }
        return getCompletedOutcome();
    }

    @Override
    public void preventStart() {
        source.preventStart();
    }

    @Override
    public void cancel() {
        source.cancel();
    }

    @Override
    public void close() {
        source.close();
    }

    @Override
    protected boolean cacheResolutionFailures() {
        return false;
    }

    private void mapOutcome(Outcome<S> outcome) throws Exception {
        if (outcome.isFailure()) {
            complete(Outcome.failure(outcome.getError()));
            return;
        }
        try {
            complete(Outcome.success(mapper.apply(outcome.getValue())));
        } catch (Exception e) {
            rethrowFatal(e);
            complete(Outcome.failure(e));
        }
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
