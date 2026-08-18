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
 * Runtime base for sub-agent setups, holding the per-task id allocators and pending-call registries
 * keyed to the currently executing action task. How an invocation is issued stays an execution mode
 * owned by the concrete subclass.
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
            registry.setActionName(to.getAction().getName());
            perTaskRegistries.put(to, registry);
        }
    }

    @Override
    public void onTaskFinished(ActionTask task) {
        currentTask = null;
        perTaskAllocators.remove(task);
        PendingSubagentCallRegistry registry = perTaskRegistries.remove(task);
        if (registry != null) {
            registry.checkEmpty();
        }
    }

    /**
     * The registry of the currently executing task, where handles record themselves on creation.
     * Returns {@code null} outside a prepared task, so calls issued without a task context skip
     * tracking.
     */
    @Nullable
    protected final PendingSubagentCallRegistry currentTaskRegistry() {
        if (currentTask == null) {
            return null;
        }
        ActionTask task = currentTask;
        return perTaskRegistries.computeIfAbsent(
                task, t -> new PendingSubagentCallRegistry(t.getAction().getName()));
    }

    /** The task whose execution is currently issuing calls, or {@code null} outside one. */
    @Nullable
    protected final ActionTask currentTask() {
        return currentTask;
    }

    /**
     * Injected by the framework with the setup's resource name when the resource is materialized.
     */
    @Nullable private String subagentName;

    public final void setSubagentName(String subagentName) {
        this.subagentName = subagentName;
    }

    public final String getSubagentName() {
        return subagentName;
    }

    @Override
    public SubagentFuture submit(RunnerContext ctx, Object prompt, String sessionId)
            throws Exception {
        return submit(ctx, prompt, sessionId, currentAllocator().nextCallId(sessionId));
    }

    @Override
    public SubagentFuture submit(RunnerContext ctx, Object prompt) throws Exception {
        SubagentIdAllocator allocator = currentAllocator();
        String sessionId = allocator.nextSessionId();
        return submit(ctx, prompt, sessionId, allocator.nextCallId(sessionId));
    }

    /**
     * The allocator of the currently executing task, scoped to one action execution so ordinals
     * restart for the next action. Failover replays hand out the same ids.
     */
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
                                subagentName));
    }
}
