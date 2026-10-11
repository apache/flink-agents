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
import org.apache.flink.agents.plan.utils.CancellationUtils;
import org.apache.flink.util.Preconditions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/** Runtime-owned implementation of a deferred asynchronous call handle. */
abstract class AsyncFutureImpl<T> implements AsyncFuture<T> {
    private final RunnerContextImpl owner;
    private boolean resolving;
    private boolean done;
    private T value;
    private Exception error;

    AsyncFutureImpl(RunnerContextImpl owner) {
        this.owner = owner;
    }

    final RunnerContextImpl getOwner() {
        return owner;
    }

    final boolean isDone() {
        return done;
    }

    final Outcome<T> getCompletedOutcome() {
        if (!done) {
            throw new IllegalStateException("Async future has not been resolved");
        }
        return error == null ? Outcome.success(value) : Outcome.failure(error);
    }

    @Override
    public final T await() throws Exception {
        if (done) {
            return completedValue();
        }
        if (resolving) {
            throw new IllegalStateException("An async future cannot await itself recursively");
        }

        resolving = true;
        try {
            value = resolveValue();
            done = true;
            return value;
        } catch (Exception e) {
            if (CancellationUtils.isCancellation(e)) {
                if (CancellationUtils.isInterruption(e)) {
                    Thread.currentThread().interrupt();
                }
                throw e;
            }
            error = e;
            done = true;
            throw e;
        } finally {
            resolving = false;
        }
    }

    final void complete(Outcome<T> outcome) {
        if (done) {
            throw new IllegalStateException("Async future has already been resolved");
        }
        value = outcome.getValue();
        error = outcome.getError();
        done = true;
    }

    private T completedValue() throws Exception {
        if (error != null) {
            throw error;
        }
        return value;
    }

    abstract T resolveValue() throws Exception;
}

/** Deferred handle for one ordinary callable. */
class SingleAsyncFuture<T> extends AsyncFutureImpl<T> {
    private final Callable<T> callable;

    SingleAsyncFuture(RunnerContextImpl owner, Callable<T> callable) {
        super(owner);
        this.callable = callable;
    }

    Callable<T> getCallable() {
        return callable;
    }

    DurableCallable<T> getDurableCallable() {
        return null;
    }

    @Override
    T resolveValue() throws Exception {
        return getOwner().resolveAsync(callable);
    }
}

/** Deferred handle with the additional durable execution contract. */
final class SingleDurableFuture<T> extends SingleAsyncFuture<T> implements DurableFuture<T> {
    private final DurableCallable<T> durableCallable;

    SingleDurableFuture(RunnerContextImpl owner, DurableCallable<T> callable) {
        super(owner, callable::call);
        this.durableCallable = callable;
    }

    @Override
    DurableCallable<T> getDurableCallable() {
        return durableCallable;
    }

    @Override
    T resolveValue() throws Exception {
        return getOwner().resolveDurableAsync(durableCallable);
    }
}

/** Deferred handle for a batch of ordinary and/or durable call handles. */
final class GatherAsyncFuture<T> extends AsyncFutureImpl<List<Outcome<T>>> {
    private final List<SingleAsyncFuture<T>> futures;

    GatherAsyncFuture(RunnerContextImpl owner, List<? extends AsyncFuture<T>> asyncFutures) {
        super(owner);
        Preconditions.checkNotNull(asyncFutures, "futures must not be null");

        List<SingleAsyncFuture<T>> singleFutures = new ArrayList<>(asyncFutures.size());
        Set<AsyncFuture<T>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AsyncFuture<T> future : asyncFutures) {
            Preconditions.checkNotNull(future, "future must not be null");
            if (!(future instanceof SingleAsyncFuture)) {
                throw new IllegalArgumentException(
                        "gather only accepts futures returned by executeAsync or durableExecuteAsync");
            }
            SingleAsyncFuture<?> internalFuture = (SingleAsyncFuture<?>) future;
            if (internalFuture.getOwner() != owner) {
                throw new IllegalArgumentException(
                        "All async futures passed to gather must be created by this runner context");
            }
            if (!seen.add(future)) {
                throw new IllegalArgumentException(
                        "The same async future cannot appear more than once in gather");
            }
            @SuppressWarnings("unchecked")
            SingleAsyncFuture<T> singleFuture = (SingleAsyncFuture<T>) internalFuture;
            singleFutures.add(singleFuture);
        }
        this.futures = List.copyOf(singleFutures);
    }

    @Override
    List<Outcome<T>> resolveValue() throws Exception {
        List<Outcome<T>> outcomes = new ArrayList<>(Collections.nCopies(futures.size(), null));
        List<SingleAsyncFuture<T>> unresolvedFutures = new ArrayList<>();
        List<Integer> unresolvedIndexes = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            SingleAsyncFuture<T> future = futures.get(i);
            if (future.isDone()) {
                outcomes.set(i, future.getCompletedOutcome());
            } else {
                unresolvedFutures.add(future);
                unresolvedIndexes.add(i);
            }
        }

        if (!unresolvedFutures.isEmpty()) {
            List<Outcome<T>> unresolvedOutcomes = getOwner().resolveAsyncBatch(unresolvedFutures);
            for (int i = 0; i < unresolvedFutures.size(); i++) {
                Outcome<T> outcome = unresolvedOutcomes.get(i);
                unresolvedFutures.get(i).complete(outcome);
                outcomes.set(unresolvedIndexes.get(i), outcome);
            }
        }
        return outcomes;
    }
}
