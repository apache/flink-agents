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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests worker submission, cancellation and shutdown independently of batch scheduling. */
@Timeout(15)
class AsyncBatchExecutorTest {
    @Test
    void cancellationInterruptsRunningWorkAndPreventsQueuedWork() throws Exception {
        AsyncBatchExecutor executor = new AsyncBatchExecutor(1, () -> {});
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicInteger unwantedCalls = new AtomicInteger();
        try {
            CompletableFuture<Integer> running =
                    executor.submit(
                            () -> {
                                started.countDown();
                                try {
                                    new CountDownLatch(1).await();
                                    return 0;
                                } catch (InterruptedException e) {
                                    interrupted.countDown();
                                    throw e;
                                }
                            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Integer> queued = executor.submit(unwantedCalls::incrementAndGet);
            queued.cancel(true);
            running.cancel(true);
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            executor.submit(() -> "sentinel").get(5, TimeUnit.SECONDS);
            assertThat(unwantedCalls).hasValue(0);
        } finally {
            executor.close();
        }
    }

    @Test
    void closeCancelsSubmissionsAndWaitsForManagedThreadCleanup() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        AtomicInteger cleanups = new AtomicInteger();
        AsyncBatchExecutor executor =
                new AsyncBatchExecutor(
                        1,
                        () -> {
                            assertThat(AsyncExecutorThreadFactory.isAsyncExecutorThread()).isTrue();
                            cleanupStarted.countDown();
                            boolean interrupted = false;
                            while (true) {
                                try {
                                    releaseCleanup.await();
                                    break;
                                } catch (InterruptedException e) {
                                    interrupted = true;
                                }
                            }
                            cleanups.incrementAndGet();
                            if (interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        });
        ExecutorService drivers = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<String> running =
                    executor.submit(
                            () -> {
                                started.countDown();
                                new CountDownLatch(1).await();
                                return "first";
                            });
            CompletableFuture<String> queued = executor.submit(() -> "queued");
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> closing = drivers.submit(executor::close);
            assertThat(cleanupStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(running.isCancelled()).isTrue();
            assertThat(queued.isCancelled()).isTrue();
            assertThat(closing.isDone()).isFalse();
            releaseCleanup.countDown();
            closing.get(5, TimeUnit.SECONDS);
            assertThat(cleanups.get()).isEqualTo(1);
        } finally {
            releaseCleanup.countDown();
            drivers.shutdownNow();
            executor.close();
        }
    }

    @Test
    void submissionPreservesFailuresAndWorkerRemainsUsable() throws Exception {
        AsyncBatchExecutor executor = new AsyncBatchExecutor(1, () -> {});
        try {
            AssertionError fatal = new AssertionError("fatal");
            CompletableFuture<String> failed =
                    executor.submit(
                            () -> {
                                throw fatal;
                            });
            assertThatThrownBy(() -> failed.get(5, TimeUnit.SECONDS)).hasCause(fatal);
            assertThat(
                            executor.submit(AsyncExecutorThreadFactory::isAsyncExecutorThread)
                                    .get(5, TimeUnit.SECONDS))
                    .isTrue();
        } finally {
            executor.close();
        }
    }
}
