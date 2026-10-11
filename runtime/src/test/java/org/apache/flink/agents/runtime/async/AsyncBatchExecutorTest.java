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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests the blocking batch driver's concurrency, timeout and shutdown contracts. */
@Timeout(15)
class AsyncBatchExecutorTest {
    @Test
    void slidingWindowMakesProgressPastSlowFirstCallAndPreservesOrder() throws Exception {
        AsyncBatchExecutor executor = new AsyncBatchExecutor(3, () -> {});
        CountDownLatch thirdStarted = new CountDownLatch(1);
        CyclicBarrier firstPair = new CyclicBarrier(2);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        try {
            List<Callable<String>> calls =
                    List.of(
                            () -> {
                                peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                                try {
                                    firstPair.await(5, TimeUnit.SECONDS);
                                    assertThat(thirdStarted.await(5, TimeUnit.SECONDS)).isTrue();
                                    return "first";
                                } finally {
                                    inFlight.decrementAndGet();
                                }
                            },
                            () -> {
                                peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                                try {
                                    firstPair.await(5, TimeUnit.SECONDS);
                                    throw new IllegalArgumentException("second failed");
                                } finally {
                                    inFlight.decrementAndGet();
                                }
                            },
                            () -> {
                                peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                                try {
                                    assertThat(AsyncExecutorThreadFactory.isAsyncExecutorThread())
                                            .isTrue();
                                    thirdStarted.countDown();
                                    return "third";
                                } finally {
                                    inFlight.decrementAndGet();
                                }
                            });
            BatchExecutionResult<String> result = executor.execute(calls, null, 2);
            assertThat(peak.get()).isEqualTo(2);
            assertThat(result.getOutcomes().get(0).getValue()).isEqualTo("first");
            assertThat(result.getOutcomes().get(1).getError()).hasMessage("second failed");
            assertThat(result.getOutcomes().get(2).getValue()).isEqualTo("third");
            for (int i = 0; i < 3; i++) {
                assertThat(result.wasStarted(i)).isTrue();
            }
        } finally {
            executor.close();
        }
    }

    @Test
    void parallelismOneSerializesCallsEvenWithMultipleWorkers() throws Exception {
        AsyncBatchExecutor executor = new AsyncBatchExecutor(3, () -> {});
        AtomicInteger sequence = new AtomicInteger();
        try {
            BatchExecutionResult<Integer> result =
                    executor.execute(
                            List.of(
                                    sequence::incrementAndGet,
                                    sequence::incrementAndGet,
                                    sequence::incrementAndGet),
                            Duration.ZERO,
                            1);
            assertThat(result.getOutcomes()).extracting(Outcome::getValue).containsExactly(1, 2, 3);
        } finally {
            executor.close();
        }
    }

    @Test
    void timeoutPreservesCompletedResultsAndDistinguishesQueuedAndUnsubmittedCalls()
            throws Exception {
        AsyncBatchExecutor executor = new AsyncBatchExecutor(1, () -> {});
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger unwantedCalls = new AtomicInteger();
        try {
            BatchExecutionResult<Integer> result =
                    executor.execute(
                            List.of(
                                    () -> 1,
                                    () -> {
                                        release.await();
                                        return 2;
                                    },
                                    unwantedCalls::incrementAndGet,
                                    unwantedCalls::incrementAndGet),
                            Duration.ofSeconds(1),
                            2);
            assertThat(result.getOutcomes().get(0).getValue()).isEqualTo(1);
            assertThat(result.wasStarted(0)).isTrue();
            assertThat(result.wasStarted(1)).isTrue();
            assertThat(result.wasStarted(2)).isFalse();
            assertThat(result.wasStarted(3)).isFalse();
            for (int i = 1; i < 4; i++) {
                assertThat(result.getOutcomes().get(i).getError())
                        .isInstanceOf(TimeoutException.class);
            }
            release.countDown();
            // Drain the queue with a subsequent batch: cancelled queued calls must never execute.
            executor.execute(List.of(() -> "sentinel"), null, 1);
            assertThat(unwantedCalls.get()).isZero();
        } finally {
            release.countDown();
            executor.close();
        }
    }

    @Test
    void callerInterruptionCancelsRunningAndQueuedCalls() throws Exception {
        AsyncBatchExecutor executor = new AsyncBatchExecutor(1, () -> {});
        ExecutorService driver = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicInteger unwantedCalls = new AtomicInteger();
        try {
            Future<?> batch =
                    driver.submit(
                            () ->
                                    executor.execute(
                                            List.of(
                                                    () -> {
                                                        started.countDown();
                                                        try {
                                                            new CountDownLatch(1).await();
                                                            return 0;
                                                        } catch (InterruptedException e) {
                                                            interrupted.countDown();
                                                            throw e;
                                                        }
                                                    },
                                                    unwantedCalls::incrementAndGet),
                                            null,
                                            2));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            batch.cancel(true);
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            driver.submit(() -> {}).get(5, TimeUnit.SECONDS);
            executor.execute(List.of(() -> "sentinel"), null, 1);
            assertThat(unwantedCalls.get()).isZero();
        } finally {
            driver.shutdownNow();
            executor.close();
        }
    }

    @Test
    void closeUnblocksBatchAndWaitsForManagedThreadCleanup() throws Exception {
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
            Future<?> batch =
                    drivers.submit(
                            () ->
                                    executor.execute(
                                            List.of(
                                                    () -> {
                                                        started.countDown();
                                                        new CountDownLatch(1).await();
                                                        return "first";
                                                    },
                                                    () -> "queued"),
                                            null,
                                            2));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> closing = drivers.submit(executor::close);
            assertThat(cleanupStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> batch.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(java.util.concurrent.CancellationException.class);
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
    void fatalErrorsEscapeInsteadOfBecomingPerCallFailures() {
        AsyncBatchExecutor executor = new AsyncBatchExecutor(1, () -> {});
        try {
            assertThatThrownBy(
                            () ->
                                    executor.execute(
                                            List.of(
                                                    () -> {
                                                        throw new AssertionError("fatal");
                                                    }),
                                            null,
                                            1))
                    .isInstanceOf(AssertionError.class)
                    .hasMessage("fatal");
        } finally {
            executor.close();
        }
    }
}
