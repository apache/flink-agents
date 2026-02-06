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

import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.api.resource.python.PythonResourceWrapper;
import pemja.core.object.PyObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;

public class PythonMCPServer extends Resource implements PythonResourceWrapper {
    private final PyObject server;
    private final PythonResourceAdapter adapter;

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
            BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);
        this.server = server;
        this.adapter = adapter;
    }

    @SuppressWarnings("unchecked")
    public List<PythonMCPTool> listTools() {
        Object result = adapter.callMethod(server, "list_tools", Collections.emptyMap());
        if (result instanceof List) {
            List<Object> pythonTools = (List<Object>) result;
            List<PythonMCPTool> tools = new ArrayList<>(pythonTools.size());
            for (Object pyTool : pythonTools) {
                tools.add(new PythonMCPTool(adapter, (PyObject) pyTool));
            }
            return tools;
        }
        return Collections.emptyList();
    }

    public List<PythonMCPPrompt> listPrompts() {
        Object result = adapter.callMethod(server, "list_prompts", Collections.emptyMap());
        if (result instanceof List) {
            List<Object> pythonPrompts = (List<Object>) result;
            List<PythonMCPPrompt> prompts = new ArrayList<>(pythonPrompts.size());
            for (Object pythonPrompt : pythonPrompts) {
                prompts.add(new PythonMCPPrompt(adapter, (PyObject) pythonPrompt));
            }
            return prompts;
        }
        return Collections.emptyList();
    }

    @Override
    public Object getPythonResource() {
        return server;
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.MCP_SERVER;
    }
}
