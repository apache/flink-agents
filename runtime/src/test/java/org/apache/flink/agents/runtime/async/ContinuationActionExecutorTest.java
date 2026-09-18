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

import org.apache.flink.agents.runtime.operator.parallel.ParallelExecutionContextRestorer;
import org.apache.flink.agents.runtime.operator.parallel.ParallelExecutionLock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests the JDK<21 mailbox-lock handoff contract. */
class ContinuationActionExecutorTest {

    @Test
    void executeAsyncReleasesAndReacquiresLockAndRestoresContext() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        AtomicBoolean restored = new AtomicBoolean();
        // The executor drives the restorer once the worker re-acquires the lock after the blocking
        // callable returns; the context is otherwise opaque to this test.
        ParallelExecutionContextRestorer restorer =
                (key, action) -> {
                    lock.checkReentrant();
                    restored.set(true);
                };
        ContinuationActionExecutor executor =
                new ContinuationActionExecutor(1, () -> {}, lock, restorer);
        ContinuationContext context = new ContinuationContext();

        CountDownLatch supplierStarted = new CountDownLatch(1);
        CountDownLatch allowSupplierToFinish = new CountDownLatch(1);

        ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<String> workerResult =
                    workerExecutor.submit(
                            () -> {
                                lock.acquireByWorker(0, 0);
                                try {
                                    return executor.executeAsync(
                                            context,
                                            () -> {
                                                supplierStarted.countDown();
                                                await(allowSupplierToFinish);
                                                return "result";
                                            });
                                } finally {
                                    lock.release();
                                }
                            });

            assertThat(supplierStarted.await(5, TimeUnit.SECONDS)).isTrue();

            lock.acquireByMain();
            try {
                lock.checkReentrant();
            } finally {
                lock.release();
            }
            assertThat(restored).isFalse();

            allowSupplierToFinish.countDown();
            assertThat(workerResult.get(5, TimeUnit.SECONDS)).isEqualTo("result");
            assertThat(restored).isTrue();
        } finally {
            workerExecutor.shutdownNow();
            executor.close();
        }
    }

    @Test
    void executeAllAsyncReleasesAndReacquiresLockAndRestoresContext() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        AtomicBoolean restored = new AtomicBoolean();
        ParallelExecutionContextRestorer restorer =
                (key, action) -> {
                    lock.checkReentrant();
                    restored.set(true);
                };
        ContinuationActionExecutor executor =
                new ContinuationActionExecutor(1, () -> {}, lock, restorer);
        ContinuationContext context = new ContinuationContext();
        CountDownLatch supplierStarted = new CountDownLatch(1);
        CountDownLatch allowSupplierToFinish = new CountDownLatch(1);
        List<Callable<String>> suppliers =
                List.of(
                        () -> {
                            supplierStarted.countDown();
                            await(allowSupplierToFinish);
                            return "first";
                        },
                        () -> "second");

        ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<BatchExecutionResult<String>> workerResult =
                    workerExecutor.submit(
                            () -> {
                                lock.acquireByWorker(0, 0);
                                try {
                                    return executor.executeAllAsync(
                                            context, suppliers, null, suppliers.size());
                                } finally {
                                    lock.release();
                                }
                            });

            assertThat(supplierStarted.await(5, TimeUnit.SECONDS)).isTrue();

            lock.acquireByMain();
            try {
                lock.checkReentrant();
            } finally {
                lock.release();
            }
            assertThat(restored).isFalse();

            allowSupplierToFinish.countDown();
            BatchExecutionResult<String> result = workerResult.get(5, TimeUnit.SECONDS);
            assertThat(result.getOutcomes().get(0).getValue()).isEqualTo("first");
            assertThat(result.getOutcomes().get(1).getValue()).isEqualTo("second");
            assertThat(restored).isTrue();
        } finally {
            workerExecutor.shutdownNow();
            executor.close();
        }
    }

    @Test
    void executeAsyncAbortsBeforeSupplierWhenCallerDoesNotOwnLock() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        AtomicBoolean supplierCalled = new AtomicBoolean();
        AtomicBoolean restored = new AtomicBoolean();
        ContinuationActionExecutor executor =
                new ContinuationActionExecutor(
                        1, () -> {}, lock, (key, action) -> restored.set(true));
        try {
            // executeAsync asserts mailbox-lock ownership before releasing it. Calling without the
            // lock must fail fast, before the supplier runs and before any restore.
            assertThatThrownBy(
                            () ->
                                    executor.executeAsync(
                                            new ContinuationContext(),
                                            () -> {
                                                supplierCalled.set(true);
                                                return "unexpected";
                                            }))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(supplierCalled).isFalse();
            assertThat(restored).isFalse();
        } finally {
            executor.close();
        }
    }

    @Test
    void executeAsyncWithoutMailboxLockKeepsSynchronousFallback() throws Exception {
        ContinuationActionExecutor executor =
                new ContinuationActionExecutor(1, () -> {}, null, null);
        try {
            assertThat(executor.executeAsync(new ContinuationContext(), () -> "result"))
                    .isEqualTo("result");
        } finally {
            executor.close();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test supplier", e);
        }
    }
}
