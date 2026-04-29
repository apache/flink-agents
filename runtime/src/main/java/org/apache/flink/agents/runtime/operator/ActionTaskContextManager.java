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

import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.runtime.ResourceCache;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.async.ContinuationContext;
import org.apache.flink.agents.runtime.context.JavaRunnerContextImpl;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.memory.CachedMemoryStore;
import org.apache.flink.agents.runtime.memory.MemoryObjectImpl;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.agents.runtime.python.context.PythonRunnerContextImpl;
import org.apache.flink.api.common.state.MapState;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Owns the per-{@link ActionTask} runtime context bookkeeping for {@link ActionExecutionOperator}.
 *
 * <p>Owned state:
 *
 * <ul>
 *   <li>The shared (Java) {@link RunnerContextImpl} that is reused across action tasks via {@link
 *       RunnerContextImpl#switchActionContext}.
 *   <li>Three per-{@link ActionTask} maps that survive across the boundary between a finishing
 *       action and the action it generates: memory contexts, continuation contexts (for async Java
 *       actions), and Python awaitable references.
 *   <li>The {@link ContinuationActionExecutor} thread pool used to run async Java continuations.
 * </ul>
 *
 * <p>Lifecycle: instantiated by the operator's {@code open()} with the configured async-thread
 * count from the agent plan. Has no separate {@code open()} step — fully constructed in the
 * operator's {@code open()}. {@link #close()} closes the shared runner context and the continuation
 * executor.
 *
 * <p>Note: the Python {@link RunnerContextImpl} is not owned here — it is owned by {@link
 * PythonBridgeManager} and passed in as a parameter to {@link #createOrGetRunnerContext} and {@link
 * #createAndSetRunnerContext}. The durable-execution context map likewise lives on {@link
 * DurableExecutionManager} and is accessed via the manager parameter passed to {@link
 * #transferContexts}.
 *
 * <p>Design constraint: package-private; no manager-to-manager held references. Cross-cutting data
 * flows via method parameters.
 */
class ActionTaskContextManager implements AutoCloseable {

    private RunnerContextImpl runnerContext;

    private final Map<ActionTask, RunnerContextImpl.MemoryContext> actionTaskMemoryContexts;
    private final Map<ActionTask, ContinuationContext> continuationContexts;
    private final Map<ActionTask, String> pythonAwaitableRefs;

    private ContinuationActionExecutor continuationActionExecutor;

    ActionTaskContextManager(int numAsyncThreads) {
        this.actionTaskMemoryContexts = new HashMap<>();
        this.continuationContexts = new HashMap<>();
        this.pythonAwaitableRefs = new HashMap<>();
        this.continuationActionExecutor = new ContinuationActionExecutor(numAsyncThreads);
    }

    /**
     * Returns a runner context for an action's exec language.
     *
     * <p>For Java actions, lazily creates a single {@link JavaRunnerContextImpl} that is reused for
     * every Java action. For Python actions, returns the supplied {@link PythonRunnerContextImpl}
     * (owned by {@link PythonBridgeManager}). Throws {@link IllegalStateException} if a Python
     * context is requested but none was provided, or if the continuation executor has not been
     * initialized.
     *
     * @param isJava {@code true} if the action is a Java action, {@code false} if Python.
     * @param agentPlan the agent plan, used when creating the Java runner context.
     * @param resourceCache the resource cache, used when creating the Java runner context.
     * @param metricGroup the agent metric group.
     * @param jobIdentifier the job identifier.
     * @param mailboxThreadChecker hook used by runner contexts to assert mailbox-thread access.
     * @param pythonRunnerContext the pre-built Python runner context, or {@code null} for Java.
     * @return the runner context appropriate for the action's exec language.
     */
    RunnerContextImpl createOrGetRunnerContext(
            boolean isJava,
            AgentPlan agentPlan,
            ResourceCache resourceCache,
            FlinkAgentsMetricGroupImpl metricGroup,
            String jobIdentifier,
            Runnable mailboxThreadChecker,
            PythonRunnerContextImpl pythonRunnerContext) {
        if (isJava) {
            if (runnerContext == null) {
                if (continuationActionExecutor == null) {
                    throw new IllegalStateException(
                            "ContinuationActionExecutor has not been initialized.");
                }
                runnerContext =
                        new JavaRunnerContextImpl(
                                metricGroup,
                                mailboxThreadChecker,
                                agentPlan,
                                resourceCache,
                                jobIdentifier,
                                continuationActionExecutor);
            }
            return runnerContext;
        } else {
            if (pythonRunnerContext == null) {
                throw new IllegalStateException(
                        "PythonRunnerContextImpl has not been initialized.");
            }
            return pythonRunnerContext;
        }
    }

    /**
     * Resolves the runner context for the given action task, switches it to that task's action, and
     * wires its memory, continuation, and Python-awaitable contexts.
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Selects a Java or Python runner context based on the action's {@code Exec} type.
     *   <li>Reuses any existing {@link RunnerContextImpl.MemoryContext} for this task; otherwise
     *       builds a fresh one backed by the supplied sensory/short-term memory states.
     *   <li>Calls {@link RunnerContextImpl#switchActionContext} so the shared context now points at
     *       this action's name, memory, and key namespace.
     *   <li>For Java contexts, attaches a continuation context (re-used if the task is resuming
     *       from an async suspend, fresh otherwise).
     *   <li>For Python contexts, attaches the per-task awaitable reference (or {@code null} if the
     *       awaitable was lost across a checkpoint restore — the action will then re-execute).
     * </ol>
     *
     * @param actionTask the task to be set up before execution.
     * @param key the current Flink key.
     * @param agentPlan the agent plan.
     * @param resourceCache the resource cache.
     * @param metricGroup the agent metric group.
     * @param jobIdentifier the job identifier.
     * @param mailboxThreadChecker hook used to assert mailbox-thread access from runner contexts.
     * @param sensoryMemState keyed map state backing sensory memory.
     * @param shortTermMemState keyed map state backing short-term memory.
     * @param pythonRunnerContext the Python runner context, or {@code null} when no Python runtime
     *     is initialized.
     */
    void createAndSetRunnerContext(
            ActionTask actionTask,
            Object key,
            AgentPlan agentPlan,
            ResourceCache resourceCache,
            FlinkAgentsMetricGroupImpl metricGroup,
            String jobIdentifier,
            Runnable mailboxThreadChecker,
            MapState<String, MemoryObjectImpl.MemoryItem> sensoryMemState,
            MapState<String, MemoryObjectImpl.MemoryItem> shortTermMemState,
            PythonRunnerContextImpl pythonRunnerContext) {
        RunnerContextImpl context;
        if (actionTask.action.getExec() instanceof JavaFunction) {
            context =
                    createOrGetRunnerContext(
                            true,
                            agentPlan,
                            resourceCache,
                            metricGroup,
                            jobIdentifier,
                            mailboxThreadChecker,
                            pythonRunnerContext);
        } else if (actionTask.action.getExec() instanceof PythonFunction) {
            context =
                    createOrGetRunnerContext(
                            false,
                            agentPlan,
                            resourceCache,
                            metricGroup,
                            jobIdentifier,
                            mailboxThreadChecker,
                            pythonRunnerContext);
        } else {
            throw new IllegalStateException(
                    "Unsupported action type: " + actionTask.action.getExec().getClass());
        }

        RunnerContextImpl.MemoryContext memoryContext;
        if (actionTaskMemoryContexts.containsKey(actionTask)) {
            memoryContext = actionTaskMemoryContexts.get(actionTask);
        } else {
            memoryContext =
                    new RunnerContextImpl.MemoryContext(
                            new CachedMemoryStore(sensoryMemState),
                            new CachedMemoryStore(shortTermMemState));
        }

        context.switchActionContext(
                actionTask.action.getName(), memoryContext, String.valueOf(key.hashCode()));

        if (context instanceof JavaRunnerContextImpl) {
            ContinuationContext continuationContext;
            if (this.hasContinuationContext(actionTask)) {
                // action task for async execution action, should retrieve intermediate results
                // from map.
                continuationContext = this.getContinuationContext(actionTask);
            } else {
                continuationContext = new ContinuationContext();
            }
            ((JavaRunnerContextImpl) context).setContinuationContext(continuationContext);
        }
        if (context instanceof PythonRunnerContextImpl) {
            // Get the awaitable ref from the transient map. After checkpoint restore, this will
            // be null, signaling that the awaitable was lost and needs re-execution.
            String awaitableRef = this.getPythonAwaitableRef(actionTask);
            ((PythonRunnerContextImpl) context).setPythonAwaitableRef(awaitableRef);
        }
        actionTask.setRunnerContext(context);
    }

    @Nullable
    RunnerContextImpl.MemoryContext getMemoryContext(ActionTask actionTask) {
        return actionTaskMemoryContexts.get(actionTask);
    }

    void putMemoryContext(ActionTask actionTask, RunnerContextImpl.MemoryContext memoryContext) {
        actionTaskMemoryContexts.put(actionTask, memoryContext);
    }

    @Nullable
    RunnerContextImpl.MemoryContext removeMemoryContext(ActionTask actionTask) {
        return actionTaskMemoryContexts.remove(actionTask);
    }

    /**
     * Transfers per-task contexts from a finishing action task to the action task it generated.
     *
     * <p>Always transfers the memory context. For Java tasks, transfers the continuation context.
     * For Python tasks, transfers the awaitable reference when present. The durable-execution
     * context map lives on {@link DurableExecutionManager}, so that manager is passed in as a
     * parameter rather than held as a field — this keeps the no-manager-to-manager-references
     * design constraint intact.
     *
     * @param fromTask the finishing task whose contexts should be transferred.
     * @param toTask the newly generated task that will inherit the contexts.
     * @param durableExecManager used to copy the durable-execution context entry, if any.
     */
    void transferContexts(
            ActionTask fromTask, ActionTask toTask, DurableExecutionManager durableExecManager) {
        putMemoryContext(toTask, fromTask.getRunnerContext().getMemoryContext());
        RunnerContextImpl.DurableExecutionContext durableContext =
                fromTask.getRunnerContext().getDurableExecutionContext();
        if (durableContext != null) {
            durableExecManager.putDurableContext(toTask, durableContext);
        }
        if (fromTask.getRunnerContext() instanceof JavaRunnerContextImpl) {
            this.putContinuationContext(
                    toTask,
                    ((JavaRunnerContextImpl) fromTask.getRunnerContext()).getContinuationContext());
        }
        if (fromTask.getRunnerContext() instanceof PythonRunnerContextImpl) {
            String awaitableRef =
                    ((PythonRunnerContextImpl) fromTask.getRunnerContext()).getPythonAwaitableRef();
            if (awaitableRef != null) {
                this.putPythonAwaitableRef(toTask, awaitableRef);
            }
        }
    }

    @Nullable
    ContinuationContext getContinuationContext(ActionTask actionTask) {
        return continuationContexts.get(actionTask);
    }

    void putContinuationContext(ActionTask actionTask, ContinuationContext context) {
        continuationContexts.put(actionTask, context);
    }

    void removeContinuationContext(ActionTask actionTask) {
        continuationContexts.remove(actionTask);
    }

    boolean hasContinuationContext(ActionTask actionTask) {
        return continuationContexts.containsKey(actionTask);
    }

    @Nullable
    String getPythonAwaitableRef(ActionTask actionTask) {
        return pythonAwaitableRefs.get(actionTask);
    }

    void putPythonAwaitableRef(ActionTask actionTask, String ref) {
        pythonAwaitableRefs.put(actionTask, ref);
    }

    void removePythonAwaitableRef(ActionTask actionTask) {
        pythonAwaitableRefs.remove(actionTask);
    }

    @Override
    public void close() throws Exception {
        if (runnerContext != null) {
            try {
                runnerContext.close();
            } finally {
                runnerContext = null;
            }
        }
        if (continuationActionExecutor != null) {
            continuationActionExecutor.close();
        }
    }
}
