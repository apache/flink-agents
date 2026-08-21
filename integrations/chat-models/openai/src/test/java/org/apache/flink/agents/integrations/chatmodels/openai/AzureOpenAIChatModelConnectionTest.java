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

import com.fasterxml.jackson.core.type.TypeReference;
import com.openai.errors.BadRequestException;
import com.openai.models.ChatModel;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.completions.CompletionUsage;
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
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AzureOpenAIChatModelConnection} — constructor validation and request
 * building, with no network access. End-to-end tests against a real Azure OpenAI deployment live in
 * {@link AzureOpenAIChatModelIT}.
 */
class AzureOpenAIChatModelConnectionTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    /** A deployment name is chosen by the user and carries no capability information. */
    private static final String DEPLOYMENT = "my-deployment";

    private static final String CAPABLE_API_VERSION = "2024-08-01-preview";

    private static final String BELOW_FLOOR_API_VERSION = "2024-02-01";

    private static final Map<String, Object> CALLER_RESPONSE_FORMAT = Map.of("type", "json_object");

    private static ResourceDescriptor.Builder connectionDescriptor() {
        return ResourceDescriptor.Builder.newBuilder(
                AzureOpenAIChatModelConnection.class.getName());
    }

    /** A representative POJO output schema. */
    public static class Person {
        public String name;
        public int age;
    }

    private static AzureOpenAIChatModelConnection connection(String apiVersion) {
        ResourceDescriptor desc =
                connectionDescriptor()
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("api_version", apiVersion)
                        .addInitialArgument("azure_endpoint", "https://example.openai.azure.com")
                        .build();
        return new AzureOpenAIChatModelConnection(desc, NOOP);
    }

    private static AzureOpenAIChatModelConnection connection() {
        return connection(CAPABLE_API_VERSION);
    }

    private static AzureOpenAIChatModelConnection connection(
            String apiVersion, String azureEndpoint, String azureUrlPathMode) {
        ResourceDescriptor desc =
                connectionDescriptor()
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("api_version", apiVersion)
                        .addInitialArgument("azure_endpoint", azureEndpoint)
                        .addInitialArgument("azure_url_path_mode", azureUrlPathMode)
                        .build();
        return new AzureOpenAIChatModelConnection(desc, NOOP);
    }

    @Test
    void testConnectionArgumentDefaultsAndZeroTimeout() {
        AzureOpenAIChatModelConnection connection =
                new AzureOpenAIChatModelConnection(
                        connectionDescriptor()
                                .addInitialArgument("api_key", "test-key")
                                .addInitialArgument("api_version", "2024-02-01")
                                .addInitialArgument(
                                        "azure_endpoint", "https://example.openai.azure.com")
                                .addInitialArgument("timeout", 0)
                                .addInitialArgument("max_retries", 0)
                                .build(),
                        NOOP);
        assertThat(connection).isInstanceOf(BaseChatModelConnection.class);
        assertThat(connection.getTimeout()).isEqualTo(Duration.ZERO);
        assertThat(connection.getMaxRetries()).isZero();
        OpenAIClientTestUtils.assertNoTimeoutConfigured(connection);
    }

    /**
     * Model params addressing {@link #DEPLOYMENT}. A null {@code modelOfAzureDeployment} omits the
     * key entirely, which is how the setup emits an unset backing model.
     */
    private static Map<String, Object> params(String modelOfAzureDeployment) {
        Map<String, Object> params = new HashMap<>();
        params.put("model", DEPLOYMENT);
        if (modelOfAzureDeployment != null) {
            params.put("model_of_azure_deployment", modelOfAzureDeployment);
        }
        return params;
    }

    private static Map<String, Object> paramsWithCallerResponseFormat(
            String modelOfAzureDeployment) {
        Map<String, Object> params = params(modelOfAzureDeployment);
        params.put("additional_kwargs", Map.of("response_format", CALLER_RESPONSE_FORMAT));
        return params;
    }

    private static List<ChatMessage> userMessage() {
        return List.of(new ChatMessage(MessageRole.USER, "hi"));
    }

    @Test
    @DisplayName("Constructor throws when api_key is missing")
    void testConstructorMissingApiKey() {
        ResourceDescriptor desc =
                connectionDescriptor()
                        .addInitialArgument("api_version", "2024-02-01")
                        .addInitialArgument("azure_endpoint", "https://example.openai.azure.com")
                        .build();
        assertThatThrownBy(() -> new AzureOpenAIChatModelConnection(desc, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("api_key");
    }

    @Test
    @DisplayName("Constructor throws when api_version is missing")
    void testConstructorMissingApiVersion() {
        ResourceDescriptor desc =
                connectionDescriptor()
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("azure_endpoint", "https://example.openai.azure.com")
                        .build();
        assertThatThrownBy(() -> new AzureOpenAIChatModelConnection(desc, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("api_version");
    }

    @Test
    @DisplayName("Constructor throws when azure_endpoint is missing")
    void testConstructorMissingAzureEndpoint() {
        ResourceDescriptor desc =
                connectionDescriptor()
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("api_version", "2024-02-01")
                        .build();
        assertThatThrownBy(() -> new AzureOpenAIChatModelConnection(desc, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("azure_endpoint");
    }

    @Test
    @DisplayName("Constructor succeeds with all required args (no network call yet)")
    void testConstructorAllRequiredArgs() {
        ResourceDescriptor desc =
                connectionDescriptor()
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("api_version", "2024-02-01")
                        .addInitialArgument("azure_endpoint", "https://example.openai.azure.com")
                        .build();
        AzureOpenAIChatModelConnection conn = new AzureOpenAIChatModelConnection(desc, NOOP);
        assertThat(conn).isInstanceOf(BaseChatModelConnection.class);
    }

    @Test
    @DisplayName("chat() rejects additional_kwargs that collide with reserved typed fields")
    void testChatRejectsReservedKeyInAdditionalKwargs() {
        ResourceDescriptor desc =
                connectionDescriptor()
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("api_version", "2024-02-01")
                        .addInitialArgument("azure_endpoint", "https://example.openai.azure.com")
                        .build();
        AzureOpenAIChatModelConnection conn = new AzureOpenAIChatModelConnection(desc, NOOP);

        Map<String, Object> args =
                Map.of(
                        "model",
                        "my-deployment",
                        "temperature",
                        0.3d,
                        "additional_kwargs",
                        Map.of("temperature", 5.0d));

        assertThatThrownBy(
                        () ->
                                conn.chat(
                                        List.of(new ChatMessage(MessageRole.USER, "hi")),
                                        null,
                                        args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("additional_kwargs")
                .hasMessageContaining("temperature");
    }

    @Test
    @DisplayName("A provider error reaches the caller as the SDK exception carrying its payload")
    void testProviderErrorPropagatesUnwrapped() throws IOException {
        try (FakeOpenAIErrorEndpoint endpoint = FakeOpenAIErrorEndpoint.rejectingWith400()) {
            // A loopback endpoint is a custom gateway rather than an *.openai.azure.com resource,
            // so LEGACY is what builds the deployment-scoped Azure request path against it.
            AzureOpenAIChatModelConnection connection =
                    connection(CAPABLE_API_VERSION, endpoint.baseUrl(), "LEGACY");

            assertThatThrownBy(() -> connection.chat(userMessage(), List.of(), params(null), null))
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
    @DisplayName("Native response_format json_schema strict applied for a POJO on a capable model")
    void testNativeAppliedForCapableDeploymentModel() {
        ChatCompletionCreateParams request =
                connection()
                        .buildRequest(
                                userMessage(), List.of(), params("gpt-4o-mini"), Person.class);

        assertThat(request.responseFormat()).isPresent();
        ResponseFormatJsonSchema jsonSchema = request.responseFormat().get().asJsonSchema();
        // The SDK derives the wire name from the class, so it identifies the schema without being
        // equal to the class name.
        assertThat(jsonSchema.jsonSchema().name()).contains("Person");
        assertThat(jsonSchema.jsonSchema().strict()).contains(true);
    }

    @Test
    @DisplayName("A native request still targets the deployment, not the backing model")
    void testCapableNativeRequestStillTargetsTheDeployment() {
        // Capability is keyed on the backing model, but the provider is still addressed by
        // deployment; substituting one for the other would route the call to a deployment that may
        // not exist on the resource.
        ChatCompletionCreateParams request =
                connection()
                        .buildRequest(
                                userMessage(), List.of(), params("gpt-4o-mini"), Person.class);

        assertThat(request.model()).isEqualTo(ChatModel.of(DEPLOYMENT));
    }

    @Test
    @DisplayName("Native NOT applied when the backing model of the deployment is absent")
    void testNativeNotAppliedWhenDeploymentModelAbsent() {
        ChatCompletionCreateParams request =
                connection().buildRequest(userMessage(), List.of(), params(null), Person.class);

        assertThat(request.responseFormat()).isEmpty();
    }

    @Test
    @DisplayName("Native NOT applied for a backing model outside the allowlist")
    void testNativeNotAppliedForUnknownDeploymentModel() {
        ChatCompletionCreateParams request =
                connection()
                        .buildRequest(
                                userMessage(),
                                List.of(),
                                params("some-unknown-model"),
                                Person.class);

        assertThat(request.responseFormat()).isEmpty();
    }

    @Test
    @DisplayName("Native NOT applied for a bare gpt-4o backing model")
    void testNativeNotAppliedForBareGpt4o() {
        // Azure carries model name and model version as separate properties, so a bare gpt-4o may
        // be the 2024-05-13 version, which predates structured-output support.
        ChatCompletionCreateParams request =
                connection().buildRequest(userMessage(), List.of(), params("gpt-4o"), Person.class);

        assertThat(request.responseFormat()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2024-08-01", "2024-10-21"})
    @DisplayName("Native applied for a bare GA date at or above the floor")
    void testNativeAppliedForGaDateAtOrAboveFloor(String apiVersion) {
        // The documented floor is the preview form 2024-08-01-preview, so these pin that a bare GA
        // date carrying no -preview suffix is admitted, and that 2024-08-01 is the inclusive
        // boundary.
        ChatCompletionCreateParams request =
                connection(apiVersion)
                        .buildRequest(
                                userMessage(), List.of(), params("gpt-4o-mini"), Person.class);

        assertThat(request.responseFormat()).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "latest"})
    @DisplayName("Native NOT applied for an api-version outside the documented dated form")
    void testNativeNotAppliedForNonDateApiVersion(String apiVersion) {
        // Every one of these sorts above the floor as a string, so only classifying the dated form
        // keeps them out. The v1 literal in particular does not select Azure's v1 endpoint on the
        // default path mode: it is sent as a query parameter on the deployment-scoped
        // chat/completions path.
        ChatCompletionCreateParams request =
                connection(apiVersion)
                        .buildRequest(
                                userMessage(), List.of(), params("gpt-4o-mini"), Person.class);

        assertThat(request.responseFormat()).isEmpty();
    }

    @Test
    @DisplayName("Native NOT applied when the configured api-version predates the floor")
    void testNativeNotAppliedWhenApiVersionBelowFloor() {
        // An absent api-version cannot reach this gate at all: the constructor rejects it, which
        // testConstructorMissingApiVersion pins.
        ChatCompletionCreateParams request =
                connection(BELOW_FLOOR_API_VERSION)
                        .buildRequest(
                                userMessage(), List.of(), params("gpt-4o-mini"), Person.class);

        assertThat(request.responseFormat()).isEmpty();
    }

    @Test
    @DisplayName("Native NOT applied when no output schema is supplied")
    void testNativeNotAppliedWhenSchemaNull() {
        ChatCompletionCreateParams request =
                connection().buildRequest(userMessage(), List.of(), params("gpt-4o-mini"), null);

        assertThat(request.responseFormat()).isEmpty();
    }

    @Test
    @DisplayName("Native NOT applied for a non-POJO schema form (POJO-only scope)")
    void testNativeNotAppliedForNonPojoSchema() {
        // A RowTypeInfo schema arrives wrapped in OutputSchema rather than as a bare POJO Class, so
        // it must not activate native structured output. OutputSchema cannot be instantiated here
        // because RowTypeInfo is not on this module's classpath; any non-Class schema object
        // exercises the same gate.
        Object nonClassSchema = "row<name STRING>";

        ChatCompletionCreateParams request =
                connection()
                        .buildRequest(
                                userMessage(), List.of(), params("gpt-4o-mini"), nonClassSchema);

        assertThat(request.responseFormat()).isEmpty();
    }

    @Test
    @DisplayName("Native applied for a POJO even when tools are bound")
    void testNativeAppliedEvenWhenToolsBound() {
        // Azure documents structured outputs as unsupported with parallel function calls, which
        // constrains strict tool schemas rather than the response_format this branch sets, so
        // binding tools does not gate it.
        ChatCompletionCreateParams request =
                connection()
                        .buildRequest(
                                userMessage(),
                                List.of(new StubTool()),
                                params("gpt-4o-mini"),
                                Person.class);

        assertThat(request.responseFormat()).isPresent();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "gpt-5.1",
                "gpt-5.1-chat",
                "gpt-5",
                "gpt-5-mini",
                "gpt-5-nano",
                "o3-mini",
                "o1",
                "gpt-4o-mini",
                "gpt-4.1",
                "gpt-4.1-nano",
                "gpt-4.1-mini",
                "o4-mini",
                "o3"
            })
    @DisplayName("Capability predicate accepts every documented capable Azure model name")
    void testCapabilityPredicateAcceptsCapableModels(String model) {
        // The list is the whole allowlist, so dropping an entry is caught rather than only
        // narrowing capability silently.
        assertThat(connection().supportsNativeStructuredOutput(model)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "gpt-4o",
                "gpt-35-turbo",
                "gpt-4",
                "gpt-4o-2024-08-06",
                "some-unknown-model",
                "gpt-5.1-codex",
                "gpt-5.1-codex-mini",
                "gpt-5-pro",
                "gpt-5-codex",
                "codex-mini",
                "o3-pro"
            })
    @DisplayName("Capability predicate rejects incapable, Responses-only, and empty names")
    void testCapabilityPredicateRejectsIncapableModels(String model) {
        // A version-suffixed value such as gpt-4o-2024-08-06 is an OpenAI snapshot name, not a name
        // Azure reports as the model behind a deployment. The codex, gpt-5-pro and o3-pro names do
        // support structured outputs but are served only on the Responses API, so they are
        // incapable on the chat completions API this connection calls.
        assertThat(connection().supportsNativeStructuredOutput(model)).isFalse();
    }

    @Test
    @DisplayName(
            "A caller-supplied response_format alongside a natively applied schema is rejected")
    void testCallerResponseFormatConflictsWithNativeSchema() {
        // Both values would otherwise reach the same request, where the additional body property
        // silently competes with the typed response_format the schema produced.
        AzureOpenAIChatModelConnection conn = connection();
        Map<String, Object> args = paramsWithCallerResponseFormat("gpt-4o-mini");

        assertThatThrownBy(() -> conn.chat(userMessage(), null, args, Person.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("response_format")
                .hasMessageContaining("Person");
    }

    private static Stream<Arguments> nonNativePaths() {
        return Stream.of(
                Arguments.of("incapable_model", CAPABLE_API_VERSION, "gpt-4o", Person.class),
                Arguments.of(
                        "non_pojo_schema", CAPABLE_API_VERSION, "gpt-4o-mini", "row<name STRING>"),
                Arguments.of("no_output_schema", CAPABLE_API_VERSION, "gpt-4o-mini", null),
                Arguments.of(
                        "api_version_below_floor",
                        BELOW_FLOOR_API_VERSION,
                        "gpt-4o-mini",
                        Person.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonNativePaths")
    @DisplayName("A caller-supplied response_format survives wherever native output is skipped")
    void testCallerResponseFormatSurvivesWhenNativeIsSkipped(
            String label, String apiVersion, String modelOfAzureDeployment, Object outputSchema) {
        // Only the branch that actually sends a schema as response_format may reject the caller's
        // own value, so identical caller code has to keep working along every path that skips it,
        // including the no-schema path taken by any caller that drives response_format itself.
        ChatCompletionCreateParams request =
                connection(apiVersion)
                        .buildRequest(
                                userMessage(),
                                List.of(),
                                paramsWithCallerResponseFormat(modelOfAzureDeployment),
                                outputSchema);

        assertThat(request.responseFormat()).isEmpty();
        assertThat(request._additionalBodyProperties())
                .hasEntrySatisfying(
                        "response_format",
                        value ->
                                assertThat(
                                                value.convert(
                                                        new TypeReference<
                                                                Map<String, Object>>() {}))
                                        .isEqualTo(CALLER_RESPONSE_FORMAT));
    }

    @Test
    @DisplayName("Token metrics label the response with the model backing the deployment")
    void testResponseCarriesBackingModelTokenMetrics() {
        // The deployment name is what the request targets, but usage has to be attributed to the
        // model behind it, which is the only name that identifies what actually ran.
        ChatMessage response =
                connection().toResponse(completionWithUsage(11L, 7L), params("gpt-4o-mini"));

        assertThat(response.getExtraArgs())
                .containsEntry("model_name", "gpt-4o-mini")
                .containsEntry("promptTokens", 11L)
                .containsEntry("completionTokens", 7L);
    }

    @Test
    @DisplayName("Response handling leaves the caller's model params intact")
    void testResponseHandlingDoesNotConsumeCallerModelParams() {
        // The backing model is read from the map the caller owns. Consuming the entry would strip
        // token metrics from every later call that reuses the same map.
        Map<String, Object> callerParams = params("gpt-4o-mini");

        connection().toResponse(completionWithUsage(11L, 7L), callerParams);

        assertThat(callerParams).containsEntry("model_of_azure_deployment", "gpt-4o-mini");
    }

    private static Stream<Arguments> incompleteMetricsInputs() {
        return Stream.of(
                Arguments.of("backing_model_unset", completionWithUsage(11L, 7L), params(null)),
                Arguments.of(
                        "completion_without_usage",
                        completionWithoutUsage(),
                        params("gpt-4o-mini")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompleteMetricsInputs")
    @DisplayName("Token metrics are omitted when the backing model or the usage report is absent")
    void testNoTokenMetricsWhenMetricsInputsAreIncomplete(
            String label, ChatCompletion completion, Map<String, Object> modelParams) {
        // Leaving the backing model unset is the documented default, and a completion may arrive
        // without a usage report; both drop the metrics rather than costing the caller the reply.
        ChatMessage response = connection().toResponse(completion, modelParams);

        assertThat(response.getExtraArgs())
                .doesNotContainKeys("model_name", "promptTokens", "completionTokens");
    }

    private static ChatCompletion completionWithUsage(long promptTokens, long completionTokens) {
        return completionBuilder()
                .usage(
                        CompletionUsage.builder()
                                .promptTokens(promptTokens)
                                .completionTokens(completionTokens)
                                .totalTokens(promptTokens + completionTokens)
                                .build())
                .build();
    }

    private static ChatCompletion completionWithoutUsage() {
        return completionBuilder().build();
    }

    private static ChatCompletion.Builder completionBuilder() {
        ChatCompletionMessage message =
                ChatCompletionMessage.builder().content("hi").refusal(Optional.empty()).build();
        return ChatCompletion.builder()
                .id("completion-1")
                .created(0L)
                .model(DEPLOYMENT)
                .addChoice(
                        ChatCompletion.Choice.builder()
                                .finishReason(ChatCompletion.Choice.FinishReason.STOP)
                                .index(0L)
                                .logprobs(Optional.empty())
                                .message(message)
                                .build());
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
