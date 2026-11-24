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

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Managed async workers for JDKs without continuations. Batch scheduling belongs to the runner. */
final class AsyncBatchExecutor {
    private final AsyncExecutorThreadFactory threadFactory;
    private final ExecutorService executor;
    private final Set<CompletableFuture<?>> submissions = ConcurrentHashMap.newKeySet();

    AsyncBatchExecutor(int numThreads, Runnable threadCleanup) {
        threadFactory = new AsyncExecutorThreadFactory(threadCleanup);
        // Separate from the action-worker pool: all action workers may be waiting for batches.
        executor = Executors.newFixedThreadPool(numThreads, threadFactory);
    }

    <T> CompletableFuture<T> submit(Callable<T> callable) {
        CompletableFuture<T> result = submit(executor, callable);
        submissions.add(result);
        result.whenComplete((value, error) -> submissions.remove(result));
        return result;
    }

    static <T> CompletableFuture<T> submit(Executor executor, Callable<T> callable) {
        CompletableFuture<T> result = new CompletableFuture<>();
        FutureTask<Void> task =
                new FutureTask<>(
                        () -> {
                            try {
                                result.complete(callable.call());
                            } catch (Throwable failure) {
                                result.completeExceptionally(failure);
                            }
                            return null;
                        });
        result.whenComplete(
                (value, error) -> {
                    if (result.isCancelled()) {
                        task.cancel(true);
                    }
                });
        try {
            executor.execute(task);
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    /** Stops submissions and interrupts workers without waiting for their exit cleanup. */
    void shutdown() {
        submissions.forEach(future -> future.cancel(true));
        executor.shutdownNow();
    }

    void close() {
        shutdown();
        boolean interrupted = false;
        try {
            while (!executor.isTerminated()) {
                try {
                    executor.awaitTermination(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            threadFactory.awaitThreadExit();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
