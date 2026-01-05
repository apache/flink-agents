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
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.SerializableResource;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.integrations.mcp.auth.ApiKeyAuth;
import org.apache.flink.agents.integrations.mcp.auth.Auth;
import org.apache.flink.agents.integrations.mcp.auth.BasicAuth;
import org.apache.flink.agents.integrations.mcp.auth.BearerTokenAuth;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resource representing an MCP server and exposing its tools/prompts.
 *
 * <p>This is a logical container for MCP tools and prompts; it is not directly invokable. It uses
 * the official MCP Java SDK to communicate with MCP servers via HTTP/SSE.
 *
 * <p>Authentication is supported through the {@link Auth} interface with multiple implementations:
 *
 * <ul>
 *   <li>{@link BearerTokenAuth} - For OAuth 2.0 and JWT tokens
 *   <li>{@link BasicAuth} - For username/password authentication
 *   <li>{@link ApiKeyAuth} - For API key authentication via custom headers
 * </ul>
 *
 * <p>Example with OAuth authentication:
 *
 * <pre>{@code
 * MCPServer server = MCPServer.builder("https://api.example.com/mcp")
 *     .auth(new BearerTokenAuth("your-oauth-token"))
 *     .timeout(Duration.ofSeconds(30))
 *     .build();
 *
 * List<MCPTool> tools = server.listTools();
 * server.close();
 * }</pre>
 *
 * <p>Reference: <a href="https://modelcontextprotocol.io/sdk/java/mcp-client">MCP Java Client</a>
 */
public class MCPServer extends SerializableResource {

    private static final String FIELD_ENDPOINT = "endpoint";
    private static final String FIELD_HEADERS = "headers";
    private static final String FIELD_TIMEOUT_SECONDS = "timeoutSeconds";
    private static final String FIELD_AUTH = "auth";

    @JsonProperty(FIELD_ENDPOINT)
    private final String endpoint;

    @JsonProperty(FIELD_HEADERS)
    private final Map<String, String> headers;

    @JsonProperty(FIELD_TIMEOUT_SECONDS)
    private final long timeoutSeconds;

    @JsonProperty(FIELD_AUTH)
    private final Auth auth;

    @JsonIgnore private transient McpSyncClient client;

    /** Builder for MCPServer with fluent API. */
    public static class Builder {
        private String endpoint;
        private final Map<String, String> headers = new HashMap<>();
        private long timeoutSeconds = 30;
        private Auth auth = null;

        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder header(String key, String value) {
            this.headers.put(key, value);
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            this.headers.putAll(headers);
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeoutSeconds = timeout.getSeconds();
            return this;
        }

        public Builder auth(Auth auth) {
            this.auth = auth;
            return this;
        }

        public MCPServer build() {
            return new MCPServer(endpoint, headers, timeoutSeconds, auth);
        }
    }

    /**
     * Creates a new MCPServer instance.
     *
     * @param endpoint The HTTP endpoint of the MCP server
     */
    public MCPServer(String endpoint) {
        this(endpoint, new HashMap<>(), 30, null);
    }

