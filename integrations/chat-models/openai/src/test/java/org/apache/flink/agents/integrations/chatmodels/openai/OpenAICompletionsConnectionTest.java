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

package org.apache.flink.agents.integrations.chatmodels.openai;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.openai.errors.BadRequestException;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
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

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link OpenAICompletionsConnection}'s native structured-output behavior. These
 * assert the built request body without a live API call by inspecting {@code buildRequest}, and
 * exercise the model-dependent capability predicate directly.
 */
class OpenAICompletionsConnectionTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    /** A representative POJO output schema. */
    public static class Person {
        public String name;
        public int age;
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
        public String name;
        public Pet pet;
    }

    /** The JSON Schema the request carries, as the SDK holds it on the response format. */
    private static String nativeSchemaPayload(ChatCompletionCreateParams params) {
        return params.responseFormat()
                .orElseThrow()
                .asJsonSchema()
                .jsonSchema()
                ._schema()
                .toString();
    }

    private static OpenAICompletionsConnection connection() {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(OpenAICompletionsConnection.class.getName())
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("model", "gpt-4o")
                        .build();
        return new OpenAICompletionsConnection(desc, NOOP);
    }

    private static OpenAICompletionsConnection connection(String apiBaseUrl) {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(OpenAICompletionsConnection.class.getName())
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("api_base_url", apiBaseUrl)
                        .addInitialArgument("model", "gpt-4o")
                        .build();
        return new OpenAICompletionsConnection(desc, NOOP);
    }

    private static Map<String, Object> params(String model) {
        Map<String, Object> params = new HashMap<>();
        params.put("model", model);
        return params;
    }

    private static List<ChatMessage> userMessage() {
        return List.of(new ChatMessage(MessageRole.USER, "hi"));
    }

    @Test
    void testConnectionArgumentValidation() {
        ResourceDescriptor missingKey =
                ResourceDescriptor.Builder.newBuilder(OpenAICompletionsConnection.class.getName())
                        .build();
        assertThatThrownBy(() -> new OpenAICompletionsConnection(missingKey, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("api_key");

        OpenAICompletionsConnection connection =
                new OpenAICompletionsConnection(
                        ResourceDescriptor.Builder.newBuilder(
                                        OpenAICompletionsConnection.class.getName())
                                .addInitialArgument("api_key", "test-key")
                                .addInitialArgument("timeout", 0)
                                .addInitialArgument("max_retries", 0)
                                .build(),
                        NOOP);
        assertThat(connection).isInstanceOf(BaseChatModelConnection.class);
        assertThat(connection.getTimeout()).isEqualTo(Duration.ZERO);
        assertThat(connection.getMaxRetries()).isZero();
        OpenAIClientTestUtils.assertNoTimeoutConfigured(connection);
    }

    @Test
    @DisplayName("A request-building failure reaches the caller as its own type, not a wrapper")
    void testRequestBuildingFailurePropagatesUnwrapped() {
        List<ChatMessage> toolMessageWithoutExternalId =
                List.of(new ChatMessage(MessageRole.TOOL, "result", Map.of()));

        assertThatThrownBy(
                        () ->
                                connection()
                                        .chat(
                                                toolMessageWithoutExternalId,
                                                List.of(),
                                                params("gpt-4o"),
                                                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("externalId");
    }

    @Test
    @DisplayName("A provider error reaches the caller as the SDK exception carrying its payload")
    void testProviderErrorPropagatesUnwrapped() throws IOException {
        try (FakeOpenAIErrorEndpoint endpoint = FakeOpenAIErrorEndpoint.rejectingWith400()) {
            OpenAICompletionsConnection connection = connection(endpoint.baseUrl());

            assertThatThrownBy(
                            () -> connection.chat(userMessage(), List.of(), params("gpt-4o"), null))
                    .isInstanceOfSatisfying(
                            BadRequestException.class,
                            e -> {
                                assertThat(e.statusCode()).isEqualTo(400);
                                assertThat(e.code()).contains(FakeOpenAIErrorEndpoint.ERROR_CODE);
                            })
                    .hasMessageContaining(FakeOpenAIErrorEndpoint.ERROR_MESSAGE);
        }
    }

    @Test
    @DisplayName("The finish reason reported by the provider reaches the response extra args")
    void testResponseCarriesFinishReason() throws IOException {
        try (FakeOpenAICompletionsEndpoint endpoint =
                FakeOpenAICompletionsEndpoint.servingFinishReason("length")) {
            ChatMessage response =
                    connection(endpoint.baseUrl())
                            .chat(userMessage(), List.of(), params("gpt-4o"), null);

            assertThat(response.getExtraArgs()).containsEntry("finish_reason", "length");
        }
    }

    @Test
    @DisplayName("A finish reason outside the documented set is stored as received")
    void testResponseCarriesUnknownFinishReasonVerbatim() throws IOException {
        try (FakeOpenAICompletionsEndpoint endpoint =
                FakeOpenAICompletionsEndpoint.servingFinishReason("some_vendor_reason")) {
            ChatMessage response =
                    connection(endpoint.baseUrl())
                            .chat(userMessage(), List.of(), params("gpt-4o"), null);

            assertThat(response.getExtraArgs())
                    .containsEntry("finish_reason", "some_vendor_reason");
        }
    }

    @Test
    @DisplayName("An empty finish reason is recorded rather than discarded")
    void testResponseCarriesEmptyFinishReason() throws IOException {
        // The choice carries a value, so it is recorded; emptiness is not treated as absence.
        try (FakeOpenAICompletionsEndpoint endpoint =
                FakeOpenAICompletionsEndpoint.servingFinishReason("")) {
            ChatMessage response =
                    connection(endpoint.baseUrl())
                            .chat(userMessage(), List.of(), params("gpt-4o"), null);

            assertThat(response.getExtraArgs()).containsEntry("finish_reason", "");
        }
    }

    @Test
    @DisplayName("The finish reason is captured independently of the token metrics")
    void testResponseCarriesFinishReasonWithoutUsage() throws IOException {
        // The metrics come from the usage report the response here omits, so the absent
        // promptTokens proves that branch did not run and could not have written the reason.
        try (FakeOpenAICompletionsEndpoint endpoint =
                FakeOpenAICompletionsEndpoint.servingFinishReasonWithoutUsage("tool_calls")) {
            ChatMessage response =
                    connection(endpoint.baseUrl())
                            .chat(userMessage(), List.of(), params("gpt-4o"), null);

            assertThat(response.getExtraArgs())
                    .containsEntry("finish_reason", "tool_calls")
                    .doesNotContainKey("promptTokens");
        }
    }

    @Test
    @DisplayName("A choice with no finish_reason member yields no key and no error")
    void testNoFinishReasonKeyWhenMemberAbsent() throws IOException {
        try (FakeOpenAICompletionsEndpoint endpoint =
                FakeOpenAICompletionsEndpoint.servingNoFinishReasonMember()) {
            assertNoFinishReasonKey(endpoint);
        }
    }

    @Test
    @DisplayName("A choice whose finish_reason is JSON null yields no key and no error")
    void testNoFinishReasonKeyWhenJsonNull() throws IOException {
        try (FakeOpenAICompletionsEndpoint endpoint =
                FakeOpenAICompletionsEndpoint.servingNullFinishReason()) {
            assertNoFinishReasonKey(endpoint);
        }
    }

    private static void assertNoFinishReasonKey(FakeOpenAICompletionsEndpoint endpoint) {
        // ChatCompletion.Choice#finishReason throws OpenAIInvalidDataException for both of these
        // response shapes, so reading the value has to go through the raw field.
        OpenAICompletionsConnection connection = connection(endpoint.baseUrl());
        AtomicReference<ChatMessage> response = new AtomicReference<>();

        assertThatCode(
                        () ->
                                response.set(
                                        connection.chat(
                                                userMessage(), List.of(), params("gpt-4o"), null)))
                .doesNotThrowAnyException();

        assertThat(response.get().getExtraArgs()).doesNotContainKey("finish_reason");
    }

    @Test
    @DisplayName("Native response_format json_schema strict applied for a POJO on a capable model")
    void testNativeAppliedForPojoCapableModel() {
        ChatCompletionCreateParams params =
                connection().buildRequest(userMessage(), List.of(), params("gpt-4o"), Person.class);

        assertThat(params.responseFormat()).isPresent();
        ResponseFormatJsonSchema jsonSchema = params.responseFormat().get().asJsonSchema();
        assertThat(jsonSchema.jsonSchema().strict()).contains(true);
        // Asserting the members rather than only the flags: a schema declaring no properties
        // would satisfy strict() and the derived name while constraining nothing at all.
        assertThat(nativeSchemaPayload(params))
                .contains("name={type=string}")
                .contains("age={type=integer}");
    }

    @Test
    @DisplayName("A polymorphic member is sent as the discriminated union the SDK derives")
    void testPolymorphicMemberSchemaIsSent() {
        // Jackson renders this member as an object declaring no properties, while the SDK derives
        // the full union the provider accepts. Reading a Jackson-rendered schema here would refuse
        // a request that works.
        ChatCompletionCreateParams request =
                connection().buildRequest(userMessage(), List.of(), params("gpt-4o"), Owner.class);

        assertThat(nativeSchemaPayload(request))
                .contains("bark={type=string}")
                .contains("meow={type=string}")
                .contains("kind={const=dog}");
    }

    @Test
    @DisplayName("Native NOT applied for a POJO on an incapable model (prompt fallback)")
    void testNativeNotAppliedForIncapableModel() {
        ChatCompletionCreateParams params =
                connection()
                        .buildRequest(
                                userMessage(), List.of(), params("gpt-3.5-turbo"), Person.class);

        assertThat(params.responseFormat()).isEmpty();
    }

    @Test
    @DisplayName("Native NOT applied for a pre-cutoff same-family gpt-4o snapshot")
    void testNativeNotAppliedForPreCutoffSnapshot() {
        // gpt-4o-2024-05-13 predates the Structured Outputs cutoff even though it shares the gpt-4o
        // prefix; treating it as capable would fail silently at the provider.
        ChatCompletionCreateParams params =
                connection()
                        .buildRequest(
                                userMessage(),
                                List.of(),
                                params("gpt-4o-2024-05-13"),
                                Person.class);

        assertThat(params.responseFormat()).isEmpty();
    }

    @Test
    @DisplayName("Native NOT applied when no output schema is supplied")
    void testNativeNotAppliedWhenSchemaNull() {
        ChatCompletionCreateParams params =
                connection().buildRequest(userMessage(), List.of(), params("gpt-4o"), null);

        assertThat(params.responseFormat()).isEmpty();
    }

    @Test
    @DisplayName("Effective model falls back to the connection default when the parameter is unset")
    void testEffectiveModelForFallsBackToTheDefaultModel() {
        // buildRequest applies the same fallback before feeding the capability predicate, so an
        // answer taken without it would disagree with the model the request is issued against.
        assertThat(connection().effectiveModelFor(new HashMap<>())).isEqualTo("gpt-4o");
        assertThat(connection().effectiveModelFor(params(null))).isEqualTo("gpt-4o");
        assertThat(connection().effectiveModelFor(params("   "))).isEqualTo("gpt-4o");
    }

    @Test
    @DisplayName("The model the request builder judges is the one the hook names")
    void testEffectiveModelForNamesTheModelTheBuilderJudges() {
        // The hook duplicates the builder's own model resolution rather than centralizing it, so
        // capturing what the builder actually feeds the predicate is the only thing that keeps the
        // two from drifting apart. Asserting each against a literal would let them drift in step.
        AtomicReference<String> judged = new AtomicReference<>();
        OpenAICompletionsConnection connection =
                new OpenAICompletionsConnection(
                        ResourceDescriptor.Builder.newBuilder(
                                        OpenAICompletionsConnection.class.getName())
                                .addInitialArgument("api_key", "test-key")
                                .addInitialArgument("model", "gpt-4o")
                                .build(),
                        NOOP) {
                    @Override
                    protected boolean supportsNativeStructuredOutput(String effectiveModel) {
                        judged.set(effectiveModel);
                        return super.supportsNativeStructuredOutput(effectiveModel);
                    }
                };

        for (Map<String, Object> modelParams :
                List.<Map<String, Object>>of(
                        params("gpt-4o-mini"), params("   "), new HashMap<>())) {
            String named = connection.effectiveModelFor(modelParams);

            connection.buildRequest(userMessage(), List.of(), modelParams, Person.class);

            assertThat(judged.get()).isEqualTo(named);
        }
    }

    @Test
    @DisplayName("Effective model reads the model parameter without consuming it")
    void testEffectiveModelForDoesNotConsumeTheModelParameter() {
        // buildRequest resolves the model with remove(). A query doing the same would leave the
        // caller's parameters without a model for the request it is about to build.
        Map<String, Object> modelParams = params("gpt-4o-mini");

        assertThat(connection().effectiveModelFor(modelParams)).isEqualTo("gpt-4o-mini");
        assertThat(modelParams).containsEntry("model", "gpt-4o-mini");
    }

    @Test
    @DisplayName("Native NOT applied for a non-POJO schema form (POJO-only scope)")
    void testNativeNotAppliedForNonPojoSchema() {
        // A RowTypeInfo schema arrives wrapped in OutputSchema (not a bare POJO Class), so it must
        // not activate native structured output; any non-Class schema object exercises the same
        // instanceof gate.
        Object nonClassSchema = "row<name STRING>";

        ChatCompletionCreateParams params =
                connection()
                        .buildRequest(userMessage(), List.of(), params("gpt-4o"), nonClassSchema);

        assertThat(params.responseFormat()).isEmpty();
    }

    @Test
    @DisplayName("Native applied for a POJO even when tools are bound (no empty-tools gate)")
    void testNativeAppliedEvenWhenToolsBound() {
        ChatCompletionCreateParams params =
                connection()
                        .buildRequest(
                                userMessage(),
                                List.of(new StubTool()),
                                params("gpt-4o"),
                                Person.class);

        assertThat(params.responseFormat()).isPresent();
    }

    @Test
    @DisplayName("Capability predicate accepts the documented capable models")
    void testCapabilityPredicateAcceptsCapableModels() {
        OpenAICompletionsConnection connection = connection();

        assertThat(connection.supportsNativeStructuredOutput("gpt-4o")).isTrue();
        assertThat(connection.supportsNativeStructuredOutput("gpt-4o-2024-08-06")).isTrue();
        assertThat(connection.supportsNativeStructuredOutput("gpt-4o-2024-11-20")).isTrue();
        assertThat(connection.supportsNativeStructuredOutput("gpt-4o-mini")).isTrue();
        assertThat(connection.supportsNativeStructuredOutput("gpt-4o-mini-2024-07-18")).isTrue();
        assertThat(connection.supportsNativeStructuredOutput("gpt-4o-search-preview")).isTrue();
        assertThat(connection.supportsNativeStructuredOutput("gpt-4o-search-preview-2025-03-11"))
                .isTrue();
        assertThat(connection.supportsNativeStructuredOutput("gpt-4o-mini-search-preview"))
                .isTrue();
        assertThat(connection.supportsNativeStructuredOutput("gpt-4.1")).isTrue();
        assertThat(connection.supportsNativeStructuredOutput("gpt-4.1-mini")).isTrue();
        assertThat(connection.supportsNativeStructuredOutput("gpt-5")).isTrue();
        assertThat(connection.supportsNativeStructuredOutput("gpt-5-mini")).isTrue();
        assertThat(connection.supportsNativeStructuredOutput("gpt-5-chat-latest")).isTrue();
        assertThat(connection.supportsNativeStructuredOutput("o1")).isTrue();
        assertThat(connection.supportsNativeStructuredOutput("o1-2024-12-17")).isTrue();
        assertThat(connection.supportsNativeStructuredOutput("o3")).isTrue();
        assertThat(connection.supportsNativeStructuredOutput("o3-mini")).isTrue();
        assertThat(connection.supportsNativeStructuredOutput("o4-mini")).isTrue();
    }

    @Test
    @DisplayName(
            "Capability predicate rejects non-text modality, incapable, pre-cutoff, unknown, empty,"
                    + " and null models")
    void testCapabilityPredicateRejectsIncapableModels() {
        OpenAICompletionsConnection connection = connection();

        assertThat(connection.supportsNativeStructuredOutput("gpt-3.5-turbo")).isFalse();
        assertThat(connection.supportsNativeStructuredOutput("gpt-4")).isFalse();
        assertThat(connection.supportsNativeStructuredOutput("gpt-4-turbo")).isFalse();
        assertThat(connection.supportsNativeStructuredOutput("gpt-4o-2024-05-13")).isFalse();
        assertThat(connection.supportsNativeStructuredOutput("gpt-4o-audio-preview")).isFalse();
        assertThat(connection.supportsNativeStructuredOutput("gpt-4o-mini-audio-preview"))
                .isFalse();
        assertThat(connection.supportsNativeStructuredOutput("gpt-4o-mini-realtime-preview"))
                .isFalse();
        assertThat(connection.supportsNativeStructuredOutput("gpt-4o-mini-tts")).isFalse();
        assertThat(connection.supportsNativeStructuredOutput("gpt-4o-mini-transcribe")).isFalse();
        assertThat(connection.supportsNativeStructuredOutput("o1-mini")).isFalse();
        assertThat(connection.supportsNativeStructuredOutput("some-unknown-model")).isFalse();
        assertThat(connection.supportsNativeStructuredOutput("")).isFalse();
        assertThat(connection.supportsNativeStructuredOutput(null)).isFalse();
    }

    /** A connection that records what the feasibility query answered on each request it built. */
    private static OpenAICompletionsConnection recordingConnection(
            AtomicReference<Boolean> answered) {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(OpenAICompletionsConnection.class.getName())
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("model", "gpt-4o")
                        .build();
        return new OpenAICompletionsConnection(desc, NOOP) {
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
    void testFeasibilityQueryAgreesWithTheNativeBranch() {
        // Comparing the answer against what the request ends up carrying, rather than against a
        // literal, is what keeps the query and the branch from drifting in step. The model is
        // capable in every case, so the only conjunct left for the branch is the query itself.
        AtomicReference<Boolean> answered = new AtomicReference<>();
        OpenAICompletionsConnection connection = recordingConnection(answered);

        for (Object schema : Arrays.asList(Person.class, "row<name STRING>", null)) {
            for (List<Tool> tools :
                    Arrays.asList(List.<Tool>of(), List.<Tool>of(new StubTool()), null)) {
                answered.set(null);

                ChatCompletionCreateParams request =
                        connection.buildRequest(userMessage(), tools, params("gpt-4o"), schema);

                // A null here means the branch never consulted the query at all, which is the
                // drift this test exists to catch. The value assertion below would fail too, but
                // on a null comparison that does not say why.
                assertThat(answered.get()).as("query reached for schema %s", schema).isNotNull();
                assertThat(answered.get())
                        .as("schema %s, tools %s", schema, tools)
                        .isEqualTo(request.responseFormat().isPresent());
            }
        }
    }

    @Test
    @DisplayName("The feasibility query leaves the model's capability out of its answer")
    void testFeasibilityQueryExcludesModelCapability() {
        // The two answers are independent: a POJO is feasible here even on a model the allowlist
        // rejects, and it is the branch's separate capability conjunct that keeps that request
        // unconstrained. An override that folded capability in would make this pair agree, which
        // the binding test above cannot see because it moves both sides at once.
        Map<String, Object> incapable = params("gpt-4o-2024-05-13");

        assertThat(connection().canApplyNativeStructuredOutput(Person.class, List.of(), incapable))
                .isTrue();
        assertThat(
                        connection()
                                .buildRequest(userMessage(), List.of(), incapable, Person.class)
                                .responseFormat())
                .isEmpty();
    }

    @Test
    @DisplayName("The feasibility query reads its tools and parameters without consuming them")
    void testFeasibilityQueryDoesNotConsumeItsInputs() {
        // The same tools and parameters go on to build the request the answer was about, so a
        // query that took anything out of either would answer about one request and build another.
        // Both are immutable, so a consuming implementation raises rather than silently differing.
        List<Tool> tools = List.of(new StubTool());
        Map<String, Object> modelParams = Map.of("model", "gpt-4o", "temperature", 0.5);

        connection().canApplyNativeStructuredOutput(Person.class, tools, modelParams);

        assertThat(tools).hasSize(1);
        assertThat(modelParams).isEqualTo(Map.of("model", "gpt-4o", "temperature", 0.5));
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
