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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link ParallelExecutionTaskQueue}. */
class ParallelExecutionTaskQueueTest {

    /**
     * A queue whose lock is held by the test thread for the whole test, so every lock-asserted call
     * can be driven single-threaded.
     */
    private static ParallelExecutionTaskQueue newSchedule() {
        ParallelExecutionLock lock = new ParallelExecutionLock();
        try {
            lock.acquireByMain();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while acquiring the test lock", e);
        }
        return new ParallelExecutionTaskQueue(lock);
    }

    @Test
    void addTaskCreatesGroupAndAssignsRecordIndexAndPerKeyTaskIndex() {
        ParallelExecutionTaskQueue schedule = newSchedule();
        schedule.addTask("a");
        schedule.addTask("a");

        ParallelExecutionTaskQueue.Node first = schedule.pollPendingNode();
        assertThat(first.getKey()).isEqualTo("a");
        assertThat(first.getRecordIndex()).isEqualTo(0L);
        assertThat(first.getTaskIndex()).isEqualTo(0L);

        ParallelExecutionTaskQueue.Node second = schedule.pollPendingNode();
        assertThat(second.getRecordIndex()).isEqualTo(0L);
        assertThat(second.getTaskIndex()).isEqualTo(1L);
    }

    @Test
    void pollPendingServesLowestRecordIndexThenTaskIndex() {
        ParallelExecutionTaskQueue schedule = newSchedule();
        // Key group recordIndex is assigned on the first addTask: "a" first (recordIndex 0), then
        // "b" (recordIndex 1). The interleaved third add for "a" still sorts by (recordIndex,
        // taskIndex), not add order.
        schedule.addTask("a");
        schedule.addTask("b");
        schedule.addTask("a");

        ParallelExecutionTaskQueue.Node n0 = schedule.pollPendingNode();
        assertThat(n0.getKey()).isEqualTo("a");
        assertThat(n0.getRecordIndex()).isEqualTo(0L);
        assertThat(n0.getTaskIndex()).isEqualTo(0L);
        ParallelExecutionTaskQueue.Node n1 = schedule.pollPendingNode();
        assertThat(n1.getKey()).isEqualTo("a");
        assertThat(n1.getTaskIndex()).isEqualTo(1L);
        ParallelExecutionTaskQueue.Node n2 = schedule.pollPendingNode();
        assertThat(n2.getKey()).isEqualTo("b");
        assertThat(n2.getRecordIndex()).isEqualTo(1L);
        assertThat(n2.getTaskIndex()).isEqualTo(0L);
    }

    @Test
    void hasOutstandingStaysTrueFromAddTaskThroughCommit() {
        ParallelExecutionTaskQueue schedule = newSchedule();
        assertThat(schedule.hasOutstanding("a")).isFalse();

        schedule.addTask("a");
        assertThat(schedule.hasOutstanding("a")).isTrue();

        ParallelExecutionTaskQueue.Node node = schedule.pollPendingNode();
        // Node pulled off the pull queue but still in its group: must remain outstanding.
        assertThat(schedule.hasOutstanding("a")).isTrue();

        schedule.markDone(node);
        assertThat(schedule.hasOutstanding("a")).isTrue();

        // Committing the last node drops the group, so the key is no longer outstanding.
        schedule.removeCommitted(node);
        assertThat(schedule.hasOutstanding("a")).isFalse();
    }

    @Test
    void hasOutstandingCountsEachNode() {
        ParallelExecutionTaskQueue schedule = newSchedule();
        schedule.addTask("a");
        schedule.addTask("a");
        ParallelExecutionTaskQueue.Node a0 = schedule.pollPendingNode();
        ParallelExecutionTaskQueue.Node a1 = schedule.pollPendingNode();
        schedule.markDone(a0);
        schedule.markDone(a1);

        schedule.removeCommitted(a0);
        assertThat(schedule.hasOutstanding("a")).isTrue();
        schedule.removeCommitted(a1);
        assertThat(schedule.hasOutstanding("a")).isFalse();
    }

    @Test
    void groupIsDroppedWhenLastNodeCommitted() {
        ParallelExecutionTaskQueue schedule = newSchedule();
        schedule.addTask("a");
        ParallelExecutionTaskQueue.Node node = schedule.pollPendingNode();
        schedule.markDone(node);
        schedule.removeCommitted(node);

        assertThat(schedule.hasOutstanding("a")).isFalse();
        assertThat(schedule.sizeForTesting()).isZero();

        // Re-adding the same key mints a fresh group with the next arrival recordIndex.
        schedule.addTask("a");
        assertThat(schedule.pollPendingNode().getRecordIndex()).isEqualTo(1L);
    }

    @Test
    void nodesAreBornRunning() {
        ParallelExecutionTaskQueue schedule = newSchedule();
        schedule.addTask("a");
        assertThat(schedule.pollPendingNode().getState())
                .isEqualTo(ParallelExecutionTaskQueue.State.RUNNING);
    }

    @Test
    void markDoneRequiresRunningNode() {
        ParallelExecutionTaskQueue schedule = newSchedule();
        schedule.addTask("a");
        ParallelExecutionTaskQueue.Node node = schedule.pollPendingNode();
        schedule.markDone(node);
        // Already DONE: a second markDone is rejected.
        assertThatThrownBy(() -> schedule.markDone(node))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RUNNING");
    }

    @Test
    void removeCommittedRequiresDoneNodeAndRemovesIt() {
        ParallelExecutionTaskQueue schedule = newSchedule();
        schedule.addTask("a");
        schedule.addTask("a");
        ParallelExecutionTaskQueue.Node a0 = schedule.pollPendingNode();

        assertThatThrownBy(() -> schedule.removeCommitted(a0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DONE");

        schedule.markDone(a0);
        schedule.removeCommitted(a0);
        assertThat(a0.getState()).isEqualTo(ParallelExecutionTaskQueue.State.COMMITTED);
        assertThat(schedule.sizeForTesting()).isEqualTo(1);
    }

    @Test
    void pollCommittableHeadStopsAtHeadNotYetDone() {
        ParallelExecutionTaskQueue schedule = newSchedule();
        schedule.addTask("a");
        schedule.addTask("a");
        ParallelExecutionTaskQueue.Node a0 = schedule.pollPendingNode();
        ParallelExecutionTaskQueue.Node a1 = schedule.pollPendingNode();

        // Out-of-order completion: a1 is DONE, but the drain must stop at the not-yet-done head a0.
        schedule.markDone(a1);
        assertThat(schedule.pollCommittableHead("a")).isNull();

        // Once a0 is DONE, the mailbox drain commits the contiguous done prefix a0, a1 in order.
        schedule.markDone(a0);
        ParallelExecutionTaskQueue.Node head = schedule.pollCommittableHead("a");
        assertThat(head).isSameAs(a0);
        schedule.removeCommitted(head);
        head = schedule.pollCommittableHead("a");
        assertThat(head).isSameAs(a1);
        schedule.removeCommitted(head);
        assertThat(schedule.pollCommittableHead("a")).isNull();
        assertThat(schedule.sizeForTesting()).isZero();
    }
}
