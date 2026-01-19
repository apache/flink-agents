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

import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.api.resource.python.PythonResourceWrapper;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import pemja.core.object.PyObject;

import java.util.HashMap;
import java.util.Map;

public class PythonMCPTool extends Tool implements PythonResourceWrapper {
    private static final String GET_JAVA_TOOL_META =
            "python_java_utils.get_java_tool_metadata_from_tool";
    private final PyObject tool;
    private final PythonResourceAdapter adapter;

    /**
     * Creates a new PythonMCPServer.
     *
     * @param adapter The Python resource adapter (required by PythonResourceProvider's
     *     reflection-based instantiation but not used directly in this implementation)
     * @param tool The Python MCP tool object
     */
    public PythonMCPTool(PythonResourceAdapter adapter, PyObject tool) {
        super(getToolMetadata(adapter, tool));
        this.tool = tool;
        this.adapter = adapter;
    }

    @SuppressWarnings("unchecked")
    private static ToolMetadata getToolMetadata(PythonResourceAdapter adapter, PyObject tool) {
        Map<String, String> metadata =
                (Map<String, String>) adapter.invoke(GET_JAVA_TOOL_META, tool);
        return new ToolMetadata(
                metadata.get("name"), metadata.get("description"), metadata.get("inputSchema"));
    }

    @Override
    public ToolResponse call(ToolParameters parameters) {
        Map<String, Object> kwargs = new HashMap<>();
        for (String paramName : parameters.getParameterNames()) {
            kwargs.put(paramName, parameters.getParameter(paramName));
        }
        try {
            Object result = adapter.callMethod(tool, "call", kwargs);
            return ToolResponse.success(result);
        } catch (Exception e) {
            return ToolResponse.error(e);
        }
    }

    @Override
    public Object getPythonResource() {
        return tool;
    }

    @Override
    public ToolType getToolType() {
        return ToolType.MCP;
    }
}
