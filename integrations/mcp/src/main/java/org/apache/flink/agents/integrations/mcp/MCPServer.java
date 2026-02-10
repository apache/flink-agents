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
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.integrations.mcp.auth.ApiKeyAuth;
import org.apache.flink.agents.integrations.mcp.auth.Auth;
import org.apache.flink.agents.integrations.mcp.auth.BasicAuth;
import org.apache.flink.agents.integrations.mcp.auth.BearerTokenAuth;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;

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
public class MCPServer extends Resource {

    private static final Random RANDOM = new Random();

    private static final String FIELD_ENDPOINT = "endpoint";
    private static final String FIELD_HEADERS = "headers";
    private static final String FIELD_TIMEOUT_SECONDS = "timeoutSeconds";
    private static final String FIELD_TIMEOUT = "timeout";
    private static final String FIELD_AUTH = "auth";
    private static final String FIELD_MAX_RETRIES = "maxRetries";
    private static final String FIELD_INITIAL_BACKOFF_MS = "initialBackoffMs";
    private static final String FIELD_MAX_BACKOFF_MS = "maxBackoffMs";

    private static final long DEFAULT_TIMEOUT_VALUE = 30L;
    private static final int MAX_RETRIES_VALUE = 3;
    private static final int INITIAL_BACKOFF_MS_VALUE = 100;
    private static final int MAX_BACKOFF_MS_VALUE = 10000;

    @JsonProperty(FIELD_ENDPOINT)
    private final String endpoint;

    @JsonProperty(FIELD_HEADERS)
    private final Map<String, String> headers;

    @JsonProperty(FIELD_TIMEOUT_SECONDS)
    private final long timeoutSeconds;

    @JsonProperty(FIELD_AUTH)
    private final Auth auth;

    @JsonProperty(FIELD_MAX_RETRIES)
    private final int maxRetries;

    @JsonProperty(FIELD_INITIAL_BACKOFF_MS)
    private final long initialBackoffMs;

    @JsonProperty(FIELD_MAX_BACKOFF_MS)
    private final long maxBackoffMs;

    @JsonIgnore private transient McpSyncClient client;

    /** Builder for MCPServer with fluent API. */
    public static class Builder {
        private String endpoint;
        private final Map<String, String> headers = new HashMap<>();
        private long timeoutSeconds = DEFAULT_TIMEOUT_VALUE;
        private Auth auth = null;
        private int maxRetries = MAX_RETRIES_VALUE;
        private long initialBackoffMs = INITIAL_BACKOFF_MS_VALUE;
        private long maxBackoffMs = MAX_BACKOFF_MS_VALUE;

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

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder initialBackoff(Duration backoff) {
            this.initialBackoffMs = backoff.toMillis();
            return this;
        }

        public Builder maxBackoff(Duration backoff) {
            this.maxBackoffMs = backoff.toMillis();
            return this;
        }

        public MCPServer build() {
            return new MCPServer(
                    endpoint, headers, timeoutSeconds, auth, maxRetries, initialBackoffMs, maxBackoffMs);
        }
    }

    public MCPServer(
            ResourceDescriptor descriptor, BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);
        this.endpoint =
                Objects.requireNonNull(
                        descriptor.getArgument(FIELD_ENDPOINT), "endpoint cannot be null");
        Map<String, String> headers = descriptor.getArgument(FIELD_HEADERS);
        this.headers = headers != null ? new HashMap<>(headers) : new HashMap<>();
        Object timeoutArg = descriptor.getArgument(FIELD_TIMEOUT);
        this.timeoutSeconds = timeoutArg instanceof Number ? ((Number) timeoutArg).longValue() : DEFAULT_TIMEOUT_VALUE;
        this.auth = descriptor.getArgument(FIELD_AUTH);

        Object maxRetriesArg = descriptor.getArgument(FIELD_MAX_RETRIES);
        this.maxRetries = maxRetriesArg instanceof Number ? ((Number) maxRetriesArg).intValue() : MAX_RETRIES_VALUE;

