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
package org.apache.flink.agents.runtime.async;

import org.apache.flink.agents.api.context.Outcome;

import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executor for Java actions that supports asynchronous execution using JDK 21+ Continuation API.
 *
 * <p>This version uses {@code jdk.internal.vm.Continuation} to implement true async execution.
 */
public class ContinuationActionExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(ContinuationActionExecutor.class);

    private static final ContinuationScope SCOPE = new ContinuationScope("FlinkAgentsAction");

    private final ExecutorService asyncExecutor;

    public ContinuationActionExecutor(int numAsyncThreads) {
        LOG.info("Initialize fixed thread pool for async task with {} threads", numAsyncThreads);
        this.asyncExecutor =
                Executors.newFixedThreadPool(numAsyncThreads, new AsyncExecutorThreadFactory());
    }

    /**
     * Executes the action inside a Continuation.
     *
     * <p>If the action calls executeAsync and yields, this method checks if the async Future is
     * done. If not done, returns false to indicate the action is not finished. If done, resumes the
     * Continuation.
     *
     * @param context the continuation context for this action
     * @param action the action to execute
     * @return true if the action completed, false if waiting for async execution
     */
    public boolean executeAction(ContinuationContext context, Runnable action) {
        // Wait while async work is still pending, unless the batch deadline elapsed — then resume
        // so executeAllAsync can finalize timed-out slots.
        if (context.hasPendingAsync() && !context.isBatchDeadlineElapsed()) {
            return false;
        }

        Future<?> pending = context.getPendingFuture();
        if (pending != null) {
            LOG.debug("Async task done...");
            context.setPendingFuture(null);
        }
        Future<?> pendingBatch = context.getPendingBatchFuture();
        if (pendingBatch != null) {
            LOG.debug("Async batch done...");
            context.setPendingBatchFuture(null);
        }

        Continuation currentContinuation = context.getCurrentContinuation();
        if (currentContinuation == null) {
            // First invocation: create new Continuation
            LOG.debug("Create new continuation.");
            currentContinuation = new Continuation(SCOPE, action);
            context.setCurrentContinuation(currentContinuation);
        }

        // Run the continuation. It returns either when the action completes or when it yields
        // inside executeAsync; in the latter case we return false and let the next executeAction
        // call observe pendingFuture completion and resume.
        currentContinuation.run();

        if (currentContinuation.isDone()) {
            // Continuation completed
            context.setCurrentContinuation(null);
            LOG.debug("Current continuation is done.");
            return true;
        } else {
            // Continuation yielded, waiting for async task
            // pendingFuture should have been set by executeAsync
            LOG.debug("Current continuation still running.");
            return false;
        }
    }

    /**
     * Asynchronously executes the provided supplier using Continuation.
     *
     * <p>This method submits the task to a thread pool and yields the Continuation. The next call
     * to executeAction will check if the Future is done and resume accordingly.
     *
     * @param context the continuation context for this action
     * @param supplier the supplier to execute
     * @param <T> the result type
     * @return the result of the supplier
     * @throws Exception if the async execution fails
     */
    @SuppressWarnings("unchecked")
    public <T> T executeAsync(ContinuationContext context, Supplier<T> supplier) throws Exception {
        // Clear previous state
        context.clearAsyncState();

        // Submit task to thread pool and store the Future
        Future<?> future =
                asyncExecutor.submit(
                        () -> {
                            try {
                                T result = supplier.get();
                                context.getAsyncResultRef().set(result);
                            } catch (Throwable t) {
                                context.getAsyncExceptionRef().set(t);
                            }
                        });

        // Store the future reference before yielding (volatile write ensures visibility)
        context.setPendingFuture(future);

        // Yield until the future is done
        while (!future.isDone()) {
            Continuation.yield(SCOPE);
        }

        // Check for exception from the async task
        Throwable exception = context.getAsyncExceptionRef().get();
        if (exception != null) {
            if (exception instanceof Exception) {
                throw (Exception) exception;
            } else if (exception instanceof Error) {
                throw (Error) exception;
            } else {
                throw new RuntimeException(exception);
            }
        }

        return (T) context.getAsyncResultRef().get();
    }

    /**
     * Executes all suppliers as one async batch and returns one {@link Outcome} per supplier.
     * Supplier failures are captured in their own outcome so one failed supplier does not abort the
     * whole batch.
     *
     * @param context the continuation context for this action
     * @param suppliers the suppliers to execute
     * @param timeout the timeout for the whole batch; null or non-positive means no timeout
     * @param <T> the result type
     * @return outcomes in supplier order
     */
    @SuppressWarnings("unchecked")
    public <T> BatchExecutionResult<T> executeAllAsync(
            ContinuationContext context,
            List<Callable<T>> suppliers,
            Duration timeout,
            int maxParallelism)
            throws Exception {
        context.clearAsyncState();
        if (suppliers.isEmpty()) {
            return new BatchExecutionResult<>(List.of(), new boolean[0]);
        }

        final int batchSize = suppliers.size();
        CompletableFuture<Outcome<T>>[] slots = new CompletableFuture[batchSize];
        AtomicIntegerArray started = new AtomicIntegerArray(batchSize);
        boolean[] counted = new boolean[batchSize];
        int completed = 0;
        int nextToSubmit = 0;
        int parallelismLimit = Math.min(Math.max(maxParallelism, 1), batchSize);

        long deadlineNanos = getDeadlineNanos(timeout);

        while (completed < batchSize) {
            if (System.nanoTime() >= deadlineNanos) {
                TimeoutException exception =
                        new TimeoutException(
                                "Async durable batch execution timed out after " + timeout);
                context.setPendingBatchFuture(null);
                return collectBatchOutcomesOnTimeout(slots, started, exception);
            }

            while (nextToSubmit < batchSize && countInFlight(slots, nextToSubmit) < parallelismLimit) {
                int index = nextToSubmit++;
                Callable<T> supplier = suppliers.get(index);
                slots[index] =
                        CompletableFuture.supplyAsync(
                                () -> {
                                    // Mark started only when the worker truly begins, so a task
                                    // queued in a saturated pool but never run stays re-executable
                                    // on recovery instead of being recorded as a timeout failure.
                                    started.set(index, 1);
                                    try {
                                        return Outcome.success(supplier.call());
                                    } catch (Exception e) {
                                        return Outcome.failure(e);
                                    }
                                }, asyncExecutor);
            }

            List<CompletableFuture<?>> inFlight = new ArrayList<>(parallelismLimit);
            for (int i = 0; i < nextToSubmit; i++) {
                if (slots[i].isDone()) {
                    if (!counted[i]) {
                        counted[i] = true;
                        completed++;
                    }
                } else {
                    inFlight.add(slots[i]);
                }
            }

            if (completed < batchSize) {
                if (inFlight.isEmpty()) {
                    continue;
                }

                CompletableFuture<Object> progressBarrier =
                        CompletableFuture.anyOf(
                                inFlight.toArray(new CompletableFuture<?>[0]));
                context.setPendingBatchFuture(progressBarrier, deadlineNanos);
                if (!progressBarrier.isDone()) {
                    Continuation.yield(SCOPE);
                }
                context.setPendingBatchFuture(null);
            }
        }

        context.setPendingBatchFuture(null);
        return collectBatchOutcomes(Arrays.asList(slots), started);
    }

    private static <T> int countInFlight(
            CompletableFuture<Outcome<T>>[] slots, int submittedCount) {
        int inFlight = 0;
        for (int i = 0; i < submittedCount; i++) {
            if (!slots[i].isDone()) {
                inFlight++;
            }
        }
        return inFlight;
    }


    /**
     * Collects per-slot outcomes after the batch barrier completes normally.
     *
     * <p>Each supplier already wraps success and failure into an {@link Outcome}, so {@code join()}
     * returns that outcome rather than throwing for ordinary tool exceptions.
     */
    private static <T> BatchExecutionResult<T> collectBatchOutcomes(
            List<CompletableFuture<Outcome<T>>> futures, AtomicIntegerArray started) {
        List<Outcome<T>> results = new ArrayList<>(futures.size());
        for (CompletableFuture<Outcome<T>> future : futures) {
            results.add(future.join());
        }
        return new BatchExecutionResult<>(results, toStartedFlags(started));
    }

    /**
     * Collects per-slot outcomes when the batch deadline elapses.
     *
     * <p>Completed slots keep their success or failure outcome. A slot whose worker never began
     * ({@code started == 0}) is reported as a timeout failure but flagged as not started, so the
     * caller leaves it pending for re-execution on recovery. Slots that started but are still
     * running are cancelled and finalized as timeout failures; {@code cancel(true)} is attempted
     * only for unfinished futures, and a future that completes between the check and cancel stays
     * non-cancelled and is collected as a normal outcome.
     */
    private static <T> BatchExecutionResult<T> collectBatchOutcomesOnTimeout(
            CompletableFuture<Outcome<T>>[] futures,
            AtomicIntegerArray started,
            TimeoutException timeoutException) {
        List<Outcome<T>> results = new ArrayList<>(futures.length);
        for (int i = 0; i < futures.length; i++) {
            CompletableFuture<Outcome<T>> future = futures[i];
            if (started.get(i) == 0 || future == null) {
                results.add(Outcome.failure(timeoutException));
                continue;
            }
            if (!future.isDone()) {
                future.cancel(true);
            }
            if (future.isDone() && !future.isCancelled()) {
                results.add(future.join());
            } else {
                results.add(Outcome.failure(timeoutException));
            }
        }
        return new BatchExecutionResult<>(results, toStartedFlags(started));
    }

    private static boolean[] toStartedFlags(AtomicIntegerArray started) {
        boolean[] flags = new boolean[started.length()];
        for (int i = 0; i < flags.length; i++) {
            flags[i] = started.get(i) == 1;
        }
        return flags;
    }

    private long getDeadlineNanos(Duration timeout) {
        return timeout == null || timeout.isZero() || timeout.isNegative()
                ? Long.MAX_VALUE
                : System.nanoTime() + timeout.toNanos();
    }

    public void close() {
        asyncExecutor.shutdownNow();
    }

    /**
     * Returns whether continuation-based async execution is supported.
     *
     * @return true (this is the JDK 21+ version)
     */
    public static boolean isContinuationSupported() {
        return true;
    }
}
