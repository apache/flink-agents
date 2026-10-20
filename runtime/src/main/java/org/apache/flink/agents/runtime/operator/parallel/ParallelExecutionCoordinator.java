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

import org.apache.flink.agents.runtime.async.AsyncExecutorThreadFactory;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.util.function.ThrowingRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * Coordinates worker execution of {@link ParallelExecutionTask}: callers {@link #addTask(Object)
 * add} one node per queued task, and the coordinator submits one anonymous <em>dispatch permit</em>
 * per node to an elastic {@link ThreadPoolExecutor}. A pool thread redeems a permit under the lock
 * (node poll and FIFO task pull share the lock hold, keeping their binding correct — permits never
 * name a task). Commits run on the mailbox thread in per-key taskIndex order, and a task stays
 * outstanding until its commit completes.
 */
public final class ParallelExecutionCoordinator implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ParallelExecutionCoordinator.class);

    private static final long WORKER_TERMINATION_TIMEOUT_SECONDS = 180L;

    /**
     * Factor applied to {@code num-async-threads} when sizing the worker pool; see {@link
     * #workerCeiling}.
     */
    private static final int WORKER_CEILING_MULTIPLIER = 2;

    /** Lower bound for the worker pool; see {@link #workerCeiling}. */
    private static final int MIN_WORKER_CEILING = 10;

    /**
     * Sizes the worker pool for {@code numAsyncThreads}. An async call runs its supplier inline on
     * the worker that issued it, so that worker is occupied for the whole call even though it holds
     * no lock, and the pool is also what dispatches every queued task. Sized at {@code
     * numAsyncThreads} instead, the calls the pool exists to serve would starve the dispatch queued
     * behind them. That does not fail loudly: {@link DispatchQueue} is unbounded and the pool
     * cannot grow past its maximum, so the queued permits are never redeemed and the job hangs
     * until Flink's checkpoint timeout fails it, naming no cause. The floor extends the headroom to
     * configurations too small for the multiplier to reach it.
     */
    public static int workerCeiling(int numAsyncThreads) {
        return Math.max(MIN_WORKER_CEILING, WORKER_CEILING_MULTIPLIER * numAsyncThreads);
    }

    @FunctionalInterface
    public interface MailboxDispatcher {
        void execute(ThrowingRunnable<? extends Exception> command, String description);
    }

    private final ParallelExecutionTaskQueue schedule;
    private final ParallelExecutionLock parallelExecutionLock;
    private final AsyncExecutorThreadFactory workerThreadFactory;
    private final ThreadPoolExecutor workerExecutor;
    private final MailboxDispatcher mailboxDispatcher;

    /** Supplies a fresh work; the work pulls and prepares its own task inside {@code execute()}. */
    private final Supplier<ParallelExecutionTask> workFactory;

    /** Pulled tasks not yet committed; read by {@link #isQuiesced()} for checkpoint quiesce. */
    private final AtomicInteger inFlightExecuting = new AtomicInteger();

    /**
     * When {@code true}, workers finish in-flight tasks but pull no new nodes. Set before a
     * checkpoint, cleared once the snapshot is taken. Read under {@link #drainLock} together with
     * {@link #forfeitedPermits}, so a worker either observes the drain and hands its permit back
     * for the resume to re-submit, or observes the resume and pulls normally.
     */
    private volatile boolean draining;

    private final Object drainLock = new Object();

    /**
     * Root permits handed back by workers that found a drain in progress. {@link #stopDraining()}
     * re-submits exactly this many, so a pending node never loses the permit that will eventually
     * pull it.
     */
    private int forfeitedPermits;

    /**
     * Permits submitted and not yet fully redeemed; compared against the pool size by {@link
     * DispatchQueue#offer} to decide when the pool must grow.
     */
    private final AtomicInteger submittedPermits = new AtomicInteger();

    /**
     * Creates a coordinator with an elastic worker pool: one resident thread, on-demand growth up
     * to {@code maxWorkers}, and idle retirement after {@code workerIdleTimeoutMillis}. Every
     * worker participates in the managed async lifecycle and runs {@code threadCleanup} when it
     * retires or the pool shuts down. Admission is not gated here; the operator bounds outstanding
     * work via input-record backpressure.
     */
    public ParallelExecutionCoordinator(
            ParallelExecutionLock parallelExecutionLock,
            MailboxDispatcher mailboxDispatcher,
            Supplier<ParallelExecutionTask> workFactory,
            Runnable threadCleanup,
            int maxWorkers,
            long workerIdleTimeoutMillis) {
        this.parallelExecutionLock = checkNotNull(parallelExecutionLock);
        this.schedule = new ParallelExecutionTaskQueue(parallelExecutionLock);
        this.mailboxDispatcher = checkNotNull(mailboxDispatcher);
        this.workFactory = checkNotNull(workFactory);
        this.workerThreadFactory = new AsyncExecutorThreadFactory(checkNotNull(threadCleanup));
        DispatchQueue dispatchQueue = new DispatchQueue();
        this.workerExecutor =
                new ThreadPoolExecutor(
                        1,
                        maxWorkers,
                        workerIdleTimeoutMillis,
                        TimeUnit.MILLISECONDS,
                        dispatchQueue,
                        workerThreadFactory,
                        (permit, pool) -> {
                            // Only reachable when offer() returned false to grow the pool but the
                            // addWorker race was lost (already at max), or during shutdown.
                            if (pool.isShutdown()) {
                                submittedPermits.decrementAndGet();
                            } else {
                                dispatchQueue.forceOffer(permit);
                            }
                        });
    }

    /**
     * Adds one task node for {@code key} and submits one anonymous dispatch permit (a permit means
     * "pull one node under the lock", never a specific task, so pool threads may redeem permits in
     * any order). Must be called on the mailbox thread holding the lock.
     */
    public void addTask(Object key) {
        schedule.addTask(key);
        submittedPermits.incrementAndGet();
        workerExecutor.execute(this::runOneTask);
    }

    /** Whether {@code key} still has outstanding (added but not yet committed) work. */
    public boolean hasOutstanding(Object key) {
        return schedule.hasOutstanding(key);
    }

    /**
     * Pauses new task dispatch before a checkpoint: workers finish and commit in-flight tasks but
     * pull no new nodes. Returns only after fencing in-progress pulls: a worker increments {@link
     * #inFlightExecuting} before it first releases the lock, so after the acquire/release below
     * {@link #isQuiesced()} can no longer miss a pulled task.
     */
    public void startDraining() throws InterruptedException {
        synchronized (drainLock) {
            draining = true;
        }
        parallelExecutionLock.acquireByMain();
        parallelExecutionLock.release();
    }

    /**
     * Resumes task dispatch after a checkpoint: re-submits one permit per worker that handed its
     * permit back during the drain, then takes and releases the lock once as the mailbox. That
     * consumes any reservation left to the mailbox, which workers always yield to, so the
     * re-submitted permits are redeemed at once instead of stalling until the next mailbox action.
     * Idempotent: a second call finds nothing forfeited and only repeats the fence.
     */
    public void stopDraining() throws InterruptedException {
        int resubmit;
        synchronized (drainLock) {
            draining = false;
            resubmit = forfeitedPermits;
            forfeitedPermits = 0;
        }
        for (int i = 0; i < resubmit; i++) {
            submittedPermits.incrementAndGet();
            workerExecutor.execute(this::runOneTask);
        }
        parallelExecutionLock.acquireByMain();
        parallelExecutionLock.release();
    }

    /** Whether no task is currently pulled-but-uncommitted, i.e. a safe point for a snapshot. */
    public boolean isQuiesced() {
        return inFlightExecuting.get() == 0;
    }

    /**
     * Reports the pool state that explains a checkpoint drain which is not quiescing: how many
     * workers exist against the ceiling, how many are executing, and the permit counters behind
     * them. Reads counters only, so the mailbox can call this while workers are running.
     */
    public String diagnostics() {
        synchronized (drainLock) {
            return "workers="
                    + workerExecutor.getPoolSize()
                    + '/'
                    + workerExecutor.getMaximumPoolSize()
                    + ", executing="
                    + workerExecutor.getActiveCount()
                    + ", inFlight="
                    + inFlightExecuting.get()
                    + ", queuedPermits="
                    + workerExecutor.getQueue().size()
                    + ", submittedPermits="
                    + submittedPermits.get()
                    + ", forfeitedPermits="
                    + forfeitedPermits;
        }
    }

    /**
     * Redeems one dispatch permit: under the lock, takes the highest-priority pending node, runs a
     * fresh work, then hands the completion to the mailbox commit drain. A worker that finds a
     * checkpoint drain in progress hands its permit back and exits instead of holding the thread.
     */
    private void runOneTask() {
        try {
            try {
                // Fresh pulls acquire at the lowest priority: async resumes always take precedence.
                parallelExecutionLock.acquireByWorker(Long.MAX_VALUE, Long.MAX_VALUE);
            } catch (InterruptedException e) {
                // The only interruptible call here, and it throws without holding the lock, so an
                // interrupt is unambiguously the shutdown signal.
                Thread.currentThread().interrupt();
                return;
            }
            synchronized (drainLock) {
                if (draining) {
                    // Parking here would hold a worker out of the pool for the whole snapshot,
                    // leaving every dispatch queued behind it -- a pending root node, or an async
                    // call resuming onto the mailbox -- with no thread to run on. Forfeiting costs
                    // nothing at this point: the node is still pending and quiesce accounting
                    // starts after the poll.
                    forfeitedPermits++;
                    parallelExecutionLock.release();
                    return;
                }
            }

            // Holding the lock from here on: every failure is funneled to the mailbox (an uncaught
            // throwable would only kill this pool thread silently), and the lock is returned
            // unless a shutdown interrupt aborted the work's async re-acquire.
            try {
                ParallelExecutionTaskQueue.Node node = schedule.pollPendingNode();
                ParallelExecutionTask work = workFactory.get();
                work.setup(node.getKey(), node.getRecordIndex(), node.getTaskIndex());
                node.setWork(work);
                inFlightExecuting.incrementAndGet();
                // execute() never throws: it captures pull/prepare/run failures and rethrows at
                // commit.
                work.execute();
                if (workerExecutor.isShutdown() && !parallelExecutionLock.isHeldByCurrentThread()) {
                    // A shutdown interrupt aborted the work's async re-acquire, so this worker no
                    // longer owns the lock: abandon the task without completing or releasing it.
                    return;
                }
                parallelExecutionLock.checkReentrant();
                schedule.markDone(node);
                mailboxDispatcher.execute(() -> completeOnMailbox(node), "complete action task");
            } catch (Throwable t) {
                mailboxDispatcher.execute(
                        () -> {
                            throw t;
                        },
                        "throw action task scheduling failure");
            } finally {
                if (parallelExecutionLock.isHeldByCurrentThread()) {
                    parallelExecutionLock.releaseToMain();
                }
            }
        } finally {
            submittedPermits.decrementAndGet();
        }
    }

    /**
     * Mailbox-thread commit drain: commits the completed node's key group in taskIndex order,
     * stopping at the first not-yet-done head (picked up by a later drain), then runs the group's
     * follow-up.
     */
    private void completeOnMailbox(ParallelExecutionTaskQueue.Node completedNode) throws Exception {
        parallelExecutionLock.acquireByMain();
        try {
            Object key = completedNode.getKey();
            ParallelExecutionTask lastCommitted = null;
            while (true) {
                ParallelExecutionTaskQueue.Node head = schedule.pollCommittableHead(key);
                if (head == null) {
                    break;
                }
                try {
                    head.getWork().commit();
                    lastCommitted = head.getWork();
                } finally {
                    schedule.removeCommitted(head);
                    inFlightExecuting.decrementAndGet();
                }
            }
            completedNode.getWork().finishGroup(lastCommitted);
        } finally {
            parallelExecutionLock.release();
        }
    }

    /** Stops dispatch and interrupts action workers without waiting for exit cleanup. */
    public void shutdown() {
        workerExecutor.shutdownNow();
    }

    @Override
    public void close() {
        // Wait for in-flight workers to terminate before the caller releases resources they may
        // still be using. Interrupts are re-issued each round: a worker may block on the lock
        // (e.g. a sticky main-grant nobody consumes while the mailbox waits here) only AFTER the
        // first interrupt was delivered, so a single shutdownNow is not enough. Waiting for worker
        // execution is bounded because user code may ignore the interrupt; once the executor has
        // terminated, wait for every worker's exit cleanup before returning.
        long deadlineNanos =
                System.nanoTime() + TimeUnit.SECONDS.toNanos(WORKER_TERMINATION_TIMEOUT_SECONDS);
        try {
            do {
                shutdown();
            } while (!workerExecutor.awaitTermination(1, TimeUnit.SECONDS)
                    && System.nanoTime() < deadlineNanos);
            if (workerExecutor.isTerminated()) {
                // ThreadPoolExecutor may signal termination before the ThreadFactory wrapper has
                // run its exit cleanup. Join the already-terminating workers before resources used
                // by that cleanup are closed by the operator.
                workerThreadFactory.awaitThreadExit();
            } else {
                LOG.warn(
                        "Worker pool did not terminate within {}s; proceeding with close.",
                        WORKER_TERMINATION_TIMEOUT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @VisibleForTesting
    int getWorkerPoolSizeForTesting() {
        return workerExecutor.getPoolSize();
    }

    /** Root permits handed back by workers since the last resume; see {@link #runOneTask()}. */
    @VisibleForTesting
    public int getForfeitedPermitsForTesting() {
        synchronized (drainLock) {
            return forfeitedPermits;
        }
    }

    /**
     * Work queue of anonymous dispatch permits. {@link #offer} reports "full" when every pool
     * thread is busy and the pool may still grow — the only way to make {@link ThreadPoolExecutor}
     * add threads beyond its core size; the refused permit is handed to the new thread or re-queued
     * by the rejection handler. Idle threads simply time out polling this queue and exit.
     */
    private final class DispatchQueue extends LinkedBlockingQueue<Runnable> {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean offer(Runnable permit) {
            ThreadPoolExecutor pool = workerExecutor;
            if (pool != null) {
                int poolSize = pool.getPoolSize();
                if (poolSize < pool.getMaximumPoolSize() && submittedPermits.get() > poolSize) {
                    // Every thread is busy and the pool is below its cap: refuse, forcing the
                    // pool to spawn a thread for this permit.
                    return false;
                }
            }
            return super.offer(permit);
        }

        private boolean forceOffer(Runnable permit) {
            return super.offer(permit);
        }
    }
}
