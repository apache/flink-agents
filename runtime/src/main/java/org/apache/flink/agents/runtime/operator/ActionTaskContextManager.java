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

import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;
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

import java.util.HashMap;
import java.util.Map;

class ActionTaskContextManager implements AutoCloseable {

    private RunnerContextImpl runnerContext;

    private final Map<ActionTask, RunnerContextImpl.MemoryContext> actionTaskMemoryContexts;

    private ContinuationActionExecutor continuationActionExecutor;

    ActionTaskContextManager(int numAsyncThreads) {
        this.actionTaskMemoryContexts = new HashMap<>();
        this.continuationActionExecutor = new ContinuationActionExecutor(numAsyncThreads);
    }

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
            PythonRunnerContextImpl pythonRunnerContext,
            DurableExecutionManager durableExecManager) {
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
            if (durableExecManager.hasContinuationContext(actionTask)) {
                // action task for async execution action, should retrieve intermediate results
                // from map.
                continuationContext = durableExecManager.getContinuationContext(actionTask);
            } else {
                continuationContext = new ContinuationContext();
            }
            ((JavaRunnerContextImpl) context).setContinuationContext(continuationContext);
        }
        if (context instanceof PythonRunnerContextImpl) {
            // Get the awaitable ref from the transient map. After checkpoint restore, this will
            // be null, signaling that the awaitable was lost and needs re-execution.
            String awaitableRef = durableExecManager.getPythonAwaitableRef(actionTask);
            ((PythonRunnerContextImpl) context).setPythonAwaitableRef(awaitableRef);
        }
        actionTask.setRunnerContext(context);
    }

    Resource getResource(String name, ResourceType type, ResourceCache resourceCache) {
        try {
            return resourceCache.getResource(name, type);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    RunnerContextImpl.MemoryContext getMemoryContext(ActionTask actionTask) {
        return actionTaskMemoryContexts.get(actionTask);
    }

    void putMemoryContext(ActionTask actionTask, RunnerContextImpl.MemoryContext memoryContext) {
        actionTaskMemoryContexts.put(actionTask, memoryContext);
    }

    RunnerContextImpl.MemoryContext removeMemoryContext(ActionTask actionTask) {
        return actionTaskMemoryContexts.remove(actionTask);
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
