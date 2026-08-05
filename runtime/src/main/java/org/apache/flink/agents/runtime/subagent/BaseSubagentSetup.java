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

package org.apache.flink.agents.runtime.subagent;

import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.subagent.SubagentFuture;
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.runtime.lifecycle.TaskLifecycleListener;
import org.apache.flink.agents.runtime.operator.ActionTask;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Runtime base for sub-agent setups: task lifecycle observation plus deterministic id assignment
 * for the short {@link org.apache.flink.agents.api.subagent.Subagent} forms.
 *
 * <p>The setup observes the task lifecycle to know which action task is currently executing: submit
 * runs on the mailbox thread immediately after the executing task's {@code onTaskPrepared}. The
 * short forms then assign the missing ids through a per-task {@link SubagentIdAllocator} built from
 * that task's caller-side facts, so a failover replay assigns the same ids, and delegate to the
 * full {@code submit}, which stays abstract: how an invocation is issued is an execution mode owned
 * by the concrete subclass.
 *
 * <p>The base also owns the per-task {@link PendingSubagentCallRegistry}: handles created during
 * the task record themselves there, and {@code onTaskFinished} fails the action when a handle was
 * left unresolved — a dropped handle must either be resolved or explicitly cancelled, never
 * silently skipped.
 */
public abstract class BaseSubagentSetup extends SubagentSetup implements TaskLifecycleListener {

    private final Map<ActionTask, SubagentIdAllocator> perTaskAllocators = new HashMap<>();
    private final Map<ActionTask, PendingSubagentCallRegistry> perTaskRegistries = new HashMap<>();

    /** The task whose execution is currently issuing calls. */
    @Nullable private ActionTask currentTask;

    @Override
    public void onTaskPrepared(ActionTask task) {
        currentTask = task;
    }

    @Override
    public void onTaskTransferred(ActionTask from, ActionTask to) {
        SubagentIdAllocator allocator = perTaskAllocators.remove(from);
        if (allocator != null) {
            perTaskAllocators.put(to, allocator);
        }
        PendingSubagentCallRegistry registry = perTaskRegistries.remove(from);
        if (registry != null) {
            perTaskRegistries.put(to, registry);
        }
    }

    @Override
    public void onTaskFinished(ActionTask task) {
        currentTask = null;
        perTaskAllocators.remove(task);
        PendingSubagentCallRegistry registry = perTaskRegistries.remove(task);
        if (registry != null) {
            registry.checkEmpty(task.getAction().getName());
        }
    }

    /**
     * The registry of the currently executing task; handles record themselves there on creation.
     * Returns {@code null} outside a prepared task, so calls issued without a task context skip
     * tracking.
     */
    @Nullable
    protected final PendingSubagentCallRegistry currentTaskRegistry() {
        if (currentTask == null) {
            return null;
        }
        ActionTask task = currentTask;
        return perTaskRegistries.computeIfAbsent(task, t -> new PendingSubagentCallRegistry());
    }

    /** The task whose execution is currently issuing calls, or {@code null} outside one. */
    @Nullable
    protected final ActionTask currentTask() {
        return currentTask;
    }

    /**
     * The (qualified) resource name of this setup's sub-agent, injected by the framework when the
     * setup is materialized: the bare resource name at the root cache, qualified by the enclosing
     * plan's scope for nested setups. It is carried into the id namespace as the agent name, so
     * sub-agents sharing one caller's counting range never hand out the same ids. Setups created
     * outside the framework may leave it unset (a single sub-agent per action execution needs no
     * name).
     */
    @Nullable private String resourceName;

    public final void setResourceName(String resourceName) {
        this.resourceName = resourceName;
    }

    public final String getResourceName() {
        return resourceName;
    }

    /** Issues under the given session with a deterministically assigned call id. */
    @Override
    public SubagentFuture submit(RunnerContext ctx, Object prompt, String sessionId)
            throws Exception {
        return submit(ctx, prompt, sessionId, currentAllocator().nextCallId(sessionId));
    }

    /** Issues under a fully assigned identity. */
    @Override
    public SubagentFuture submit(RunnerContext ctx, Object prompt) throws Exception {
        SubagentIdAllocator allocator = currentAllocator();
        String sessionId = allocator.nextSessionId();
        return submit(ctx, prompt, sessionId, allocator.nextCallId(sessionId));
    }

    /** The allocator of the currently executing task; failover replays hand out the same ids. */
    protected final SubagentIdAllocator currentAllocator() {
        if (currentTask == null) {
            throw new IllegalStateException(
                    "No prepared action task to assign sub-agent ids from.");
        }
        ActionTask task = currentTask;
        return perTaskAllocators.computeIfAbsent(
                task,
                t ->
                        new SubagentIdAllocator(
                                t.getKey(),
                                t.getSequenceNumber(),
                                t.getAction().getName(),
                                t.getEvent(),
                                resourceName));
    }
}