    @JsonCreator
    public MCPServer(
            @JsonProperty(FIELD_ENDPOINT) String endpoint,
            @JsonProperty(FIELD_HEADERS) Map<String, String> headers,
            @JsonProperty(FIELD_TIMEOUT_SECONDS) long timeoutSeconds,
            @JsonProperty(FIELD_AUTH) Auth auth) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint cannot be null");
        this.headers = headers != null ? new HashMap<>(headers) : new HashMap<>();
        this.timeoutSeconds = timeoutSeconds;
        this.auth = auth;
    }

    public static Builder builder(String endpoint) {
        return new Builder().endpoint(endpoint);
    }

    @Override
    @JsonIgnore
    public ResourceType getResourceType() {
        return ResourceType.MCP_SERVER;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public Map<String, String> getHeaders() {
        return new HashMap<>(headers);
    }

    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public Auth getAuth() {
        return auth;
    }

    /**
     * Get or create a synchronized MCP client.
     *
     * @return The MCP sync client
     */
    @JsonIgnore
    private synchronized McpSyncClient getClient() {
        if (client == null) {
            client = createClient();
        }
        return client;
    }

    /**
     * Create a new MCP client with the configured transport.
     *
     * @return A new MCP sync client
     */
    private McpSyncClient createClient() {
        validateHttpUrl();

        var requestBuilder = HttpRequest.newBuilder().timeout(Duration.ofSeconds(timeoutSeconds));

        // Add custom headers
        headers.forEach(requestBuilder::header);

        // Apply authentication if configured
        if (auth != null) {
            auth.applyAuth(requestBuilder);
        }

        // Create transport based on type
        var transport =
                HttpClientStreamableHttpTransport.builder(endpoint)
                        .requestBuilder(requestBuilder)
                        .build();

        // Build and initialize the client
        var mcpClient =
                McpClient.sync(transport)
                        .requestTimeout(Duration.ofSeconds(timeoutSeconds))
                        .build();

        mcpClient.initialize();
        return mcpClient;
    }

    /** Validate that the endpoint is a valid HTTP URL. */
    private void validateHttpUrl() {
        try {
            URI uri = URI.create(endpoint);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
                throw new IllegalArgumentException(
                        "Invalid HTTP endpoint: " + endpoint + ". Scheme must be http or https");
            }
            if (uri.getHost() == null || uri.getHost().isEmpty()) {
                throw new IllegalArgumentException(
                        "Invalid HTTP endpoint: " + endpoint + ". Host cannot be empty");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid HTTP endpoint: " + endpoint, e);
        }
    }

    /**
     * List available tools from the MCP server.
     *
     * @return List of MCPTool instances
     */
    public List<MCPTool> listTools() {
        McpSyncClient mcpClient = getClient();
        McpSchema.ListToolsResult toolsResult = mcpClient.listTools();

        List<MCPTool> tools = new ArrayList<>();
        for (McpSchema.Tool toolData : toolsResult.tools()) {
            ToolMetadata metadata =
                    new ToolMetadata(
                            toolData.name(),
                            toolData.description() != null ? toolData.description() : "",
                            serializeInputSchema(toolData.inputSchema()));

            MCPTool tool = new MCPTool(metadata, this);
            tools.add(tool);
        }

        return tools;
    }

    /**
     * Get a specific tool by name.
     *
     * @param name The tool name
     * @return The MCPTool instance
     * @throws IllegalArgumentException if tool not found
     */
    public MCPTool getTool(String name) {
        List<MCPTool> tools = listTools();
        return tools.stream()
                .filter(tool -> tool.getName().equals(name))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Tool '"
                                                + name
                                                + "' not found on MCP server at "
                                                + endpoint));
    }

    /**
     * Get tool metadata by name.
     *
     * @param name The tool name
     * @return The ToolMetadata
     */
    public ToolMetadata getToolMetadata(String name) {
        return getTool(name).getMetadata();
    }

    /**
     * Call a tool on the MCP server.
     *
     * @param toolName The name of the tool to call
     * @param arguments The arguments to pass to the tool
     * @return The result as a list of content items
     */
    public List<Object> callTool(String toolName, Map<String, Object> arguments) {
        McpSyncClient mcpClient = getClient();
        McpSchema.CallToolRequest request =
                new McpSchema.CallToolRequest(
                        toolName, arguments != null ? arguments : new HashMap<>());
        McpSchema.CallToolResult result = mcpClient.callTool(request);

        List<Object> content = new ArrayList<>();
        for (var item : result.content()) {
            content.add(MCPContentExtractor.extractContentItem(item));
        }

        return content;
    }

    /**
     * List available prompts from the MCP server.
     *
     * @return List of MCPPrompt instances
     */
    public List<MCPPrompt> listPrompts() {
        McpSyncClient mcpClient = getClient();
        McpSchema.ListPromptsResult promptsResult = mcpClient.listPrompts();

        List<MCPPrompt> prompts = new ArrayList<>();
        for (McpSchema.Prompt promptData : promptsResult.prompts()) {
            Map<String, MCPPrompt.PromptArgument> argumentsMap = new HashMap<>();
            if (promptData.arguments() != null) {
                for (var arg : promptData.arguments()) {
                    argumentsMap.put(
                            arg.name(),
                            new MCPPrompt.PromptArgument(
                                    arg.name(), arg.description(), arg.required()));
                }
            }

            MCPPrompt prompt =
                    new MCPPrompt(promptData.name(), promptData.description(), argumentsMap, this);
            prompts.add(prompt);
        }

        return prompts;
    }

    /**
     * Get a prompt by name with optional arguments.
     *
     * @param name The prompt name
     * @param arguments Optional arguments for the prompt
     * @return List of chat messages
     */
    public List<ChatMessage> getPrompt(String name, Map<String, Object> arguments) {
        McpSyncClient mcpClient = getClient();
        McpSchema.GetPromptRequest request =
                new McpSchema.GetPromptRequest(
                        name, arguments != null ? arguments : new HashMap<>());
        McpSchema.GetPromptResult result = mcpClient.getPrompt(request);

        List<ChatMessage> chatMessages = new ArrayList<>();
        for (var message : result.messages()) {
            if (message.content() instanceof McpSchema.TextContent) {
                var textContent = (McpSchema.TextContent) message.content();
                MessageRole role = MessageRole.valueOf(message.role().name().toUpperCase());
                chatMessages.add(new ChatMessage(role, textContent.text()));
            }
        }

        return chatMessages;
    }

    /** Close the MCP client and clean up resources. */
    public void close() {
        if (client != null) {
            try {
                client.closeGracefully();
            } catch (Exception e) {
                // Ignore exceptions during cleanup
            } finally {
                client = null;
            }
        }
    }

    /** Serialize input schema to JSON string. */
    private String serializeInputSchema(Object inputSchema) {
        if (inputSchema == null) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
        try {
            return new ObjectMapper().writeValueAsString(inputSchema);
        } catch (Exception e) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MCPServer that = (MCPServer) o;
        return timeoutSeconds == that.timeoutSeconds
                && Objects.equals(endpoint, that.endpoint)
                && Objects.equals(headers, that.headers)
                && Objects.equals(auth, that.auth);
    }

    @Override
    public int hashCode() {
        return Objects.hash(endpoint, headers, timeoutSeconds, auth);
    }

    @Override
    public String toString() {
        return String.format("MCPServer{endpoint='%s'}", endpoint);
    }
}
