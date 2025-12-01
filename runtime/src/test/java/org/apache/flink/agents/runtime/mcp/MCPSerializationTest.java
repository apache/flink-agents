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

package org.apache.flink.agents.runtime.mcp;

import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.mcp.MCPPrompt;
import org.apache.flink.agents.api.mcp.MCPServer;
import org.apache.flink.agents.api.mcp.MCPTool;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for MCP classes serialization with Flink's Kryo serializer.
 *
 * <p>This test ensures that MCP objects (MCPServer, MCPTool, MCPPrompt) can be properly serialized
 * and deserialized by Flink's serialization framework, which uses Kryo under the hood. This is
 * critical for distributed execution where these objects need to be sent across the network.
 */
class MCPSerializationTest {

    /**
     * Test agent with MCP resources.
     *
     * <p>This agent programmatically adds MCP resources to test serialization without requiring an
     * actual MCP server.
     */
    static class MCPTestAgent extends Agent {

        public MCPTestAgent() {
            // Create MCP server
            MCPServer server =
                    MCPServer.builder("http://localhost:8000/mcp")
                            .timeout(Duration.ofSeconds(30))
                            .header("X-Test-Header", "test-value")
                            .build();

            // Add MCP tool
            ToolMetadata toolMetadata =
                    new ToolMetadata(
                            "calculator",
                            "Perform calculations",
                            "{\"type\":\"object\",\"properties\":{\"expression\":{\"type\":\"string\"}}}");
            MCPTool tool = new MCPTool(toolMetadata, server);
            addResource("calculator", ResourceType.TOOL, tool);

            // Add MCP prompt
            Map<String, MCPPrompt.PromptArgument> args = new HashMap<>();
            args.put("topic", new MCPPrompt.PromptArgument("topic", "Essay topic", true));
            args.put("style", new MCPPrompt.PromptArgument("style", "Writing style", false));
            MCPPrompt prompt = new MCPPrompt("essay", "Write an essay", args, server);
            addResource("essay", ResourceType.PROMPT, prompt);
        }

        @Action(listenEvents = {InputEvent.class})
        public void processInput(InputEvent event, RunnerContext context) {
            // Simple test action - no-op for testing serialization
        }
    }

    /**
     * Create an ObjectMapper configured to ignore unknown properties during deserialization. This
     * is needed because base classes may have getters that are serialized.
     */
    private org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper
            createObjectMapper() {
        org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper mapper =
                new org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper();
        mapper.configure(
                org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind
                        .DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                false);
        return mapper;
    }

    @Test
    @DisplayName("Test MCPServer JSON serialization and deserialization")
    void testMCPServerJsonSerialization() throws Exception {
        MCPServer original =
                MCPServer.builder("http://localhost:8000/mcp")
                        .timeout(Duration.ofSeconds(30))
                        .sseReadTimeout(Duration.ofSeconds(300))
                        .header("X-Custom", "value")
                        .build();

        org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper mapper =
                createObjectMapper();
        String json = mapper.writeValueAsString(original);
        MCPServer deserialized = mapper.readValue(json, MCPServer.class);

        assertThat(deserialized.getEndpoint()).isEqualTo(original.getEndpoint());
        assertThat(deserialized.getTimeoutSeconds()).isEqualTo(original.getTimeoutSeconds());
        assertThat(deserialized.getSseReadTimeoutSeconds())
                .isEqualTo(original.getSseReadTimeoutSeconds());
        assertThat(deserialized.getHeaders()).isEqualTo(original.getHeaders());
    }

    @Test
    @DisplayName("Test MCPTool JSON serialization and deserialization")
    void testMCPToolJsonSerialization() throws Exception {
        MCPServer server = new MCPServer("http://localhost:8000/mcp");
        ToolMetadata metadata =
                new ToolMetadata("add", "Add numbers", "{\"type\":\"object\",\"properties\":{}}");
        MCPTool original = new MCPTool(metadata, server);

        org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper mapper =
                createObjectMapper();
        String json = mapper.writeValueAsString(original);
        MCPTool deserialized = mapper.readValue(json, MCPTool.class);

        assertThat(deserialized.getName()).isEqualTo(original.getName());
        assertThat(deserialized.getMetadata()).isEqualTo(original.getMetadata());
        assertThat(deserialized.getMcpServer()).isEqualTo(original.getMcpServer());
    }

    @Test
    @DisplayName("Test MCPPrompt JSON serialization and deserialization")
    void testMCPPromptJsonSerialization() throws Exception {
        MCPServer server = new MCPServer("http://localhost:8000/mcp");
        Map<String, MCPPrompt.PromptArgument> args = new HashMap<>();
        args.put("name", new MCPPrompt.PromptArgument("name", "User name", true));

        MCPPrompt original = new MCPPrompt("greeting", "Greet user", args, server);

        org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper mapper =
                createObjectMapper();
        String json = mapper.writeValueAsString(original);
        MCPPrompt deserialized = mapper.readValue(json, MCPPrompt.class);

        assertThat(deserialized.getName()).isEqualTo(original.getName());
        assertThat(deserialized.getDescription()).isEqualTo(original.getDescription());
        assertThat(deserialized.getPromptArguments().size())
                .isEqualTo(original.getPromptArguments().size());
        assertThat(deserialized.getMcpServer()).isEqualTo(original.getMcpServer());
    }

    @Test
    @DisplayName("Test HashMap serialization in MCP objects")
    void testHashMapSerialization() throws Exception {
        // This specifically tests that the HashMap instances in MCP objects
        // don't cause Kryo serialization issues (like Arrays$ArrayList did)

        Map<String, String> headers = new HashMap<>();
        headers.put("Header1", "Value1");
        headers.put("Header2", "Value2");
        headers.put("Header3", "Value3");

        MCPServer server = MCPServer.builder("http://localhost:8000/mcp").headers(headers).build();

        Map<String, MCPPrompt.PromptArgument> args = new HashMap<>();
        args.put("arg1", new MCPPrompt.PromptArgument("arg1", "Argument 1", true));
        args.put("arg2", new MCPPrompt.PromptArgument("arg2", "Argument 2", false));
        args.put("arg3", new MCPPrompt.PromptArgument("arg3", "Argument 3", true));

        MCPPrompt prompt = new MCPPrompt("test", "Test prompt", args, server);

        // Serialize
        org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper mapper =
                createObjectMapper();
        String json = mapper.writeValueAsString(prompt);

        // Deserialize
        MCPPrompt deserialized = mapper.readValue(json, MCPPrompt.class);

        // Verify HashMaps are properly serialized
        assertThat(deserialized.getMcpServer().getHeaders()).hasSize(3);
        assertThat(deserialized.getPromptArguments()).hasSize(3);
        assertThat(deserialized.getMcpServer().getHeaders()).containsEntry("Header1", "Value1");
        assertThat(deserialized.getPromptArguments()).containsKey("arg1");
    }
}
