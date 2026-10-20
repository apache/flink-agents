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

import org.apache.flink.agents.runtime.operator.parallel.ActionTaskLifecycleException;
import org.apache.flink.agents.runtime.operator.parallel.ParallelExecutionContextLifecycle;
import org.apache.flink.agents.runtime.operator.parallel.ParallelExecutionLock;
import org.apache.flink.agents.runtime.operator.parallel.UncapturableException;

import javax.annotation.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Executor for Java actions that supports asynchronous execution (JDK 11 version; JDK 21+ uses a
 * multi-release variant backed by the Continuation API).
 *
 * <p>On the parallel path a {@link ParallelExecutionLock} and {@link
 * ParallelExecutionContextLifecycle} are wired in: async calls suspend the task context before
 * releasing the lock, then resume it after re-acquiring worker ownership. Blocking single calls run
 * inline; batches run on managed workers. Without a lock no lifecycle callbacks run.
 */
public class ContinuationActionExecutor {

    private final AsyncBatchExecutor batchExecutor;

    @Nullable private final ParallelExecutionLock parallelExecutionLock;
    @Nullable private final ParallelExecutionContextLifecycle contextLifecycle;

    /** Creates a new ContinuationActionExecutor. */
    public ContinuationActionExecutor(int numAsyncThreads) {
        this(numAsyncThreads, () -> {}, null, null);
    }

    /**
     * Creates a new ContinuationActionExecutor. On the JDK&lt;21 parallel MVP path both {@code
     * parallelExecutionLock} and {@code contextLifecycle} are supplied; otherwise they are {@code
     * null} and {@link #executeAsync} runs synchronously.
     *
     * <p>Batches use a separate managed pool so action workers waiting for their children cannot
     * exhaust the batch pool.
     */
    public ContinuationActionExecutor(
            int numAsyncThreads,
            Runnable threadCleanup,
            @Nullable ParallelExecutionLock parallelExecutionLock,
            @Nullable ParallelExecutionContextLifecycle contextLifecycle) {
        this.batchExecutor = new AsyncBatchExecutor(numAsyncThreads, threadCleanup);
        this.parallelExecutionLock = parallelExecutionLock;
        this.contextLifecycle = contextLifecycle;
    }

    /**
     * Executes the action inline on its calling thread. With the JDK &lt; 21 parallel engine, this
     * is an action worker; async calls release the shared execution lock while doing work.
     *
     * @param context the continuation context
     * @param action the action to execute
     * @return true if the action completed, false if it yielded (always true in JDK 11)
     */
    public boolean executeAction(ContinuationContext context, Runnable action) {
        action.run();
        return true;
    }

    /**
     * Executes the supplier inline, releasing the shared execution lock when running on the
     * parallel engine's action worker. Other actions and the mailbox can progress while this worker
     * executes the supplier. Without the parallel engine, execution is synchronous.
     *
     * @param context the continuation context
     * @param supplier the supplier to execute
     * @param <T> the result type
     * @return the result of the supplier
     */
    public <T> T executeAsync(ContinuationContext context, Supplier<T> supplier) throws Exception {
        if (parallelExecutionLock == null) {
            // No mailbox lock wired in: fall back to synchronous execution.
            return supplier.get();
        }

        return executeWithLockHandoff(context, supplier::get);
    }

    /**
     * Executes a batch concurrently on managed workers. The calling action waits without the shared
     * operator lock; its context is restored after worker ownership is re-acquired.
     *
     * @param context the continuation context
     * @param suppliers the suppliers to execute
     * @param timeout whole-batch timeout, or null/negative for no timeout
     * @param maxParallelism maximum in-flight calls in this batch
     * @param <T> the result type
     * @return outcomes in supplier order
     */
    public <T> BatchExecutionResult<T> executeAllAsync(
            ContinuationContext context,
            List<Callable<T>> suppliers,
            Duration timeout,
            int maxParallelism)
            throws Exception {
        if (parallelExecutionLock == null || suppliers.isEmpty()) {
            return batchExecutor.execute(suppliers, timeout, maxParallelism);
        }

        return executeWithLockHandoff(
                context, () -> batchExecutor.execute(suppliers, timeout, maxParallelism));
    }

    /**
     * Runs {@code supplier} with the execution lock handed off: the task context is suspended and
     * the lock released so the mailbox and other workers can progress, then worker ownership is
     * re-acquired at this task's priority and its context resumed before control returns to the
     * caller.
     *
     * <p>A suspend or resume failure is a framework fault, not a business result, so it surfaces as
     * an {@link ActionTaskLifecycleException} inside its {@link UncapturableException} shell and
     * never as the supplier's own outcome. A resume failure after a supplier failure keeps the
     * supplier failure as the suppressed cause.
     */
    private <T> T executeWithLockHandoff(ContinuationContext context, Callable<T> supplier)
            throws Exception {
        parallelExecutionLock.checkReentrant();
        try {
            contextLifecycle.suspend(context.getKey(), context.getActionTask());
        } catch (Throwable failure) {
            throw ActionTaskLifecycleException.suspendFailed(failure);
        }
        parallelExecutionLock.release();
        Throwable supplierFailure = null;
        try {
            return supplier.call();
        } catch (Throwable failure) {
            supplierFailure = failure;
            throw failure;
        } finally {
            reacquireWorkerOwnership(context);
            try {
                contextLifecycle.resume(context.getKey(), context.getActionTask());
            } catch (Throwable failure) {
                throw ActionTaskLifecycleException.resumeFailed(failure, supplierFailure);
            }
        }
    }

    /**
     * Re-acquires worker ownership at this task's priority. An interrupt flag left set by the call
     * (for example by code that re-interrupts after catching {@link InterruptedException}) must not
     * discard its outcome, so the flag is cleared for the wait and restored afterward. A new
     * interrupt during the wait, such as the coordinator's shutdown interrupts, still aborts it.
     */
    private void reacquireWorkerOwnership(ContinuationContext context) throws InterruptedException {
        boolean interrupted = Thread.interrupted();
        try {
            parallelExecutionLock.acquireByWorker(context.getRecordIndex(), context.getTaskIndex());
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Awaits a {@link CompletableFuture} by blocking the calling thread. On JDK 21+ the
     * multi-release variant yields the continuation instead, avoiding async-pool exhaustion (issue
     * #1213).
     */
    public <T> T awaitFuture(ContinuationContext context, CompletableFuture<T> future)
            throws Exception {
        return future.get();
    }

    /** Stops async work without waiting for worker exit cleanup. */
    public void shutdown() {
        batchExecutor.shutdown();
    }

    public void close() {
        batchExecutor.close();
    }

    /**
     * Returns whether continuation-based async execution is supported.
     *
     * @return true if Continuation API is available (JDK 21+), false otherwise
     */
    public static boolean isContinuationSupported() {
        return false;
    }

    /**
     * Whether an async wait can hand the shared execution lock off, so the operator can dispatch
     * other work while this worker blocks. This is the JDK&lt;21 parallel engine's substitute for
     * yielding a continuation: a caller waiting on a child graph keeps its worker but stops holding
     * the operator, which is what lets the child run and complete.
     */
    public boolean canHandOffExecutionLock() {
        return parallelExecutionLock != null;
    }
}
