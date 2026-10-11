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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Blocking batch driver for action workers on JDKs without continuations. */
final class AsyncBatchExecutor {
    private final AsyncExecutorThreadFactory threadFactory;
    private final ExecutorService executor;
    private final Set<BatchTask<?>> activeTasks = ConcurrentHashMap.newKeySet();

    AsyncBatchExecutor(int numThreads, Runnable threadCleanup) {
        threadFactory = new AsyncExecutorThreadFactory(threadCleanup);
        // Separate from the action-worker pool: all action workers may be waiting for batches.
        executor = Executors.newFixedThreadPool(numThreads, threadFactory);
    }

    <T> BatchExecutionResult<T> execute(
            List<Callable<T>> suppliers, Duration timeout, int maxParallelism) throws Exception {
        List<BatchTask<T>> tasks = new ArrayList<>();
        BlockingQueue<BatchTask<T>> completed = new LinkedBlockingQueue<>();
        int limit = Math.min(Math.max(maxParallelism, 1), suppliers.size());
        long start = System.nanoTime();
        long budget = timeoutNanos(timeout);
        StartGate gate = new StartGate();
        int finished = 0;
        try {
            while (finished < suppliers.size()) {
                if (System.nanoTime() - start >= budget) {
                    break;
                }
                while (tasks.size() < suppliers.size() && tasks.size() - finished < limit) {
                    BatchTask<T> task =
                            new BatchTask<>(
                                    suppliers.get(tasks.size()), completed, new StartState(gate));
                    tasks.add(task);
                    activeTasks.add(task);
                    executor.execute(task);
                }
                BatchTask<T> task =
                        budget == Long.MAX_VALUE
                                ? completed.take()
                                : completed.poll(
                                        Math.max(0, budget - (System.nanoTime() - start)),
                                        TimeUnit.NANOSECONDS);
                if (task == null) {
                    break;
                }
                // Propagate cancellation and fatal failures instead of submitting more work.
                result(task);
                finished++;
            }
            synchronized (gate) {
                gate.cancelled = true;
            }
            TimeoutException timeoutFailure =
                    new TimeoutException("Async batch execution timed out after " + timeout);
            List<Outcome<T>> outcomes = new ArrayList<>(suppliers.size());
            boolean[] started = new boolean[suppliers.size()];
            for (int i = 0; i < suppliers.size(); i++) {
                if (i >= tasks.size()) {
                    outcomes.add(Outcome.failure(timeoutFailure));
                    continue;
                }
                BatchTask<T> task = tasks.get(i);
                // Freeze start eligibility before inspecting it. Running calls may finish later,
                // but queued calls must never begin after being reported as not started.
                boolean cancelled = task.cancel(false);
                started[i] = task.state.started;
                outcomes.add(
                        cancelled || !started[i] ? Outcome.failure(timeoutFailure) : result(task));
            }
            return new BatchExecutionResult<>(outcomes, started);
        } catch (Exception | Error failure) {
            synchronized (gate) {
                gate.cancelled = true;
                tasks.forEach(task -> task.cancel(true));
            }
            throw failure;
        }
    }

    private static long timeoutNanos(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return Long.MAX_VALUE;
        }
        try {
            return timeout.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static <T> Outcome<T> result(BatchTask<T> task) throws Exception {
        try {
            return task.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw (Exception) cause;
        }
    }

    /** Stops submissions and interrupts workers without waiting for their exit cleanup. */
    void shutdown() {
        executor.shutdownNow();
        // shutdownNow alone leaves queued FutureTasks incomplete and their batch drivers blocked.
        activeTasks.forEach(task -> task.cancel(true));
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

    private static final class StartGate {
        private boolean cancelled;
    }

    private static final class StartState {
        private final StartGate gate;
        private boolean cancelled;
        private volatile boolean started;

        StartState(StartGate gate) {
            this.gate = gate;
        }

        boolean start() {
            synchronized (gate) {
                if (cancelled || gate.cancelled) {
                    return false;
                }
                started = true;
                return true;
            }
        }
    }

    private final class BatchTask<T> extends FutureTask<Outcome<T>> {
        private final StartState state;
        private final BlockingQueue<BatchTask<T>> completed;

        private BatchTask(
                Callable<T> supplier, BlockingQueue<BatchTask<T>> completed, StartState state) {
            super(
                    () -> {
                        if (!state.start()) {
                            throw new CancellationException();
                        }
                        try {
                            return Outcome.success(supplier.call());
                        } catch (Exception e) {
                            return Outcome.failure(e);
                        }
                    });
            this.state = state;
            this.completed = completed;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            synchronized (state.gate) {
                state.cancelled = true;
                return super.cancel(mayInterruptIfRunning);
            }
        }

        @Override
        protected void done() {
            activeTasks.remove(this);
            completed.add(this);
        }
    }
}
