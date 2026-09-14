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
package org.apache.flink.agents.plan.resource.python;

import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.python.PythonObjectScope;
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.api.resource.python.PythonResourceWrapper;
import org.apache.flink.util.LambdaUtil;
import pemja.core.object.PyObject;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

public class PythonMCPServer extends Resource implements PythonResourceWrapper {
    private final PyObject server;
    private final PythonResourceAdapter adapter;
    private final PythonObjectScope ownedObjects = new PythonObjectScope();

    /**
     * Creates a new PythonMCPServer.
     *
     * @param adapter The Python resource adapter (required by PythonResourceProvider's
     *     reflection-based instantiation but not used directly in this implementation)
     * @param server The Python MCP Server object
     * @param descriptor The resource descriptor
     * @param getResource Function to retrieve resources by name and type
     */
    public PythonMCPServer(
            PythonResourceAdapter adapter,
            PyObject server,
            ResourceDescriptor descriptor,
            ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        this.server = ownedObjects.own(server);
        this.adapter = adapter;
    }

    @SuppressWarnings("unchecked")
    public List<PythonMCPTool> listTools() {
        return listTools(null);
    }

    @SuppressWarnings("unchecked")
    public List<PythonMCPTool> listTools(@Nullable String mcpServerName) {
        Object result = adapter.callMethod(server, "list_tools", Collections.emptyMap());
        return transferChildren(
                result, pyTool -> new PythonMCPTool(adapter, pyTool, mcpServerName));
    }

    public List<PythonMCPPrompt> listPrompts() {
        Object result = adapter.callMethod(server, "list_prompts", Collections.emptyMap());
        return transferChildren(result, pyPrompt -> new PythonMCPPrompt(adapter, pyPrompt));
    }

    @Override
    public Object getPythonResource() {
        return server;
    }

    @Override
    public PythonResourceAdapter getPythonResourceAdapter() {
        return adapter;
    }

    @Override
    public void setMetricGroup(FlinkAgentsMetricGroup metricGroup) {
        super.setMetricGroup(metricGroup);
        setPythonResourceMetricGroup(metricGroup);
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.MCP_SERVER;
    }

    @Override
    public void close() throws Exception {
        ownedObjects.closeResource(adapter, server);
    }

    @SuppressWarnings("unchecked")
    private <T extends Resource> List<T> transferChildren(
            Object bridgeResult, Function<PyObject, T> wrapperFactory) {
        try (PythonObjectScope scope = new PythonObjectScope()) {
            Object result = scope.own(bridgeResult);
            if (!(result instanceof List)) {
                return Collections.emptyList();
            }

            List<T> children = new ArrayList<>(((List<?>) result).size());
            try {
                for (Object childObject : (List<Object>) result) {
                    T child = wrapperFactory.apply((PyObject) childObject);
                    scope.release(childObject);
                    children.add(child);
                }
                return children;
            } catch (RuntimeException | Error creationFailure) {
                try {
                    scope.close();
                } catch (RuntimeException closeFailure) {
                    creationFailure.addSuppressed(closeFailure);
                }
                Collections.reverse(children);
                try {
                    LambdaUtil.applyToAllWhileSuppressingExceptions(children, Resource::close);
                } catch (Exception closeFailure) {
                    creationFailure.addSuppressed(closeFailure);
                }
                throw creationFailure;
            }
        }
    }
}
