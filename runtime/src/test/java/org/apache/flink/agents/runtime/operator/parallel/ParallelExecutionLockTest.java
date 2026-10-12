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
package org.apache.flink.agents.runtime.operator.parallel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

/** Tests for {@link ParallelExecutionLock}. */
class ParallelExecutionLockTest {

    // -----------------------------------------------------------------------
    // Basic acquire / release / check
    // -----------------------------------------------------------------------

    @Test
    void acquireReleaseAndCheckUseCurrentOwnerOnly() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();

        lock.acquireByMain();
        lock.checkReentrant();
        lock.release();

        assertThatThrownBy(lock::checkReentrant)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not own");
    }

    // -----------------------------------------------------------------------
    // Non-reentrant guard
    // -----------------------------------------------------------------------

    @Test
    void acquireIsNonReentrant() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();

        lock.acquireByMain();
        try {
            assertThatThrownBy(lock::acquireByMain)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("non-reentrant");
            assertThatThrownBy(() -> lock.acquireByWorker(0, 0))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("non-reentrant");
        } finally {
            lock.release();
        }
    }

    // -----------------------------------------------------------------------
    // Non-owner release guard
    // -----------------------------------------------------------------------

    @Test
    void nonOwnerCannotRelease() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        lock.acquireByMain();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> result =
                    executor.submit(
                            () ->
                                    assertThatThrownBy(lock::release)
                                            .isInstanceOf(IllegalStateException.class)
                                            .hasMessageContaining("does not own"));
            result.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            lock.release();
        }
    }

    @Test
    void nonOwnerCannotReleaseToMailbox() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        lock.acquireByMain();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> result =
                    executor.submit(
                            () ->
                                    assertThatThrownBy(lock::releaseToMain)
                                            .isInstanceOf(IllegalStateException.class)
                                            .hasMessageContaining("does not own"));
            result.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            lock.release();
        }
    }

    // -----------------------------------------------------------------------
    // releaseToMain sticky grant
    // -----------------------------------------------------------------------

    @Test
    void releaseToMainKeepsStickyGrantUntilMainAcquires() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> worker =
                    executor.submit(
                            () -> {
                                lock.acquireByWorker(0, 0);
                                lock.releaseToMain();
                                return null;
                            });

            worker.get(5, TimeUnit.SECONDS);
            // Lock is still held (sticky grant pending)
            assertThat(lock.isMailboxGrantedForTesting()).isTrue();
            assertThat(lock.isLockedForTesting()).isTrue();

            // Mailbox acquires via fast path — flag must be cleared
            lock.acquireByMain();
            try {
                lock.checkReentrant();
                assertThat(lock.isMailboxGrantedForTesting()).isFalse();
            } finally {
                lock.release();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    // -----------------------------------------------------------------------
    // Worker FIFO order
    // -----------------------------------------------------------------------

    @Test
    void equalPriorityWorkersAllAcquireInArbitraryOrder() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<Integer> acquisitionOrder = Collections.synchronizedList(new ArrayList<>());

        lock.acquireByMain();
        try {
            Future<?> worker1 = submitWorker(lock, executor, acquisitionOrder, 1);
            waitUntil(() -> lock.getQueuedWorkerCountForTesting() == 1);

            Future<?> worker2 = submitWorker(lock, executor, acquisitionOrder, 2);
            waitUntil(() -> lock.getQueuedWorkerCountForTesting() == 2);

            Future<?> worker3 = submitWorker(lock, executor, acquisitionOrder, 3);
            waitUntil(() -> lock.getQueuedWorkerCountForTesting() == 3);

            lock.release();

            worker1.get(5, TimeUnit.SECONDS);
            worker2.get(5, TimeUnit.SECONDS);
            worker3.get(5, TimeUnit.SECONDS);
            // Equal-priority workers have no defined grant order; all must acquire exactly once.
            assertThat(acquisitionOrder).containsExactlyInAnyOrder(1, 2, 3);
        } finally {
            executor.shutdownNow();
        }
    }

    // -----------------------------------------------------------------------
    // Mailbox priority over queued workers
    // -----------------------------------------------------------------------

    @Test
    void mailboxAcquireHasPriorityOverQueuedWorkers() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<String> acquisitionOrder = Collections.synchronizedList(new ArrayList<>());

        lock.acquireByMain();
        try {
            Future<?> worker =
                    executor.submit(
                            () -> {
                                lock.acquireByWorker(0, 0);
                                try {
                                    acquisitionOrder.add("worker");
                                } finally {
                                    lock.release();
                                }
                                return null;
                            });
            waitUntil(() -> lock.getQueuedWorkerCountForTesting() == 1);

            Future<?> mailbox =
                    executor.submit(
                            () -> {
                                lock.acquireByMain();
                                try {
                                    acquisitionOrder.add("mailbox");
                                } finally {
                                    lock.release();
                                }
                                return null;
                            });
            waitUntil(lock::isMailboxWaitingForTesting);

            lock.release();

            mailbox.get(5, TimeUnit.SECONDS);
            worker.get(5, TimeUnit.SECONDS);
            assertThat(acquisitionOrder).containsExactly("mailbox", "worker");
        } finally {
            executor.shutdownNow();
        }
    }

    // -----------------------------------------------------------------------
    // Worker interrupt cleanup
    // -----------------------------------------------------------------------

    @Test
    void interruptedWorkerIsRemovedFromQueue() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        lock.acquireByMain();
        try {
            Future<?> worker =
                    executor.submit(
                            () -> {
                                lock.acquireByWorker(0, 0);
                                lock.release();
                                return null;
                            });
            waitUntil(() -> lock.getQueuedWorkerCountForTesting() == 1);

            worker.cancel(true);
            waitUntil(() -> lock.getQueuedWorkerCountForTesting() == 0);
            assertThat(worker.isCancelled()).isTrue();
        } finally {
            lock.release();
            executor.shutdownNow();
        }
    }

    // -----------------------------------------------------------------------
    // Worker acquireByWorker fast path (no contention)
    // -----------------------------------------------------------------------

    @Test
    void workerAcquiresImmediatelyWhenLockFree() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> worker =
                    executor.submit(
                            () -> {
                                lock.acquireByWorker(0, 0);
                                lock.release();
                                return null;
                            });
            worker.get(5, TimeUnit.SECONDS);
            assertThat(lock.isLockedForTesting()).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    // -----------------------------------------------------------------------
    // Worker priority order
    // -----------------------------------------------------------------------

    @Test
    void workersAcquireInPriorityOrder() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<Integer> acquisitionOrder = Collections.synchronizedList(new ArrayList<>());

        lock.acquireByMain();
        try {
            Future<?> worker1 = submitWorker(lock, executor, acquisitionOrder, 1, 5L);
            waitUntil(() -> lock.getQueuedWorkerCountForTesting() == 1);

            Future<?> worker2 = submitWorker(lock, executor, acquisitionOrder, 2, 1L);
            waitUntil(() -> lock.getQueuedWorkerCountForTesting() == 2);

            Future<?> worker3 = submitWorker(lock, executor, acquisitionOrder, 3, 3L);
            waitUntil(() -> lock.getQueuedWorkerCountForTesting() == 3);

            lock.release();

            worker1.get(5, TimeUnit.SECONDS);
            worker2.get(5, TimeUnit.SECONDS);
            worker3.get(5, TimeUnit.SECONDS);
            // Smaller priority value is granted first despite later arrival.
            assertThat(acquisitionOrder).containsExactly(2, 3, 1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void samePriorityWorkersAllAcquireInArbitraryOrder() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<Integer> acquisitionOrder = Collections.synchronizedList(new ArrayList<>());

        lock.acquireByMain();
        try {
            Future<?> worker1 = submitWorker(lock, executor, acquisitionOrder, 1, 7L);
            waitUntil(() -> lock.getQueuedWorkerCountForTesting() == 1);

            Future<?> worker2 = submitWorker(lock, executor, acquisitionOrder, 2, 7L);
            waitUntil(() -> lock.getQueuedWorkerCountForTesting() == 2);

            Future<?> worker3 = submitWorker(lock, executor, acquisitionOrder, 3, 7L);
            waitUntil(() -> lock.getQueuedWorkerCountForTesting() == 3);

            lock.release();

            worker1.get(5, TimeUnit.SECONDS);
            worker2.get(5, TimeUnit.SECONDS);
            worker3.get(5, TimeUnit.SECONDS);
            // Equal-priority workers have no defined grant order; all must acquire exactly once.
            assertThat(acquisitionOrder).containsExactlyInAnyOrder(1, 2, 3);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void mailboxAcquireHasPriorityOverHighPriorityWorker() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<String> acquisitionOrder = Collections.synchronizedList(new ArrayList<>());

        lock.acquireByMain();
        try {
            Future<?> worker =
                    executor.submit(
                            () -> {
                                // Even the smallest priority value must not overtake the mailbox.
                                lock.acquireByWorker(Long.MIN_VALUE, Long.MIN_VALUE);
                                try {
                                    acquisitionOrder.add("worker");
                                } finally {
                                    lock.release();
                                }
                                return null;
                            });
            waitUntil(() -> lock.getQueuedWorkerCountForTesting() == 1);

            Future<?> mailbox =
                    executor.submit(
                            () -> {
                                lock.acquireByMain();
                                try {
                                    acquisitionOrder.add("mailbox");
                                } finally {
                                    lock.release();
                                }
                                return null;
                            });
            waitUntil(lock::isMailboxWaitingForTesting);

            lock.release();

            mailbox.get(5, TimeUnit.SECONDS);
            worker.get(5, TimeUnit.SECONDS);
            assertThat(acquisitionOrder).containsExactly("mailbox", "worker");
        } finally {
            executor.shutdownNow();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Future<?> submitWorker(
            ParallelExecutionLock lock,
            ExecutorService executor,
            List<Integer> acquisitionOrder,
            int workerId) {
        return executor.submit(
                () -> {
                    lock.acquireByWorker(0, 0);
                    try {
                        acquisitionOrder.add(workerId);
                    } finally {
                        lock.release();
                    }
                    return null;
                });
    }

    private static Future<?> submitWorker(
            ParallelExecutionLock lock,
            ExecutorService executor,
            List<Integer> acquisitionOrder,
            int workerId,
            long priority) {
        return executor.submit(
                () -> {
                    lock.acquireByWorker(priority, 0L);
                    try {
                        acquisitionOrder.add(workerId);
                    } finally {
                        lock.release();
                    }
                    return null;
                });
    }

    private static void waitUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("Condition was not met before timeout.");
            }
            Thread.sleep(10L);
        }
    }
}
