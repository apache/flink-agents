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
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.flink.agents.api.RetryExecutor;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.configuration.DurationUtils;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.integrations.mcp.auth.ApiKeyAuth;
import org.apache.flink.agents.integrations.mcp.auth.Auth;
import org.apache.flink.agents.integrations.mcp.auth.BasicAuth;
import org.apache.flink.agents.integrations.mcp.auth.BearerTokenAuth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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
public class MCPServer extends Resource {

    private static final String FIELD_ENDPOINT = "endpoint";
    private static final String FIELD_HEADERS = "headers";
    private static final String FIELD_TIMEOUT = "timeout";
    private static final String FIELD_AUTH = "auth";
    private static final String FIELD_MAX_RETRIES = "maxRetries";
    private static final String FIELD_INITIAL_BACKOFF = "initialBackoff";
    private static final String FIELD_MAX_BACKOFF = "maxBackoff";

    private static final Duration DEFAULT_TIMEOUT_VALUE = Duration.ofSeconds(30);

    @JsonProperty(FIELD_ENDPOINT)
    private final String endpoint;

    @JsonProperty(FIELD_HEADERS)
    private final Map<String, String> headers;

    @JsonProperty(FIELD_TIMEOUT)
    @JsonSerialize(using = ToStringSerializer.class)
    private final Duration timeout;

    @JsonProperty(FIELD_AUTH)
    private final Auth auth;

    private final Integer maxRetries;

    private final Long initialBackoffMs;

    private final Long maxBackoffMs;

    @JsonIgnore private transient RetryExecutor retryExecutor;

    @JsonIgnore private transient McpSyncClient client;

    /** Builder for MCPServer with fluent API. */
    public static class Builder {
        private String endpoint;
        private final Map<String, String> headers = new HashMap<>();
        private Duration timeout = DEFAULT_TIMEOUT_VALUE;
        private Auth auth = null;
        private Integer maxRetries;
        private Duration initialBackoff;
        private Duration maxBackoff;

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
            this.timeout = timeout;
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
            this.initialBackoff = backoff;
            return this;
        }

        public Builder maxBackoff(Duration backoff) {
            this.maxBackoff = backoff;
            return this;
        }

