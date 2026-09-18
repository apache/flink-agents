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

import org.apache.flink.util.function.ThrowingRunnable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the coordinator's {@link ParallelExecutionTask} lifecycle: node-driven pull, eager per-key
 * taskIndex assignment at add time, worker execution, the mailbox-thread in-order commit drain, and
 * its follow-up. Works are supplied by a FIFO factory standing in for the operator's lazy {@code
 * Work}.
 */
class ParallelExecutionCoordinatorTest {

    private static final Object KEY = "k0";

    @Test
    void addTaskRequiresMailboxLock() {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        BlockingQueue<ThrowingRunnable<? extends Exception>> mails = new LinkedBlockingQueue<>();
        try (ParallelExecutionCoordinator coordinator = newCoordinator(lock, mails, () -> null)) {
            assertThatThrownBy(() -> coordinator.addTask(KEY))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not own");
        }
    }

    @Test
    @Timeout(15)
    void workerRunsSetsUpThenExecutesCommitsAndFinishes() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        BlockingQueue<ThrowingRunnable<? extends Exception>> mails = new LinkedBlockingQueue<>();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        Queue<ParallelExecutionTask> works = new ConcurrentLinkedQueue<>();
        try (ParallelExecutionCoordinator coordinator = newCoordinator(lock, mails, works::poll)) {
            lock.acquireByMain();
            // The worker owns the mailbox lock while it runs the action.
            works.add(FakeWork.blocking("A", events, lock::checkReentrant));
            coordinator.addTask(KEY);
            lock.release();

            runOneMail(mails);

            // The work is set up + executed on the worker, then restored + committed and its group
            // finished on the mailbox thread; finishGroup receives the just-committed work as last.
            assertThat(events).containsExactly("execute-A", "restore-A", "commit-A", "finish-A->A");
            assertThat(coordinator.hasOutstanding(KEY)).isFalse();
        }
    }

    @Test
    @Timeout(15)
    void outOfOrderCompletionCommitsInKeyOrder() throws Exception {
        // Both same-key works run on separate workers; each releases the lock inside execute()
        // (mimicking the durable-async segment) so they can block concurrently under the real lock.
        ParallelExecutionLock lock = new ParallelExecutionLock();
        BlockingQueue<ThrowingRunnable<? extends Exception>> mails = new LinkedBlockingQueue<>();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        Queue<ParallelExecutionTask> works = new ConcurrentLinkedQueue<>();
        CountDownLatch aStarted = new CountDownLatch(1);
        CountDownLatch bStarted = new CountDownLatch(1);
        CountDownLatch allowA = new CountDownLatch(1);
        CountDownLatch allowB = new CountDownLatch(1);
        try (ParallelExecutionCoordinator coordinator =
                newCoordinator(2, lock, mails, works::poll)) {
            lock.acquireByMain();
            // Add A, wait until it is running (taskIndex 0), then add B (taskIndex 1): the
            // sequential
            // add fixes the pull order deterministically.
            works.add(
                    FakeWork.blockingAsync(
                            "A",
                            events,
                            lock,
                            () -> {
                                aStarted.countDown();
                                await(allowA);
                            }));
            coordinator.addTask(KEY);
            lock.release();
            assertThat(aStarted.await(5, TimeUnit.SECONDS)).isTrue();

            works.add(
                    FakeWork.blockingAsync(
                            "B",
                            events,
                            lock,
                            () -> {
                                bStarted.countDown();
                                await(allowB);
                            }));
            lock.acquireByMain();
            coordinator.addTask(KEY);
            lock.release();
            assertThat(bStarted.await(5, TimeUnit.SECONDS)).isTrue();

            // B finishes first, but its drain must stop at the still-running head A.
            allowB.countDown();
            runOneMail(mails);
            assertThat(committed(events)).isEmpty();
            assertThat(coordinator.hasOutstanding(KEY)).isTrue();

            // Once A finishes, the contiguous done prefix commits A then B in key-taskIndex order.
            allowA.countDown();
            runOneMail(mails);
            assertThat(committed(events)).containsExactly("A", "B");
            assertThat(coordinator.hasOutstanding(KEY)).isFalse();
        } finally {
            allowA.countDown();
            allowB.countDown();
        }
    }

    @Test
    @Timeout(15)
    void commitFailureEscapesMailbox() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        BlockingQueue<ThrowingRunnable<? extends Exception>> mails = new LinkedBlockingQueue<>();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        Queue<ParallelExecutionTask> works = new ConcurrentLinkedQueue<>();
        RuntimeException commitFailure = new RuntimeException("commit failure");
        try (ParallelExecutionCoordinator coordinator = newCoordinator(lock, mails, works::poll)) {
            lock.acquireByMain();
            works.add(FakeWork.failingCommit("A", events, commitFailure));
            coordinator.addTask(KEY);
            lock.release();

            assertThatThrownBy(() -> runOneMail(mails)).isSameAs(commitFailure);
        }
    }

    @Test
    @Timeout(15)
    void commitDrainFailureAmongSiblingsDoesNotLeakLockOrWedgeGroup() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        BlockingQueue<ThrowingRunnable<? extends Exception>> mails = new LinkedBlockingQueue<>();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        Queue<ParallelExecutionTask> works = new ConcurrentLinkedQueue<>();
        CountDownLatch aStarted = new CountDownLatch(1);
        CountDownLatch bStarted = new CountDownLatch(1);
        CountDownLatch allowA = new CountDownLatch(1);
        CountDownLatch allowB = new CountDownLatch(1);
        RuntimeException commitFailure = new RuntimeException("commit failure");

        try (ParallelExecutionCoordinator coordinator =
                newCoordinator(2, lock, mails, works::poll)) {
            lock.acquireByMain();
            works.add(
                    FakeWork.blockingAsync(
                            "A",
                            events,
                            lock,
                            () -> {
                                aStarted.countDown();
                                await(allowA);
                            }));
            coordinator.addTask(KEY);
            lock.release();
            assertThat(aStarted.await(5, TimeUnit.SECONDS)).isTrue();

            works.add(
                    FakeWork.blockingAsyncFailingCommit(
                            "B",
                            events,
                            lock,
                            () -> {
                                bStarted.countDown();
                                await(allowB);
                            },
                            commitFailure));
            lock.acquireByMain();
            coordinator.addTask(KEY);
            lock.release();
            assertThat(bStarted.await(5, TimeUnit.SECONDS)).isTrue();

            allowB.countDown();
            runOneMail(mails);
            assertThat(committed(events)).isEmpty();
            assertThat(coordinator.hasOutstanding(KEY)).isTrue();

            allowA.countDown();
            assertThatThrownBy(() -> runOneMail(mails)).isSameAs(commitFailure);

            lock.acquireByMain();
            lock.release();
            assertThat(coordinator.hasOutstanding(KEY)).isFalse();
            assertThat(committed(events)).containsExactly("A");
        } finally {
            allowA.countDown();
            allowB.countDown();
        }
    }

    @Test
    @Timeout(15)
    void hasOutstandingReflectsOutstandingWork() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        BlockingQueue<ThrowingRunnable<? extends Exception>> mails = new LinkedBlockingQueue<>();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        Queue<ParallelExecutionTask> works = new ConcurrentLinkedQueue<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch allow = new CountDownLatch(1);
        try (ParallelExecutionCoordinator coordinator = newCoordinator(lock, mails, works::poll)) {
            // An inactive key has no outstanding work.
            assertThat(coordinator.hasOutstanding(KEY)).isFalse();

            lock.acquireByMain();
            works.add(
                    FakeWork.blockingAsync(
                            "A",
                            events,
                            lock,
                            () -> {
                                started.countDown();
                                await(allow);
                            }));
            coordinator.addTask(KEY);
            lock.release();

            // Outstanding from the moment a task is added until the commit drain removes it.
            assertThat(coordinator.hasOutstanding(KEY)).isTrue();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(coordinator.hasOutstanding(KEY)).isTrue();

            allow.countDown();
            runOneMail(mails);
            assertThat(coordinator.hasOutstanding(KEY)).isFalse();
        } finally {
            allow.countDown();
        }
    }

    @Test
    @Timeout(15)
    void isQuiescedTracksInFlightWork() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        BlockingQueue<ThrowingRunnable<? extends Exception>> mails = new LinkedBlockingQueue<>();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        Queue<ParallelExecutionTask> works = new ConcurrentLinkedQueue<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch allow = new CountDownLatch(1);
        try (ParallelExecutionCoordinator coordinator = newCoordinator(lock, mails, works::poll)) {
            // No in-flight work: quiesced.
            assertThat(coordinator.isQuiesced()).isTrue();

            lock.acquireByMain();
            works.add(
                    FakeWork.blockingAsync(
                            "A",
                            events,
                            lock,
                            () -> {
                                started.countDown();
                                await(allow);
                            }));
            coordinator.addTask(KEY);
            lock.release();

            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            // Pulled and executing but not yet committed: not quiesced.
            assertThat(coordinator.isQuiesced()).isFalse();

            allow.countDown();
            runOneMail(mails); // commit drain removes the node
            assertThat(coordinator.isQuiesced()).isTrue();
        } finally {
            allow.countDown();
        }
    }

    @Test
    @Timeout(15)
    void drainingPausesNewDispatchUntilStopped() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        BlockingQueue<ThrowingRunnable<? extends Exception>> mails = new LinkedBlockingQueue<>();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        Queue<ParallelExecutionTask> works = new ConcurrentLinkedQueue<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch allow = new CountDownLatch(1);
        try (ParallelExecutionCoordinator coordinator = newCoordinator(lock, mails, works::poll)) {
            coordinator.startDraining();

            works.add(
                    FakeWork.blockingAsync(
                            "A",
                            events,
                            lock,
                            () -> {
                                started.countDown();
                                await(allow);
                            }));
            lock.acquireByMain();
            coordinator.addTask(KEY);
            lock.release();

            // While draining, the worker declines the pulled credit; the task is not started, but
            // its node stays pending (outstanding), so its task stays in the durable queue.
            assertThat(started.await(1, TimeUnit.SECONDS)).isFalse();
            assertThat(events).isEmpty();
            assertThat(coordinator.hasOutstanding(KEY)).isTrue();

            // Resuming dispatch alone must revive the worker — no other mailbox lock cycle runs.
            coordinator.stopDraining();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(coordinator.isQuiesced()).isFalse();

            allow.countDown();
            runOneMail(mails);
        } finally {
            allow.countDown();
        }
    }

    @Test
    @Timeout(15)
    void closeWaitsForInFlightWorkerBeforeReturning() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        BlockingQueue<ThrowingRunnable<? extends Exception>> mails = new LinkedBlockingQueue<>();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch allowFinish = new CountDownLatch(1);
        // Emulates user code that does not react to the shutdown interrupt.
        Supplier<ParallelExecutionTask> works =
                () ->
                        FakeWork.blocking(
                                "A",
                                events,
                                () -> {
                                    workerEntered.countDown();
                                    boolean released = false;
                                    while (!released) {
                                        try {
                                            released = allowFinish.await(5, TimeUnit.SECONDS);
                                        } catch (InterruptedException ignored) {
                                            // keep waiting
                                        }
                                    }
                                });
        ParallelExecutionCoordinator coordinator = newCoordinator(lock, mails, works);
        try {
            lock.acquireByMain();
            coordinator.addTask(KEY);
            lock.release();
            assertThat(workerEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Thread releaser =
                    new Thread(
                            () -> {
                                try {
                                    Thread.sleep(400L);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                allowFinish.countDown();
                            });
            releaser.setDaemon(true);
            releaser.start();

            // close() must block until the in-flight worker has terminated, so callers can
            // safely release resources the worker may still be using.
            coordinator.close();
            assertThat(coordinator.getWorkerPoolSizeForTesting()).isZero();
            releaser.join();
        } finally {
            allowFinish.countDown();
            coordinator.close();
        }
    }

    @Test
    @Timeout(15)
    void startDrainingFencesInProgressPullSoQuiesceCannotMissIt() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        BlockingQueue<ThrowingRunnable<? extends Exception>> mails = new LinkedBlockingQueue<>();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch allowFactory = new CountDownLatch(1);
        CountDownLatch drainerDone = new CountDownLatch(1);
        AtomicBoolean quiescedAfterFence = new AtomicBoolean();
        // The factory runs inside the worker's pull section: after the draining check, before
        // inFlightExecuting is incremented — exactly the TOCTOU window.
        Supplier<ParallelExecutionTask> works =
                () -> {
                    factoryEntered.countDown();
                    await(allowFactory);
                    return FakeWork.blocking("A", events, () -> {});
                };
        try (ParallelExecutionCoordinator coordinator = newCoordinator(lock, mails, works)) {
            lock.acquireByMain();
            coordinator.addTask(KEY);
            lock.release();
            assertThat(factoryEntered.await(5, TimeUnit.SECONDS)).isTrue();

            // Start draining while the worker sits in the window; the fence must block until the
            // worker's lock hold (which includes the increment) is over.
            Thread drainer =
                    new Thread(
                            () -> {
                                try {
                                    coordinator.startDraining();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                quiescedAfterFence.set(coordinator.isQuiesced());
                                drainerDone.countDown();
                            });
            drainer.start();
            // Give the drainer time to reach the fence (pre-fix it would finish immediately).
            Thread.sleep(300L);
            allowFactory.countDown();
            assertThat(drainerDone.await(5, TimeUnit.SECONDS)).isTrue();

            // The pulled-but-uncommitted task must be visible to the quiesce check.
            assertThat(quiescedAfterFence.get()).isFalse();

            coordinator.stopDraining();
            runOneMail(mails);
            assertThat(coordinator.isQuiesced()).isTrue();
        } finally {
            allowFactory.countDown();
        }
    }

    @Test
    @Timeout(15)
    void workerFailureSurfacesOnMailboxAndKeepsLockUsable() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        BlockingQueue<ThrowingRunnable<? extends Exception>> mails = new LinkedBlockingQueue<>();
        // A non-RuntimeException failure in the pull/handoff section: without the mailbox funnel
        // it would silently kill the pool thread and leak the lock.
        AssertionError boom = new AssertionError("worker boom");
        try (ParallelExecutionCoordinator coordinator =
                newCoordinator(
                        lock,
                        mails,
                        () -> {
                            throw boom;
                        })) {
            lock.acquireByMain();
            coordinator.addTask(KEY);
            lock.release();

            // The failure arrives as a throwing mail and fails the mailbox (i.e. the job).
            assertThatThrownBy(() -> runOneMail(mails)).isSameAs(boom);

            // The lock was handed back on the failure path: the mailbox thread can still use it.
            lock.acquireByMain();
            lock.release();
        }
    }

    @Test
    @Timeout(15)
    void growsWorkerPoolWithBacklogUpToMax() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        BlockingQueue<ThrowingRunnable<? extends Exception>> mails = new LinkedBlockingQueue<>();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        Queue<ParallelExecutionTask> works = new ConcurrentLinkedQueue<>();
        CountDownLatch aStarted = new CountDownLatch(1);
        CountDownLatch bStarted = new CountDownLatch(1);
        CountDownLatch cStarted = new CountDownLatch(1);
        CountDownLatch allowAll = new CountDownLatch(1);
        try (ParallelExecutionCoordinator coordinator =
                newCoordinator(3, lock, mails, works::poll)) {
            // The pool is lazy: no thread exists before the first task.
            assertThat(coordinator.getWorkerPoolSizeForTesting()).isZero();

            // Every live thread is busy when the next task arrives, so each add grows the pool.
            works.add(
                    FakeWork.blockingAsync(
                            "A",
                            events,
                            lock,
                            () -> {
                                aStarted.countDown();
                                await(allowAll);
                            }));
            lock.acquireByMain();
            coordinator.addTask(KEY);
            lock.release();
            assertThat(aStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(coordinator.getWorkerPoolSizeForTesting()).isEqualTo(1);

            works.add(
                    FakeWork.blockingAsync(
                            "B",
                            events,
                            lock,
                            () -> {
                                bStarted.countDown();
                                await(allowAll);
                            }));
            lock.acquireByMain();
            coordinator.addTask(KEY);
            lock.release();
            assertThat(bStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(coordinator.getWorkerPoolSizeForTesting()).isEqualTo(2);

            works.add(
                    FakeWork.blockingAsync(
                            "C",
                            events,
                            lock,
                            () -> {
                                cStarted.countDown();
                                await(allowAll);
                            }));
            lock.acquireByMain();
            coordinator.addTask(KEY);
            lock.release();
            assertThat(cStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(coordinator.getWorkerPoolSizeForTesting()).isEqualTo(3);

            allowAll.countDown();
            runOneMail(mails);
            runOneMail(mails);
            runOneMail(mails);
            assertThat(committed(events)).containsExactly("A", "B", "C");
        } finally {
            allowAll.countDown();
        }
    }

    @Test
    @Timeout(15)
    void shrinksIdlePoolToOneCoreThreadAndGrowsAgainOnDemand() throws Exception {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        BlockingQueue<ThrowingRunnable<? extends Exception>> mails = new LinkedBlockingQueue<>();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        Queue<ParallelExecutionTask> works = new ConcurrentLinkedQueue<>();
        CountDownLatch aStarted = new CountDownLatch(1);
        CountDownLatch bStarted = new CountDownLatch(1);
        CountDownLatch allowFirst = new CountDownLatch(1);
        CountDownLatch cStarted = new CountDownLatch(1);
        CountDownLatch dStarted = new CountDownLatch(1);
        CountDownLatch allowSecond = new CountDownLatch(1);
        try (ParallelExecutionCoordinator coordinator =
                newCoordinator(2, 150L, lock, mails, works::poll)) {
            // Grow to the cap of 2.
            works.add(
                    FakeWork.blockingAsync(
                            "A",
                            events,
                            lock,
                            () -> {
                                aStarted.countDown();
                                await(allowFirst);
                            }));
            lock.acquireByMain();
            coordinator.addTask(KEY);
            lock.release();
            assertThat(aStarted.await(5, TimeUnit.SECONDS)).isTrue();
            works.add(
                    FakeWork.blockingAsync(
                            "B",
                            events,
                            lock,
                            () -> {
                                bStarted.countDown();
                                await(allowFirst);
                            }));
            lock.acquireByMain();
            coordinator.addTask(KEY);
            lock.release();
            assertThat(bStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(coordinator.getWorkerPoolSizeForTesting()).isEqualTo(2);

            allowFirst.countDown();
            runOneMail(mails);
            runOneMail(mails);

            // With nothing to do, the surplus thread times out and exits; the core thread does
            // not, even across several keep-alive periods.
            waitUntilPoolSize(coordinator, 1);
            Thread.sleep(500L);
            assertThat(coordinator.getWorkerPoolSizeForTesting()).isEqualTo(1);

            // New demand grows the pool again.
            works.add(
                    FakeWork.blockingAsync(
                            "C",
                            events,
                            lock,
                            () -> {
                                cStarted.countDown();
                                await(allowSecond);
                            }));
            lock.acquireByMain();
            coordinator.addTask(KEY);
            lock.release();
            assertThat(cStarted.await(5, TimeUnit.SECONDS)).isTrue();
            works.add(
                    FakeWork.blockingAsync(
                            "D",
                            events,
                            lock,
                            () -> {
                                dStarted.countDown();
                                await(allowSecond);
                            }));
            lock.acquireByMain();
            coordinator.addTask(KEY);
            lock.release();
            assertThat(dStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(coordinator.getWorkerPoolSizeForTesting()).isEqualTo(2);

            allowSecond.countDown();
            runOneMail(mails);
            runOneMail(mails);
        } finally {
            allowFirst.countDown();
            allowSecond.countDown();
        }
    }

    private static ParallelExecutionCoordinator newCoordinator(
            ParallelExecutionLock lock,
            BlockingQueue<ThrowingRunnable<? extends Exception>> mails,
            Supplier<ParallelExecutionTask> workFactory) {
        return newCoordinator(1, lock, mails, workFactory);
    }

    private static ParallelExecutionCoordinator newCoordinator(
            int maxWorkers,
            ParallelExecutionLock lock,
            BlockingQueue<ThrowingRunnable<? extends Exception>> mails,
            Supplier<ParallelExecutionTask> workFactory) {
        // A practically-infinite keep-alive keeps the pool size deterministic for these tests.
        return newCoordinator(maxWorkers, 3_600_000L, lock, mails, workFactory);
    }

    private static ParallelExecutionCoordinator newCoordinator(
            int maxWorkers,
            long workerIdleTimeoutMillis,
            ParallelExecutionLock lock,
            BlockingQueue<ThrowingRunnable<? extends Exception>> mails,
            Supplier<ParallelExecutionTask> workFactory) {
        return new ParallelExecutionCoordinator(
                lock,
                (mail, description) -> mails.add(mail),
                workFactory,
                maxWorkers,
                workerIdleTimeoutMillis);
    }

    private static void waitUntilPoolSize(ParallelExecutionCoordinator coordinator, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (coordinator.getWorkerPoolSizeForTesting() != expected) {
            assertThat(System.nanoTime())
                    .as("timed out waiting for pool size %d", expected)
                    .isLessThan(deadline);
            Thread.sleep(10L);
        }
    }

    private static void runOneMail(BlockingQueue<ThrowingRunnable<? extends Exception>> mails)
            throws Exception {
        ThrowingRunnable<? extends Exception> mail = mails.poll(5, TimeUnit.SECONDS);
        assertThat(mail).isNotNull();
        mail.run();
    }

    /** Extracts, in order, the ids of works whose {@link ParallelExecutionTask#commit()} ran. */
    private static List<String> committed(List<String> events) {
        List<String> out = new ArrayList<>();
        for (String event : events) {
            if (event.startsWith("commit-")) {
                out.add(event.substring("commit-".length()));
            }
        }
        return out;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test latch", e);
        }
    }

    /**
     * A controllable {@link ParallelExecutionTask}: records each lifecycle step into a shared log,
     * can block or run arbitrary code inside {@link #execute()} — optionally releasing the lock
     * while blocked, mimicking the durable-async segment — and can fail its {@link #commit()}. The
     * coordinator binds its scheduling priority via {@link #setup(Object, long, long)}.
     */
    private static final class FakeWork implements ParallelExecutionTask {
        private final String id;
        private final List<String> events;
        @Nullable private final Runnable onExecute;
        @Nullable private final ParallelExecutionLock asyncLock;
        @Nullable private final RuntimeException commitFailure;
        private volatile boolean done;
        private volatile Object assignedKey;
        private volatile long assignedRecordIndex = -1L;
        private volatile long assignedTaskIndex = -1L;

        private FakeWork(
                String id,
                List<String> events,
                @Nullable Runnable onExecute,
                @Nullable ParallelExecutionLock asyncLock,
                @Nullable RuntimeException commitFailure) {
            this.id = id;
            this.events = events;
            this.onExecute = onExecute;
            this.asyncLock = asyncLock;
            this.commitFailure = commitFailure;
        }

        static FakeWork blocking(String id, List<String> events, Runnable onExecute) {
            return new FakeWork(id, events, onExecute, null, null);
        }

        /**
         * Like {@link #blocking}, but releases {@code lock} around {@code onExecute} and
         * re-acquires it at the work's assigned priority — the worker-side protocol of a
         * durable-async segment.
         */
        static FakeWork blockingAsync(
                String id, List<String> events, ParallelExecutionLock lock, Runnable onExecute) {
            return new FakeWork(id, events, onExecute, lock, null);
        }

        static FakeWork blockingAsyncFailingCommit(
                String id,
                List<String> events,
                ParallelExecutionLock lock,
                Runnable onExecute,
                RuntimeException commitFailure) {
            return new FakeWork(id, events, onExecute, lock, commitFailure);
        }

        static FakeWork failingCommit(
                String id, List<String> events, RuntimeException commitFailure) {
            return new FakeWork(id, events, null, null, commitFailure);
        }

        @Override
        public void setup(Object key, long recordIndex, long taskIndex) {
            this.assignedKey = key;
            this.assignedRecordIndex = recordIndex;
            this.assignedTaskIndex = taskIndex;
        }

        @Override
        public void restoreContext() {
            events.add("restore-" + id);
        }

        @Override
        public void execute() {
            events.add("execute-" + id);
            if (asyncLock != null) {
                // Durable-async protocol: give up the lock while blocked, then resume at the
                // work's assigned (recordIndex, taskIndex) priority.
                asyncLock.release();
                try {
                    if (onExecute != null) {
                        onExecute.run();
                    }
                } finally {
                    try {
                        asyncLock.acquireByWorker(assignedRecordIndex, assignedTaskIndex);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Interrupted while re-acquiring the lock", e);
                    }
                }
            } else if (onExecute != null) {
                onExecute.run();
            }
            done = true;
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public void commit() {
            if (commitFailure != null) {
                throw commitFailure;
            }
            events.add("commit-" + id);
        }

        @Override
        public void finishGroup(@Nullable ParallelExecutionTask lastCommitted) {
            events.add(
                    "finish-"
                            + id
                            + "->"
                            + (lastCommitted == null ? "none" : ((FakeWork) lastCommitted).id));
        }
    }
}
