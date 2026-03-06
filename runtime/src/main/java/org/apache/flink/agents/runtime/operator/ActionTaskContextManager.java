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
package org.apache.flink.agents.runtime.operator;

import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.async.ContinuationContext;
import org.apache.flink.agents.runtime.context.JavaRunnerContextImpl;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.memory.CachedMemoryStore;
import org.apache.flink.agents.runtime.memory.MemoryObjectImpl;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.agents.runtime.python.context.PythonRunnerContextImpl;
import org.apache.flink.api.common.state.MapState;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages runner context lifecycle for action tasks, including creation, context switching for
 * async continuation, and cleanup.
 *
 * <p>Holds four transient maps that track intermediate context state across async action task
 * invocations: memory contexts, durable execution contexts, continuation contexts, and Python
 * awaitable references.
 */
class ActionTaskContextManager {

    // Invariant dependencies (set once at construction, never change)
    private final FlinkAgentsMetricGroupImpl metricGroup;
    private final Runnable mailboxThreadChecker;
    private final AgentPlan agentPlan;
    private final String jobIdentifier;
    private final PythonRunnerContextImpl pythonRunnerContext;

    // Lazily created singleton runner context for Java actions
    private RunnerContextImpl javaRunnerContext;

    // Transient maps for in-flight async action tasks
    private final Map<ActionTask, RunnerContextImpl.MemoryContext> actionTaskMemoryContexts;
    private final Map<ActionTask, RunnerContextImpl.DurableExecutionContext>
            actionTaskDurableContexts;
    private final Map<ActionTask, ContinuationContext> continuationContexts;
    private final Map<ActionTask, String> pythonAwaitableRefs;

    private final ContinuationActionExecutor continuationActionExecutor;

    ActionTaskContextManager(
            FlinkAgentsMetricGroupImpl metricGroup,
            Runnable mailboxThreadChecker,
            AgentPlan agentPlan,
            String jobIdentifier,
            PythonRunnerContextImpl pythonRunnerContext) {
        this.metricGroup = metricGroup;
        this.mailboxThreadChecker = mailboxThreadChecker;
        this.agentPlan = agentPlan;
        this.jobIdentifier = jobIdentifier;
        this.pythonRunnerContext = pythonRunnerContext;
        this.continuationActionExecutor =
                new ContinuationActionExecutor(
                        agentPlan.getConfig().get(AgentExecutionOptions.NUM_ASYNC_THREADS));
        this.actionTaskMemoryContexts = new HashMap<>();
        this.actionTaskDurableContexts = new HashMap<>();
        this.continuationContexts = new HashMap<>();
        this.pythonAwaitableRefs = new HashMap<>();
    }

    Map<ActionTask, RunnerContextImpl.DurableExecutionContext> getActionTaskDurableContexts() {
        return actionTaskDurableContexts;
    }

    /**
     * Creates or retrieves the appropriate runner context for the given action task and configures
     * it with the correct memory context, continuation context, and awaitable ref.
     *
     * <p>If the task has previously cached contexts (from an unfinished async action), those are
     * restored. Otherwise, fresh contexts are created from Flink state.
     */
    void createAndSetRunnerContext(
            ActionTask actionTask,
            Object key,
            MapState<String, MemoryObjectImpl.MemoryItem> sensoryMemState,
            MapState<String, MemoryObjectImpl.MemoryItem> shortTermMemState) {
        RunnerContextImpl ctx;
        if (actionTask.action.getExec() instanceof JavaFunction) {
            ctx = getOrCreateJavaRunnerContext();
        } else if (actionTask.action.getExec() instanceof PythonFunction) {
            ctx = getOrCreatePythonRunnerContext();
        } else {
            throw new IllegalStateException(
                    "Unsupported action type: " + actionTask.action.getExec().getClass());
        }

        RunnerContextImpl.MemoryContext memoryContext = actionTaskMemoryContexts.get(actionTask);
        if (memoryContext == null) {
            memoryContext =
                    new RunnerContextImpl.MemoryContext(
                            new CachedMemoryStore(sensoryMemState),
                            new CachedMemoryStore(shortTermMemState));
        }

        ctx.switchActionContext(
                actionTask.action.getName(), memoryContext, String.valueOf(key.hashCode()));

        if (ctx instanceof JavaRunnerContextImpl) {
            ContinuationContext continuationContext = continuationContexts.get(actionTask);
            if (continuationContext == null) {
                continuationContext = new ContinuationContext();
            }
            ((JavaRunnerContextImpl) ctx).setContinuationContext(continuationContext);
        }
        if (ctx instanceof PythonRunnerContextImpl) {
            String awaitableRef = pythonAwaitableRefs.get(actionTask);
            ((PythonRunnerContextImpl) ctx).setPythonAwaitableRef(awaitableRef);
        }
        actionTask.setRunnerContext(ctx);
    }

    /**
     * Removes cached contexts for the given action task after it completes execution. Called after
     * each action task invocation.
     */
    void removeContexts(ActionTask actionTask) {
        actionTaskMemoryContexts.remove(actionTask);
        actionTaskDurableContexts.remove(actionTask);
        continuationContexts.remove(actionTask);
        pythonAwaitableRefs.remove(actionTask);
    }

    /**
     * Transfers context state from a completed (but not finished) action task to its generated
     * continuation task. This preserves memory, durable execution, continuation, and awaitable
     * state across async invocations.
     */
    void transferContexts(ActionTask fromTask, ActionTask toTask) {
        actionTaskMemoryContexts.put(toTask, fromTask.getRunnerContext().getMemoryContext());
        RunnerContextImpl.DurableExecutionContext durableContext =
                fromTask.getRunnerContext().getDurableExecutionContext();
        if (durableContext != null) {
            actionTaskDurableContexts.put(toTask, durableContext);
        }
        if (fromTask.getRunnerContext() instanceof JavaRunnerContextImpl) {
            continuationContexts.put(
                    toTask,
                    ((JavaRunnerContextImpl) fromTask.getRunnerContext()).getContinuationContext());
        }
        if (fromTask.getRunnerContext() instanceof PythonRunnerContextImpl) {
            String awaitableRef =
                    ((PythonRunnerContextImpl) fromTask.getRunnerContext()).getPythonAwaitableRef();
            if (awaitableRef != null) {
                pythonAwaitableRefs.put(toTask, awaitableRef);
            }
        }
    }

    private RunnerContextImpl getOrCreateJavaRunnerContext() {
        if (javaRunnerContext == null) {
            javaRunnerContext =
                    new JavaRunnerContextImpl(
                            metricGroup,
                            mailboxThreadChecker,
                            agentPlan,
                            jobIdentifier,
                            continuationActionExecutor);
        }
        return javaRunnerContext;
    }

    private RunnerContextImpl getOrCreatePythonRunnerContext() {
        if (pythonRunnerContext == null) {
            throw new IllegalStateException(
                    "PythonRunnerContext should have been initialized by PythonBridgeManager");
        }
        return pythonRunnerContext;
    }

    void close() throws Exception {
        if (javaRunnerContext != null) {
            try {
                javaRunnerContext.close();
            } finally {
                javaRunnerContext = null;
            }
        }
        continuationActionExecutor.close();
    }
}