        public MCPServer build() {
            return new MCPServer(
                    endpoint, headers, timeout, auth, maxRetries, initialBackoff, maxBackoff);
        }
    }

    public MCPServer(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        this.endpoint =
                Objects.requireNonNull(
                        descriptor.getArgument(FIELD_ENDPOINT), "endpoint cannot be null");
        Map<String, String> headers = descriptor.getArgument(FIELD_HEADERS);
        this.headers = headers != null ? new HashMap<>(headers) : new HashMap<>();
        this.timeout =
                validateTimeout(
                        descriptor.getDurationArgument(FIELD_TIMEOUT, DEFAULT_TIMEOUT_VALUE));
        this.auth = descriptor.getArgument(FIELD_AUTH);

        Object maxRetriesArg = descriptor.getArgument(FIELD_MAX_RETRIES);
        this.maxRetries =
                maxRetriesArg instanceof Number ? ((Number) maxRetriesArg).intValue() : null;

        this.initialBackoffMs =
                toBackoffMillis(
                        descriptor.getDurationArgument(FIELD_INITIAL_BACKOFF, null),
                        FIELD_INITIAL_BACKOFF);
        this.maxBackoffMs =
                toBackoffMillis(
                        descriptor.getDurationArgument(FIELD_MAX_BACKOFF, null), FIELD_MAX_BACKOFF);
    }

    /**
     * Creates a new MCPServer instance.
     *
     * @param endpoint The HTTP endpoint of the MCP server
     */
    public MCPServer(String endpoint) {
        this(endpoint, new HashMap<>(), DEFAULT_TIMEOUT_VALUE, null, null, null, null);
    }

    @JsonCreator
    public MCPServer(
            @JsonProperty(FIELD_ENDPOINT) String endpoint,
            @JsonProperty(FIELD_HEADERS) Map<String, String> headers,
            @JsonProperty(FIELD_TIMEOUT) @JsonDeserialize(using = DurationDeserializer.class)
                    Duration timeout,
            @JsonProperty(FIELD_AUTH) Auth auth,
            @JsonProperty(FIELD_MAX_RETRIES) Integer maxRetries,
            @JsonProperty(FIELD_INITIAL_BACKOFF)
                    @JsonDeserialize(using = DurationDeserializer.class)
                    Duration initialBackoff,
            @JsonProperty(FIELD_MAX_BACKOFF) @JsonDeserialize(using = DurationDeserializer.class)
                    Duration maxBackoff) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint cannot be null");
        this.headers = headers != null ? new HashMap<>(headers) : new HashMap<>();
        this.timeout = validateTimeout(timeout != null ? timeout : DEFAULT_TIMEOUT_VALUE);
        this.auth = auth;
        this.maxRetries = maxRetries;
        this.initialBackoffMs = toBackoffMillis(initialBackoff, FIELD_INITIAL_BACKOFF);
        this.maxBackoffMs = toBackoffMillis(maxBackoff, FIELD_MAX_BACKOFF);
    }

    private static Duration validateTimeout(Duration value) {
        Duration duration = DurationUtils.parse(value);
        if (!DurationUtils.isPositive(duration)) {
            throw new IllegalArgumentException("timeout must be a positive duration.");
        }
        return duration;
    }

    private static Long toBackoffMillis(Duration value, String name) {
        if (value == null) {
            return null;
        }
        if (value.isNegative()
                || value.getNano() % 1_000_000 != 0
                || value.compareTo(Duration.ofMillis(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(
                    name
                            + " must be a non-negative whole number of milliseconds within long range.");
        }
        return value.toMillis();
    }

    static final class DurationDeserializer extends JsonDeserializer<Duration> {
        @Override
        public Duration deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            return DurationUtils.parse(parser.readValueAs(Object.class));
        }
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

    public Duration getTimeout() {
        return timeout;
    }

    public Auth getAuth() {
        return auth;
    }

    @JsonProperty(FIELD_MAX_RETRIES)
    public int getMaxRetries() {
        return maxRetries != null ? maxRetries : getRetryExecutor().getMaxRetries();
    }

    @JsonProperty(FIELD_INITIAL_BACKOFF)
    public long getInitialBackoffMs() {
        return initialBackoffMs != null
                ? initialBackoffMs
                : getRetryExecutor().getInitialBackoffMs();
    }

    @JsonProperty(FIELD_MAX_BACKOFF)
    public long getMaxBackoffMs() {
        return maxBackoffMs != null ? maxBackoffMs : getRetryExecutor().getMaxBackoffMs();
    }

    /**
     * Get or create the retry executor.
     *
     * @return The retry executor
     */
    @JsonIgnore
    private synchronized RetryExecutor getRetryExecutor() {
        if (retryExecutor == null) {
            RetryExecutor.Builder builder = RetryExecutor.builder();
            if (maxRetries != null) {
                builder.maxRetries(maxRetries);
            }
            if (initialBackoffMs != null) {
                builder.initialBackoffMs(initialBackoffMs);
            }
            if (maxBackoffMs != null) {
                builder.maxBackoffMs(maxBackoffMs);
            }
            retryExecutor = builder.build();
        }
        return retryExecutor;
    }

    /**
     * Get or create a synchronized MCP client.
     *
     * @return The MCP sync client
     */
    @JsonIgnore
    private synchronized McpSyncClient getClient() {
        if (client == null) {
            client = getRetryExecutor().execute(this::createClient, "createClient");
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

        var requestBuilder = HttpRequest.newBuilder().timeout(timeout);

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
        var mcpClient = McpClient.sync(transport).requestTimeout(timeout).build();

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
     * Check if the MCP server supports prompts based on its declared capabilities.
     *
     * @return true if the server declared prompt capabilities during initialization
     */
    public boolean supportsPrompts() {
        try {
            McpSyncClient mcpClient = getClient();
            McpSchema.ServerCapabilities caps = mcpClient.getServerCapabilities();
            return caps != null && caps.prompts() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * List available tools from the MCP server.
     *
     * @return List of MCPTool instances
     */
    public List<MCPTool> listTools() {
        return getRetryExecutor()
                .execute(
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
     * @throws IllegalStateException if the MCP response reports a protocol-level tool error
     */
    public List<Object> callTool(String toolName, Map<String, Object> arguments) {
        McpSchema.CallToolResult result =
                getRetryExecutor()
                        .execute(
                                () -> {
                                    McpSyncClient mcpClient = getClient();
                                    McpSchema.CallToolRequest request =
                                            new McpSchema.CallToolRequest(
                                                    toolName,
                                                    arguments != null
                                                            ? arguments
                                                            : new HashMap<>());
                                    return mcpClient.callTool(request);
                                },
                                "callTool:" + toolName);
        return extractToolContent(toolName, result);
    }

    static List<Object> extractToolContent(
            String toolName, McpSchema.CallToolResult callToolResult) {
        List<Object> content = new ArrayList<>();
        for (var item : callToolResult.content()) {
            content.add(MCPContentExtractor.extractContentItem(item));
        }
        if (Boolean.TRUE.equals(callToolResult.isError())) {
            throw new IllegalStateException(
                    "MCP tool '" + toolName + "' returned an error: " + content);
        }
        return content;
    }

    /**
     * List available prompts from the MCP server.
     *
     * @return List of MCPPrompt instances
     */
    public List<MCPPrompt> listPrompts() {
        if (!supportsPrompts()) {
            return Collections.emptyList();
        }
        return getRetryExecutor()
                .execute(
                        () -> {
                            McpSyncClient mcpClient = getClient();
                            McpSchema.ListPromptsResult promptsResult = mcpClient.listPrompts();

                            List<MCPPrompt> prompts = new ArrayList<>();
                            for (McpSchema.Prompt promptData : promptsResult.prompts()) {
                                Map<String, MCPPrompt.PromptArgument> argumentsMap =
                                        new HashMap<>();
                                if (promptData.arguments() != null) {
                                    for (var arg : promptData.arguments()) {
                                        argumentsMap.put(
                                                arg.name(),
                                                new MCPPrompt.PromptArgument(
                                                        arg.name(),
                                                        arg.description(),
                                                        arg.required()));
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
        return getRetryExecutor()
                .execute(
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
                                            MessageRole.valueOf(
                                                    message.role().name().toUpperCase());
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
        return timeout.equals(that.timeout)
                && getMaxRetries() == that.getMaxRetries()
                && getInitialBackoffMs() == that.getInitialBackoffMs()
                && getMaxBackoffMs() == that.getMaxBackoffMs()
                && Objects.equals(endpoint, that.endpoint)
                && Objects.equals(headers, that.headers)
                && Objects.equals(auth, that.auth);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                endpoint,
                headers,
                timeout,
                auth,
                getMaxRetries(),
                getInitialBackoffMs(),
                getMaxBackoffMs());
    }

    @Override
    public String toString() {
        return String.format("MCPServer{endpoint='%s'}", endpoint);
    }
}
