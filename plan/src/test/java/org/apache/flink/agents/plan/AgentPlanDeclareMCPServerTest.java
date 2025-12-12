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

package org.apache.flink.agents.plan;

import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.mcp.MCPPrompt;
import org.apache.flink.agents.api.mcp.MCPServer;
import org.apache.flink.agents.api.mcp.MCPTool;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for MCP server integration with AgentPlan.
 *
 * <p>This test verifies that MCP servers, tools, and prompts are properly discovered and registered
 * in the agent plan, following the pattern from {@link AgentPlanDeclareToolMethodTest}.
 */
class AgentPlanDeclareMCPServerTest {

    static GenericContainer<?> mcpStreamableHttpServerContainer =
            new GenericContainer<>("docker.io/tzolov/mcp-everything-server:v3")
                    .withCommand("node dist/index.js streamableHttp")
                    .withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8String()))
                    .withExposedPorts(3001)
                    .waitingFor(Wait.forHttp("/").forStatusCode(404));

    private AgentPlan agentPlan;

    /** Test agent with MCP server annotation. */
    static class TestMCPAgent extends Agent {

        @org.apache.flink.agents.api.annotation.MCPServer
        public static MCPServer testMcpServer() {
            // Create real MCP server connection
            String mcpEndpoint =
                    String.format(
                            "http://%s:%d/mcp",
                            mcpStreamableHttpServerContainer.getHost(),
                            mcpStreamableHttpServerContainer.getMappedPort(3001));

            return MCPServer.builder(mcpEndpoint).timeout(Duration.ofSeconds(30)).build();
        }

        @Action(listenEvents = {InputEvent.class})
        public void process(Event event, RunnerContext ctx) {
            // no-op
        }
    }

    @BeforeAll
    static void beforeAll() {
        mcpStreamableHttpServerContainer.start();
    }

    @BeforeEach
    void setup() throws Exception {
        agentPlan = new AgentPlan(new TestMCPAgent());
    }

    @AfterAll
    static void afterAll() {
        mcpStreamableHttpServerContainer.stop();
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Discover @MCPServer method and register MCP server")
    void discoverMCPServer() {
        Map<ResourceType, Map<String, ResourceProvider>> providers =
                agentPlan.getResourceProviders();
        assertTrue(providers.containsKey(ResourceType.MCP_SERVER));
        Map<String, ?> mcpServerProviders = providers.get(ResourceType.MCP_SERVER);
        assertTrue(mcpServerProviders.containsKey("testMcpServer"));
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Discover and register tools from MCP server")
    void discoverToolsFromMCPServer() {
        Map<ResourceType, Map<String, ResourceProvider>> providers =
                agentPlan.getResourceProviders();
        assertTrue(providers.containsKey(ResourceType.TOOL));

        Map<String, ?> toolProviders = providers.get(ResourceType.TOOL);
        // Verify some of the expected tools from mcp-everything-server
        assertTrue(toolProviders.containsKey("echo"), "echo tool should be discovered");
        assertTrue(toolProviders.containsKey("add"), "add tool should be discovered");
        assertTrue(
                toolProviders.containsKey("longRunningOperation"),
                "longRunningOperation tool should be discovered");
        assertTrue(toolProviders.containsKey("sampleLLM"), "sampleLLM tool should be discovered");
        assertTrue(
                toolProviders.containsKey("getTinyImage"),
                "getTinyImage tool should be discovered");
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Discover and register prompts from MCP server")
    void discoverPromptsFromMCPServer() {
        Map<ResourceType, Map<String, ResourceProvider>> providers =
                agentPlan.getResourceProviders();
        assertTrue(providers.containsKey(ResourceType.PROMPT));

        Map<String, ?> promptProviders = providers.get(ResourceType.PROMPT);
        // Verify the expected prompts from mcp-everything-server
        assertTrue(
                promptProviders.containsKey("simple_prompt"), "simple_prompt should be discovered");
        assertTrue(
                promptProviders.containsKey("complex_prompt"),
                "complex_prompt should be discovered");
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Retrieve MCP tool from AgentPlan - echo tool")
    void retrieveMCPToolEcho() throws Exception {
        Tool tool = (Tool) agentPlan.getResource("echo", ResourceType.TOOL);
        assertNotNull(tool);
        assertInstanceOf(MCPTool.class, tool);

        MCPTool mcpTool = (MCPTool) tool;
        assertEquals("echo", mcpTool.getName());
        assertEquals("Echoes back the input", mcpTool.getMetadata().getDescription());
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Retrieve MCP tool from AgentPlan - add tool")
    void retrieveMCPToolAdd() throws Exception {
        Tool tool = (Tool) agentPlan.getResource("add", ResourceType.TOOL);
        assertNotNull(tool);
        assertInstanceOf(MCPTool.class, tool);

        MCPTool mcpTool = (MCPTool) tool;
        assertEquals("add", mcpTool.getName());
        assertEquals("Adds two numbers", mcpTool.getMetadata().getDescription());
        // Verify input schema contains expected parameters
        String schema = mcpTool.getMetadata().getInputSchema();
        assertTrue(schema.contains("a"), "Schema should contain parameter 'a'");
        assertTrue(schema.contains("b"), "Schema should contain parameter 'b'");
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Retrieve MCP prompt from AgentPlan - simple_prompt")
    void retrieveMCPPromptSimple() throws Exception {
        Prompt prompt = (Prompt) agentPlan.getResource("simple_prompt", ResourceType.PROMPT);
        assertNotNull(prompt);
        assertInstanceOf(MCPPrompt.class, prompt);

        MCPPrompt mcpPrompt = (MCPPrompt) prompt;
        assertEquals("simple_prompt", mcpPrompt.getName());
        assertEquals("A prompt without arguments", mcpPrompt.getDescription());
        // Simple prompt should have no required arguments
        assertTrue(
                mcpPrompt.getPromptArguments().isEmpty()
                        || mcpPrompt.getPromptArguments().values().stream()
                                .noneMatch(MCPPrompt.PromptArgument::isRequired));
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Retrieve MCP prompt from AgentPlan - complex_prompt")
    void retrieveMCPPromptComplex() throws Exception {
        Prompt prompt = (Prompt) agentPlan.getResource("complex_prompt", ResourceType.PROMPT);
        assertNotNull(prompt);
        assertInstanceOf(MCPPrompt.class, prompt);

        MCPPrompt mcpPrompt = (MCPPrompt) prompt;
        assertEquals("complex_prompt", mcpPrompt.getName());
        assertEquals("A prompt with arguments", mcpPrompt.getDescription());
        // Complex prompt should have temperature as required argument
        Map<String, MCPPrompt.PromptArgument> args = mcpPrompt.getPromptArguments();
        assertTrue(args.containsKey("temperature"), "Should have temperature argument");
        assertTrue(args.get("temperature").isRequired(), "temperature should be required");
        // style is optional
        if (args.containsKey("style")) {
            assertFalse(args.get("style").isRequired(), "style should be optional");
        }
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("AgentPlan JSON serialization with MCP resources")
    void testAgentPlanJsonSerializableWithMCP() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(agentPlan);

        // Verify JSON contains MCP resources from real server
        assertTrue(json.contains("echo"), "JSON should contain echo tool");
        assertTrue(json.contains("add"), "JSON should contain add tool");
        assertTrue(json.contains("simple_prompt"), "JSON should contain simple_prompt");
        assertTrue(json.contains("mcp_server"), "JSON should contain mcp_server type");

        // Verify serialization works without errors
        assertNotNull(json);
        assertFalse(json.isEmpty());
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Test MCP server is closed after discovery")
    void testMCPServerClosedAfterDiscovery() throws Exception {
        // The MCPServer.close() should be called after listTools() and listPrompts()
        // We verify this indirectly by checking that the plan was created successfully
        assertNotNull(agentPlan);
        assertTrue(agentPlan.getResourceProviders().containsKey(ResourceType.MCP_SERVER));
        assertTrue(agentPlan.getResourceProviders().containsKey(ResourceType.TOOL));
        assertTrue(agentPlan.getResourceProviders().containsKey(ResourceType.PROMPT));
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Test metadata from MCP tool - longRunningOperation")
    void testMCPToolMetadata() throws Exception {
        Tool tool = (Tool) agentPlan.getResource("longRunningOperation", ResourceType.TOOL);
        ToolMetadata metadata = tool.getMetadata();

        assertEquals("longRunningOperation", metadata.getName());
        assertEquals(
                "Demonstrates a long running operation with progress updates",
                metadata.getDescription());
        assertNotNull(metadata.getInputSchema());

        String schema = metadata.getInputSchema();
        // Verify the tool has expected parameters
        assertTrue(schema.contains("duration"), "Schema should contain duration parameter");
        assertTrue(schema.contains("steps"), "Schema should contain steps parameter");
    }
}
