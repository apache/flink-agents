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

package org.apache.flink.agents.integrations.chatmodels.anthropic;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

/**
 * Unit tests for {@link AnthropicChatModelConnection}'s request construction, its native
 * structured-output capability check, and the response conversion that consumes the request. No
 * test issues a request: they inspect what {@code buildRequest} produced, call the capability
 * predicate directly, and feed {@code convertResponse} a hand-built response, so they need no
 * credentials, no network, and no mocking framework.
 */
class AnthropicChatModelConnectionTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /** The continuation an assistant returns after a "{" prefill, and the document it completes. */
    private static final String CONTINUATION = "\"ok\": true}";

    private static final String COMPLETED = "{" + CONTINUATION;

    private static ResourceDescriptor descriptor(String model) {
        return ResourceDescriptor.Builder.newBuilder(AnthropicChatModelConnection.class.getName())
                .addInitialArgument("api_key", "test-key")
                .addInitialArgument("model", model)
                .build();
    }

    private static AnthropicChatModelConnection connection() {
        return new AnthropicChatModelConnection(descriptor("claude-sonnet-4-20250514"), NOOP);
    }

    private static Map<String, Object> params(Object jsonPrefill) {
        Map<String, Object> params = new HashMap<>();
        params.put("max_tokens", 256);
        if (jsonPrefill != null) {
            params.put("json_prefill", jsonPrefill);
        }
        return params;
    }

    private static List<ChatMessage> userMessage() {
        return List.of(new ChatMessage(MessageRole.USER, "hi"));
    }

    /** An assistant response carrying a single text block. */
    private static Message textResponse(String text) {
        Usage usage =
                Usage.builder()
                        .inputTokens(1)
                        .outputTokens(1)
                        .cacheCreation(Optional.empty())
                        .cacheCreationInputTokens(Optional.empty())
                        .cacheReadInputTokens(Optional.empty())
                        .serverToolUse(Optional.empty())
                        .serviceTier(Optional.empty())
                        .build();
        return Message.builder()
                .id("msg_test")
                .model(Model.of("claude-sonnet-4-20250514"))
                .addContent(TextBlock.builder().text(text).citations(Optional.empty()).build())
                .stopReason(Optional.empty())
                .stopSequence(Optional.empty())
                .usage(usage)
                .build();
    }

    /** True when the built request ends with the prefilled assistant "{" message. */
    private static boolean requestCarriesPrefill(AnthropicChatModelConnection.BuiltRequest built) {
        List<MessageParam> messages = built.params.messages();
        MessageParam last = messages.get(messages.size() - 1);
        return last.role().equals(MessageParam.Role.ASSISTANT)
                && last.content().string().isPresent()
                && "{".equals(last.content().string().get());
    }

    /**
     * Asserts that the recorded decision, the request content, and the converted response all agree
     * with each other and with {@code expectedApplied}.
     *
     * <p>The agreement is the invariant that matters: a decision that does not match what the
     * request actually carries makes the conversion either prepend a stray {@code "{"} or drop a
     * required one, yielding malformed JSON the response gives no sign of.
     */
    private static void assertPrefillDecision(
            Object jsonPrefill, List<Tool> tools, boolean expectedApplied) {
        AnthropicChatModelConnection connection = connection();
        AnthropicChatModelConnection.BuiltRequest built =
                connection.buildRequest(userMessage(), tools, params(jsonPrefill), null);

        assertThat(built.jsonPrefillApplied).isEqualTo(expectedApplied);
        assertThat(requestCarriesPrefill(built)).isEqualTo(expectedApplied);
        assertThat(connection.convertResponse(built, textResponse(CONTINUATION)).getContent())
                .isEqualTo(expectedApplied ? COMPLETED : CONTINUATION);
    }

    @Test
    @DisplayName("json_prefill applied when requested with no tools")
    void testPrefillAppliedWithoutTools() {
        assertPrefillDecision(true, List.of(), true);
    }

    @Test
    @DisplayName("json_prefill not applied when tools are present")
    void testPrefillNotAppliedWithTools() {
        assertPrefillDecision(true, List.of(new StubTool()), false);
    }

    @Test
    @DisplayName("json_prefill not applied when the parameter is absent")
    void testPrefillNotAppliedWhenAbsent() {
        assertPrefillDecision(null, List.of(), false);
    }

    @Test
    @DisplayName("json_prefill not applied when the parameter is false")
    void testPrefillNotAppliedWhenFalse() {
        assertPrefillDecision(false, List.of(), false);
    }

    @Test
    @DisplayName("json_prefill applied when the tools list is null")
    void testPrefillAppliedWithNullTools() {
        assertPrefillDecision(true, null, true);
    }

    @Test
    @DisplayName("null model params are copied rather than dereferenced")
    void testNullModelParamsAreCopied() {
        // With no params there is no max_tokens either, so the SDK's own required-field check is
        // the first thing that can fail. Reaching it at all is the assertion: a regression in the
        // null handling would surface earlier, as a NullPointerException.
        assertThatThrownBy(() -> connection().buildRequest(userMessage(), List.of(), null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("request build failures surface as a wrapped RuntimeException")
    void testBuildFailureIsWrapped() {
        List<ChatMessage> messages = List.of(new ChatMessage(MessageRole.TOOL, "result"));

        assertThatThrownBy(() -> connection().chat(messages, List.of(), params(null)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to call Anthropic messages API.")
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------------------------------
    // Native structured output
    // ---------------------------------------------------------------------------------------

    /**
     * A model the provider documents native structured-output support for.
     *
     * <p>Deliberately a 4.5-generation name, which is the only generation that is both
     * structured-output capable and still accepts a JSON prefill. The output_config tests below
     * assert that a prefill is suppressed; on a 4.6-or-later name the prefill capability guard
     * would suppress it as well, so those assertions would hold even with the output_config
     * suppression removed.
     */
    private static final String CAPABLE_MODEL = "claude-sonnet-4-5";

    /**
     * A model the provider does not document native structured-output support for, predating the
     * cutoff.
     */
    private static final String INCAPABLE_MODEL = "claude-sonnet-4-20250514";

    /**
     * The models the provider documents native structured-output support for, in the order the
     * connection lists them: the exact-matched names first, then the prefix-matched aliases.
     * Mirroring that order keeps the two lists comparable side by side, so a name added to one and
     * not the other stands out.
     */
    private static Stream<String> capableModels() {
        return Stream.of(
                "claude-opus-4-6",
                "claude-opus-4-7",
                "claude-opus-4-8",
                "claude-opus-5",
                "claude-sonnet-4-6",
                "claude-sonnet-5",
                "claude-fable-5",
                "claude-mythos-5",
                "claude-mythos-preview",
                "claude-opus-4-5",
                "claude-sonnet-4-5",
                "claude-haiku-4-5");
    }

    /**
     * Names that must not be treated as capable. {@code claude-opus-4-1-20250805} and {@code
     * claude-opus-4} are the reason the alias prefixes retain their minor version: truncating
     * {@code claude-opus-4-5} to {@code claude-opus-4} would admit both.
     *
     * <p>The empty name is reachable — a blank configured model survives the blank-check in the
     * request builder and arrives here unchanged — and it is the shortest name the predicate has to
     * answer for, so a rewrite that indexes into the name rather than matching it whole breaks on
     * it.
     */
    private static Stream<String> incapableModels() {
        return Stream.of(
                "claude-opus-4-1-20250805",
                "claude-opus-4",
                "claude-sonnet-4-20250514",
                "claude-3-5-sonnet-latest",
                "");
    }

    /** A POJO the SDK can derive a JSON schema from. */
    public static class Answer {
        public String verdict;
    }

    /** A polymorphic member, which the SDK renders as a discriminated union. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Dog.class, name = "dog"),
        @JsonSubTypes.Type(value = Cat.class, name = "cat")
    })
    public abstract static class Pet {}

    /** One arm of the {@link Pet} union. */
    public static class Dog extends Pet {
        public String bark;
    }

    /** The other arm of the {@link Pet} union. */
    public static class Cat extends Pet {
        public String meow;
    }

    /** Holds a polymorphic member. */
    public static class Owner {
        public String verdict;
        public Pet pet;
    }

    private static Map<String, Object> paramsWithModel(String model, Object jsonPrefill) {
        Map<String, Object> params = params(jsonPrefill);
        params.put("model", model);
        return params;
    }

    private static AnthropicChatModelConnection.BuiltRequest build(
            String model, Object outputSchema, Object jsonPrefill) {
        return connection()
                .buildRequest(
                        userMessage(),
                        List.of(),
                        paramsWithModel(model, jsonPrefill),
                        outputSchema);
    }

    /** The property names of the JSON schema the request carries, or empty when it carries none. */
    private static Set<String> nativeSchemaProperties(
            AnthropicChatModelConnection.BuiltRequest built) {
        return built.params
                .outputConfig()
                .flatMap(OutputConfig::format)
                .map(format -> format.schema()._additionalProperties())
                .map(schema -> schema.get("properties"))
                .map(properties -> MAPPER.convertValue(properties, MAP_TYPE).keySet())
                .orElse(Set.of());
    }

    /** The JSON Schema the request carries, as the SDK holds it on the output config. */
    private static String nativeSchemaPayload(AnthropicChatModelConnection.BuiltRequest built) {
        return built.params
                .outputConfig()
                .flatMap(OutputConfig::format)
                .map(format -> format.schema()._additionalProperties().toString())
                .orElseThrow();
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-sonnet-4-5", "claude-opus-4-6"})
    @DisplayName("a POJO schema on a capable model is sent as output_config")
    void testNativeSchemaAppliedOnCapableModel(String model) {
        // One name from each way the capability check can match: a 4.5-generation alias reached by
        // prefix, and a 4.6 name reached by exact match. The request-build site consults the check
        // as a whole, so covering only one branch would let it be narrowed to that branch while
        // silently dropping native structured output for every model on the other.
        AnthropicChatModelConnection.BuiltRequest built = build(model, Answer.class, null);

        // Asserting the property name rather than mere presence: an output_config built from the
        // wrong class, or from an empty placeholder, would still be present.
        assertThat(nativeSchemaProperties(built)).containsExactly("verdict");
    }

    @Test
    @DisplayName("a POJO schema on an incapable model keeps the prompt fallback")
    void testNativeSchemaNotAppliedOnIncapableModel() {
        assertThat(build(INCAPABLE_MODEL, Answer.class, null).params.outputConfig()).isEmpty();
    }

    @Test
    @DisplayName("no output_config is sent when no schema is supplied")
    void testNativeSchemaNotAppliedWithoutSchema() {
        assertThat(build(CAPABLE_MODEL, null, null).params.outputConfig()).isEmpty();
    }

    @Test
    @DisplayName("a schema that is not a Class keeps the prompt fallback")
    void testNonClassSchemaKeepsFallback() {
        // The RowTypeInfo case arrives wrapped rather than as a Class; anything but a Class has no
        // native translation and must degrade rather than fail.
        assertThat(build(CAPABLE_MODEL, "not-a-class", null).params.outputConfig()).isEmpty();
    }

    @Test
    @DisplayName("the native path adds no anthropic-beta header")
    void testNativePathSendsNoBetaHeader() {
        // Structured outputs are generally available; the beta header the neighbouring strict_tools
        // path sends must not leak onto this one.
        assertThat(build(CAPABLE_MODEL, Answer.class, null).params._additionalHeaders().names())
                .doesNotContain("anthropic-beta");
    }

    @ParameterizedTest
    @MethodSource("capableModels")
    @DisplayName("every documented model reports capable")
    void testCapableModelsReportCapable(String model) {
        assertThat(connection().supportsNativeStructuredOutput(model)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @MethodSource("incapableModels")
    @DisplayName("an undocumented model reports not capable")
    void testIncapableModelsReportNotCapable(String model) {
        assertThat(connection().supportsNativeStructuredOutput(model)).isFalse();
    }

    @Test
    @DisplayName("an alias prefix also matches the dated snapshot behind it")
    void testAliasPrefixMatchesDatedSnapshot() {
        // The three 4.5-generation names are aliases, so a request may carry the snapshot instead.
        // Converting the prefixes to exact matches would still satisfy the capable-models test.
        assertThat(connection().supportsNativeStructuredOutput("claude-sonnet-4-5-20250929"))
                .isTrue();
    }

    @Test
    @DisplayName("an alias prefix does not match a longer minor version")
    void testAliasPrefixDoesNotMatchLongerMinorVersion() {
        // A dated snapshot continues the alias with a "-" separator. A name that extends the
        // alias without one is a different minor version, whose capability is not the alias's to
        // answer for.
        assertThat(connection().supportsNativeStructuredOutput("claude-sonnet-4-50")).isFalse();
    }

    @Test
    @DisplayName("capability does not depend on the connection's configured model")
    void testCapabilityReadsNoInstanceState() {
        AnthropicChatModelConnection configuredCapable =
                new AnthropicChatModelConnection(descriptor(CAPABLE_MODEL), NOOP);

        // connection() is configured with an incapable default. Both must answer for the argument
        // alone, so a predicate that consulted the configured model would disagree with itself.
        assertThat(configuredCapable.supportsNativeStructuredOutput(INCAPABLE_MODEL))
                .isEqualTo(connection().supportsNativeStructuredOutput(INCAPABLE_MODEL));
        assertThat(configuredCapable.supportsNativeStructuredOutput(CAPABLE_MODEL))
                .isEqualTo(connection().supportsNativeStructuredOutput(CAPABLE_MODEL));
    }

    @Test
    @DisplayName("a polymorphic member is sent as the discriminated union the SDK derives")
    void testPolymorphicMemberSchemaIsSent() {
        // Jackson renders this member as an object declaring no properties, while the SDK derives
        // the full union the provider accepts. Reading a Jackson-rendered schema here would refuse
        // a request that works.
        assertThat(nativeSchemaPayload(build(CAPABLE_MODEL, Owner.class, null)))
                .contains("bark={type=string}")
                .contains("meow={type=string}")
                .contains("kind={const=dog}");
    }

    @Test
    @DisplayName("a caller-supplied output_config wins and the schema falls back")
    void testCallerOutputConfigWinsOverSchema() {
        Map<String, Object> params = paramsWithModel(CAPABLE_MODEL, true);
        params.put("additional_kwargs", Map.of("output_config", Map.of("format", Map.of())));

        AnthropicChatModelConnection.BuiltRequest built =
                connection().buildRequest(userMessage(), List.of(), params, Answer.class);

        assertThat(built.params.outputConfig()).isEmpty();
        assertThat(built.params._additionalBodyProperties()).containsKey("output_config");
        // The request still carries an output_config, just the caller's, and a prefill alongside
        // one is a combination the provider documents as unsupported.
        assertThat(built.jsonPrefillApplied).isFalse();
        assertThat(requestCarriesPrefill(built)).isFalse();
    }

    @Test
    @DisplayName("a caller-supplied output_config suppresses json_prefill with no schema supplied")
    void testCallerOutputConfigSuppressesPrefillWithoutSchema() {
        // No output schema, so nothing derives an output_config and the caller's is the only one on
        // the request. Asserted on both a capable and an incapable model because suppression has to
        // follow the config the request carries rather than the model's structured-output
        // capability, which a check folded in beside the capability test would get wrong.
        for (String model : List.of(CAPABLE_MODEL, INCAPABLE_MODEL)) {
            Map<String, Object> params = paramsWithModel(model, true);
            params.put("additional_kwargs", Map.of("output_config", Map.of("format", Map.of())));

            AnthropicChatModelConnection.BuiltRequest built =
                    connection().buildRequest(userMessage(), List.of(), params, null);

            assertThat(built.jsonPrefillApplied).as(model).isFalse();
            assertThat(requestCarriesPrefill(built)).as(model).isFalse();
        }
    }

    @Test
    @DisplayName("the three-argument chat forwards its arguments and no output schema")
    void testThreeArgChatForwardsNoSchema() {
        // Existing framework callers reach the connection through the three-argument overload. It
        // has to keep forwarding no schema: a non-null argument here would switch every capable
        // model to native structured output without any caller asking for it. The other three
        // arguments have to arrive unchanged, since dropping any of them is silent - the request
        // still builds, just without the caller's tools or model parameters. Overriding the
        // four-argument overload is what makes the forwarded values observable without a network
        // call, since the real one issues the request.
        Object notCalled = new Object();
        AtomicReference<Object> forwardedSchema = new AtomicReference<>(notCalled);
        AtomicReference<List<ChatMessage>> forwardedMessages = new AtomicReference<>();
        AtomicReference<List<Tool>> forwardedTools = new AtomicReference<>();
        AtomicReference<Map<String, Object>> forwardedParams = new AtomicReference<>();
        AnthropicChatModelConnection connection =
                new AnthropicChatModelConnection(descriptor(CAPABLE_MODEL), NOOP) {
                    @Override
                    public ChatMessage chat(
                            List<ChatMessage> messages,
                            List<Tool> tools,
                            Map<String, Object> modelParams,
                            Object outputSchema) {
                        forwardedSchema.set(outputSchema);
                        forwardedMessages.set(messages);
                        forwardedTools.set(tools);
                        forwardedParams.set(modelParams);
                        return ChatMessage.assistant("");
                    }
                };

        List<ChatMessage> messages = userMessage();
        List<Tool> tools = List.of(new StubTool());
        Map<String, Object> modelParams = params(null);
        connection.chat(messages, tools, modelParams);

        assertThat(forwardedSchema.get()).isNotSameAs(notCalled);
        assertThat(forwardedSchema.get()).isNull();
        assertThat(forwardedMessages.get()).isSameAs(messages);
        assertThat(forwardedTools.get()).isSameAs(tools);
        assertThat(forwardedParams.get()).isSameAs(modelParams);
    }

    @Test
    @DisplayName("json_prefill is suppressed when the schema is applied natively")
    void testJsonPrefillSuppressedWhenNativeApplies() {
        AnthropicChatModelConnection connection = connection();
        AnthropicChatModelConnection.BuiltRequest built =
                connection.buildRequest(
                        userMessage(),
                        List.of(),
                        paramsWithModel(CAPABLE_MODEL, true),
                        Answer.class);

        assertThat(built.jsonPrefillApplied).isFalse();
        assertThat(requestCarriesPrefill(built)).isFalse();
        // The provider returns a complete document, so nothing may be prepended to it.
        assertThat(connection.convertResponse(built, textResponse(COMPLETED)).getContent())
                .isEqualTo(COMPLETED);
    }

    @Test
    @DisplayName("json_prefill survives when a schema falls back to prompt engineering")
    void testJsonPrefillAppliedWhenSchemaFallsBack() {
        // Suppression keys on whether the schema was applied, not on whether one was supplied.
        // Keying it on the schema instead would strip the prefill the fallback still depends on.
        AnthropicChatModelConnection connection = connection();
        AnthropicChatModelConnection.BuiltRequest built =
                connection.buildRequest(
                        userMessage(),
                        List.of(),
                        paramsWithModel(INCAPABLE_MODEL, true),
                        Answer.class);

        assertThat(built.jsonPrefillApplied).isTrue();
        assertThat(requestCarriesPrefill(built)).isTrue();
        assertThat(connection.convertResponse(built, textResponse(CONTINUATION)).getContent())
                .isEqualTo(COMPLETED);
    }

    // ---------------------------------------------------------------------------------------
    // JSON prefill model capability
    // ---------------------------------------------------------------------------------------

    /**
     * The models the provider documents as rejecting assistant-message prefilling, in the order the
     * connection lists them. Mirroring that order keeps the two lists comparable side by side, so a
     * name added to one and not the other stands out.
     */
    private static Stream<String> prefillUnsupportedModels() {
        return Stream.of(
                "claude-opus-4-6",
                "claude-opus-4-7",
                "claude-opus-4-8",
                "claude-opus-5",
                "claude-sonnet-4-6",
                "claude-sonnet-5",
                "claude-fable-5",
                "claude-mythos-5",
                "claude-mythos-preview");
    }

    /**
     * Names that accept a prefill. The three 4.5-generation names are the load-bearing ones: they
     * are documented as structured-output capable, so folding the two rules onto one list would
     * silently withdraw the prefill from exactly these models.
     *
     * <p>{@code claude-sonnet-4-5-20250929} is the dated snapshot behind one of those aliases, and
     * {@code claude-3-5-sonnet-latest} stands for every name the list does not mention, which keeps
     * the prefill because only the listed names withdraw it.
     */
    private static Stream<String> prefillSupportedModels() {
        return Stream.of(
                "claude-opus-4-5",
                "claude-sonnet-4-5",
                "claude-haiku-4-5",
                "claude-sonnet-4-5-20250929",
                "claude-sonnet-4-20250514",
                "claude-3-5-sonnet-latest",
                "");
    }

    /**
     * Asserts the prefill decision, the request content and the converted response for a request
     * that asks for the prefill on {@code model} and gives the decision no other reason to go
     * either way: no tools and no output configuration.
     */
    private static void assertPrefillDecisionForModel(String model, boolean expectedApplied) {
        AnthropicChatModelConnection connection = connection();
        AnthropicChatModelConnection.BuiltRequest built =
                connection.buildRequest(
                        userMessage(), List.of(), paramsWithModel(model, true), null);

        assertThat(built.jsonPrefillApplied).isEqualTo(expectedApplied);
        assertThat(requestCarriesPrefill(built)).isEqualTo(expectedApplied);
        assertThat(connection.convertResponse(built, textResponse(CONTINUATION)).getContent())
                .isEqualTo(expectedApplied ? COMPLETED : CONTINUATION);
    }

    @ParameterizedTest
    @MethodSource("prefillUnsupportedModels")
    @DisplayName("every model documented as rejecting prefilling reports unsupported")
    void testPrefillUnsupportedModelsReportUnsupported(String model) {
        assertThat(AnthropicChatModelConnection.supportsJsonPrefill(model)).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @MethodSource("prefillSupportedModels")
    @DisplayName("a model outside that list reports prefill supported")
    void testPrefillSupportedModelsReportSupported(String model) {
        assertThat(AnthropicChatModelConnection.supportsJsonPrefill(model)).isTrue();
    }

    @Test
    @DisplayName("json_prefill is suppressed on a model that rejects prefilling")
    void testPrefillSuppressedOnUnsupportedModel() {
        assertPrefillDecisionForModel("claude-opus-4-6", false);
    }

    /**
     * The models the provider documents as rejecting a non-default sampling parameter, in the order
     * the connection lists them. Mirroring that order keeps the two lists comparable side by side,
     * so a name added to one and not the other stands out.
     */
    private static Stream<String> samplingUnsupportedModels() {
        return Stream.of(
                "claude-opus-4-7",
                "claude-opus-4-8",
                "claude-opus-5",
                "claude-sonnet-5",
                "claude-fable-5",
                "claude-mythos-5",
                "claude-mythos-preview");
    }

    /**
     * Names that accept a temperature. The two 4.6-generation names are the load-bearing ones: both
     * reject a prefill, so deriving the sampling rule from the prefill list would strip a
     * temperature the provider still accepts.
     */
    private static Stream<String> samplingSupportedModels() {
        return Stream.of(
                "claude-opus-4-6",
                "claude-sonnet-4-6",
                "claude-opus-4-5",
                "claude-sonnet-4-5",
                "claude-sonnet-4-20250514",
                "claude-3-5-sonnet-latest",
                "");
    }

    /**
     * Builds a request for {@code model} carrying each supplied sampling parameter, so the caller
     * can read back which of them survived. {@code temperature} is a top-level parameter while
     * {@code top_p} and {@code top_k} travel in {@code additional_kwargs}, and a null argument
     * leaves that parameter out of the request entirely.
     */
    private static MessageCreateParams samplingRequest(
            String model, Double temperature, Double topP, Long topK) {
        Map<String, Object> params = paramsWithModel(model, null);
        if (temperature != null) {
            params.put("temperature", temperature);
        }
        Map<String, Object> additionalKwargs = new HashMap<>();
        if (topP != null) {
            additionalKwargs.put("top_p", topP);
        }
        if (topK != null) {
            additionalKwargs.put("top_k", topK);
        }
        params.put("additional_kwargs", additionalKwargs);
        return connection().buildRequest(userMessage(), List.of(), params, null).params;
    }

    /**
     * Builds a request for {@code model} whose temperature arrives through {@code
     * additional_kwargs}, the second route the parameter can reach the request body by.
     */
    private static MessageCreateParams kwargsTemperatureRequest(String model) {
        Map<String, Object> params = paramsWithModel(model, null);
        params.put("additional_kwargs", Map.of("temperature", 0.5d));
        return connection().buildRequest(userMessage(), List.of(), params, null).params;
    }

    /**
     * Builds a request for {@code model} carrying a top-level temperature and a different one in
     * {@code additional_kwargs}, the case where the two routes disagree about the value.
     */
    private static MessageCreateParams competingTemperatureRequest(String model) {
        Map<String, Object> params = paramsWithModel(model, null);
        params.put("temperature", 0.1d);
        params.put("additional_kwargs", Map.of("temperature", 0.5d));
        return connection().buildRequest(userMessage(), List.of(), params, null).params;
    }

    /**
     * A top-level temperature, an {@code additional_kwargs} map and the value the two resolve to.
     * The map wins only when it holds a number, since only a number reaches the request by either
     * route.
     */
    private static Stream<Arguments> temperatureResolutions() {
        return Stream.of(
                Arguments.of(0.1d, Map.of("temperature", 0.5d), 0.5d),
                Arguments.of(0.1d, Map.of("top_p", 0.9d), 0.1d),
                Arguments.of(0.1d, Map.of("temperature", "0.5"), 0.1d),
                Arguments.of(null, Map.of("temperature", 0.5d), 0.5d),
                Arguments.of(0.1d, null, 0.1d));
    }

    @ParameterizedTest
    @MethodSource("samplingUnsupportedModels")
    @DisplayName("every model documented as rejecting sampling parameters reports unsupported")
    void testSamplingUnsupportedModelsReportUnsupported(String model) {
        assertThat(AnthropicChatModelConnection.supportsSamplingParams(model)).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @MethodSource("samplingSupportedModels")
    @DisplayName("a model outside that list reports sampling supported")
    void testSamplingSupportedModelsReportSupported(String model) {
        assertThat(AnthropicChatModelConnection.supportsSamplingParams(model)).isTrue();
    }

    @Test
    @DisplayName("temperature is dropped on a model that rejects sampling parameters")
    void testTemperatureDroppedOnUnsupportedModel() {
        assertThat(samplingRequest("claude-opus-4-7", 0.1d, null, null).temperature()).isEmpty();
    }

    @Test
    @DisplayName("top_p is dropped on a model that rejects sampling parameters")
    void testTopPDroppedOnUnsupportedModel() {
        MessageCreateParams params = samplingRequest("claude-opus-4-7", null, 0.9d, null);

        assertThat(params.topP()).isEmpty();
        // An additional_kwargs key with no case of its own reaches the body as a raw property, so
        // an empty typed field is not on its own proof the parameter was left off the request.
        assertThat(params._additionalBodyProperties()).doesNotContainKey("top_p");
    }

    @Test
    @DisplayName("top_k is dropped on a model that rejects sampling parameters")
    void testTopKDroppedOnUnsupportedModel() {
        MessageCreateParams params = samplingRequest("claude-opus-4-7", null, null, 5L);

        assertThat(params.topK()).isEmpty();
        assertThat(params._additionalBodyProperties()).doesNotContainKey("top_k");
    }

    @Test
    @DisplayName("all three sampling parameters are sent on a model that accepts them")
    void testSamplingParamsSentOnSupportedModel() {
        MessageCreateParams params = samplingRequest("claude-sonnet-4-20250514", 0.1d, 0.9d, 5L);

        assertThat(params.temperature()).contains(0.1d);
        assertThat(params.topP()).contains(0.9d);
        assertThat(params.topK()).contains(5L);
    }

    @Test
    @DisplayName("temperature supplied through additional_kwargs is dropped on a rejecting model")
    void testKwargsTemperatureDroppedOnUnsupportedModel() {
        MessageCreateParams params = kwargsTemperatureRequest("claude-opus-4-7");

        assertThat(params.temperature()).isEmpty();
        // Without a case of its own the key falls to the default branch and reaches the body as a
        // raw property, which serializes to the same "temperature" field the typed setter writes.
        assertThat(params._additionalBodyProperties()).doesNotContainKey("temperature");
    }

    @Test
    @DisplayName("temperature supplied through additional_kwargs is sent on an accepting model")
    void testKwargsTemperatureSentOnSupportedModel() {
        assertThat(kwargsTemperatureRequest("claude-sonnet-4-20250514").temperature())
                .contains(0.5d);
    }

    @ParameterizedTest
    @MethodSource("temperatureResolutions")
    @DisplayName(
            "additional_kwargs overrides the top-level temperature only when it holds a number")
    void testEffectiveTemperature(Object topLevel, Map<String, Object> kwargs, Object expected) {
        assertThat(AnthropicChatModelConnection.effectiveTemperature(topLevel, kwargs))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("the additional_kwargs temperature is the one an accepting model is sent")
    void testCompetingTemperaturesResolveToKwargsOnSupportedModel() {
        assertThat(competingTemperatureRequest("claude-sonnet-4-20250514").temperature())
                .contains(0.5d);
    }

    @Test
    @DisplayName("neither competing temperature reaches a model that rejects sampling parameters")
    void testCompetingTemperaturesDroppedOnUnsupportedModel() {
        // A rejecting name whose temperature no other test drives through buildRequest. The
        // warning owed for a dropped parameter is tracked per model and parameter for the life of
        // the JVM, so sharing a name would leave this test's meaning dependent on run order.
        MessageCreateParams params = competingTemperatureRequest("claude-opus-4-8");

        assertThat(params.temperature()).isEmpty();
        // An ungated additional_kwargs value reaches the body as a raw property, which serializes
        // to the same "temperature" field the typed setter writes, so the typed field being empty
        // is not on its own proof the parameter was left off the request.
        assertThat(params._additionalBodyProperties()).doesNotContainKey("temperature");
        // One report is owed for the pair and the request spent it, so nothing is left to report.
        // That the request above is what spent it holds only while no other test claims this
        // model name and parameter first; one that did would satisfy this assertion for its own
        // reason and leave nothing here for it to catch.
        assertThat(
                        AnthropicChatModelConnection.samplingWarning(
                                "claude-opus-4-8", "temperature", 0.5d))
                .isEmpty();
    }

    @Test
    @DisplayName(
            "a dropped sampling parameter owes one warning naming the model, parameter and value")
    void testDroppedSamplingParamOwesOneWarningPerParameter() {
        // A name no other test asks a warning for, since the bookkeeping behind it lives for the
        // life of the JVM and a pair already reported would answer empty here.
        String model = "claude-mythos-preview";

        Optional<String> first =
                AnthropicChatModelConnection.samplingWarning(model, "temperature", 0.1d);
        Optional<String> repeat =
                AnthropicChatModelConnection.samplingWarning(model, "temperature", 0.1d);
        Optional<String> other = AnthropicChatModelConnection.samplingWarning(model, "top_p", 0.9d);

        // The message has to identify which request parameter went missing and what it held,
        // because that is the only trace the dropped value leaves.
        assertThat(first).get(as(STRING)).contains(model, "temperature", "0.1");
        // The repeat is silent, but a different parameter is a different fact about the request
        // and owes a report of its own.
        assertThat(repeat).isEmpty();
        assertThat(other).get(as(STRING)).contains(model, "top_p", "0.9");
    }

    @Test
    @DisplayName("a 4.6 model rejects the prefill while keeping its sampling parameters")
    void testSamplingAndPrefillBoundariesDiffer() {
        // The two rules draw different lines, and this model sits between them: the provider
        // withdraws prefilling from 4.6 on but sampling parameters only from 4.7 on. Deriving the
        // sampling rule from the prefill list would drop the temperature here, where the provider
        // still accepts it.
        assertThat(AnthropicChatModelConnection.supportsJsonPrefill("claude-sonnet-4-6")).isFalse();
        assertThat(AnthropicChatModelConnection.supportsSamplingParams("claude-sonnet-4-6"))
                .isTrue();
        assertThat(samplingRequest("claude-sonnet-4-6", 0.1d, null, null).temperature())
                .contains(0.1d);
    }

    @Test
    @DisplayName("json_prefill is applied on a structured-output capable model that accepts it")
    void testPrefillAppliedOnStructuredOutputCapableModel() {
        // The two capability rules draw different lines, and this model sits between them: the
        // provider documents structured-output support from the 4.5 generation on but withdraws
        // prefilling only from 4.6 on. Deriving the prefill rule from the structured-output
        // allowlists would strip the prefill here, where the provider still accepts it.
        assertThat(connection().supportsNativeStructuredOutput("claude-sonnet-4-5")).isTrue();

        assertPrefillDecisionForModel("claude-sonnet-4-5", true);
    }

    /**
     * A temperature value that records whether anything rendered it as text.
     *
     * <p>The report owed for a dropped sampling parameter is assembled with a {@code %s}
     * conversion, so the value it names is whichever one had {@code toString()} called on it. A
     * value that was never rendered was never reported. That is the only handle on the choice from
     * outside the class: a model rejecting sampling parameters is sent neither candidate, so the
     * request itself looks the same whichever one the report picked.
     *
     * <p>Rendering is the measurement, so observing one taints it. A breakpoint that displays this
     * value, or a debug print of it, calls {@code toString()} and makes {@code wasRendered()}
     * answer true regardless of what the code under test did.
     */
    private static final class RecordingTemperature extends Number {
        private final double value;
        private boolean rendered;

        RecordingTemperature(double value) {
            this.value = value;
        }

        boolean wasRendered() {
            return rendered;
        }

        @Override
        public String toString() {
            rendered = true;
            return Double.toString(value);
        }

        @Override
        public int intValue() {
            return (int) value;
        }

        @Override
        public long longValue() {
            return (long) value;
        }

        @Override
        public float floatValue() {
            return (float) value;
        }

        @Override
        public double doubleValue() {
            return value;
        }
    }

    @Test
    @DisplayName("a dropped temperature is reported as the additional_kwargs value it resolved to")
    void testDroppedTemperatureIsReportedAsTheResolvedValue() {
        // A rejecting name no other test builds a request for or asks a temperature report of.
        // The report is owed once per model and parameter for the life of the JVM, so a pair
        // claimed elsewhere first would leave nothing to render here and the first assertion
        // below would fail: a collision surfaces as a failure, not as a test that still passes
        // while pinning less.
        RecordingTemperature topLevel = new RecordingTemperature(0.1d);
        RecordingTemperature override = new RecordingTemperature(0.5d);
        Map<String, Object> params = paramsWithModel("claude-mythos-5", null);
        params.put("temperature", topLevel);
        params.put("additional_kwargs", Map.of("temperature", override));

        connection().buildRequest(userMessage(), List.of(), params, null);

        assertThat(override.wasRendered()).isTrue();
        // The overridden value would have named a setting the request was never going to carry.
        assertThat(topLevel.wasRendered()).isFalse();
    }

    /** Minimal tool stub; only its presence in the tools list matters. */
    private static class StubTool extends Tool {
        StubTool() {
            super(new ToolMetadata("add", "adds", "{\"type\":\"object\"}"));
        }

        @Override
        public ToolType getToolType() {
            return ToolType.FUNCTION;
        }

        @Override
        public ToolResponse call(ToolParameters parameters) {
            return ToolResponse.success(null);
        }
    }
}
