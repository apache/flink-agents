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
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.api.resource.python.PythonResourceWrapper;
import org.apache.flink.util.LambdaUtil;
import pemja.core.object.PyObject;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class PythonMCPServer extends Resource implements PythonResourceWrapper {
    private final PyObject server;
    private final PythonResourceAdapter adapter;
    private boolean closed;

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
        this.server = server;
        this.adapter = adapter;
    }

    public List<PythonMCPTool> listTools() {
        return listTools(null);
    }

    public List<PythonMCPTool> listTools(@Nullable String mcpServerName) {
        Object result = adapter.callMethod(server, "list_tools", Collections.emptyMap());
        return createChildren(result, pyTool -> new PythonMCPTool(adapter, pyTool, mcpServerName));
    }

    public List<PythonMCPPrompt> listPrompts() {
        Object result = adapter.callMethod(server, "list_prompts", Collections.emptyMap());
        return createChildren(result, pyPrompt -> new PythonMCPPrompt(adapter, pyPrompt));
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
        if (closed || server == null) {
            return;
        }
        closed = true;
        try (server) {
            adapter.callMethod(server, "close", Map.of());
        }
    }

    private <T extends Resource> List<T> createChildren(
            Object result, Function<PyObject, T> wrapperFactory) {
        if (!(result instanceof List)) {
            return Collections.emptyList();
        }

        List<?> pythonChildren = (List<?>) result;
        List<T> children = new ArrayList<>(pythonChildren.size());
        try {
            for (Object pythonChild : pythonChildren) {
                children.add(wrapperFactory.apply((PyObject) pythonChild));
            }
            return children;
        } catch (RuntimeException | Error creationFailure) {
            List<AutoCloseable> closeables = new ArrayList<>();
            // Successfully constructed wrappers own the prefix; the remaining handles are ours.
            for (int i = pythonChildren.size() - 1; i >= children.size(); i--) {
                Object pythonChild = pythonChildren.get(i);
                if (pythonChild instanceof PyObject) {
                    closeables.add((PyObject) pythonChild);
                }
            }
            Collections.reverse(children);
            for (T child : children) {
                closeables.add(child::close);
            }
            try {
                LambdaUtil.applyToAllWhileSuppressingExceptions(closeables, AutoCloseable::close);
            } catch (Exception closeFailure) {
                creationFailure.addSuppressed(closeFailure);
            }
            throw creationFailure;
        }
    }
}
