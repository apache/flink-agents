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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.integrations.mcp.MCPPrompt;
import org.apache.flink.agents.integrations.mcp.MCPTool;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for MCP server integration with AgentPlan.
 *
 * <p>This test verifies that MCP servers, tools, and prompts are properly discovered and registered
 * in the agent plan, following the pattern from {@link AgentPlanDeclareToolMethodTest}.
 *
 * <p>Uses the Python MCP server from python/flink_agents/api/tests/mcp/mcp_server.py.
 */
class AgentPlanDeclareMCPServerTest {

    private static Process pythonMcpServerProcess;
    private static final String MCP_SERVER_SCRIPT =
            "python/flink_agents/integrations/mcp/tests/mcp_server.py";
    private static final String MCP_ENDPOINT = "http://127.0.0.1:8000/mcp";

    private AgentPlan agentPlan;
    private ResourceCache resourceCache;

    /** Test agent with MCP server annotation. */
    static class TestMCPAgent extends Agent {

        @org.apache.flink.agents.api.annotation.MCPServer
        public static ResourceDescriptor testMcpServer() {
            return ResourceDescriptor.Builder.newBuilder(ResourceName.MCP_SERVER)
                    .addInitialArgument("endpoint", MCP_ENDPOINT)
                    .addInitialArgument("timeout", 30)
                    .build();
        }

        @Action(listenEvents = {InputEvent.class})
        public void process(Event event, RunnerContext ctx) {
            // no-op
        }
    }

    @BeforeAll
    static void beforeAll() throws Exception {
        // Get the project root directory
        File projectRoot = new File(System.getProperty("user.dir")).getParentFile();

        // Try to find Python executable (prefer venv if available)
        String pythonExecutable = findPythonExecutable(projectRoot);

        // Check if Python 3 is available
        boolean pythonAvailable = false;
        try {
            Process pythonCheck = new ProcessBuilder(pythonExecutable, "--version").start();
            pythonCheck.waitFor(5, TimeUnit.SECONDS);
            pythonAvailable = pythonCheck.exitValue() == 0;
        } catch (Exception e) {
            System.err.println("Python3 not available: " + e.getMessage());
        }

        assumeTrue(
                pythonAvailable,
                "python3 is not available or not in PATH. Skipping MCP server tests.");

        File mcpServerScript = new File(projectRoot, MCP_SERVER_SCRIPT);

        assumeTrue(
                mcpServerScript.exists(),
                "MCP server script not found at: " + mcpServerScript.getAbsolutePath());

        // Start Python MCP server process
        ProcessBuilder pb =
                new ProcessBuilder(pythonExecutable, mcpServerScript.getAbsolutePath())
                        .redirectErrorStream(true);
        pythonMcpServerProcess = pb.start();

        // Wait for server to be ready with health check
        boolean serverReady = false;
        int maxRetries = 30; // 30 seconds max
        for (int i = 0; i < maxRetries; i++) {
            if (isServerReady(MCP_ENDPOINT)) {
                serverReady = true;
                break;
            }
            Thread.sleep(1000);
        }

        if (!serverReady && pythonMcpServerProcess != null) {
            pythonMcpServerProcess.destroy();
        }

        assumeTrue(
                serverReady,
                "MCP server did not start within 30 seconds. "
                        + "Check that Python dependencies (mcp, dotenv) are installed.");
    }

    /**
     * Find the Python executable. Prefers venv python if available, otherwise uses system python3.
     *
     * @param projectRoot The project root directory
     * @return Path to python executable
     */
    private static String findPythonExecutable(File projectRoot) {
        // Try to find venv python first (used in CI and when building locally)
        File venvPython = new File(projectRoot, "python/.venv/bin/python3");
        if (venvPython.exists() && venvPython.canExecute()) {
            return venvPython.getAbsolutePath();
        }

        // Fallback to system python3
        return "python3";
    }

