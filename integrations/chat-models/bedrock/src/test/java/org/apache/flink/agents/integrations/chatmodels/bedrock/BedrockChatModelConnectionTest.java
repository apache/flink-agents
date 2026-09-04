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

package org.apache.flink.agents.integrations.chatmodels.bedrock;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.OutputFormatType;

import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Tests for {@link BedrockChatModelConnection}. */
class BedrockChatModelConnectionTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    private static ResourceDescriptor descriptor(String region, String model) {
        ResourceDescriptor.Builder b =
                ResourceDescriptor.Builder.newBuilder(BedrockChatModelConnection.class.getName());
        if (region != null) b.addInitialArgument("region", region);
        if (model != null) b.addInitialArgument("model", model);
        return b.build();
    }

    private static BedrockChatModelConnection connection() {
        return new BedrockChatModelConnection(
                descriptor("us-east-1", "us.anthropic.claude-sonnet-4-20250514-v1:0"), NOOP);
    }

    /** Minimal tool carrying only metadata; never invoked in these tests. */
    private static final class SchemaOnlyTool extends Tool {
        SchemaOnlyTool(String inputSchema) {
            super(new ToolMetadata("add", "Add two numbers.", inputSchema));
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

    private static ChatMessage toolMessage(String externalId, String content) {
        Map<String, Object> extraArgs = new HashMap<>();
        extraArgs.put("externalId", externalId);
        return new ChatMessage(MessageRole.TOOL, content, extraArgs);
    }

    @Test
    @DisplayName("Constructor creates client with default region")
    void testConstructorDefaultRegion() {
        BedrockChatModelConnection conn =
                new BedrockChatModelConnection(
                        descriptor(null, "us.anthropic.claude-sonnet-4-20250514-v1:0"), NOOP);
        assertNotNull(conn);
    }

    @Test
    @DisplayName("Constructor creates client with explicit region")
    void testConstructorExplicitRegion() {
        BedrockChatModelConnection conn =
                new BedrockChatModelConnection(
                        descriptor("us-west-2", "us.anthropic.claude-sonnet-4-20250514-v1:0"),
                        NOOP);
        assertNotNull(conn);
    }

    @Test
    @DisplayName("Extends BaseChatModelConnection")
    void testInheritance() {
        BedrockChatModelConnection conn =
                new BedrockChatModelConnection(descriptor("us-east-1", "test-model"), NOOP);
        assertThat(conn).isInstanceOf(BaseChatModelConnection.class);
    }

    @Test
    @DisplayName("Chat throws when no model specified")
    void testChatThrowsWithoutModel() {
        BedrockChatModelConnection conn =
                new BedrockChatModelConnection(descriptor("us-east-1", null), NOOP);
        List<ChatMessage> msgs = List.of(new ChatMessage(MessageRole.USER, "hello"));
        assertThatThrownBy(() -> conn.chat(msgs, null, Collections.emptyMap()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("chat accepts a POJO output schema instead of rejecting it as untranslatable")
    void testChatAcceptsPojoOutputSchema() {
        // A connection that does not translate schemas itself inherits a four-argument chat that
        // refuses every non-null schema outright, so the feature can be removed at the call
        // boundary while buildRequest still wires outputConfig correctly. Configuring no model
        // makes the overridden path fail while resolving the model, which reports that the schema
        // was accepted and a request was being built, without any call reaching the provider.
        BedrockChatModelConnection conn =
                new BedrockChatModelConnection(descriptor("us-east-1", null), NOOP);
        List<ChatMessage> msgs = List.of(new ChatMessage(MessageRole.USER, "hello"));
        assertThatThrownBy(() -> conn.chat(msgs, null, Collections.emptyMap(), Profile.class))
                .isNotInstanceOf(UnsupportedOperationException.class)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("stripMarkdownFences: normal text with braces is not modified")
    void testStripMarkdownFencesPreservesTextWithBraces() {
        assertThat(
                        BedrockChatModelConnection.stripMarkdownFences(
                                "Use the format {key: value} for config"))
                .isEqualTo("Use the format {key: value} for config");
    }

    @Test
    @DisplayName("stripMarkdownFences: clean JSON passes through")
    void testStripMarkdownFencesCleanJson() {
        assertThat(
                        BedrockChatModelConnection.stripMarkdownFences(
                                "{\"score\": 5, \"reasons\": []}"))
                .isEqualTo("{\"score\": 5, \"reasons\": []}");
    }

    @Test
    @DisplayName("stripMarkdownFences: strips ```json fences")
    void testStripMarkdownFencesJsonBlock() {
        assertThat(BedrockChatModelConnection.stripMarkdownFences("```json\n{\"score\": 5}\n```"))
                .isEqualTo("{\"score\": 5}");
    }

    @Test
    @DisplayName("stripMarkdownFences: strips plain ``` fences")
    void testStripMarkdownFencesPlainBlock() {
        assertThat(BedrockChatModelConnection.stripMarkdownFences("```\n{\"id\": \"P001\"}\n```"))
                .isEqualTo("{\"id\": \"P001\"}");
    }

    @Test
    @DisplayName("stripMarkdownFences: null returns null")
    void testStripMarkdownFencesNull() {
        assertThat(BedrockChatModelConnection.stripMarkdownFences(null)).isNull();
    }

    @Test
    @DisplayName("buildRequest: the effective model id lands in the request")
    void testBuildRequestResolvesModelId() {
        List<ChatMessage> messages = List.of(ChatMessage.user("hello"));

        ConverseRequest fromConnection = connection().buildRequest(messages, null, Map.of(), null);
        assertThat(fromConnection.modelId())
                .isEqualTo("us.anthropic.claude-sonnet-4-20250514-v1:0");

        ConverseRequest fromCall =
                connection().buildRequest(messages, null, Map.of("model", "per-call-model"), null);
        assertThat(fromCall.modelId()).isEqualTo("per-call-model");
    }

    @Test
    @DisplayName("buildRequest: tools land in toolConfig")
    void testBuildRequestPreservesToolConfig() {
        ConverseRequest request =
                connection()
                        .buildRequest(
                                List.of(ChatMessage.user("hello")),
                                List.of(new SchemaOnlyTool("{\"type\": \"object\"}")),
                                Map.of(),
                                null);

        assertThat(request.toolConfig()).isNotNull();
        assertThat(request.toolConfig().tools()).hasSize(1);
        assertThat(request.toolConfig().tools().get(0).toolSpec().name()).isEqualTo("add");
        assertThat(request.toolConfig().tools().get(0).toolSpec().description())
                .isEqualTo("Add two numbers.");
        assertThat(request.toolConfig().tools().get(0).toolSpec().inputSchema().json().asMap())
                .containsEntry("type", Document.fromString("object"));
    }

    @Test
    @DisplayName("buildRequest: system messages land in system, the rest in messages")
    void testBuildRequestPreservesSystemMessages() {
        ConverseRequest request =
                connection()
                        .buildRequest(
                                List.of(ChatMessage.system("be terse"), ChatMessage.user("hello")),
                                null,
                                Map.of(),
                                null);

        assertThat(request.system()).hasSize(1);
        assertThat(request.system().get(0).text()).isEqualTo("be terse");
        assertThat(request.messages()).hasSize(1);
        assertThat(request.messages().get(0).role()).isEqualTo(ConversationRole.USER);
        assertThat(request.messages().get(0).content().get(0).text()).isEqualTo("hello");
    }

    @Test
    @DisplayName("buildRequest: temperature and max_tokens land in inferenceConfig")
    void testBuildRequestPreservesInferenceConfig() {
        List<ChatMessage> messages = List.of(ChatMessage.user("hello"));

        ConverseRequest configured =
                connection()
                        .buildRequest(
                                messages, null, Map.of("temperature", 0.7, "max_tokens", 64), null);
        assertThat(configured.inferenceConfig()).isNotNull();
        assertThat(configured.inferenceConfig().temperature()).isEqualTo(0.7f);
        assertThat(configured.inferenceConfig().maxTokens()).isEqualTo(64);

        ConverseRequest bare = connection().buildRequest(messages, null, Map.of(), null);
        assertThat(bare.inferenceConfig()).isNull();
    }

    @Test
    @DisplayName("buildRequest: consecutive tool messages merge into one user message")
    void testBuildRequestMergesConsecutiveToolMessages() {
        ConverseRequest request =
                connection()
                        .buildRequest(
                                List.of(
                                        ChatMessage.user("hello"),
                                        toolMessage("call-1", "first result"),
                                        toolMessage("call-2", "second result")),
                                null,
                                Map.of(),
                                null);

        assertThat(request.messages()).hasSize(2);
        Message merged = request.messages().get(1);
        assertThat(merged.role()).isEqualTo(ConversationRole.USER);
        assertThat(merged.content()).hasSize(2);
        assertThat(merged.content())
                .extracting(block -> block.toolResult().toolUseId())
                .containsExactly("call-1", "call-2");
    }

    /** Documented on its AWS model card as supporting structured output. */
    private static final String CAPABLE_MODEL = "anthropic.claude-sonnet-4-5-20250929-v1:0";

    /** Documented on its AWS model card as not supporting structured output. */
    private static final String INCAPABLE_MODEL = "amazon.nova-micro-v1:0";

    private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();

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

    /**
     * Output schema fixture shaped to expose what the derived schema constrains: which fields a
     * response must carry, and how a map field is rendered.
     */
    public static class Reading {
        public int score;

        public Optional<String> note;

        public Map<String, Integer> counts;
    }

    /**
     * Every model id the connection reports capable.
     *
     * <p>The list is the whole allowlist, so an entry dropped or mistyped fails here rather than
     * narrowing capability silently. The four suffix shapes are part of what is under test: three
     * entries carry no version suffix at all, and {@code openai.gpt-oss-120b-1:0} carries {@code
     * -1:0} rather than {@code -v1:0}, so a rule that assumes one shape fails on the others.
     */
    private static Stream<String> capableModels() {
        return Stream.of(
                "anthropic.claude-sonnet-4-5-20250929-v1:0",
                "anthropic.claude-opus-4-5-20251101-v1:0",
                "anthropic.claude-haiku-4-5-20251001-v1:0",
                "mistral.mistral-large-3-675b-instruct",
                "openai.gpt-oss-120b-1:0",
                "openai.gpt-oss-20b-1:0",
                "qwen.qwen3-235b-a22b-2507-v1:0",
                "qwen.qwen3-32b-v1:0",
                "qwen.qwen3-coder-30b-a3b-v1:0",
                "qwen.qwen3-coder-480b-a35b-v1:0",
                "qwen.qwen3-coder-next",
                "qwen.qwen3-next-80b-a3b");
    }

    private static Map<String, Object> params(String model) {
        Map<String, Object> params = new HashMap<>();
        params.put("model", model);
        return params;
    }

    /**
     * Reads the schema the request carries.
     *
     * <p>Walks the typed accessors because {@code ConverseRequest.toString()} prints the structure
     * as redacted sensitive data.
     */
    private static JsonNode nativeSchema(ConverseRequest request) throws Exception {
        return SCHEMA_MAPPER.readTree(
                request.outputConfig().textFormat().structure().jsonSchema().schema());
    }

    @ParameterizedTest
    @MethodSource("capableModels")
    @DisplayName("every documented model reports capable")
    void testCapableModelsReportCapable(String model) {
        // connection() is configured with a model that is not on the list, so a predicate reading
        // the configured model rather than its argument disagrees with itself here.
        assertThat(connection().supportsNativeStructuredOutput(model)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"us.", "eu.", "apac.", "au.", "jp.", "global."})
    @DisplayName("a geographic inference-profile prefix resolves to the model it fronts")
    void testGeoPrefixResolvesToTheModelItFronts(String prefix) {
        // A cross-Region inference profile id is a model id behind a leading segment, and the model
        // behind it is the one whose capability the request gets.
        assertThat(connection().supportsNativeStructuredOutput(prefix + CAPABLE_MODEL)).isTrue();
    }

    @Test
    @DisplayName("the us-gov inference-profile prefix resolves to the model it fronts")
    void testHyphenatedPrefixResolvesToTheModelItFronts() {
        // us-gov. is a documented prefix that no other documented prefix resembles, so a rule
        // written as a fixed set of prefixes tends to omit it while a leading-segment strip covers
        // it without being told.
        assertThat(connection().supportsNativeStructuredOutput("us-gov.openai.gpt-oss-120b-1:0"))
                .isTrue();
    }

    @Test
    @DisplayName("a model documented as unsupported reports not capable")
    void testDocumentedUnsupportedModelReportsNotCapable() {
        // AWS documents this model as not supporting structured output, and it extends the prefix
        // shared by four capable entries. Any prefix match claims a capability the provider denies.
        assertThat(connection().supportsNativeStructuredOutput("qwen.qwen3-vl-235b-a22b"))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                INCAPABLE_MODEL,
                "anthropic.claude-sonnet-4-20250514-v1:0",
                "mistral.mistral-large-2402-v1:0",
                "us.anthropic.claude-sonnet-4-20250514-v1:0"
            })
    @DisplayName("a model with no documented answer reports not capable")
    void testUndocumentedModelsReportNotCapable(String model) {
        // An absent answer is not a positive one. The middle two each extend a capable entry
        // truncated at a version boundary; the last is the id this module's own example uses, so
        // its behavior is pinned here rather than discovered at the provider.
        assertThat(connection().supportsNativeStructuredOutput(model)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-sonnet-4-5-20250929-v1:0",
                "arn:aws:bedrock:us-east-1:123456789012:application-inference-profile/anthropic.claude-sonnet-4-5-20250929-v1:0"
            })
    @DisplayName("an ARN reports not capable even when it spells out a capable model")
    void testArnFormsReportNotCapable(String model) {
        // An ARN names a resource rather than a model, and an application inference profile's
        // trailing segment can spell an id it does not front. A substring match reports both of
        // these capable.
        assertThat(connection().supportsNativeStructuredOutput(model)).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    @DisplayName("a null or blank model reports not capable")
    void testNullOrBlankModelReportsNotCapable(String model) {
        // The guard is load-bearing rather than defensive: the allowlist is an immutable Set, whose
        // contains(null) throws instead of reporting absence.
        assertThat(connection().supportsNativeStructuredOutput(model)).isFalse();
    }

    @Test
    @DisplayName("the derived schema names properties the way Jackson deserializes them")
    void testDerivedSchemaFollowsJacksonPropertyNames() throws Exception {
        ConverseRequest request =
                connection()
                        .buildRequest(
                                List.of(ChatMessage.user("hello")),
                                null,
                                params(CAPABLE_MODEL),
                                Profile.class);

        // The response is read back into the same class, so a property that @JsonProperty renames
        // or @JsonIgnore drops has to be stated under the name the mapper reads. Derived without
        // Jackson's property model the schema instead names "name" and demands the ignored
        // "secret", constraining the model to a document the mapper then refuses.
        assertThat(nativeSchema(request).path("properties").fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("full_name", "age");
    }

    @Test
    @DisplayName("the derived schema declares its dialect, requires fields, and leaves maps bare")
    void testDerivedSchemaConstrainsTheResponse() throws Exception {
        ConverseRequest request =
                connection()
                        .buildRequest(
                                List.of(ChatMessage.user("hello")),
                                null,
                                params(CAPABLE_MODEL),
                                Reading.class);
        JsonNode schema = nativeSchema(request);

        // Left to itself the generator marks nothing required, so an empty document satisfies a
        // schema whose whole purpose is to constrain the response. An Optional field is the one a
        // caller declared omissible, so it stays out of the required set.
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(entry -> required.add(entry.asText()));
        assertThat(required).containsExactlyInAnyOrder("score", "counts");

        // A map derives as a bare object. Typing its values renders them under
        // additionalProperties, which Bedrock accepts only as false and rejects as a subschema, so
        // the value type is left off rather than putting the request outside the accepted subset.
        assertThat(schema.path("properties").path("counts").path("type").asText())
                .isEqualTo("object");
        assertThat(schema.findValues("additionalProperties")).isEmpty();

        // The dialect is stated rather than left on the generator's older default.
        assertThat(schema.path("$schema").asText())
                .isEqualTo("https://json-schema.org/draft/2020-12/schema");
    }

    @Test
    @DisplayName("tool definitions and an output schema ride the same request")
    void testToolConfigAndOutputConfigCoexist() {
        ConverseRequest request =
                connection()
                        .buildRequest(
                                List.of(ChatMessage.user("hello")),
                                List.of(new SchemaOnlyTool("{\"type\": \"object\"}")),
                                params(CAPABLE_MODEL),
                                Profile.class);

        // Converse carries tool definitions and an output schema together, so neither branch may
        // suppress the other.
        assertThat(request.toolConfig()).isNotNull();
        assertThat(request.toolConfig().tools()).hasSize(1);
        assertThat(request.outputConfig()).isNotNull();
        assertThat(request.outputConfig().textFormat().type())
                .isEqualTo(OutputFormatType.JSON_SCHEMA);
    }

    @Test
    @DisplayName("the native path applies for a POJO class schema on a capable model")
    void testNativeSchemaAppliedWhenGateHolds() throws Exception {
        ConverseRequest applied =
                connection()
                        .buildRequest(
                                List.of(ChatMessage.user("hello")),
                                null,
                                params(CAPABLE_MODEL),
                                Profile.class);

        assertThat(applied.outputConfig().textFormat().type())
                .isEqualTo(OutputFormatType.JSON_SCHEMA);
        // Bedrock takes the schema as serialized text rather than as a typed object, so what
        // arrives has to parse back into the schema document itself.
        assertThat(nativeSchema(applied).path("type").asText()).isEqualTo("object");
    }

    private static Stream<Arguments> gateFailures() {
        return Stream.of(
                Arguments.of(INCAPABLE_MODEL, Profile.class),
                Arguments.of(CAPABLE_MODEL, null),
                // A RowTypeInfo schema arrives wrapped rather than as a bare Class and has no
                // native translation here, so it degrades to the fallback rather than failing.
                // RowTypeInfo itself is not on this module's test classpath; any non-Class object
                // exercises the same gate.
                Arguments.of(CAPABLE_MODEL, "row<name STRING>"));
    }

    @ParameterizedTest
    @MethodSource("gateFailures")
    @DisplayName("the native path is skipped for an incapable model or a non-POJO schema")
    void testNativeSchemaSkippedWhenGateFails(String model, Object outputSchema) {
        assertThat(
                        connection()
                                .buildRequest(
                                        List.of(ChatMessage.user("hello")),
                                        null,
                                        params(model),
                                        outputSchema)
                                .outputConfig())
                .isNull();
    }
}
