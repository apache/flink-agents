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

import org.apache.flink.agents.runtime.operator.parallel.ParallelExecutionContextLifecycle;
import org.apache.flink.agents.runtime.operator.parallel.ParallelExecutionLock;

import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Executor for Java actions that supports asynchronous execution using JDK 21+ Continuation API.
 *
 * <p>This version uses {@code jdk.internal.vm.Continuation} to implement true async execution.
 * Queued async work runs in ascending (recordIndex, taskIndex) order of its {@link
 * ContinuationContext}, then in submission order, so the follow-up calls of earlier input records
 * don't wait behind the first calls of later ones.
 */
public class ContinuationActionExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(ContinuationActionExecutor.class);

    private static final ContinuationScope SCOPE = new ContinuationScope("FlinkAgentsAction");

    private final ThreadPoolExecutor asyncExecutor;
    private final AsyncExecutorThreadFactory asyncThreadFactory;
    private final AtomicLong submissionSequence = new AtomicLong();
    private final Set<CompletableFuture<?>> submissions = ConcurrentHashMap.newKeySet();

    public ContinuationActionExecutor(int numAsyncThreads) {
        this(numAsyncThreads, () -> {}, null, null);
    }

    /**
     * Constructor kept signature-compatible with the base (JDK&lt;21) executor for multi-release
     * uniformity. Continuation execution owns its own async thread pool and never uses the mailbox
     * lock or context lifecycle, so both are ignored here.
     */
    public ContinuationActionExecutor(
            int numAsyncThreads,
            Runnable threadCleanup,
            ParallelExecutionLock parallelExecutionLock,
            ParallelExecutionContextLifecycle contextLifecycle) {
        LOG.info("Initialize fixed thread pool for async task with {} threads", numAsyncThreads);
        this.asyncThreadFactory = new AsyncExecutorThreadFactory(threadCleanup);
        this.asyncExecutor =
                new ThreadPoolExecutor(
                        numAsyncThreads,
                        numAsyncThreads,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new PriorityBlockingQueue<>(),
                        asyncThreadFactory);
    }

    /**
     * Executes the action inside a Continuation.
     *
     * <p>If the action calls executeAsync or awaitFuture and yields, this method checks if the
     * Future is done. If not done, returns false to indicate the action is not finished. If done,
     * resumes the Continuation.
     *
     * @param context the continuation context for this action
     * @param action the action to execute
     * @return true if the action completed, false if waiting for async execution
     */
    public boolean executeAction(ContinuationContext context, Runnable action) {
        // The runner completes the pending signal when work finishes or its timeout expires.
        if (context.hasPendingAsync()) {
            return false;
        }

        Future<?> pending = context.getPendingFuture();
        if (pending != null) {
            LOG.debug("Async task done...");
            context.setPendingFuture(null);
        }

        Continuation currentContinuation = context.getCurrentContinuation();
        if (currentContinuation == null) {
            // First invocation: create new Continuation
            LOG.debug("Create new continuation.");
            currentContinuation = new Continuation(SCOPE, action);
            context.setCurrentContinuation(currentContinuation);
        }

        // Run the continuation. It returns either when the action completes or when it yields
        // inside executeAsync or awaitFuture; return false and let the next executeAction
        // call observe pendingFuture completion and resume.
        currentContinuation.run();

        if (currentContinuation.isDone()) {
            // Continuation completed
            context.setCurrentContinuation(null);
            LOG.debug("Current continuation is done.");
            return true;
        } else {
            // Continuation yielded, waiting for async task
            // pendingFuture was set by executeAsync or awaitFuture.
            LOG.debug("Current continuation still running.");
            return false;
        }
    }

    public <T> CompletableFuture<T> submitAsync(ContinuationContext context, Callable<T> callable) {
        CompletableFuture<T> result =
                AsyncBatchExecutor.submit(
                        context == null ? asyncExecutor : prioritizedExecutor(context), callable);
        submissions.add(result);
        result.whenComplete((value, error) -> submissions.remove(result));
        return result;
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
        FutureTask<?> future =
                new FutureTask<>(
                        () -> {
                            try {
                                T result = supplier.get();
                                context.getAsyncResultRef().set(result);
                            } catch (Throwable t) {
                                context.getAsyncExceptionRef().set(t);
                            }
                        },
                        null);
        prioritizedExecutor(context).execute(future);

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

    /** Returns an executor that queues work at the priority of {@code context}. */
    private Executor prioritizedExecutor(ContinuationContext context) {
        long recordIndex = context.getRecordIndex();
        long taskIndex = context.getTaskIndex();
        return command ->
                asyncExecutor.execute(
                        new PrioritizedTask(
                                command,
                                recordIndex,
                                taskIndex,
                                submissionSequence.getAndIncrement()));
    }

    /** Async work ordered by (recordIndex, taskIndex), then by submission. */
    private static final class PrioritizedTask implements Runnable, Comparable<PrioritizedTask> {
        private final Runnable command;
        private final long recordIndex;
        private final long taskIndex;
        private final long sequence;

        private PrioritizedTask(Runnable command, long recordIndex, long taskIndex, long sequence) {
            this.command = command;
            this.recordIndex = recordIndex;
            this.taskIndex = taskIndex;
            this.sequence = sequence;
        }

        @Override
        public void run() {
            command.run();
        }

        @Override
        public int compareTo(PrioritizedTask other) {
            int byRecord = Long.compare(recordIndex, other.recordIndex);
            if (byRecord != 0) {
                return byRecord;
            }
            int byTask = Long.compare(taskIndex, other.taskIndex);
            return byTask != 0 ? byTask : Long.compare(sequence, other.sequence);
        }
    }

    /**
     * Yields the current continuation until {@code future} completes, without occupying an
     * async-pool worker. The caller must be running inside a continuation on the mailbox thread;
     * this method yields that continuation and lets {@link #executeAction} resume it once the
     * future is done.
     *
     * <p>This is the non-blocking alternative to submitting a blocking {@code future.get()} to the
     * pool via {@link #executeAsync}. It prevents the deadlock where a parent awaiting a child
     * holds the only pool worker the child needs to make progress (issue #1213).
     */
    public <T> T awaitFuture(ContinuationContext context, CompletableFuture<T> future)
            throws Exception {
        context.clearAsyncState();
        // Bridge to Future<?> so executeAction's hasPendingAsync gate drives the resumption.
        context.setPendingFuture(future.thenApply(v -> null));
        // Re-checked before every yield, and resumption is polled by the operator's mail loop
        // rather than signalled by a completion callback, so a future already done here — or one
        // completing between the two lines above — simply never suspends.
        while (!future.isDone()) {
            Continuation.yield(SCOPE);
        }
        return joinCompletableFuture(future);
    }

    private static <T> T joinCompletableFuture(CompletableFuture<T> future) throws Exception {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            } else if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }

    /** Stops async work without waiting for worker exit cleanup. */
    public void shutdown() {
        submissions.forEach(future -> future.cancel(true));
        asyncExecutor.shutdownNow();
    }

    public void close() {
        shutdown();
        boolean interrupted = false;
        try {
            while (!asyncExecutor.isTerminated()) {
                try {
                    asyncExecutor.awaitTermination(1, TimeUnit.DAYS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        asyncThreadFactory.awaitThreadExit();
    }

    /**
     * Returns whether continuation-based async execution is supported.
     *
     * @return true (this is the JDK 21+ version)
     */
    public static boolean isContinuationSupported() {
        return true;
    }

    /**
     * Always false here: continuation execution releases the operator by yielding the caller's
     * continuation, never by handing off a parallel execution lock it does not use.
     */
    public boolean canHandOffExecutionLock() {
        return false;
    }
}
