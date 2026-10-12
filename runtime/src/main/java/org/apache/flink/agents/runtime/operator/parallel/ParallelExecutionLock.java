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

import org.apache.flink.annotation.VisibleForTesting;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Non-reentrant lock for the JDK<21 parallel execution engine. The main (mailbox) thread has
 * absolute priority; workers are granted in ascending (recordIndex, taskIndex) order, ties
 * unordered. {@link #releaseToMain()} reserves the lock for the main thread via a sticky {@code
 * mailboxGranted} flag even before it starts waiting.
 */
public final class ParallelExecutionLock {

    /** Worker grant order: ascending recordIndex, then taskIndex; ties unordered. */
    private static final Comparator<WorkerNode> WORKER_ORDER =
            Comparator.comparingLong((WorkerNode node) -> node.recordIndex)
                    .thenComparingLong(node -> node.taskIndex);

    private final ReentrantLock innerLock = new ReentrantLock();
    private final Condition mailboxCondition = innerLock.newCondition();
    private final PriorityQueue<WorkerNode> workerQueue = new PriorityQueue<>(WORKER_ORDER);

    /**
     * Current owner, or {@code null}. Releasers assign ownership directly to the next queued
     * worker, so the lock is held iff {@code ownerThread != null || mailboxGranted}.
     */
    private volatile Thread ownerThread;

    /**
     * Sticky reservation for the main thread (whose identity may not be known yet); workers block
     * on it. Read/written under {@link #innerLock} only.
     */
    private boolean mailboxGranted;

    /** Whether the main thread is blocked in {@link #acquireByMain()}. Guarded by innerLock. */
    private boolean mailboxWaiting;

    // -----------------------------------------------------------------------
    // Lock operations
    // -----------------------------------------------------------------------

    /** Acquires the lock for the mailbox thread. Mailbox acquisition has the highest priority. */
    public void acquireByMain() throws InterruptedException {
        Thread currentThread = Thread.currentThread();
        innerLock.lockInterruptibly();
        try {
            checkNonReentrant();
            // Fast path 1: lock is free
            if (ownerThread == null && !mailboxGranted) {
                ownerThread = currentThread;
                return;
            }
            // Fast path 2: lock is sticky-granted to mailbox
            if (mailboxGranted) {
                mailboxGranted = false;
                ownerThread = currentThread;
                return;
            }
            if (mailboxWaiting) {
                throw new IllegalStateException(
                        "Only one mailbox thread can wait for the lock at a time.");
            }
            // Slow path: wait until granted
            mailboxWaiting = true;
            try {
                while (!mailboxGranted) {
                    mailboxCondition.await();
                }
                mailboxGranted = false;
                ownerThread = currentThread;
            } catch (InterruptedException e) {
                // If we were already granted while interrupted, transfer the lock rather than
                // keeping it.
                if (mailboxGranted) {
                    mailboxGranted = false;
                    mailboxWaiting = false;
                    transferToNext();
                }
                throw e;
            } finally {
                mailboxWaiting = false;
            }
        } finally {
            innerLock.unlock();
        }
    }

    /**
     * Acquires the lock for a worker at the given (recordIndex, taskIndex) priority. The main
     * thread always has absolute priority over workers.
     */
    public void acquireByWorker(long recordIndex, long taskIndex) throws InterruptedException {
        Thread currentThread = Thread.currentThread();
        innerLock.lockInterruptibly();
        try {
            checkNonReentrant();
            // Fast path: free, no mailbox reservation or waiter, and no queued workers
            if (ownerThread == null
                    && !mailboxGranted
                    && !mailboxWaiting
                    && workerQueue.isEmpty()) {
                ownerThread = currentThread;
                return;
            }
            // Slow path: join the priority queue; granted in (recordIndex, taskIndex) order
            WorkerNode node =
                    new WorkerNode(innerLock.newCondition(), currentThread, recordIndex, taskIndex);
            workerQueue.add(node);
            try {
                while (!node.granted) {
                    node.condition.await();
                }
                // The releaser assigned ownership to this thread directly; nothing to claim.
            } catch (InterruptedException e) {
                if (node.granted) {
                    // Already made the owner but bailing out: pass the lock on.
                    ownerThread = null;
                    transferToNext();
                } else {
                    workerQueue.remove(node);
                }
                throw e;
            }
        } finally {
            innerLock.unlock();
        }
    }

    /**
     * Releases the lock and transfers ownership mailbox-first, then to the highest-priority worker.
     */
    public void release() {
        innerLock.lock();
        try {
            checkReentrant();
            ownerThread = null;
            transferToNext();
        } finally {
            innerLock.unlock();
        }
    }

    /** Releases worker ownership and reserves the lock for the mailbox thread. */
    public void releaseToMain() {
        innerLock.lock();
        try {
            checkReentrant();
            ownerThread = null;
            mailboxGranted = true;
            if (mailboxWaiting) {
                mailboxCondition.signal();
            }
        } finally {
            innerLock.unlock();
        }
    }

    /** Asserts that the calling thread currently owns the lock. */
    public void checkReentrant() {
        if (ownerThread != Thread.currentThread()) {
            throw new IllegalStateException(
                    "Current thread does not own the lock. owner="
                            + ownerThread
                            + ", current="
                            + Thread.currentThread());
        }
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Transfers ownership main-first, then to the highest-priority queued worker, else frees the
     * lock. Must be called under {@link #innerLock}.
     */
    private void transferToNext() {
        if (mailboxWaiting) {
            mailboxGranted = true;
            mailboxCondition.signal();
            return;
        }
        WorkerNode nextWorker = workerQueue.poll();
        if (nextWorker != null) {
            ownerThread = nextWorker.thread;
            nextWorker.granted = true;
            nextWorker.condition.signal();
        }
        // Otherwise the lock is free: ownerThread is null and no reservation is set.
    }

    private void checkNonReentrant() {
        if (ownerThread == Thread.currentThread()) {
            throw new IllegalStateException(
                    "ParallelExecutionLock is non-reentrant; the owner cannot acquire it again.");
        }
    }

    // -----------------------------------------------------------------------
    // Test-only accessors
    // -----------------------------------------------------------------------

    @VisibleForTesting
    boolean isLockedForTesting() {
        innerLock.lock();
        try {
            return ownerThread != null || mailboxGranted;
        } finally {
            innerLock.unlock();
        }
    }

    @VisibleForTesting
    boolean isMailboxGrantedForTesting() {
        innerLock.lock();
        try {
            return mailboxGranted;
        } finally {
            innerLock.unlock();
        }
    }

    @VisibleForTesting
    boolean isMailboxWaitingForTesting() {
        innerLock.lock();
        try {
            return mailboxWaiting;
        } finally {
            innerLock.unlock();
        }
    }

    @VisibleForTesting
    int getQueuedWorkerCountForTesting() {
        innerLock.lock();
        try {
            return workerQueue.size();
        } finally {
            innerLock.unlock();
        }
    }

    // -----------------------------------------------------------------------
    // Inner types
    // -----------------------------------------------------------------------

    private static final class WorkerNode {
        private final Condition condition;
        private final Thread thread;
        private final long recordIndex;
        private final long taskIndex;
        private boolean granted;

        private WorkerNode(Condition condition, Thread thread, long recordIndex, long taskIndex) {
            this.condition = condition;
            this.thread = thread;
            this.recordIndex = recordIndex;
            this.taskIndex = taskIndex;
        }
    }
}
