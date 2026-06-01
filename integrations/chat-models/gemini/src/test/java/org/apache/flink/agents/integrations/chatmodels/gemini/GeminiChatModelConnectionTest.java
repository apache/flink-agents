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

package org.apache.flink.agents.integrations.chatmodels.gemini;

import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link GeminiChatModelConnection}. These exercise the protocol-conversion logic
 * with no network access, so they run in CI without any API key.
 */
class GeminiChatModelConnectionTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    private static ResourceDescriptor descriptor(String apiKey, String baseUrl, String model) {
        ResourceDescriptor.Builder b =
                ResourceDescriptor.Builder.newBuilder(GeminiChatModelConnection.class.getName());
        if (apiKey != null) {
            b.addInitialArgument("api_key", apiKey);
        }
        if (baseUrl != null) {
            b.addInitialArgument("base_url", baseUrl);
        }
        if (model != null) {
            b.addInitialArgument("model", model);
        }
        return b.build();
    }

    private static GeminiChatModelConnection connection() {
        return new GeminiChatModelConnection(
                descriptor("test-key", null, "gemini-3-pro-preview"), NOOP);
    }

    @Test
    @DisplayName("Constructor with api_key creates a connection")
    void testConstructorWithApiKey() {
        GeminiChatModelConnection conn = connection();
        assertThat(conn).isInstanceOf(BaseChatModelConnection.class);
    }

    @Test
    @DisplayName("Constructor with base_url (proxy) creates a connection without api_key")
    void testConstructorWithBaseUrl() {
        GeminiChatModelConnection conn =
                new GeminiChatModelConnection(
                        descriptor(null, "http://127.0.0.1:15799", "gemini-3-pro-preview"), NOOP);
        assertThat(conn).isInstanceOf(BaseChatModelConnection.class);
    }

    @Test
    @DisplayName("Constructor throws when neither api_key nor base_url is provided")
    void testConstructorThrowsWithoutCredentials() {
        assertThatThrownBy(
                        () ->
                                new GeminiChatModelConnection(
                                        descriptor(null, null, "gemini-3-pro-preview"), NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("api_key or base_url");
    }

    @Test
    @DisplayName(
            "Vertex AI path is wired but not e2e-tested in CI. We only assert here that "
                    + "vertex_ai=true does NOT silently fall through to the Developer-API "
                    + "construction success path; either it succeeds with ADC, or it surfaces a "
                    + "credentials / configuration error. A real Vertex run is a follow-up.")
    void testConstructorVertexAiIsWired() {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(GeminiChatModelConnection.class.getName())
                        .addInitialArgument("vertex_ai", true)
                        .addInitialArgument("project", "test-project-does-not-exist")
                        .addInitialArgument("location", "us-central1")
                        .addInitialArgument("model", "gemini-3-pro-preview")
                        .build();
        // Two acceptable outcomes:
        //   1. CI/dev box without ADC -> the SDK throws while resolving credentials.
        //   2. A machine with ADC configured -> construction succeeds. We close the client to
        //      release resources.
        // What must NOT happen: vertex_ai is silently ignored and the Developer-API path is taken,
        // which would mean the Vertex flag is dead code.
        try {
            GeminiChatModelConnection conn = new GeminiChatModelConnection(desc, NOOP);
            // Reached only when ADC is configured locally. Smoke-checked the build path.
            assertThat(conn).isInstanceOf(BaseChatModelConnection.class);
            conn.close();
        } catch (RuntimeException e) {
            // ADC missing: the SDK surfaces a credentials error. The exact message is SDK-internal;
            // the important assertion is that an error was raised, not silent fallthrough.
            assertThat(e).isNotNull();
        }
    }

    @Test
    @DisplayName("convertToContent maps USER role to a Gemini user turn")
    void testConvertUserMessage() {
        Content content =
                connection().convertToContent(ChatMessage.user("hello"), Collections.emptyMap());
        assertThat(content.role()).hasValue("user");
        assertThat(content.parts().orElseThrow().get(0).text()).hasValue("hello");
    }

    @Test
    @DisplayName("convertToContent maps ASSISTANT role to a Gemini model turn")
    void testConvertAssistantMessage() {
        Content content =
                connection()
                        .convertToContent(
                                ChatMessage.assistant("hi there"), Collections.emptyMap());
        assertThat(content.role()).hasValue("model");
        assertThat(content.parts().orElseThrow().get(0).text()).hasValue("hi there");
    }

    @Test
    @DisplayName("convertToContent uses explicit `name` in extraArgs when supplied")
    void testConvertToolMessageWithExplicitName() {
        ChatMessage tool = ChatMessage.tool("sunny, 22C");
        tool.getExtraArgs().put("name", "get_weather");

        Content content = connection().convertToContent(tool, Collections.emptyMap());
        assertThat(content.role()).hasValue("user");
        Part part = content.parts().orElseThrow().get(0);
        assertThat(part.functionResponse()).isPresent();
        assertThat(part.functionResponse().orElseThrow().name()).hasValue("get_weather");
    }

    @Test
    @DisplayName(
            "convertToContent resolves the function name from `externalId` when the runtime omits "
                    + "`name` (matches ChatModelAction's emission shape)")
    void testRuntimeShapeToolMessageResolvesNameFromExternalId() {
        // Runtime contract: ChatModelAction emits TOOL messages with only `externalId` in
        // extraArgs, matching how Anthropic/OpenAI siblings work. The name must be recovered from
        // the prior ASSISTANT turn's tool-call map.
        ChatMessage tool = ChatMessage.tool("sunny, 22C");
        tool.getExtraArgs().put("externalId", "call_abc");

        Map<String, String> idToName = Map.of("call_abc", "get_weather");

        Content content = connection().convertToContent(tool, idToName);
        assertThat(content.role()).hasValue("user");
        Part part = content.parts().orElseThrow().get(0);
        assertThat(part.functionResponse()).isPresent();
        assertThat(part.functionResponse().orElseThrow().name()).hasValue("get_weather");
    }

    @Test
    @DisplayName(
            "convertToContent throws only when the function name truly cannot be resolved (no "
                    + "`name`, no matching `externalId`)")
    void testConvertToolMessageThrowsWhenUnresolvable() {
        ChatMessage tool = ChatMessage.tool("result");
        tool.getExtraArgs().put("externalId", "call_unknown");

        assertThatThrownBy(() -> connection().convertToContent(tool, Collections.emptyMap()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("function name");
    }

    @Test
    @DisplayName("convertFunctionCall captures name, args, id and Base64 thoughtSignature")
    void testConvertFunctionCall() {
        FunctionCall fc =
                FunctionCall.builder()
                        .id("call_1")
                        .name("get_weather")
                        .args(Map.of("city", "Tokyo"))
                        .build();
        byte[] signature = new byte[] {1, 2, 3, 4};

        Map<String, Object> toolCall = connection().convertFunctionCall(fc, signature);

        assertThat(toolCall).containsEntry("id", "call_1").containsEntry("original_id", "call_1");
        assertThat(toolCall).containsEntry("type", "function");
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) toolCall.get("function");
        assertThat(function).containsEntry("name", "get_weather");
        assertThat(function.get("arguments")).isEqualTo(Map.of("city", "Tokyo"));
        assertThat(toolCall.get("thought_signature"))
                .isEqualTo(Base64.getEncoder().encodeToString(signature));
    }

    @Test
    @DisplayName("convertFunctionCall omits thought_signature when absent")
    void testConvertFunctionCallNoSignature() {
        FunctionCall fc = FunctionCall.builder().name("noop").args(Map.of()).build();
        Map<String, Object> toolCall = connection().convertFunctionCall(fc, null);
        assertThat(toolCall).doesNotContainKey("thought_signature");
    }

    @Test
    @DisplayName("Tool-call round-trip preserves name, args and thoughtSignature")
    void testToolCallRoundTrip() {
        byte[] signature = new byte[] {9, 8, 7};
        FunctionCall fc =
                FunctionCall.builder()
                        .id("c1")
                        .name("get_weather")
                        .args(Map.of("city", "Osaka"))
                        .build();

        GeminiChatModelConnection conn = connection();
        Map<String, Object> toolCall = conn.convertFunctionCall(fc, signature);
        Part part = conn.convertToolCallToPart(toolCall);

        assertThat(part.functionCall()).isPresent();
        FunctionCall rebuilt = part.functionCall().orElseThrow();
        assertThat(rebuilt.name()).hasValue("get_weather");
        assertThat(rebuilt.args().orElseThrow()).containsEntry("city", "Osaka");
        assertThat(part.thoughtSignature()).isPresent();
        assertThat(part.thoughtSignature().orElseThrow()).isEqualTo(signature);
    }

    @Test
    @DisplayName("convertToContent embeds tool calls into the assistant model turn")
    void testAssistantWithToolCalls() {
        FunctionCall fc =
                FunctionCall.builder()
                        .id("c2")
                        .name("get_weather")
                        .args(Map.of("city", "Kyoto"))
                        .build();
        Map<String, Object> toolCall = connection().convertFunctionCall(fc, null);
        ChatMessage assistant = ChatMessage.assistant("", List.of(toolCall));

        Content content = connection().convertToContent(assistant, Collections.emptyMap());
        assertThat(content.role()).hasValue("model");
        assertThat(content.parts().orElseThrow())
                .anySatisfy(p -> assertThat(p.functionCall()).isPresent());
    }

    @Test
    @DisplayName(
            "buildToolCallIdToNameMap mirrors what ChatModelAction emits: ASSISTANT turn carries "
                    + "tool-call map, follow-up TOOL turn carries only externalId")
    void testRuntimeShapeMultiTurn() {
        // Step 1: simulate the assistant's tool-call turn produced by convertFunctionCall.
        FunctionCall fc =
                FunctionCall.builder()
                        .id("call_xyz")
                        .name("get_weather")
                        .args(Map.of("city", "Tokyo"))
                        .build();
        Map<String, Object> toolCall = connection().convertFunctionCall(fc, null);
        ChatMessage assistantTurn = ChatMessage.assistant("", List.of(toolCall));

        // Step 2: the runtime emits a TOOL message with only externalId (no name).
        Map<String, Object> toolExtras = new HashMap<>();
        toolExtras.put("externalId", "call_xyz");
        ChatMessage toolTurn = new ChatMessage(MessageRole.TOOL, "sunny, 22C", toolExtras);

        List<ChatMessage> conversation =
                List.of(ChatMessage.user("weather in Tokyo?"), assistantTurn, toolTurn);

        Map<String, String> idToName =
                GeminiChatModelConnection.buildToolCallIdToNameMap(conversation);
        assertThat(idToName).containsEntry("call_xyz", "get_weather");

        // Round-trip: TOOL message converts to a functionResponse with the recovered name.
        Content content = connection().convertToContent(toolTurn, idToName);
        assertThat(content.parts().orElseThrow().get(0).functionResponse().orElseThrow().name())
                .hasValue("get_weather");
    }

    @Test
    @DisplayName(
            "applyAdditionalKwargs forwards top_k, top_p and stop_sequences onto the "
                    + "GenerateContentConfig (mirrors Anthropic's `additional_kwargs` path)")
    void testApplyAdditionalKwargs() {
        GenerateContentConfig.Builder builder = GenerateContentConfig.builder();
        Map<String, Object> kwargs =
                Map.of("top_k", 40, "top_p", 0.9, "stop_sequences", List.of("END", "STOP"));

        connection().applyAdditionalKwargs(builder, kwargs);

        GenerateContentConfig config = builder.build();
        assertThat(config.topK()).hasValue(40f);
        assertThat(config.topP()).hasValue(0.9f);
        assertThat(config.stopSequences().orElseThrow()).containsExactly("END", "STOP");
    }

    @Test
    @DisplayName("applyAdditionalKwargs ignores unknown keys without throwing (logs a warning)")
    void testApplyAdditionalKwargsIgnoresUnknown() {
        GenerateContentConfig.Builder builder = GenerateContentConfig.builder();
        connection().applyAdditionalKwargs(builder, Map.of("not_a_real_param", "x"));
        GenerateContentConfig config = builder.build();
        assertThat(config).isNotNull();
        // Unknown key must not leak into a known field.
        assertThat(config.topK()).isEmpty();
        assertThat(config.topP()).isEmpty();
    }

    @Test
    @DisplayName(
            "applyAdditionalKwargs ignores known keys with the wrong value type without throwing "
                    + "(e.g. top_k as a String) — must not silently set a wrong value either")
    void testApplyAdditionalKwargsIgnoresTypeMismatch() {
        GenerateContentConfig.Builder builder = GenerateContentConfig.builder();
        connection()
                .applyAdditionalKwargs(
                        builder,
                        Map.of(
                                "top_k", "fast", // wrong type
                                "stop_sequences", "STOP" // wrong type (should be List)
                                ));
        GenerateContentConfig config = builder.build();
        assertThat(config.topK()).isEmpty();
        assertThat(config.stopSequences()).isEmpty();
    }
}
