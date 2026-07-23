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
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.api.resource.python.PythonResourceWrapper;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolExecutionMetadataProvider;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import org.apache.flink.agents.api.trace.ToolExecutionMetadataKeys;
import pemja.core.object.PyObject;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class PythonMCPTool extends Tool
        implements PythonResourceWrapper, ToolExecutionMetadataProvider {
    private static final String GET_JAVA_TOOL_META =
            "python_java_utils.get_java_tool_metadata_from_tool";
    private final PyObject tool;
    private final PythonResourceAdapter adapter;
    @Nullable private final String mcpServerName;

    /**
     * Creates a new PythonMCPServer.
     *
     * @param adapter The Python resource adapter (required by PythonResourceProvider's
     *     reflection-based instantiation but not used directly in this implementation)
     * @param tool The Python MCP tool object
     */
    public PythonMCPTool(PythonResourceAdapter adapter, PyObject tool) {
        this(adapter, tool, null);
    }

    /**
     * Creates a new Python MCP tool associated with a server resource name.
     *
     * @param adapter The Python resource adapter
     * @param tool The Python MCP tool object
     * @param mcpServerName The AgentPlan resource name of the MCP server that exposed this tool
     */
    public PythonMCPTool(
            PythonResourceAdapter adapter, PyObject tool, @Nullable String mcpServerName) {
        super(getToolMetadata(adapter, tool));
        this.tool = tool;
        this.adapter = adapter;
        this.mcpServerName = mcpServerName;
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
    public PythonResourceAdapter getPythonResourceAdapter() {
        return adapter;
    }

    @Override
    public void setMetricGroup(FlinkAgentsMetricGroup metricGroup) {
        super.setMetricGroup(metricGroup);
        setPythonResourceMetricGroup(metricGroup);
    }

    @Override
    public ToolType getToolType() {
        return ToolType.MCP;
    }

    @Override
    public Map<String, Object> getToolExecutionMetadata(ToolParameters parameters) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (mcpServerName != null) {
            metadata.put(ToolExecutionMetadataKeys.MCP_SERVER, mcpServerName);
        }
        return metadata;
    }
}
