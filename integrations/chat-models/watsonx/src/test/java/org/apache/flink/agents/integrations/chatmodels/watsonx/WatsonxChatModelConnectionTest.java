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
package org.apache.flink.agents.integrations.chatmodels.watsonx;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WatsonxChatModelConnection}. Request-level tests use a local stub server,
 * so the suite runs in CI without external network access or an API key.
 */
class WatsonxChatModelConnectionTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);
    private static final Function<String, String> NO_ENVIRONMENT = ignored -> null;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CHAT_RESPONSE =
            "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Hello!\"},\"finish_reason\":\"stop\"}]}";
    private static final String MODEL = "ibm/granite-3-3-8b-instruct";
    private static final Map<String, Object> CALLER_FORMAT = Map.of("type", "json_object");

    /**
     * Output schema fixture shaped to expose the schema-generation settings.
     *
     * <p>{@code counts} is a map whose values carry a type, and {@code getDerived} is a getter
     * backed by no field.
     */
    public static class Report {
        public String summary;
        public Map<String, Integer> counts;
        public Optional<String> note;
        public int total;

        public String getDerived() {
            return summary + total;
        }
    }

    /**
     * Output schema fixture whose enum constants are deserialized from values other than their
     * names, one through {@code @JsonProperty} on the constants and one through a
     * {@code @JsonValue} method.
     */
    public static class Ticket {
        public Status status;

        public Phase phase;
    }

    public enum Status {
        @JsonProperty("in-progress")
        IN_PROGRESS,
        @JsonProperty("done")
        DONE
    }

    public enum Phase {
        STARTED("started"),
        FINISHED("finished");

        private final String wire;

        Phase(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    private static ResourceDescriptor descriptor(String url, String apiKey, String projectId) {
        ResourceDescriptor.Builder b =
                ResourceDescriptor.Builder.newBuilder(WatsonxChatModelConnection.class.getName());
        if (url != null) {
            b.addInitialArgument("url", url);
        }
        if (apiKey != null) {
            b.addInitialArgument("api_key", apiKey);
        }
        if (projectId != null) {
            b.addInitialArgument("project_id", projectId);
        }
        return b.build();
    }

    private static ResourceDescriptor stubDescriptor(
            String baseUrl, boolean useApiKey, int maxRetries) {
        ResourceDescriptor.Builder builder =
                ResourceDescriptor.Builder.newBuilder(WatsonxChatModelConnection.class.getName())
                        .addInitialArgument("url", baseUrl)
                        .addInitialArgument("project_id", "test-project")
                        .addInitialArgument("max_retries", maxRetries);
        if (useApiKey) {
            builder.addInitialArgument("api_key", "test-key");
            builder.addInitialArgument("iam_url", baseUrl);
        } else {
            builder.addInitialArgument("token", "test-token");
        }
        return builder.build();
    }

    private static HttpServer startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(null);
        server.start();
        return server;
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void sendJson(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static ChatMessage chat(WatsonxChatModelConnection connection) {
        return connection.chat(
                List.of(new ChatMessage(MessageRole.USER, "Hello!")),
                List.of(),
                Map.of("model", MODEL));
    }

    /** A connection that is never sent a request, for the payload-building tests. */
    private static WatsonxChatModelConnection connection() {
        return new WatsonxChatModelConnection(
                descriptor("https://us-south.ml.cloud.ibm.com", "test-key", "test-project"),
                NOOP,
                NO_ENVIRONMENT);
    }

    private static ObjectNode payloadFor(Object outputSchema) {
        return payloadFor(Map.of("model", MODEL), outputSchema);
    }

    private static ObjectNode payloadFor(Map<String, Object> modelParams, Object outputSchema) {
        return connection()
                .buildPayload(
                        List.of(new ChatMessage(MessageRole.USER, "Hello!")),
                        List.of(),
                        modelParams,
                        outputSchema);
    }

    private static JsonNode derivedSchema(Object outputSchema) {
        return payloadFor(outputSchema).path("response_format").path("json_schema").path("schema");
    }

    private static List<String> fieldNames(JsonNode objectNode) {
        List<String> names = new ArrayList<>();
        objectNode.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingRequiredConfiguration")
    void testConstructorRejectsMissingRequiredConfiguration(
            String ignoredCaseName,
            String url,
            String apiKey,
            String projectId,
            String expectedMessage) {
        assertThatThrownBy(
                        () ->
                                new WatsonxChatModelConnection(
                                        descriptor(url, apiKey, projectId), NOOP, NO_ENVIRONMENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedMessage);
    }

    private static Stream<Arguments> missingRequiredConfiguration() {
        return Stream.of(
                Arguments.of("missing url", null, "test-key", "test-project", "url"),
                Arguments.of(
                        "missing credentials",
                        "https://us-south.ml.cloud.ibm.com",
                        null,
                        "test-project",
                        "credentials"),
                Arguments.of(
                        "missing project or space",
                        "https://us-south.ml.cloud.ibm.com",
                        "test-key",
                        null,
                        "project or space"));
    }

    @Test
    @DisplayName("Constructor accepts space_id without project_id")
    void testConstructorWithSpaceId() {
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder(WatsonxChatModelConnection.class.getName())
                        .addInitialArgument("url", " https://us-south.ml.cloud.ibm.com ")
                        .addInitialArgument("api_key", " test-key ")
                        .addInitialArgument("space_id", " test-space ")
                        .build();

        assertThat(new WatsonxChatModelConnection(descriptor, NOOP, NO_ENVIRONMENT))
                .isInstanceOf(BaseChatModelConnection.class);
    }

    @Test
    @DisplayName("Constructor rejects ambiguous scope and credentials")
    void testConstructorRejectsAmbiguousConfiguration() {
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder(WatsonxChatModelConnection.class.getName())
                        .addInitialArgument("url", "https://us-south.ml.cloud.ibm.com")
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("project_id", "test-project")
                        .addInitialArgument("space_id", "test-space")
                        .build();

        assertThatThrownBy(() -> new WatsonxChatModelConnection(descriptor, NOOP, NO_ENVIRONMENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot both be provided")
                .hasMessageContaining("exactly one");

        ResourceDescriptor credentials =
                ResourceDescriptor.Builder.newBuilder(WatsonxChatModelConnection.class.getName())
                        .addInitialArgument("url", " https://us-south.ml.cloud.ibm.com ")
                        .addInitialArgument("api_key", " test-key ")
                        .addInitialArgument("token", " test-token ")
                        .addInitialArgument("project_id", " test-project ")
                        .build();
        assertThatThrownBy(() -> new WatsonxChatModelConnection(credentials, NOOP, NO_ENVIRONMENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("api_key and token")
                .hasMessageContaining("exactly one");
    }

    @Test
    @DisplayName("Request timeout accepts positive fractional seconds and rejects invalid values")
    void testRequestTimeoutValidation() {
        ResourceDescriptor fractionalTimeout =
                ResourceDescriptor.Builder.newBuilder(WatsonxChatModelConnection.class.getName())
                        .addInitialArgument("url", "https://us-south.ml.cloud.ibm.com")
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("project_id", "test-project")
                        .addInitialArgument("request_timeout", 0.5)
                        .build();
        assertThat(new WatsonxChatModelConnection(fractionalTimeout, NOOP, NO_ENVIRONMENT))
                .isInstanceOf(BaseChatModelConnection.class);

        for (double invalidTimeout : List.of(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            ResourceDescriptor invalid =
                    ResourceDescriptor.Builder.newBuilder(
                                    WatsonxChatModelConnection.class.getName())
                            .addInitialArgument("url", "https://us-south.ml.cloud.ibm.com")
                            .addInitialArgument("api_key", "test-key")
                            .addInitialArgument("project_id", "test-project")
                            .addInitialArgument("request_timeout", invalidTimeout)
                            .build();
            assertThatThrownBy(() -> new WatsonxChatModelConnection(invalid, NOOP, NO_ENVIRONMENT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("request_timeout");
        }
    }

    @Test
    @DisplayName("max_retries must be a non-negative integer")
    void testMaxRetriesValidation() {
        for (Number invalidMaxRetries :
                new Number[] {-1, 0.9, Double.NaN, Double.POSITIVE_INFINITY}) {
            ResourceDescriptor invalid =
                    ResourceDescriptor.Builder.newBuilder(
                                    WatsonxChatModelConnection.class.getName())
                            .addInitialArgument("url", "https://us-south.ml.cloud.ibm.com")
                            .addInitialArgument("api_key", "test-key")
                            .addInitialArgument("project_id", "test-project")
                            .addInitialArgument("max_retries", invalidMaxRetries)
                            .build();
            assertThatThrownBy(() -> new WatsonxChatModelConnection(invalid, NOOP, NO_ENVIRONMENT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("max_retries");
        }
    }

    @Test
    @DisplayName("System, user, assistant and tool messages convert to the watsonx format")
    void testConvertMessages() {
        ChatMessage assistant = new ChatMessage(MessageRole.ASSISTANT, "");
        assistant.setToolCalls(
                List.of(
                        Map.of(
                                "id", "internal-uuid",
                                "original_id", "call_abc123",
                                "type", "function",
                                "function",
                                        Map.of(
                                                "name",
                                                "add",
                                                "arguments",
                                                Map.of("a", 1, "b", 2)))));
        ChatMessage toolResult =
                new ChatMessage(MessageRole.TOOL, "3", Map.of("externalId", "call_abc123"));

        ArrayNode converted =
                WatsonxChatModelConnection.convertMessages(
                        List.of(
                                new ChatMessage(MessageRole.SYSTEM, "You are helpful."),
                                new ChatMessage(MessageRole.USER, "What is 1 + 2?"),
                                assistant,
                                toolResult));

        assertThat(converted).hasSize(4);
        assertThat(converted.get(0).get("role").asText()).isEqualTo("system");
        assertThat(converted.get(0).get("content").asText()).isEqualTo("You are helpful.");
        assertThat(converted.get(1).get("role").asText()).isEqualTo("user");

        JsonNode assistantNode = converted.get(2);
        assertThat(assistantNode.get("role").asText()).isEqualTo("assistant");
        assertThat(assistantNode.has("content")).isFalse();
        JsonNode toolCall = assistantNode.get("tool_calls").get(0);
        assertThat(toolCall.get("id").asText()).isEqualTo("call_abc123");
        assertThat(toolCall.get("function").get("name").asText()).isEqualTo("add");
        // arguments must be serialized as a JSON string
        assertThat(toolCall.get("function").get("arguments").isTextual()).isTrue();

        JsonNode toolNode = converted.get(3);
        assertThat(toolNode.get("role").asText()).isEqualTo("tool");
        assertThat(toolNode.get("tool_call_id").asText()).isEqualTo("call_abc123");
        assertThat(toolNode.get("content").asText()).isEqualTo("3");
    }

    @Test
    @DisplayName("Tool message without externalId is rejected")
    void testConvertToolMessageWithoutExternalId() {
        assertThatThrownBy(
                        () ->
                                WatsonxChatModelConnection.convertMessages(
                                        List.of(new ChatMessage(MessageRole.TOOL, "3"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("externalId");
    }

    @Test
    @DisplayName("Model params are copied top-level into the payload")
    void testBuildPayload() {
        ObjectNode payload =
                payloadFor(
                        Map.of(
                                "model",
                                MODEL,
                                "temperature",
                                0.5,
                                "max_tokens",
                                256,
                                "top_p",
                                0.5,
                                "extract_reasoning",
                                true,
                                "additional_kwargs",
                                Map.of("top_p", 0.9)),
                        null);

        assertThat(payload.get("model_id").asText()).isEqualTo(MODEL);
        assertThat(payload.get("temperature").asDouble()).isEqualTo(0.5);
        assertThat(payload.get("max_tokens").asInt()).isEqualTo(256);
        assertThat(payload.get("top_p").asDouble()).isEqualTo(0.5);
        assertThat(payload.get("messages")).hasSize(1);
        // framework control params must not leak into the request
        assertThat(payload.has("model")).isFalse();
        assertThat(payload.has("extract_reasoning")).isFalse();
        assertThat(payload.has("tools")).isFalse();

        assertThatThrownBy(
                        () ->
                                payloadFor(
                                        Map.of(
                                                "model",
                                                MODEL,
                                                "additional_kwargs",
                                                Map.of("temperature", 5.0)),
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("additional_kwargs")
                .hasMessageContaining("temperature");

        for (String requestOwnedField :
                List.of("model_id", "messages", "tools", "project_id", "space_id")) {
            assertThatThrownBy(
                            () ->
                                    payloadFor(
                                            Map.of("model", MODEL, requestOwnedField, "override"),
                                            null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(requestOwnedField);
        }
    }

    @Test
    @DisplayName("Chat response with content and usage parses into a ChatMessage")
    void testParseResponse() throws Exception {
        JsonNode response =
                MAPPER.readTree(
                        "{\"choices\": [{\"index\": 0, \"message\": {\"role\": \"assistant\","
                                + " \"content\": \"Hello there!\"}, \"finish_reason\": \"stop\"}],"
                                + " \"usage\": {\"prompt_tokens\": 100, \"completion_tokens\": 50,"
                                + " \"total_tokens\": 150}}");

        ChatMessage message =
                WatsonxChatModelConnection.parseResponse(response, "ibm/granite-3-3-8b-instruct");

        assertThat(message.getRole()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(message.getContent()).isEqualTo("Hello there!");
        assertThat(message.getExtraArgs().get("model_name"))
                .isEqualTo("ibm/granite-3-3-8b-instruct");
        assertThat(message.getExtraArgs().get("promptTokens")).isEqualTo(100L);
        assertThat(message.getExtraArgs().get("completionTokens")).isEqualTo(50L);
        assertThat(message.getExtraArgs()).containsEntry("finish_reason", "stop");
    }

    @Test
    @DisplayName("A finish reason outside the documented set is stored as received")
    void testParseResponseCarriesUnknownFinishReasonVerbatim() throws Exception {
        JsonNode response =
                MAPPER.readTree(
                        "{\"choices\": [{\"index\": 0, \"message\": {\"role\": \"assistant\","
                                + " \"content\": \"hi\"}, \"finish_reason\":"
                                + " \"some_vendor_reason\"}]}");

        ChatMessage message = WatsonxChatModelConnection.parseResponse(response, null);

        assertThat(message.getExtraArgs()).containsEntry("finish_reason", "some_vendor_reason");
    }

    @Test
    @DisplayName("A response with no finish_reason member yields no key and no error")
    void testParseResponseNoFinishReasonKeyWhenMemberAbsent() throws Exception {
        JsonNode response =
                MAPPER.readTree(
                        "{\"choices\": [{\"index\": 0, \"message\": {\"role\": \"assistant\","
                                + " \"content\": \"hi\"}}]}");

        ChatMessage message = WatsonxChatModelConnection.parseResponse(response, null);

        assertThat(message.getExtraArgs()).doesNotContainKey("finish_reason");
    }

    @Test
    @DisplayName("A response whose finish_reason is JSON null yields no key and no error")
    void testParseResponseNoFinishReasonKeyWhenJsonNull() throws Exception {
        JsonNode response =
                MAPPER.readTree(
                        "{\"choices\": [{\"index\": 0, \"message\": {\"role\": \"assistant\","
                                + " \"content\": \"hi\"}, \"finish_reason\": null}]}");

        ChatMessage message = WatsonxChatModelConnection.parseResponse(response, null);

        assertThat(message.getExtraArgs()).doesNotContainKey("finish_reason");
    }

    @Test
    @DisplayName("The finish reason is captured independently of the token metrics")
    void testParseResponseCarriesFinishReasonWithoutUsage() throws Exception {
        // modelName is null here, so the usage-metadata branch cannot run; this proves
        // finish_reason capture does not depend on it.
        JsonNode response =
                MAPPER.readTree(
                        "{\"choices\": [{\"index\": 0, \"message\": {\"role\": \"assistant\","
                                + " \"content\": \"hi\"}, \"finish_reason\": \"tool_calls\"}]}");

        ChatMessage message = WatsonxChatModelConnection.parseResponse(response, null);

        assertThat(message.getExtraArgs())
                .containsEntry("finish_reason", "tool_calls")
                .doesNotContainKey("promptTokens");
    }

    @Test
    @DisplayName("IAM token is cached and reused between chat requests")
    void testIamTokenIsCached() throws Exception {
        HttpServer server = startServer();
        AtomicInteger iamRequests = new AtomicInteger();
        AtomicInteger chatRequests = new AtomicInteger();
        server.createContext(
                "/identity/token",
                exchange -> {
                    iamRequests.incrementAndGet();
                    sendJson(exchange, 200, "{\"access_token\":\"token-1\",\"expires_in\":3600}");
                });
        server.createContext(
                "/ml/v1/text/chat",
                exchange -> {
                    chatRequests.incrementAndGet();
                    sendJson(exchange, 200, CHAT_RESPONSE);
                });

        try {
            WatsonxChatModelConnection connection =
                    new WatsonxChatModelConnection(
                            stubDescriptor(baseUrl(server), true, 0), NOOP, NO_ENVIRONMENT);
            assertThat(chat(connection).getContent()).isEqualTo("Hello!");
            assertThat(chat(connection).getContent()).isEqualTo("Hello!");
            assertThat(iamRequests).hasValue(1);
            assertThat(chatRequests).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("IAM token is refreshed inside the 60-second expiry margin")
    void testIamTokenRefreshMargin() throws Exception {
        HttpServer server = startServer();
        AtomicInteger iamRequests = new AtomicInteger();
        server.createContext(
                "/identity/token",
                exchange -> {
                    int tokenNumber = iamRequests.incrementAndGet();
                    sendJson(
                            exchange,
                            200,
                            "{\"access_token\":\"token-" + tokenNumber + "\",\"expires_in\":60}");
                });
        server.createContext(
                "/ml/v1/text/chat", exchange -> sendJson(exchange, 200, CHAT_RESPONSE));

        try {
            WatsonxChatModelConnection connection =
                    new WatsonxChatModelConnection(
                            stubDescriptor(baseUrl(server), true, 0), NOOP, NO_ENVIRONMENT);
            chat(connection);
            chat(connection);
            assertThat(iamRequests).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    @DisplayName("Rejected IAM token is refreshed and retried once")
    void testRejectedIamTokenIsRefreshed(int rejectedStatus) throws Exception {
        HttpServer server = startServer();
        AtomicInteger iamRequests = new AtomicInteger();
        AtomicInteger chatRequests = new AtomicInteger();
        server.createContext(
                "/identity/token",
                exchange -> {
                    int tokenNumber = iamRequests.incrementAndGet();
                    sendJson(
                            exchange,
                            200,
                            "{\"access_token\":\"token-" + tokenNumber + "\",\"expires_in\":3600}");
                });
        server.createContext(
                "/ml/v1/text/chat",
                exchange -> {
                    int requestNumber = chatRequests.incrementAndGet();
                    sendJson(
                            exchange,
                            requestNumber == 1 ? rejectedStatus : 200,
                            requestNumber == 1 ? "{}" : CHAT_RESPONSE);
                });

        try {
            WatsonxChatModelConnection connection =
                    new WatsonxChatModelConnection(
                            stubDescriptor(baseUrl(server), true, 0), NOOP, NO_ENVIRONMENT);
            assertThat(chat(connection).getContent()).isEqualTo("Hello!");
            assertThat(iamRequests).hasValue(2);
            assertThat(chatRequests).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Retryable response is retried while a client error is not")
    void testRetryLoop() throws Exception {
        HttpServer server = startServer();
        AtomicInteger chatRequests = new AtomicInteger();
        server.createContext(
                "/ml/v1/text/chat",
                exchange -> {
                    int requestNumber = chatRequests.incrementAndGet();
                    sendJson(
                            exchange,
                            requestNumber == 1 ? 503 : 200,
                            requestNumber == 1 ? "{}" : CHAT_RESPONSE);
                });

        try {
            WatsonxChatModelConnection retryingConnection =
                    new WatsonxChatModelConnection(
                            stubDescriptor(baseUrl(server), false, 1), NOOP, NO_ENVIRONMENT);
            assertThat(chat(retryingConnection).getContent()).isEqualTo("Hello!");
            assertThat(chatRequests).hasValue(2);

            server.removeContext("/ml/v1/text/chat");
            chatRequests.set(0);
            server.createContext(
                    "/ml/v1/text/chat",
                    exchange -> {
                        chatRequests.incrementAndGet();
                        sendJson(exchange, 400, "{\"error\":\"bad request\"}");
                    });
            WatsonxChatModelConnection nonRetryingConnection =
                    new WatsonxChatModelConnection(
                            stubDescriptor(baseUrl(server), false, 3), NOOP, NO_ENVIRONMENT);
            assertThatThrownBy(() -> chat(nonRetryingConnection))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("status 400");
            assertThat(chatRequests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Reasoning blocks are extracted without changing plain content")
    void testExtractReasoning() {
        assertThat(WatsonxChatModelConnection.extractReasoning("<think>Plan</think>\nAnswer"))
                .containsExactly("Answer", "Plan");
        String plainContent = "| 1  | 2  |\n\n    indented";
        assertThat(WatsonxChatModelConnection.extractReasoning(plainContent))
                .containsExactly(plainContent, null);
    }

    @Test
    @DisplayName("Transient HTTP statuses are retryable and backoff honors Retry-After")
    void testRetryPolicy() {
        assertThat(WatsonxChatModelConnection.isRetryableStatus(408)).isTrue();
        assertThat(WatsonxChatModelConnection.isRetryableStatus(429)).isTrue();
        assertThat(WatsonxChatModelConnection.isRetryableStatus(500)).isTrue();
        assertThat(WatsonxChatModelConnection.isRetryableStatus(502)).isTrue();
        assertThat(WatsonxChatModelConnection.isRetryableStatus(503)).isTrue();
        assertThat(WatsonxChatModelConnection.isRetryableStatus(504)).isTrue();
        assertThat(WatsonxChatModelConnection.isRetryableStatus(200)).isFalse();
        assertThat(WatsonxChatModelConnection.isRetryableStatus(400)).isFalse();
        assertThat(WatsonxChatModelConnection.isRetryableStatus(401)).isFalse();
        assertThat(WatsonxChatModelConnection.isRetryableStatus(501)).isFalse();
        assertThat(WatsonxChatModelConnection.isRetryableStatus(505)).isFalse();

        // exponential backoff, capped
        assertThat(WatsonxChatModelConnection.retryDelayMillis(0, null)).isEqualTo(1000L);
        assertThat(WatsonxChatModelConnection.retryDelayMillis(1, null)).isEqualTo(2000L);
        assertThat(WatsonxChatModelConnection.retryDelayMillis(10, null)).isEqualTo(10_000L);
        // Retry-After wins when larger, is capped, and non-numeric values are ignored
        assertThat(WatsonxChatModelConnection.retryDelayMillis(0, "5")).isEqualTo(5000L);
        assertThat(WatsonxChatModelConnection.retryDelayMillis(0, "600")).isEqualTo(30_000L);
        assertThat(WatsonxChatModelConnection.retryDelayMillis(0, "not-a-number")).isEqualTo(1000L);
    }

    @Test
    @DisplayName("Tool arguments in messy model-emitted formats are parsed into a map")
    void testParseToolArguments() throws Exception {
        // clean JSON object string
        assertThat(
                        WatsonxChatModelConnection.parseToolArguments(
                                MAPPER.readTree("\"{\\\"a\\\": 1, \\\"b\\\": 2}\"")))
                .isEqualTo(Map.of("a", 1, "b", 2));
        // double-encoded JSON string
        assertThat(
                        WatsonxChatModelConnection.parseToolArguments(
                                MAPPER.readTree("\"\\\"{\\\\\\\"a\\\\\\\": 1}\\\"\"")))
                .isEqualTo(Map.of("a", 1));
        // single-quoted pseudo-JSON
        assertThat(
                        WatsonxChatModelConnection.parseToolArguments(
                                MAPPER.readTree("\"{'a': 17, 'b': 25}\"")))
                .isEqualTo(Map.of("a", 17, "b", 25));
        // already an object node
        assertThat(WatsonxChatModelConnection.parseToolArguments(MAPPER.readTree("{\"a\": 1}")))
                .isEqualTo(Map.of("a", 1));
        // missing or empty -> empty map
        assertThat(WatsonxChatModelConnection.parseToolArguments(null)).isEmpty();
        assertThat(WatsonxChatModelConnection.parseToolArguments(MAPPER.readTree("\"\"")))
                .isEmpty();
        // garbage -> descriptive error carrying the raw value
        assertThatThrownBy(
                        () ->
                                WatsonxChatModelConnection.parseToolArguments(
                                        MAPPER.readTree("\"not json at all\"")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not json at all");
    }

    @Test
    @DisplayName("Chat response with tool calls parses arguments and preserves the original id")
    void testParseResponseWithToolCalls() throws Exception {
        JsonNode response =
                MAPPER.readTree(
                        "{\"choices\": [{\"index\": 0, \"message\": {\"role\": \"assistant\","
                                + " \"tool_calls\": [{\"id\": \"call_abc123\", \"type\":"
                                + " \"function\", \"function\": {\"name\": \"add\", \"arguments\":"
                                + " \"{\\\"a\\\": 1, \\\"b\\\": 2}\"}}]}, \"finish_reason\":"
                                + " \"tool_calls\"}]}");

        ChatMessage message = WatsonxChatModelConnection.parseResponse(response, null);

        assertThat(message.getToolCalls()).hasSize(1);
        Map<String, Object> toolCall = message.getToolCalls().get(0);
        assertThat(toolCall.get("id")).isEqualTo("call_abc123");
        assertThat(toolCall.get("original_id")).isEqualTo("call_abc123");
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) toolCall.get("function");
        assertThat(function.get("name")).isEqualTo("add");
        assertThat(function.get("arguments")).isEqualTo(Map.of("a", 1, "b", 2));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "ibm/granite-3-3-8b-instruct",
                "meta-llama/llama-3-3-70b-instruct",
                "mistralai/mistral-large",
                " "
            })
    @DisplayName("Capability is reported for any model, since the endpoint provides it")
    void supportsNativeStructuredOutputIsUnconditional(String model) {
        // Capability comes from the serving runtime the chat API requires rather than from the
        // model, so a model allowlist here would silently drop back to the prompt fallback for
        // anything the list had not caught up with. Null and blank are included because the
        // answer does not depend on the argument at all.
        assertThat(connection().supportsNativeStructuredOutput(model)).isTrue();
    }

    @Test
    @DisplayName("A POJO output schema is sent as a native json_schema response format")
    void buildPayloadWritesResponseFormatForPojoSchema() {
        ObjectNode payload = payloadFor(Report.class);

        JsonNode responseFormat = payload.path("response_format");
        assertThat(responseFormat.path("type").asText()).isEqualTo("json_schema");
        JsonNode jsonSchema = responseFormat.path("json_schema");
        assertThat(jsonSchema.path("name").asText()).isEqualTo("Report");
        assertThat(jsonSchema.path("strict").booleanValue()).isTrue();
        assertThat(fieldNames(jsonSchema.path("schema").path("properties")))
                .containsExactlyInAnyOrder("summary", "counts", "note", "total");
    }

    @Test
    @DisplayName("No output schema leaves the payload without a response format")
    void buildPayloadOmitsResponseFormatWithoutSchema() {
        ObjectNode payload = payloadFor(null);

        // A null is still a present response_format field in the serialized body, so the key has
        // to be absent rather than written as null.
        assertThat(payload.has("response_format")).isFalse();
    }

    @Test
    @DisplayName("A RowTypeInfo-shaped schema stays on the prompt fallback")
    void buildPayloadOmitsResponseFormatForRowTypeInfo() {
        // A RowTypeInfo schema arrives wrapped rather than as a bare POJO Class, so it must not
        // activate native structured output. The wrapper cannot be built here because RowTypeInfo
        // is not on this module's classpath; any non-Class schema object exercises the same gate.
        // Deriving a schema from it must degrade to the fallback instead of failing the request.
        Object nonClassSchema = "row<name STRING>";

        ObjectNode payload = payloadFor(nonClassSchema);

        assertThat(payload.has("response_format")).isFalse();
    }

    /** Minimal tool carrying only metadata; never invoked in these tests. */
    private static final class SchemaOnlyTool extends Tool {
        SchemaOnlyTool() {
            super(new ToolMetadata("add", "Add two numbers.", "{\"type\":\"object\"}"));
        }

        @Override
        public ToolType getToolType() {
            return ToolType.FUNCTION;
        }

        @Override
        public ToolResponse call(ToolParameters parameters) {
            throw new UnsupportedOperationException("not invoked in this test");
        }
    }

    /** A connection that records what the feasibility query answered on each payload it built. */
    private static WatsonxChatModelConnection recordingConnection(
            AtomicReference<Boolean> answered) {
        return new WatsonxChatModelConnection(
                descriptor("https://us-south.ml.cloud.ibm.com", "test-key", "test-project"),
                NOOP,
                NO_ENVIRONMENT) {
            @Override
            protected boolean canApplyNativeStructuredOutput(
                    Object outputSchema, List<Tool> tools, Map<String, Object> modelParams) {
                boolean answer =
                        super.canApplyNativeStructuredOutput(outputSchema, tools, modelParams);
                answered.set(answer);
                return answer;
            }
        };
    }

    @Test
    @DisplayName("The feasibility query answers exactly what the native branch decides")
    void feasibilityQueryAgreesWithTheNativeBranch() {
        // Comparing the answer against what the payload ends up carrying, rather than against a
        // literal, is what keeps the query and the branch from drifting in step. This connection
        // reports every model capable, so the schema form is the only thing that moves.
        AtomicReference<Boolean> answered = new AtomicReference<>();
        WatsonxChatModelConnection connection = recordingConnection(answered);

        for (Object schema : Arrays.asList(Report.class, "row<name STRING>", null)) {
            for (List<Tool> tools :
                    Arrays.asList(List.<Tool>of(), List.<Tool>of(new SchemaOnlyTool()), null)) {
                answered.set(null);

                ObjectNode payload =
                        connection.buildPayload(
                                List.of(new ChatMessage(MessageRole.USER, "Hello!")),
                                tools,
                                Map.of("model", MODEL),
                                schema);

                // A null here means the branch never consulted the query at all, which is the
                // drift this test exists to catch. The value assertion below would fail too, but
                // on a null comparison that does not say why.
                assertThat(answered.get()).as("query reached for schema %s", schema).isNotNull();
                assertThat(answered.get())
                        .as("schema %s, tools %s", schema, tools)
                        .isEqualTo(payload.has("response_format"));
            }
        }
    }

    @Test
    @DisplayName("Bound tools do not make a POJO schema infeasible here")
    void feasibilityQueryIgnoresBoundTools() {
        // watsonx's native branch imposes no empty-tools precondition, unlike Gemini's. Pinning the
        // answer keeps an override from acquiring one by being copied from a connection that does.
        assertThat(
                        connection()
                                .canApplyNativeStructuredOutput(
                                        Report.class,
                                        List.of(new SchemaOnlyTool()),
                                        Map.of("model", MODEL)))
                .isTrue();
    }

    @Test
    @DisplayName("A caller response format does not make a POJO schema infeasible")
    void feasibilityQueryIgnoresACallerResponseFormat() {
        // The conflict between a caller response_format and a derived schema is raised by the
        // branch rather than being reported as infeasible, so the query has to keep answering
        // true here. Reporting false instead would silently turn that error into a skipped branch.
        assertThat(
                        connection()
                                .canApplyNativeStructuredOutput(
                                        Report.class, List.of(), callerFormatInModelParams()))
                .isTrue();
        assertThat(
                        connection()
                                .canApplyNativeStructuredOutput(
                                        Report.class, List.of(), callerFormatInAdditionalKwargs()))
                .isTrue();
    }

    /** A connection reporting no model capable, so capability and feasibility can disagree. */
    private static WatsonxChatModelConnection incapableConnection() {
        return new WatsonxChatModelConnection(
                descriptor("https://us-south.ml.cloud.ibm.com", "test-key", "test-project"),
                NOOP,
                NO_ENVIRONMENT) {
            @Override
            protected boolean supportsNativeStructuredOutput(String effectiveModel) {
                return false;
            }
        };
    }

    @Test
    @DisplayName("Feasibility is answered without consulting the capability predicate")
    void feasibilityQueryExcludesModelCapability() {
        // WatsonxChatModelConnection reports every model capable, so no model name can separate
        // the two answers. A subclass that reports nothing capable can: the query must still
        // answer true, which fails the moment a capability conjunct is folded into the override.
        // That folding is invisible to feasibilityQueryAgreesWithTheNativeBranch, which moves
        // both sides at once. The payload stays unconstrained meanwhile, which is the branch's
        // own conjunct doing the work the query does not.
        WatsonxChatModelConnection connection = incapableConnection();

        assertThat(
                        connection.canApplyNativeStructuredOutput(
                                Report.class, List.of(), Map.of("model", MODEL)))
                .isTrue();

        ObjectNode payload =
                connection.buildPayload(
                        List.of(new ChatMessage(MessageRole.USER, "Hello!")),
                        List.of(),
                        Map.of("model", MODEL),
                        Report.class);

        assertThat(payload.has("response_format")).isFalse();
    }

    @Test
    @DisplayName("The feasibility query reads its tools and parameters without consuming them")
    void feasibilityQueryDoesNotConsumeItsInputs() {
        // The same tools and parameters go on to build the payload the answer was about, so a
        // query that took anything out of either would answer about one request and build another.
        // Both are immutable, so a consuming implementation raises rather than silently differing.
        List<Tool> tools = List.of(new SchemaOnlyTool());
        Map<String, Object> modelParams = Map.of("model", MODEL);

        connection().canApplyNativeStructuredOutput(Report.class, tools, modelParams);

        assertThat(tools).hasSize(1);
        assertThat(modelParams).isEqualTo(Map.of("model", MODEL));
    }

    @Test
    @DisplayName("The derived schema lists enum constants the way Jackson deserializes them")
    void derivedSchemaFollowsJacksonEnumValues() throws Exception {
        JsonNode properties = derivedSchema(Ticket.class).path("properties");

        // Every listed value is one the model may emit, so each has to deserialize into the enum.
        // Listed by constant name instead, the caller's mapper refuses every value the schema
        // allows.
        List<Status> statuses = new ArrayList<>();
        for (JsonNode value : properties.path("status").path("enum")) {
            statuses.add(MAPPER.treeToValue(value, Status.class));
        }
        assertThat(statuses).containsExactlyInAnyOrder(Status.values());

        List<Phase> phases = new ArrayList<>();
        for (JsonNode value : properties.path("phase").path("enum")) {
            phases.add(MAPPER.treeToValue(value, Phase.class));
        }
        assertThat(phases).containsExactlyInAnyOrder(Phase.values());
    }

    @Test
    @DisplayName("The derived schema gives map values their own schema")
    void derivedSchemaGivesMapValuesTheirSchema() {
        JsonNode counts = derivedSchema(Report.class).path("properties").path("counts");

        // A map without a value schema admits any value, so a response can satisfy the schema and
        // still fail to deserialize into the declared value type at the caller.
        assertThat(counts.path("additionalProperties").isObject()).isTrue();
        assertThat(counts.path("additionalProperties").path("type").asText()).isEqualTo("integer");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("callerSuppliedResponseFormats")
    @DisplayName("A caller response format alongside an output schema is rejected")
    void chatWithSchemaAndCallerResponseFormatRaises(
            String ignoredCaseName, Map<String, Object> modelParams) throws Exception {
        HttpServer server = startServer();
        server.createContext(
                "/ml/v1/text/chat", exchange -> sendJson(exchange, 200, CHAT_RESPONSE));

        try {
            WatsonxChatModelConnection connection =
                    new WatsonxChatModelConnection(
                            stubDescriptor(baseUrl(server), false, 0), NOOP, NO_ENVIRONMENT);

            // Both channels land in the same payload field as the derived schema, so letting
            // either side win silently sends a format the caller never asked for, or drops the
            // schema the agent depends on to parse the reply. The stub answers the request, so a
            // guard that stops rejecting the combination fails here rather than hanging on a call
            // to the real endpoint.
            assertThatThrownBy(
                            () ->
                                    connection.chat(
                                            List.of(new ChatMessage(MessageRole.USER, "Hello!")),
                                            List.of(),
                                            modelParams,
                                            Report.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("response_format");
        } finally {
            server.stop(0);
        }
    }

    private static Stream<Arguments> callerSuppliedResponseFormats() {
        return Stream.of(
                Arguments.of("model params", callerFormatInModelParams()),
                Arguments.of("additional_kwargs", callerFormatInAdditionalKwargs()));
    }

    private static Map<String, Object> callerFormatInModelParams() {
        return Map.of("model", MODEL, "response_format", CALLER_FORMAT);
    }

    private static Map<String, Object> callerFormatInAdditionalKwargs() {
        return Map.of(
                "model", MODEL, "additional_kwargs", Map.of("response_format", CALLER_FORMAT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("skippedNativePathsWithCallerResponseFormat")
    @DisplayName("A caller response format is forwarded when no schema is translated")
    void callerResponseFormatSurvivesWhenNoSchemaIsSent(
            String ignoredCaseName, Map<String, Object> modelParams, Object outputSchema) {
        // response_format is on none of the reserved sets, so callers set it directly today. The
        // rejection belongs to the branch that actually derives a schema: applied any wider it
        // turns every one of those existing calls into an error.
        ObjectNode payload = payloadFor(modelParams, outputSchema);

        assertThat(payload.path("response_format")).isEqualTo(MAPPER.valueToTree(CALLER_FORMAT));
    }

    private static Stream<Arguments> skippedNativePathsWithCallerResponseFormat() {
        return Stream.of(
                Arguments.of("no schema, model params", callerFormatInModelParams(), null),
                Arguments.of(
                        "no schema, additional_kwargs", callerFormatInAdditionalKwargs(), null),
                // A schema the branch cannot translate skips it for the other reason, so the
                // caller's value has to survive that arm too.
                Arguments.of(
                        "untranslatable schema, model params",
                        callerFormatInModelParams(),
                        "row<name STRING>"));
    }

    @Test
    @DisplayName("The serialized request body carries response_format at the document root")
    void serializedRequestBodyCarriesResponseFormatAtRoot() throws Exception {
        HttpServer server = startServer();
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        server.createContext(
                "/ml/v1/text/chat",
                exchange -> {
                    captured.set(MAPPER.readTree(exchange.getRequestBody()));
                    sendJson(exchange, 200, CHAT_RESPONSE);
                });

        try {
            WatsonxChatModelConnection connection =
                    new WatsonxChatModelConnection(
                            stubDescriptor(baseUrl(server), false, 0), NOOP, NO_ENVIRONMENT);

            connection.chat(
                    List.of(new ChatMessage(MessageRole.USER, "Hello!")),
                    List.of(),
                    Map.of("model", MODEL),
                    Report.class);

            // The scope id is injected after the payload is built, so only the wire body proves
            // that a schema handed to chat reaches the request at all, and that it sits at the
            // root beside the other request fields rather than nested inside one of them.
            JsonNode body = captured.get();
            assertThat(body).isNotNull();
            assertThat(body.has("response_format")).isTrue();
            assertThat(body.path("response_format").path("json_schema").path("name").asText())
                    .isEqualTo("Report");
        } finally {
            server.stop(0);
        }
    }
}
