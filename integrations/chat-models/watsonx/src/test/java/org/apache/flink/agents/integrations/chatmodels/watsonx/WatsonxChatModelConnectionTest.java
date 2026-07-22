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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WatsonxChatModelConnection}. These exercise the protocol-conversion logic
 * with no network access, so they run in CI without any API key.
 */
class WatsonxChatModelConnectionTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);
    private static final Function<String, String> NO_ENVIRONMENT = ignored -> null;
    private static final ObjectMapper MAPPER = new ObjectMapper();

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

        assertThatThrownBy(
                        () ->
                                new WatsonxChatModelConnection(descriptor, NOOP, NO_ENVIRONMENT))
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
        assertThatThrownBy(
                        () ->
                                new WatsonxChatModelConnection(credentials, NOOP, NO_ENVIRONMENT))
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
            assertThatThrownBy(
                            () -> new WatsonxChatModelConnection(invalid, NOOP, NO_ENVIRONMENT))
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
            assertThatThrownBy(
                            () ->
                                    new WatsonxChatModelConnection(invalid, NOOP, NO_ENVIRONMENT))
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
                WatsonxChatModelConnection.buildPayload(
                        List.of(new ChatMessage(MessageRole.USER, "Hello!")),
                        List.of(),
                        Map.of(
                                "model",
                                "ibm/granite-3-3-8b-instruct",
                                "temperature",
                                0.5,
                                "max_tokens",
                                256,
                                "extract_reasoning",
                                true,
                                "additional_kwargs",
                                Map.of("top_p", 0.9)));

        assertThat(payload.get("model_id").asText()).isEqualTo("ibm/granite-3-3-8b-instruct");
        assertThat(payload.get("temperature").asDouble()).isEqualTo(0.5);
        assertThat(payload.get("max_tokens").asInt()).isEqualTo(256);
        assertThat(payload.get("top_p").asDouble()).isEqualTo(0.9);
        assertThat(payload.get("messages")).hasSize(1);
        // framework control params must not leak into the request
        assertThat(payload.has("model")).isFalse();
        assertThat(payload.has("extract_reasoning")).isFalse();
        assertThat(payload.has("tools")).isFalse();

        assertThatThrownBy(
                        () ->
                                WatsonxChatModelConnection.buildPayload(
                                        List.of(new ChatMessage(MessageRole.USER, "Hello!")),
                                        List.of(),
                                        Map.of(
                                                "model",
                                                "ibm/granite-3-3-8b-instruct",
                                                "additional_kwargs",
                                                Map.of("temperature", 5.0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("additional_kwargs")
                .hasMessageContaining("temperature");
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
}
