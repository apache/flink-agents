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
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.plan.resourceprovider.PythonResourceProvider;
import org.apache.flink.agents.runtime.env.EmbeddedPythonEnvironment;
import org.apache.flink.agents.runtime.env.PythonEnvironmentManager;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.agents.runtime.python.context.PythonRunnerContextImpl;
import org.apache.flink.agents.runtime.python.utils.JavaResourceAdapter;
import org.apache.flink.agents.runtime.python.utils.PythonActionExecutor;
import org.apache.flink.agents.runtime.python.utils.PythonResourceAdapterImpl;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.cache.DistributedCache;
import org.apache.flink.python.env.PythonDependencyInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pemja.core.PythonInterpreter;

import java.util.HashMap;
import java.util.function.BiFunction;

/**
 * Manages the Python execution environment, interpreter, action executor, and resource adapters.
 *
 * <p>This class is only active when the agent plan contains Python actions or Python resources.
 * When no Python code is present, all fields remain null and {@link #getPythonActionExecutor()}
 * returns null.
 */
class PythonBridgeManager {

    private static final Logger LOG = LoggerFactory.getLogger(PythonBridgeManager.class);

    private PythonEnvironmentManager pythonEnvironmentManager;
    private PythonInterpreter pythonInterpreter;
    private PythonActionExecutor pythonActionExecutor;
    private PythonRunnerContextImpl pythonRunnerContext;

    /** Returns the Python action executor, or null if no Python actions exist. */
    PythonActionExecutor getPythonActionExecutor() {
        return pythonActionExecutor;
    }

    /** Returns the Python runner context, or null if no Python components exist. */
    PythonRunnerContextImpl getPythonRunnerContext() {
        return pythonRunnerContext;
    }

    /**
     * Initializes the Python environment, interpreter, action executor, and resource adapters if
     * the agent plan contains Python actions or resources.
     */
    void initPythonEnvironment(
            AgentPlan agentPlan,
            ExecutionConfig executionConfig,
            DistributedCache distributedCache,
            String[] tmpDirectories,
            JobID jobId,
            FlinkAgentsMetricGroupImpl metricGroup,
            Runnable mailboxThreadChecker,
            String jobIdentifier)
            throws Exception {
        boolean containPythonAction =
                agentPlan.getActions().values().stream()
                        .anyMatch(action -> action.getExec() instanceof PythonFunction);

        boolean containPythonResource =
                agentPlan.getResourceProviders().values().stream()
                        .anyMatch(
                                resourceProviderMap ->
                                        resourceProviderMap.values().stream()
                                                .anyMatch(
                                                        resourceProvider ->
                                                                resourceProvider
                                                                        instanceof
                                                                        PythonResourceProvider));

        if (!containPythonAction && !containPythonResource) {
            return;
        }

        LOG.debug("Begin initialize PythonEnvironmentManager.");
        PythonDependencyInfo dependencyInfo =
                PythonDependencyInfo.create(executionConfig.toConfiguration(), distributedCache);
        pythonEnvironmentManager =
                new PythonEnvironmentManager(
                        dependencyInfo, tmpDirectories, new HashMap<>(System.getenv()), jobId);
        pythonEnvironmentManager.open();
        EmbeddedPythonEnvironment env = pythonEnvironmentManager.createEnvironment();
        pythonInterpreter = env.getInterpreter();
        pythonRunnerContext =
                new PythonRunnerContextImpl(
                        metricGroup, mailboxThreadChecker, agentPlan, jobIdentifier);

        BiFunction<String, ResourceType, Resource> resourceResolver =
                (name, type) -> getResource(agentPlan, name, type);
        JavaResourceAdapter javaResourceAdapter =
                new JavaResourceAdapter(resourceResolver, pythonInterpreter);
        if (containPythonResource) {
            PythonResourceAdapterImpl pythonResourceAdapter =
                    new PythonResourceAdapterImpl(
                            resourceResolver, pythonInterpreter, javaResourceAdapter);
            pythonResourceAdapter.open();
            agentPlan.setPythonResourceAdapter(pythonResourceAdapter);
        }
        if (containPythonAction) {
            pythonActionExecutor =
                    new PythonActionExecutor(
                            pythonInterpreter,
                            agentPlan,
                            javaResourceAdapter,
                            pythonRunnerContext,
                            jobIdentifier);
            pythonActionExecutor.open();
        }
    }

    private static Resource getResource(AgentPlan agentPlan, String name, ResourceType type) {
        try {
            return agentPlan.getResource(name, type);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Closes all Python-related resources in the correct order. */
    void close() throws Exception {
        if (pythonActionExecutor != null) {
            pythonActionExecutor.close();
        }
        if (pythonInterpreter != null) {
            pythonInterpreter.close();
        }
        if (pythonEnvironmentManager != null) {
            pythonEnvironmentManager.close();
        }
    }
}
