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

package org.apache.flink.agents.integrations.mcp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MCP tool definition that can be called directly.
 *
 * <p>This represents a single tool from an MCP server. It extends the base Tool class and delegates
 * actual execution to the MCP server.
 */
public class MCPTool extends Tool {

    private static final String FIELD_MCP_SERVER = "mcpServer";

    @JsonProperty(FIELD_MCP_SERVER)
    private final MCPServer mcpServer;

    /**
     * Create a new MCPTool.
     *
     * @param metadata The tool metadata
     * @param mcpServer The MCP server reference
     */
    @JsonCreator
    public MCPTool(
            @JsonProperty("metadata") ToolMetadata metadata,
            @JsonProperty(FIELD_MCP_SERVER) MCPServer mcpServer) {
        super(metadata);
        this.mcpServer = Objects.requireNonNull(mcpServer, "mcpServer cannot be null");
    }

    @Override
    @JsonIgnore
    public ToolType getToolType() {
        return ToolType.MCP;
    }

    /**
     * Call the MCP tool with the given parameters.
     *
     * @param parameters The tool parameters
     * @return The tool response
     */
    @Override
    public ToolResponse call(ToolParameters parameters) {
        long startTime = System.currentTimeMillis();

        try {
            Map<String, Object> arguments = new HashMap<>();
            for (String paramName : parameters.getParameterNames()) {
                arguments.put(paramName, parameters.getParameter(paramName));
            }

            List<Object> result = mcpServer.callTool(metadata.getName(), arguments);

            long executionTime = System.currentTimeMillis() - startTime;

            // Return the result (could be text, images, or other content)
            return ToolResponse.success(result, executionTime, metadata.getName());

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            String errorMessage =
                    "Error calling MCP tool '" + metadata.getName() + "': " + e.getMessage();
            return ToolResponse.error(errorMessage, executionTime, metadata.getName());
        }
    }

    /**
     * Get the MCP server associated with this tool.
     *
     * @return The MCP server
     */
    @JsonIgnore
    public MCPServer getMcpServer() {
        return mcpServer;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MCPTool mcpTool = (MCPTool) o;
        return Objects.equals(metadata, mcpTool.metadata)
                && Objects.equals(mcpServer, mcpTool.mcpServer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(metadata, mcpServer);
    }

    @Override
    public String toString() {
        return String.format(
                "MCPTool{name='%s', server='%s'}", metadata.getName(), mcpServer.getEndpoint());
    }
}