        Object initialBackoffArg = descriptor.getArgument(FIELD_INITIAL_BACKOFF_MS);
        this.initialBackoffMs = initialBackoffArg instanceof Number
                ? ((Number) initialBackoffArg).longValue() : INITIAL_BACKOFF_MS_VALUE;

        Object maxBackoffArg = descriptor.getArgument(FIELD_MAX_BACKOFF_MS);
        this.maxBackoffMs = maxBackoffArg instanceof Number
                ? ((Number) maxBackoffArg).longValue() : MAX_BACKOFF_MS_VALUE;
    }

    /**
     * Creates a new MCPServer instance.
     *
     * @param endpoint The HTTP endpoint of the MCP server
     */
    public MCPServer(String endpoint) {
        this(endpoint, new HashMap<>(), 30L, null, 3, 100L, 10000L);
    }

    @JsonCreator
    public MCPServer(
            @JsonProperty(FIELD_ENDPOINT) String endpoint,
            @JsonProperty(FIELD_HEADERS) Map<String, String> headers,
            @JsonProperty(FIELD_TIMEOUT_SECONDS) Long timeoutSeconds,
            @JsonProperty(FIELD_AUTH) Auth auth,
            @JsonProperty(FIELD_MAX_RETRIES) Integer maxRetries,
            @JsonProperty(FIELD_INITIAL_BACKOFF_MS) Long initialBackoffMs,
            @JsonProperty(FIELD_MAX_BACKOFF_MS) Long maxBackoffMs) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint cannot be null");
        this.headers = headers != null ? new HashMap<>(headers) : new HashMap<>();
        this.timeoutSeconds = timeoutSeconds != null ? timeoutSeconds : DEFAULT_TIMEOUT_VALUE;
        this.auth = auth;
        this.maxRetries = maxRetries != null ? maxRetries : MAX_RETRIES_VALUE;
        this.initialBackoffMs = initialBackoffMs != null ? initialBackoffMs : INITIAL_BACKOFF_MS_VALUE;
        this.maxBackoffMs = maxBackoffMs != null ? maxBackoffMs : MAX_BACKOFF_MS_VALUE;
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

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getInitialBackoffMs() {
        return initialBackoffMs;
    }

    public long getMaxBackoffMs() {
        return maxBackoffMs;
    }

    /**
     * Get or create a synchronized MCP client.
     *
     * @return The MCP sync client
     */
    @JsonIgnore
    private synchronized McpSyncClient getClient() {
        if (client == null) {
            client = executeWithRetry(this::createClient, "createClient");
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

        var requestBuilder =
                HttpRequest.newBuilder().timeout(Duration.ofSeconds(timeoutSeconds));

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
     * Execute an operation with retry logic.
     *
     * @param operation The operation to execute
     * @param operationName Name of the operation for error messages
     * @return The result of the operation
     * @throws RuntimeException if all retries fail
     */
    private <T> T executeWithRetry(Callable<T> operation, String operationName) {
        int attempt = 0;
        long backoff = initialBackoffMs;
        Exception lastException = null;

        while (attempt <= maxRetries) {
            try {
                return operation.call();

            } catch (Exception e) {
                lastException = e;
                attempt++;

                if (!isRetryable(e)) {
                    throw new RuntimeException(
                            String.format(
                                    "MCP operation '%s' failed: %s",
                                    operationName, e.getMessage()),
                            e);
                }

                if (attempt > maxRetries) {
                    break;
                }

                // Exponential backoff with jitter
                try {
                    long jitter = RANDOM.nextInt((int) (backoff / 10) + 1);
                    long sleepTime = backoff + jitter;
                    Thread.sleep(sleepTime);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(
                            "Interrupted while retrying MCP operation: " + operationName, ie);
                }

                backoff = Math.min(backoff * 2, maxBackoffMs);
            }
        }

        // All retries exhausted
        throw new RuntimeException(
                String.format(
                        "MCP operation '%s' failed after %d retries: %s",
                        operationName, maxRetries, lastException.getMessage()),
                lastException);
    }

    /**
     * Check if an exception is retryable.
     *
     * @param e The exception to check
     * @return true if the operation should be retried
     */
    private boolean isRetryable(Exception e) {
        // Network-related errors are retryable
        if (e instanceof SocketTimeoutException || e instanceof ConnectException) {
            return true;
        }
        String message = e.getMessage();
        if (message != null) {
            if (message.contains("503")) {
                return true;
            }
            if (message.contains("429")) {
                return true;
            }
            // Connection reset, connection refused - retryable
            return message.contains("Connection reset")
                    || message.contains("Connection refused")
                    || message.contains("Connection timed out");
        }

        return false;
    }

    /**
     * List available tools from the MCP server.
     *
     * @return List of MCPTool instances
     */
    public List<MCPTool> listTools() {
        return executeWithRetry(
                () -> {
                    McpSyncClient mcpClient = getClient();
                    McpSchema.ListToolsResult toolsResult = mcpClient.listTools();

                    List<MCPTool> tools = new ArrayList<>();
                    for (McpSchema.Tool toolData : toolsResult.tools()) {
                        ToolMetadata metadata =
                                new ToolMetadata(
                                        toolData.name(),
                                        toolData.description() != null
                                                ? toolData.description()
                                                : "",
                                        serializeInputSchema(toolData.inputSchema()));

                        MCPTool tool = new MCPTool(metadata, this);
                        tools.add(tool);
                    }

                    return tools;
                },
                "listTools");
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
        return executeWithRetry(
                () -> {
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
                },
                "callTool:" + toolName);
    }

    /**
     * List available prompts from the MCP server.
     *
     * @return List of MCPPrompt instances
     */
    public List<MCPPrompt> listPrompts() {
        return executeWithRetry(
                () -> {
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
                                new MCPPrompt(
                                        promptData.name(),
                                        promptData.description(),
                                        argumentsMap,
                                        this);
                        prompts.add(prompt);
                    }

                    return prompts;
                },
                "listPrompts");
    }

    /**
     * Get a prompt by name with optional arguments.
     *
     * @param name The prompt name
     * @param arguments Optional arguments for the prompt
     * @return List of chat messages
     */
    public List<ChatMessage> getPrompt(String name, Map<String, Object> arguments) {
        return executeWithRetry(
                () -> {
                    McpSyncClient mcpClient = getClient();
                    McpSchema.GetPromptRequest request =
                            new McpSchema.GetPromptRequest(
                                    name, arguments != null ? arguments : new HashMap<>());
                    McpSchema.GetPromptResult result = mcpClient.getPrompt(request);

                    List<ChatMessage> chatMessages = new ArrayList<>();
                    for (var message : result.messages()) {
                        if (message.content() instanceof McpSchema.TextContent) {
                            var textContent = (McpSchema.TextContent) message.content();
                            MessageRole role =
                                    MessageRole.valueOf(message.role().name().toUpperCase());
                            chatMessages.add(new ChatMessage(role, textContent.text()));
                        }
                    }

                    return chatMessages;
                },
                "getPrompt:" + name);
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
                && maxRetries == that.maxRetries
                && initialBackoffMs == that.initialBackoffMs
                && maxBackoffMs == that.maxBackoffMs
                && Objects.equals(endpoint, that.endpoint)
                && Objects.equals(headers, that.headers)
                && Objects.equals(auth, that.auth);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                endpoint,
                headers,
                timeoutSeconds,
                auth,
                maxRetries,
                initialBackoffMs,
                maxBackoffMs);
    }

    @Override
    public String toString() {
        return String.format("MCPServer{endpoint='%s'}", endpoint);
    }
}
