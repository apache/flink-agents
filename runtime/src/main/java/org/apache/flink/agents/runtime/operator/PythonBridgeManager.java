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

import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.plan.resourceprovider.PythonResourceProvider;
import org.apache.flink.agents.runtime.PythonMCPResourceDiscovery;
import org.apache.flink.agents.runtime.ResourceCache;
import org.apache.flink.agents.runtime.env.EmbeddedPythonEnvironment;
import org.apache.flink.agents.runtime.env.PythonEnvironmentManager;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.agents.runtime.python.context.PythonRunnerContextImpl;
import org.apache.flink.agents.runtime.python.utils.JavaResourceAdapter;
import org.apache.flink.agents.runtime.python.utils.PythonActionExecutor;
import org.apache.flink.agents.runtime.python.utils.PythonResourceAdapterImpl;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.JobID;
import org.apache.flink.python.env.PythonDependencyInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pemja.core.PythonInterpreter;

import java.util.HashMap;

class PythonBridgeManager implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PythonBridgeManager.class);

    private PythonEnvironmentManager pythonEnvironmentManager;
    private PythonInterpreter pythonInterpreter;
    private PythonActionExecutor pythonActionExecutor;
    private PythonRunnerContextImpl pythonRunnerContext;
    private PythonResourceAdapterImpl pythonResourceAdapter;
    private JavaResourceAdapter javaResourceAdapter;
    private boolean initialized;

    PythonBridgeManager() {
        this.initialized = false;
    }

    void open(
            AgentPlan agentPlan,
            ResourceCache resourceCache,
            ExecutionConfig executionConfig,
            org.apache.flink.api.common.cache.DistributedCache distributedCache,
            String[] tmpDirs,
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

        if (containPythonAction || containPythonResource) {
            LOG.debug("Begin initialize PythonEnvironmentManager.");
            PythonDependencyInfo dependencyInfo =
                    PythonDependencyInfo.create(
                            executionConfig.toConfiguration(), distributedCache);
            pythonEnvironmentManager =
                    new PythonEnvironmentManager(
                            dependencyInfo, tmpDirs, new HashMap<>(System.getenv()), jobId);
            pythonEnvironmentManager.open();
            EmbeddedPythonEnvironment env = pythonEnvironmentManager.createEnvironment();
            pythonInterpreter = env.getInterpreter();
            pythonRunnerContext =
                    new PythonRunnerContextImpl(
                            metricGroup,
                            mailboxThreadChecker,
                            agentPlan,
                            resourceCache,
                            jobIdentifier);

            javaResourceAdapter =
                    new JavaResourceAdapter(
                            (name, type) -> {
                                try {
                                    return resourceCache.getResource(name, type);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            },
                            pythonInterpreter);
            if (containPythonResource) {
                initPythonResourceAdapter(agentPlan, resourceCache);
            }
            if (containPythonAction) {
                initPythonActionExecutor(agentPlan, jobIdentifier);
            }
            initialized = true;
        }
    }

    private void initPythonActionExecutor(AgentPlan agentPlan, String jobIdentifier)
            throws Exception {
        pythonActionExecutor =
                new PythonActionExecutor(
                        pythonInterpreter,
                        agentPlan,
                        javaResourceAdapter,
                        pythonRunnerContext,
                        jobIdentifier);
        pythonActionExecutor.open();
    }

    private void initPythonResourceAdapter(AgentPlan agentPlan, ResourceCache resourceCache)
            throws Exception {
        pythonResourceAdapter =
                new PythonResourceAdapterImpl(
                        (String anotherName, ResourceType anotherType) -> {
                            try {
                                return resourceCache.getResource(anotherName, anotherType);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        },
                        pythonInterpreter,
                        javaResourceAdapter);
        pythonResourceAdapter.open();
        PythonMCPResourceDiscovery.discoverPythonMCPResources(
                agentPlan.getResourceProviders(), pythonResourceAdapter, resourceCache);
    }

    PythonActionExecutor getPythonActionExecutor() {
        return pythonActionExecutor;
    }

    PythonRunnerContextImpl getPythonRunnerContext() {
        return pythonRunnerContext;
    }

    boolean isInitialized() {
        return initialized;
    }

    @Override
    public void close() throws Exception {
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
