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

import javax.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * Key-grouped ledger of action task nodes for the parallel execution engine. A node is created on
 * {@link #addTask(Object)} with its (recordIndex, taskIndex) priority and lives in its key group
 * until committed; pull takes the highest-priority pending node, commit drains each group's DONE
 * head in taskIndex order, and an emptied group is dropped ({@code hasOutstanding == false}). Holds
 * no internal lock: mutators assert the caller owns the {@link ParallelExecutionLock}.
 */
final class ParallelExecutionTaskQueue {

    /** Node lifecycle: RUNNING (born, to be pulled) -> DONE (executed) -> COMMITTED (removed). */
    enum State {
        RUNNING,
        DONE,
        COMMITTED
    }

    /**
     * Per-key group: arrival-ordered {@code recordIndex}, next per-key {@code taskIndex}, and its
     * nodes.
     */
    private static final class KeyGroup {
        private final Object key;
        private final long recordIndex;
        private long taskIndexGen;
        private final Deque<Node> nodes = new ArrayDeque<>();

        private KeyGroup(Object key, long recordIndex) {
            this.key = key;
            this.recordIndex = recordIndex;
        }
    }

    /** A single action task, ordered within its group by {@code taskIndex}. */
    static final class Node {
        private final KeyGroup group;
        private final long taskIndex;
        private State state = State.RUNNING;
        private ParallelExecutionTask work;

        private Node(KeyGroup group, long taskIndex) {
            this.group = group;
            this.taskIndex = taskIndex;
        }

        Object getKey() {
            return group.key;
        }

        long getRecordIndex() {
            return group.recordIndex;
        }

        long getTaskIndex() {
            return taskIndex;
        }

        State getState() {
            return state;
        }

        ParallelExecutionTask getWork() {
            return work;
        }

        /**
         * Sets the work; called by the pulling worker under the lock, which also publishes it to
         * the commit-drain readers.
         */
        void setWork(ParallelExecutionTask work) {
            this.work = work;
        }
    }

    /** Worker pull order: ascending group {@code recordIndex}, then per-key {@code taskIndex}. */
    private static final Comparator<Node> PULL_ORDER =
            Comparator.comparingLong((Node node) -> node.group.recordIndex)
                    .thenComparingLong(node -> node.taskIndex);

    /** Enforces the "called while holding the mailbox lock" contract on mutating methods. */
    private final ParallelExecutionLock parallelExecutionLock;

    /**
     * Active key groups, keyed by the operator key. A group exists iff it has outstanding nodes.
     */
    private final Map<Object, KeyGroup> groupsByKey = new HashMap<>();

    /** Not-yet-pulled nodes across all groups, ordered by (recordIndex, taskIndex). */
    private final PriorityQueue<Node> pendingNodes = new PriorityQueue<>(PULL_ORDER);

    /** Next key arrival sequence to assign. */
    private long recordIndexGen;

    ParallelExecutionTaskQueue(ParallelExecutionLock parallelExecutionLock) {
        this.parallelExecutionLock = checkNotNull(parallelExecutionLock);
    }

    /**
     * Creates a pending node for {@code key} (lazily creating its arrival-sequenced group) and
     * registers it in the pull queue; the caller submits the matching dispatch permit.
     */
    void addTask(Object key) {
        parallelExecutionLock.checkReentrant();
        KeyGroup group = groupsByKey.computeIfAbsent(key, k -> new KeyGroup(k, recordIndexGen++));
        Node node = new Node(group, group.taskIndexGen++);
        group.nodes.addLast(node);
        pendingNodes.add(node);
    }

    /**
     * Takes the highest-priority pending node; guaranteed present because the coordinator submits
     * exactly one dispatch permit per pending node. The node stays in its key group.
     */
    Node pollPendingNode() {
        parallelExecutionLock.checkReentrant();
        Node node = pendingNodes.poll();
        checkState(node != null, "No pending node available for a redeemed dispatch permit.");
        return node;
    }

    /** Marks a node as executed; called by the owning worker before handing off to the mailbox. */
    void markDone(Node node) {
        parallelExecutionLock.checkReentrant();
        checkState(
                node.state == State.RUNNING,
                "Node must be RUNNING to mark DONE, but was: %s",
                node.state);
        node.state = State.DONE;
    }

    /**
     * Returns {@code key}'s group head iff it is DONE, without removing it; otherwise {@code null}.
     * The commit drain repeats commit + {@link #removeCommitted} until this returns null.
     */
    @Nullable
    Node pollCommittableHead(Object key) {
        KeyGroup group = groupsByKey.get(key);
        if (group == null || group.nodes.isEmpty()) {
            return null;
        }
        Node head = group.nodes.peekFirst();
        return head.state == State.DONE ? head : null;
    }

    /**
     * Removes a committed node (must be its group's head); drops the group once its last node is
     * removed, so a drained key is no longer outstanding.
     */
    void removeCommitted(Node node) {
        parallelExecutionLock.checkReentrant();
        checkState(
                node.state == State.DONE, "Node must be DONE to commit, but was: %s", node.state);
        node.state = State.COMMITTED;
        KeyGroup group = node.group;
        Node head = group.nodes.pollFirst();
        checkState(head == node, "Committed node was not its key group's head.");
        if (group.nodes.isEmpty()) {
            groupsByKey.remove(group.key);
        }
    }

    /** Whether {@code key} still has outstanding (added but not yet committed) nodes. */
    boolean hasOutstanding(Object key) {
        KeyGroup group = groupsByKey.get(key);
        return group != null && !group.nodes.isEmpty();
    }

    @VisibleForTesting
    int sizeForTesting() {
        int total = 0;
        for (KeyGroup group : groupsByKey.values()) {
            total += group.nodes.size();
        }
        return total;
    }
}
