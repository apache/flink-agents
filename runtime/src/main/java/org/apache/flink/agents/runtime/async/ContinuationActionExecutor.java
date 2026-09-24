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
import org.apache.flink.agents.runtime.operator.parallel.ParallelExecutionContextRestorer;
import org.apache.flink.agents.runtime.operator.parallel.ParallelExecutionLock;

import javax.annotation.Nullable;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Executor for Java actions that supports asynchronous execution (JDK 11 version; JDK 21+ uses a
 * multi-release variant backed by the Continuation API).
 *
 * <p>On the parallel path a {@link ParallelExecutionLock} and {@link
 * ParallelExecutionContextRestorer} are wired in: {@link #executeAsync} releases the lock while the
 * blocking work runs inline, then re-acquires worker ownership and restores the runner context.
 * Without a lock it runs synchronously.
 */
public class ContinuationActionExecutor {

    @Nullable private final ParallelExecutionLock parallelExecutionLock;
    @Nullable private final ParallelExecutionContextRestorer contextRestorer;

    /** Creates a new ContinuationActionExecutor. */
    public ContinuationActionExecutor(int numAsyncThreads) {
        this(numAsyncThreads, () -> {}, null, null);
    }

    /**
     * Creates a new ContinuationActionExecutor. On the JDK&lt;21 parallel MVP path both {@code
     * parallelExecutionLock} and {@code contextRestorer} are supplied; otherwise they are {@code
     * null} and {@link #executeAsync} runs synchronously.
     *
     * <p>JDK 11 fallback has no worker threads, so the cleanup hook is never needed.
     */
    public ContinuationActionExecutor(
            int numAsyncThreads,
            Runnable threadCleanup,
            @Nullable ParallelExecutionLock parallelExecutionLock,
            @Nullable ParallelExecutionContextRestorer contextRestorer) {
        this.parallelExecutionLock = parallelExecutionLock;
        this.contextRestorer = contextRestorer;
    }

    /**
     * Executes the action. In JDK 11, this simply runs the action synchronously.
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
     * Asynchronously executes the provided supplier. In JDK 11, this falls back to synchronous
     * execution.
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

        parallelExecutionLock.checkReentrant();
        parallelExecutionLock.release();
        try {
            return supplier.get();
        } finally {
            parallelExecutionLock.acquireByWorker(context.getRecordIndex(), context.getTaskIndex());
            contextRestorer.restore(context.getKey(), context.getActionTask());
        }
    }

    /**
     * Executes all suppliers as one batch. In JDK 11, this falls back to serial execution and
     * captures each supplier's success or failure as an {@link Outcome}. When invoked by the
     * parallel engine, the whole batch runs without the shared operator lock and the task context
     * is restored after worker ownership is re-acquired.
     *
     * @param context the continuation context
     * @param suppliers the suppliers to execute
     * @param timeout ignored in the JDK 11 fallback
     * @param maxParallelism ignored in the JDK 11 fallback
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
            return executeSuppliers(suppliers);
        }

        parallelExecutionLock.checkReentrant();
        parallelExecutionLock.release();
        try {
            return executeSuppliers(suppliers);
        } finally {
            parallelExecutionLock.acquireByWorker(context.getRecordIndex(), context.getTaskIndex());
            contextRestorer.restore(context.getKey(), context.getActionTask());
        }
    }

    private static <T> BatchExecutionResult<T> executeSuppliers(List<Callable<T>> suppliers) {
        List<Outcome<T>> outcomes =
                suppliers.stream()
                        .map(
                                supplier -> {
                                    try {
                                        return Outcome.success(supplier.call());
                                    } catch (Exception e) {
                                        return Outcome.<T>failure(e);
                                    }
                                })
                        .collect(Collectors.toList());
        boolean[] started = new boolean[suppliers.size()];
        Arrays.fill(started, true);
        return new BatchExecutionResult<>(outcomes, started);
    }

    public void close() {}

    /**
     * Returns whether continuation-based async execution is supported.
     *
     * @return true if Continuation API is available (JDK 21+), false otherwise
     */
    public static boolean isContinuationSupported() {
        return false;
    }
}