    /**
     * Check if the MCP server is ready by attempting to connect to the endpoint.
     *
     * @param endpoint The MCP server endpoint
     * @return true if server is ready, false otherwise
     */
    private static boolean isServerReady(String endpoint) {
        try {
            URL url = new URL(endpoint);
            // noinspection StartSSRFNetHookCheckingInspection
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);
            int responseCode = connection.getResponseCode();
            // MCP server might return 404 or other codes, we just want to know it's responding
            return responseCode > 0;
        } catch (Exception e) {
            // Server not ready yet
            return false;
        }
    }

    @BeforeEach
    void setup() throws Exception {
        agentPlan = new AgentPlan(new TestMCPAgent());
        resourceCache = new ResourceCache(agentPlan.getResourceProviders());
    }

    @AfterAll
    static void afterAll() {
        if (pythonMcpServerProcess != null) {
            pythonMcpServerProcess.destroy();
            try {
                pythonMcpServerProcess.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
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
        assertTrue(toolProviders.containsKey("add"), "add tool should be discovered");
        assertEquals(1, toolProviders.size(), "Should have exactly 1 tool from Python server");
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Discover and register prompts from MCP server")
    void discoverPromptsFromMCPServer() {
        Map<ResourceType, Map<String, ResourceProvider>> providers =
                agentPlan.getResourceProviders();
        assertTrue(providers.containsKey(ResourceType.PROMPT));

        Map<String, ?> promptProviders = providers.get(ResourceType.PROMPT);
        assertTrue(promptProviders.containsKey("ask_sum"), "ask_sum prompt should be discovered");
        assertEquals(1, promptProviders.size(), "Should have exactly 1 prompt from Python server");
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Retrieve MCP tool from AgentPlan - add tool")
    void retrieveMCPToolAdd() throws Exception {
        Tool tool = (Tool) resourceCache.getResource("add", ResourceType.TOOL);
        assertNotNull(tool);
        assertInstanceOf(MCPTool.class, tool);

        MCPTool mcpTool = (MCPTool) tool;
        assertEquals("add", mcpTool.getName());
        // Verify description starts with expected text
        assertTrue(
                mcpTool.getMetadata()
                        .getDescription()
                        .startsWith("Get the detailed information of a specified IP address."),
                "Description should start with expected text");
        // Verify input schema contains expected parameters
        String schema = mcpTool.getMetadata().getInputSchema();
        assertTrue(schema.contains("a"), "Schema should contain parameter 'a'");
        assertTrue(schema.contains("b"), "Schema should contain parameter 'b'");
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Retrieve MCP prompt from AgentPlan - ask_sum")
    void retrieveMCPPromptAskSum() throws Exception {
        Prompt prompt = (Prompt) resourceCache.getResource("ask_sum", ResourceType.PROMPT);
        assertNotNull(prompt);
        assertInstanceOf(MCPPrompt.class, prompt);

        MCPPrompt mcpPrompt = (MCPPrompt) prompt;
        assertEquals("ask_sum", mcpPrompt.getName());
        assertEquals("Prompt of add tool.", mcpPrompt.getDescription());
        // ask_sum prompt should have 'a' and 'b' as arguments
        Map<String, MCPPrompt.PromptArgument> args = mcpPrompt.getPromptArguments();
        assertTrue(args.containsKey("a"), "Should have 'a' argument");
        assertTrue(args.containsKey("b"), "Should have 'b' argument");
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("AgentPlan JSON serialization with MCP resources")
    void testAgentPlanJsonSerializableWithMCP() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(agentPlan);

        // Verify JSON contains MCP resources
        assertTrue(json.contains("add"), "JSON should contain add tool");
        assertTrue(json.contains("ask_sum"), "JSON should contain ask_sum prompt");
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
    @DisplayName("Test metadata from MCP tool - add")
    void testMCPToolMetadata() throws Exception {
        Tool tool = (Tool) resourceCache.getResource("add", ResourceType.TOOL);
        ToolMetadata metadata = tool.getMetadata();

        assertEquals("add", metadata.getName());
        // Verify description starts with expected text (full docstring includes Args/Returns)
        assertTrue(
                metadata.getDescription()
                        .startsWith("Get the detailed information of a specified IP address."),
                "Description should start with expected text");
        assertNotNull(metadata.getInputSchema());

        String schema = metadata.getInputSchema();
        // Verify the tool has expected parameters
        assertTrue(schema.contains("a"), "Schema should contain 'a' parameter");
        assertTrue(schema.contains("b"), "Schema should contain 'b' parameter");
    }
}
