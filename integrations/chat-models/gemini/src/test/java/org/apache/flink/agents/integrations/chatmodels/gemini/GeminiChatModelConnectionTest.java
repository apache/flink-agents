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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
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
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link GeminiChatModelConnection}. These exercise the protocol-conversion logic
 * with no network access, so they run in CI without any API key.
 */
class GeminiChatModelConnectionTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    /** A model Google documents native structured-output support for. */
    private static final String CAPABLE_MODEL = "gemini-2.5-flash";

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

    /**
     * buildConfig consumes the keys it recognizes via {@code arguments.remove(...)}, so the map
     * handed to it must be mutable.
     */
    private static Map<String, Object> params() {
        return new HashMap<>();
    }

    private static List<ChatMessage> userMessage() {
        return List.of(ChatMessage.user("hi"));
    }

    private static JsonNode nativeSchema(GenerateContentConfig config) {
        return (JsonNode) config.responseJsonSchema().orElseThrow();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * Output schema fixture shaped to expose the derivation settings: fields are declared out of
     * alphabetical order, {@code counts} is a map whose values carry a type, and {@code note} is
     * the only optional field.
     */
    public static class Report {
        public String summary;
        public Map<String, Integer> counts;
        public Optional<String> note;
        public int total;
    }

    /**
     * Output schema fixture shaped to expose Jackson's property model.
     *
     * <p>{@code name} is deserialized from {@code full_name} rather than from the Java field name,
     * and {@code secret} is not deserialized at all.
     */
    public static class Profile {
        @JsonProperty("full_name")
        public String name;

        @JsonIgnore public String secret;

        public int age;
    }

    /** Nested type reused by two described fields of {@link Addresses}. */
    public static class Address {
        public String street;
    }

    /**
     * Output schema fixture whose reused nested type is extracted into {@code $defs}, so each
     * described field emits a {@code $ref} that would otherwise carry a {@code description}
     * sibling.
     */
    public static class Addresses {
        @JsonPropertyDescription("home address")
        public Address home;

        @JsonPropertyDescription("work address")
        public Address work;
    }

    /**
     * Output schema fixture that reuses {@link Addresses}, so the forbidden pairing also appears
     * inside a {@code $defs} entry rather than only among the root's own properties.
     */
    public static class Building {
        @JsonPropertyDescription("primary occupant")
        public Addresses primary;

        @JsonPropertyDescription("secondary occupant")
        public Addresses secondary;
    }

    /**
     * Subtype union whose branches victools renders as a {@code $ref} each, inside an {@code anyOf}
     * array.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Dog.class, name = "dog"),
        @JsonSubTypes.Type(value = Cat.class, name = "cat")
    })
    public abstract static class Animal {
        public String name;
    }

    public static class Dog extends Animal {
        public int barks;
    }

    public static class Cat extends Animal {
        public int lives;
    }

    /**
     * Output schema fixture whose two described fields share a subtype union, so every {@code $ref}
     * carrying a forbidden sibling sits inside an {@code anyOf} array rather than directly under a
     * {@code properties} map.
     */
    public static class Owner {
        @JsonPropertyDescription("the pet")
        public Animal pet;

        @JsonPropertyDescription("the backup pet")
        public Animal backup;
    }

    /**
     * Output schema fixture declaring a property literally named {@code $ref}, which puts a member
     * of that name into the enclosing {@code properties} map without making that map a reference.
     */
    public static class RefNamedProperty {
        @JsonProperty("$ref")
        public String reference;

        public String other;
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
    @DisplayName(
            "convertFunctionCall synthesizes a unique id when the API omits functionCall.id, so"
                    + " parallel id-less calls cannot collide")
    void testConvertFunctionCallWithoutIdSynthesizesUniqueIds() {
        // The Gemini Developer API frequently returns functionCall parts with no id.
        FunctionCall first = FunctionCall.builder().name("get_weather").args(Map.of()).build();
        FunctionCall second = FunctionCall.builder().name("get_time").args(Map.of()).build();

        GeminiChatModelConnection conn = connection();
        Map<String, Object> firstCall = conn.convertFunctionCall(first, null);
        Map<String, Object> secondCall = conn.convertFunctionCall(second, null);

        assertThat(firstCall.get("id")).isNotNull();
        assertThat(firstCall.get("original_id")).isEqualTo(firstCall.get("id"));
        assertThat(firstCall).containsEntry("synthetic_id", Boolean.TRUE);
        // ToolCallAction keys success/responses/error on `id`; distinct ids are what prevent two
        // parallel id-less calls from overwriting each other.
        assertThat(firstCall.get("id")).isNotEqualTo(secondCall.get("id"));
    }

    @Test
    @DisplayName("A synthetic id is never echoed back to the Gemini API on replay")
    void testSyntheticIdNotEchoedToGemini() {
        FunctionCall fc = FunctionCall.builder().name("get_weather").args(Map.of()).build();

        GeminiChatModelConnection conn = connection();
        Map<String, Object> toolCall = conn.convertFunctionCall(fc, null);
        Part part = conn.convertToolCallToPart(toolCall);

        FunctionCall replayed = part.functionCall().orElseThrow();
        assertThat(replayed.id()).isEmpty();
        assertThat(replayed.name()).hasValue("get_weather");
    }

    @Test
    @DisplayName(
            "Second turn resolves the function name via the synthetic id (full id-less round"
                    + " trip)")
    void testSyntheticIdResolvesFunctionNameOnSecondTurn() {
        FunctionCall fc = FunctionCall.builder().name("get_weather").args(Map.of()).build();

        GeminiChatModelConnection conn = connection();
        Map<String, Object> toolCall = conn.convertFunctionCall(fc, null);
        String syntheticId = (String) toolCall.get("original_id");

        // Assistant turn carrying the id-less tool call, exactly as convertResponse builds it.
        ChatMessage assistant = ChatMessage.assistant("");
        assistant.setToolCalls(List.of(toolCall));

        // Runtime contract: ToolCallAction copies `original_id` into the TOOL message's
        // `externalId`. Before the fix, no id existed, externalId was never set, and this
        // second-turn conversion threw "Tool message must carry the function name".
        ChatMessage tool = ChatMessage.tool("sunny, 22C");
        tool.getExtraArgs().put("externalId", syntheticId);

        Map<String, String> idToName =
                GeminiChatModelConnection.buildToolCallIdToNameMap(List.of(assistant, tool));
        Content content = conn.convertToContent(tool, idToName);

        Part part = content.parts().orElseThrow().get(0);
        assertThat(part.functionResponse()).isPresent();
        assertThat(part.functionResponse().orElseThrow().name()).hasValue("get_weather");
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

    @Test
    @DisplayName("buildConfig lifts SYSTEM messages into the config's systemInstruction")
    void buildConfigAppliesSystemInstruction() {
        List<ChatMessage> messages =
                List.of(ChatMessage.system("be terse"), ChatMessage.user("hi"));

        GenerateContentConfig config =
                connection().buildConfig(messages, null, params(), CAPABLE_MODEL, null);

        Content instruction = config.systemInstruction().orElseThrow();
        // Exactly one part: the USER turn must not be lifted into the system instruction.
        List<Part> parts = instruction.parts().orElseThrow();
        assertThat(parts).hasSize(1);
        assertThat(parts.get(0).text()).hasValue("be terse");
    }

    @Test
    @DisplayName("buildConfig routes additional_kwargs through applyAdditionalKwargs")
    void buildConfigForwardsAdditionalKwargs() {
        Map<String, Object> arguments = params();
        arguments.put("additional_kwargs", Map.of("top_k", 40, "top_p", 0.9));

        GenerateContentConfig config =
                connection().buildConfig(userMessage(), null, arguments, CAPABLE_MODEL, null);

        assertThat(config.topK()).hasValue(40f);
        assertThat(config.topP()).hasValue(0.9f);
    }

    @Test
    @DisplayName("buildConfig sets temperature and max_output_tokens from the arguments map")
    void buildConfigSetsTemperatureAndMaxOutputTokens() {
        Map<String, Object> arguments = params();
        arguments.put("temperature", 0.25);
        arguments.put("max_output_tokens", 512);

        GenerateContentConfig config =
                connection().buildConfig(userMessage(), null, arguments, CAPABLE_MODEL, null);

        assertThat(config.temperature()).hasValue(0.25f);
        assertThat(config.maxOutputTokens()).hasValue(512);
    }

    @Test
    @DisplayName("buildConfig wires declared tools into the config")
    void buildConfigSetsToolsWhenPresent() {
        GenerateContentConfig config =
                connection()
                        .buildConfig(
                                userMessage(),
                                List.of(new SchemaOnlyTool()),
                                params(),
                                CAPABLE_MODEL,
                                null);

        List<FunctionDeclaration> declarations =
                config.tools().orElseThrow().get(0).functionDeclarations().orElseThrow();
        assertThat(declarations).hasSize(1);
        assertThat(declarations.get(0).name()).hasValue("add");
        assertThat(declarations.get(0).description()).hasValue("Add two numbers.");
        assertThat(declarations.get(0).parametersJsonSchema()).hasValue(Map.of("type", "object"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "gemini-3.1-pro-preview",
                "gemini-3.8-flash",
                "gemini-3.5-flash-lite",
                "gemini-2.5-pro",
                "gemini-2.5-flash",
                "gemini-robotics-er-1.6-preview"
            })
    @DisplayName("Every live Gemini text model reports native structured-output support")
    void supportsNativeStructuredOutputForTextModels(String model) {
        assertThat(connection().supportsNativeStructuredOutput(model)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "gemini-3.1-flash-image",
                "gemini-2.5-flash-image",
                "gemini-2.5-flash-preview-tts",
                "gemini-2.5-flash-native-audio-preview-12-2025",
                "gemini-3.1-flash-live-preview",
                "gemini-3.5-transcribe",
                "gemini-embedding-001",
                "gemini-omni-flash"
            })
    @DisplayName("A non-text output modality is rejected even though it carries the family prefix")
    void supportsNativeStructuredOutputRejectsNonTextModalities(String model) {
        // gemini-2.5-flash-image is the case the marker exists for: its published capability row
        // claims support, and the service answers 400 "JSON mode is not enabled for this model".
        assertThat(connection().supportsNativeStructuredOutput(model)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "gemini-"})
    @DisplayName("A null, blank or bare-prefix model reports not-capable")
    void supportsNativeStructuredOutputRejectsNullBlankAndBarePrefix(String model) {
        assertThat(connection().supportsNativeStructuredOutput(model)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "models/gemini-2.5-flash",
                "gemma-4-31b-it",
                "tunedModels/my-tune",
                "gemini",
                "imagen-4.0-generate-001"
            })
    @DisplayName("A name outside the family reports not-capable and keeps the prompt fallback")
    void supportsNativeStructuredOutputRejectsOutsideFamily(String model) {
        assertThat(connection().supportsNativeStructuredOutput(model)).isFalse();
    }

    @Test
    @DisplayName("A POJO schema is sent as responseJsonSchema alongside a JSON response mime type")
    void nativeSchemaAppliedForPojo() {
        GenerateContentConfig config =
                connection()
                        .buildConfig(userMessage(), null, params(), CAPABLE_MODEL, Report.class);

        assertThat(config.responseMimeType()).hasValue("application/json");
        JsonNode schema = nativeSchema(config);
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(fieldNames(schema.path("properties")))
                .containsExactly("summary", "counts", "note", "total");
    }

    @Test
    @DisplayName("A RowTypeInfo-shaped schema is skipped rather than rejected")
    void nativeSchemaSkippedForRowTypeInfo() {
        // A RowTypeInfo schema arrives wrapped in OutputSchema rather than as a bare POJO Class,
        // so it must not activate native structured output. OutputSchema cannot be instantiated
        // here because RowTypeInfo is not on this module's classpath; any non-Class schema object
        // exercises the same gate.
        Object nonClassSchema = "row<name STRING>";

        GenerateContentConfig config =
                connection()
                        .buildConfig(userMessage(), null, params(), CAPABLE_MODEL, nonClassSchema);

        assertThat(config.responseJsonSchema()).isEmpty();
        assertThat(config.responseMimeType()).isEmpty();
    }

    @Test
    @DisplayName("A request carrying tools keeps the tools and drops the schema")
    void nativeSchemaSkippedWhenToolsPresent() {
        // Outside a documented preview, Gemini answers a request combining function declarations
        // with a JSON response mime type with 400 INVALID_ARGUMENT, so the schema degrades to the
        // prompt fallback rather than failing the whole request.
        GenerateContentConfig config =
                connection()
                        .buildConfig(
                                userMessage(),
                                List.of(new SchemaOnlyTool()),
                                params(),
                                CAPABLE_MODEL,
                                Report.class);

        assertThat(config.tools()).isPresent();
        assertThat(config.responseJsonSchema()).isEmpty();
        assertThat(config.responseMimeType()).isEmpty();
    }

    @Test
    @DisplayName("A model without documented support is never sent a schema")
    void nativeSchemaSkippedForIncapableModel() {
        GenerateContentConfig config =
                connection()
                        .buildConfig(
                                userMessage(),
                                null,
                                params(),
                                "gemini-2.5-flash-image",
                                Report.class);

        assertThat(config.responseJsonSchema()).isEmpty();
        assertThat(config.responseMimeType()).isEmpty();
    }

    @Test
    @DisplayName("No output schema leaves the response format unconstrained")
    void nullSchemaLeavesRequestUnconstrained() {
        GenerateContentConfig config =
                connection().buildConfig(userMessage(), null, params(), CAPABLE_MODEL, null);

        assertThat(config.responseJsonSchema()).isEmpty();
        assertThat(config.responseMimeType()).isEmpty();
    }

    @Test
    @DisplayName("The derived schema names properties the way Jackson deserializes them")
    void derivedSchemaHonorsJacksonAnnotations() {
        // The response is read back with an ObjectMapper, which accepts the renamed property and
        // rejects the Java field name, and which discards an ignored property the schema would
        // otherwise force the model to fabricate.
        GenerateContentConfig config =
                connection()
                        .buildConfig(userMessage(), null, params(), CAPABLE_MODEL, Profile.class);

        assertThat(fieldNames(nativeSchema(config).path("properties")))
                .containsExactly("full_name", "age");
    }

    @Test
    @DisplayName("The derived schema closes objects without unsetting a map's value schema")
    void derivedSchemaClosesObjects() {
        GenerateContentConfig config =
                connection()
                        .buildConfig(userMessage(), null, params(), CAPABLE_MODEL, Report.class);
        JsonNode schema = nativeSchema(config);

        // No Gemini document states that a schema without the keyword is closed, so an undeclared
        // key the ObjectMapper then rejects is admissible unless the schema says otherwise.
        assertThat(schema.path("additionalProperties").isBoolean()).isTrue();
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        // The closure applies to the enclosing object, never to a map's declared value type.
        assertThat(
                        schema.path("properties")
                                .path("counts")
                                .path("additionalProperties")
                                .path("type")
                                .asText())
                .isEqualTo("integer");
    }

    @Test
    @DisplayName("The derived schema requires every field the caller did not declare omissible")
    void derivedSchemaMarksNonOptionalFieldsRequired() {
        // Gemini treats a field the schema does not list as required as one the model may skip,
        // so leaving `required` unset would let a response omit fields at will.
        GenerateContentConfig config =
                connection()
                        .buildConfig(userMessage(), null, params(), CAPABLE_MODEL, Report.class);

        List<String> required = new ArrayList<>();
        nativeSchema(config).path("required").forEach(entry -> required.add(entry.asText()));
        assertThat(required).containsExactlyInAnyOrder("summary", "counts", "total");
    }

    @Test
    @DisplayName("A $ref carries no sibling that Gemini forbids beside it")
    void derivedSchemaStripsRefSiblings() {
        // Gemini states that a sub-schema setting $ref may set no other property except those
        // starting with $. A described field whose type is reused is extracted into $defs and
        // emits exactly that pairing.
        GenerateContentConfig config =
                connection()
                        .buildConfig(userMessage(), null, params(), CAPABLE_MODEL, Addresses.class);
        JsonNode properties = nativeSchema(config).path("properties");

        assertThat(properties.path("home").path("$ref").isTextual()).isTrue();
        assertThat(fieldNames(properties.path("home"))).containsExactly("$ref");
        assertThat(fieldNames(properties.path("work"))).containsExactly("$ref");
    }

    @Test
    @DisplayName("A $ref nested inside a $defs entry is stripped too")
    void derivedSchemaStripsRefSiblingsInsideDefs() {
        // Reusing a type that itself reuses one puts the forbidden pairing four levels down, under
        // $defs rather than under the root's properties, so a walk that only visited the root's
        // own properties would leave it in place.
        GenerateContentConfig config =
                connection()
                        .buildConfig(userMessage(), null, params(), CAPABLE_MODEL, Building.class);
        JsonNode nested = nativeSchema(config).path("$defs").path("Addresses").path("properties");

        assertThat(nested.path("home").path("$ref").isTextual()).isTrue();
        assertThat(fieldNames(nested.path("home"))).containsExactly("$ref");
        assertThat(fieldNames(nested.path("work"))).containsExactly("$ref");
    }

    @Test
    @DisplayName("A $ref inside an anyOf array is stripped, so the walk descends through arrays")
    void derivedSchemaStripsRefSiblingsInsideAnyOfBranches() {
        // A subtype union renders as an anyOf array of $refs, and a description on the declaring
        // field is copied onto every branch. These are the only forbidden pairings the generator
        // places inside a JSON array rather than inside an object, so a walk that visited object
        // members only would leave all four in place.
        GenerateContentConfig config =
                connection().buildConfig(userMessage(), null, params(), CAPABLE_MODEL, Owner.class);
        JsonNode properties = nativeSchema(config).path("properties");

        for (String field : List.of("pet", "backup")) {
            JsonNode branches = properties.path(field).path("anyOf");
            assertThat(branches).hasSize(2);
            branches.forEach(
                    branch -> {
                        assertThat(branch.path("$ref").isTextual()).isTrue();
                        assertThat(fieldNames(branch)).containsExactly("$ref");
                    });
        }
    }

    @Test
    @DisplayName("A property named $ref does not make its enclosing map look like a reference")
    void derivedSchemaKeepsSiblingsOfAPropertyNamedRef() {
        // The properties map of this class carries a member named $ref whose value is that
        // property's own schema, an object. Treating the map as a reference would delete every
        // other property from it while required still listed them, leaving a document that
        // additionalProperties:false makes unsatisfiable.
        GenerateContentConfig config =
                connection()
                        .buildConfig(
                                userMessage(),
                                null,
                                params(),
                                CAPABLE_MODEL,
                                RefNamedProperty.class);
        JsonNode schema = nativeSchema(config);

        assertThat(fieldNames(schema.path("properties"))).containsExactly("$ref", "other");
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(entry -> required.add(entry.asText()));
        assertThat(required).containsExactlyInAnyOrder("$ref", "other");
    }

    @Test
    @DisplayName("The schema-less chat overload delegates to the schema-carrying one")
    void chatWithoutSchemaDelegatesToTheSchemaCarryingOverload() {
        // The three-argument overload holds no body of its own; everything, including the model
        // resolution that raises this error, lives in the four-argument one. A three-argument call
        // that stopped delegating would never reach it.
        GeminiChatModelConnection conn =
                new GeminiChatModelConnection(descriptor("test-key", null, null), NOOP);

        assertThatThrownBy(() -> conn.chat(List.of(ChatMessage.user("hi")), null, params()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model name must be provided");
    }
}
