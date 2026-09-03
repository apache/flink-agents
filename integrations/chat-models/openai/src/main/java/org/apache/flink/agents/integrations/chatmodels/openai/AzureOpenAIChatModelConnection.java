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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.azure.AzureOpenAIServiceVersion;
import com.openai.azure.AzureUrlPathMode;
import com.openai.azure.credential.AzureApiKeyCredential;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonSchemaLocalValidation;
import com.openai.core.JsonValue;
import com.openai.models.ChatModel;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionTool;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Chat model integration for Azure OpenAI Service. Built on the openai-java SDK using its built-in
 * Azure support ({@link AzureOpenAIServiceVersion}, {@link AzureApiKeyCredential}).
 *
 * <p>Required connection arguments:
 *
 * <ul>
 *   <li><b>api_key</b>: Azure OpenAI API key
 *   <li><b>api_version</b>: Azure OpenAI REST API version (e.g., {@code "2024-02-01"})
 *   <li><b>azure_endpoint</b>: base URL for the Azure OpenAI deployment — either a direct Azure
 *       resource (e.g., {@code "https://your-resource.openai.azure.com"}) or a proxy/gateway URL
 *       that fronts an Azure OpenAI service. Custom gateway hostnames also require setting {@code
 *       azure_url_path_mode} below.
 * </ul>
 *
 * <p>Optional connection arguments:
 *
 * <ul>
 *   <li><b>timeout</b> (Number): seconds before an API call times out; must be greater than 0,
 *       otherwise ignored (SDK default applies)
 *   <li><b>max_retries</b> (Number): retry attempts on failure; must be non-negative, otherwise
 *       ignored (SDK default applies)
 *   <li><b>azure_url_path_mode</b> (String): one of {@code "AUTO"}, {@code "LEGACY"}, or {@code
 *       "UNIFIED"} (default {@code "AUTO"}). Controls how the SDK constructs Azure OpenAI request
 *       URLs. In {@code AUTO} mode the SDK only treats the endpoint as Azure when its hostname
 *       matches a known suffix (e.g. {@code .openai.azure.com}); custom gateways that proxy Azure
 *       OpenAI need {@code LEGACY} to force the {@code /openai/deployments/{model}} path.
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * @ChatModelConnection
 * public static ResourceDescriptor azureOpenAIConnection() {
 *   return ResourceDescriptor.Builder.newBuilder(
 *               AzureOpenAIChatModelConnection.class.getName())
 *           .addInitialArgument("api_key", System.getenv("AZURE_OPENAI_API_KEY"))
 *           .addInitialArgument("api_version", "2024-02-01")
 *           .addInitialArgument("azure_endpoint", "https://my-resource.openai.azure.com")
 *           .build();
 * }
 * }</pre>
 */
public class AzureOpenAIChatModelConnection extends BaseChatModelConnection {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final Set<String> RESERVED_KWARG_KEYS =
            Set.of("model", "model_of_azure_deployment", "temperature", "max_tokens", "logprobs");

    // Models that both have documented json_schema strict Structured Outputs support and are served
    // on the Chat Completions API, which is the API this connection calls. The set is that
    // intersection, taken from two sources:
    // https://learn.microsoft.com/en-us/azure/ai-foundry/openai/how-to/structured-outputs lists the
    // models supporting Structured Outputs on any API, and
    // https://learn.microsoft.com/en-us/azure/ai-foundry/openai/how-to/reasoning carries the
    // per-model feature table whose "Chat Completions API" row excludes the models Azure serves
    // only on the Responses API.
    //
    // Matching is exact, never by prefix: Azure exposes a deployment's model name and model version
    // as separate properties, so a name carries no version to discriminate on. The documented list
    // includes gpt-4o only at versions 2024-08-06 and 2024-11-20 while version 2024-05-13 is
    // unsupported, so a bare "gpt-4o" is ambiguous and is deliberately absent from the set below.
    // An unrecognized name reports not-capable and degrades to the prompt fallback rather than
    // failing at the provider.
    private static final Set<String> NATIVE_STRUCTURED_OUTPUT_MODELS =
            Set.of(
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
                    "o3");

    // Date prefix of 2024-08-01-preview, the earliest api-version Azure documents as supporting
    // structured outputs.
    private static final String MIN_STRUCTURED_OUTPUT_API_VERSION = "2024-08-01";

    // Leading zero-padded YYYY-MM-DD date of the api-version form Azure documents, which is a date
    // optionally carrying a suffix such as -preview.
    private static final Pattern API_VERSION_DATE_PREFIX = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}");

    private final OpenAIClient client;
    private final Duration timeout;
    private final int maxRetries;

    private final String apiVersion;

    public AzureOpenAIChatModelConnection(
            ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);

        String apiKey = descriptor.getArgument("api_key");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("api_key should not be null or empty.");
        }

        String apiVersion = descriptor.getArgument("api_version");
        if (apiVersion == null || apiVersion.isBlank()) {
            throw new IllegalArgumentException("api_version should not be null or empty.");
        }
        this.apiVersion = apiVersion;

        String azureEndpoint = descriptor.getArgument("azure_endpoint");
        if (azureEndpoint == null || azureEndpoint.isBlank()) {
            throw new IllegalArgumentException("azure_endpoint should not be null or empty.");
        }

        OpenAIOkHttpClient.Builder clientBuilder =
                OpenAIOkHttpClient.builder()
                        .baseUrl(azureEndpoint)
                        .credential(AzureApiKeyCredential.create(apiKey))
                        .azureServiceVersion(AzureOpenAIServiceVersion.fromString(apiVersion));

        this.timeout = OpenAIChatCompletionsUtils.parseTimeout(descriptor);
        clientBuilder.timeout(OpenAIChatCompletionsUtils.toSdkTimeout(this.timeout));

        this.maxRetries = OpenAIChatCompletionsUtils.parseMaxRetries(descriptor);
        clientBuilder.maxRetries(this.maxRetries);

        String azureUrlPathMode = descriptor.getArgument("azure_url_path_mode");
        if (azureUrlPathMode != null && !azureUrlPathMode.isBlank()) {
            try {
                clientBuilder.azureUrlPathMode(
                        AzureUrlPathMode.valueOf(azureUrlPathMode.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "azure_url_path_mode must be one of AUTO, LEGACY, or UNIFIED; got: "
                                + azureUrlPathMode,
                        e);
            }
        }

        this.client = clientBuilder.build();
    }

    // visible for testing
    Duration getTimeout() {
        return timeout;
    }

    // visible for testing
    int getMaxRetries() {
        return maxRetries;
    }

    /**
     * Whether Azure documents json_schema strict support for {@code effectiveModel}.
     *
     * <p>{@code effectiveModel} is the model backing an Azure deployment, not the deployment name.
     * See the allowlist above for the source of truth and for why the match is exact. An
     * unrecognized model reports {@code false} so it degrades to the prompt-engineering fallback
     * rather than failing at the provider.
     *
     * <p>Reads no instance state, so capability stays answerable independently of how the
     * connection was configured.
     */
    @Override
    protected boolean supportsNativeStructuredOutput(String effectiveModel) {
        if (effectiveModel == null || effectiveModel.isEmpty()) {
            return false;
        }
        return NATIVE_STRUCTURED_OUTPUT_MODELS.contains(effectiveModel);
    }

    /**
     * Whether the configured api-version reaches the structured-output floor.
     *
     * <p>Azure documents {@code 2024-08-01-preview} as the first api-version supporting structured
     * outputs, and whether an older version rejects {@code response_format} or silently ignores it
     * is not documented. The request therefore never carries {@code response_format} below the
     * floor, which is safe under either behavior.
     *
     * <p>Only the documented api-version form is classified, a zero-padded {@code YYYY-MM-DD} date
     * optionally suffixed {@code -preview}; over that form comparing the leading date
     * lexicographically is exact. A value of any other shape, including the GA {@code v1} literal,
     * reports {@code false} and keeps the prompt fallback. That is the right answer for {@code v1}
     * under the default {@code AUTO} path mode against a resource endpoint, where the request is
     * built on the deployment-scoped path {@code /openai/deployments/{deployment}/chat/completions}
     * with the api-version carried as a query parameter, so the literal is sent as {@code
     * ?api-version=v1} rather than selecting Azure's {@code /openai/v1} endpoint. Under {@code
     * UNIFIED}, or an endpoint already ending in {@code /openai/v1}, the request does reach the
     * unified endpoint, and reporting not-capable there costs only the prompt fallback. The
     * constructor rejects a null or blank api-version, so no value of that shape reaches here.
     */
    private boolean apiVersionSupportsStructuredOutput() {
        if (!API_VERSION_DATE_PREFIX.matcher(apiVersion).lookingAt()) {
            return false;
        }
        return apiVersion
                        .substring(0, MIN_STRUCTURED_OUTPUT_API_VERSION.length())
                        .compareTo(MIN_STRUCTURED_OUTPUT_API_VERSION)
                >= 0;
    }

    /**
     * Returns the model response. When the provider reports a finish reason it is carried verbatim
     * in {@code extraArgs} under {@code finish_reason}, including values outside the documented
     * set, and the entry is absent when the provider reports none.
     */
    @Override
    public ChatMessage chat(
            List<ChatMessage> messages, List<Tool> tools, Map<String, Object> modelParams) {
        return doChat(messages, tools, modelParams, null);
    }

    /**
     * Translates {@code outputSchema} into Azure's native strict {@code response_format}
     * json_schema when it is a POJO {@link Class}, the model backing the deployment is one Azure
     * documents json_schema strict support for, and the configured api-version reaches {@code
     * 2024-08-01-preview}. Any other combination leaves the request unconstrained so that the
     * prompt-engineering fallback still governs the response, rather than failing at the provider.
     *
     * <p>Capability is keyed on the {@code model_of_azure_deployment} model parameter rather than
     * on the deployment the request targets, because a deployment name is chosen by the user and
     * carries no model information. Leaving that parameter unset therefore keeps even a capable
     * deployment on the fallback.
     *
     * <p>When the provider reports a finish reason it is carried verbatim in {@code extraArgs}
     * under {@code finish_reason}, including values outside the documented set, and the entry is
     * absent when the provider reports none.
     *
     * @throws IllegalArgumentException if the schema is applied natively while {@code
     *     additional_kwargs} also carries a {@code response_format}, since the two would otherwise
     *     compete on the same request
     */
    @Override
    public ChatMessage chat(
            List<ChatMessage> messages,
            List<Tool> tools,
            Map<String, Object> modelParams,
            Object outputSchema) {
        return doChat(messages, tools, modelParams, outputSchema);
    }

    private ChatMessage doChat(
            List<ChatMessage> messages,
            List<Tool> tools,
            Map<String, Object> modelParams,
            Object outputSchema) {
        ChatCompletionCreateParams params =
                buildRequest(messages, tools, modelParams, outputSchema);
        return toResponse(client.chat().completions().create(params), modelParams);
    }

    // Package-private so response handling can be asserted against a constructed completion without
    // issuing a live API call through the final OpenAI client.
    ChatMessage toResponse(ChatCompletion completion, Map<String, Object> modelParams) {
        // Read from the caller's map rather than the copy buildRequest consumed, and read without
        // consuming: a caller may reuse the same map across calls. The map is assembled fresh for
        // each call and no one retains it, so reading it once the response has arrived yields the
        // same value as reading it before the request was issued. Token metrics report the model
        // backing the deployment, which buildRequest only uses to decide capability.
        String modelOfAzureDeployment =
                modelParams != null ? (String) modelParams.get("model_of_azure_deployment") : null;

        ChatCompletion.Choice choice = completion.choices().get(0);
        ChatMessage response =
                OpenAIChatCompletionsUtils.convertFromOpenAIMessage(choice.message());

        // ChatCompletion.Choice#finishReason throws OpenAIInvalidDataException when the member is
        // absent or null, so the value is read through the raw field.
        choice._finishReason()
                .asKnown()
                .ifPresent(
                        reason -> response.getExtraArgs().put("finish_reason", reason.asString()));

        if (modelOfAzureDeployment != null
                && !modelOfAzureDeployment.isBlank()
                && completion.usage().isPresent()) {
            response.getExtraArgs().put("model_name", modelOfAzureDeployment);
            response.getExtraArgs().put("promptTokens", completion.usage().get().promptTokens());
            response.getExtraArgs()
                    .put("completionTokens", completion.usage().get().completionTokens());
        }

        return response;
    }

    // Package-private so the request body (including the native response_format) can be asserted
    // without issuing a live API call through the final OpenAI client.
    ChatCompletionCreateParams buildRequest(
            List<ChatMessage> messages,
            List<Tool> tools,
            Map<String, Object> rawModelParams,
            Object outputSchema) {
        Map<String, Object> mutableArgs =
                rawModelParams != null ? new HashMap<>(rawModelParams) : new HashMap<>();

        String azureDeployment = (String) mutableArgs.remove("model");
        if (azureDeployment == null || azureDeployment.isBlank()) {
            throw new IllegalArgumentException("model is required for Azure OpenAI API calls");
        }
        String modelOfAzureDeployment = (String) mutableArgs.remove("model_of_azure_deployment");

        ChatCompletionCreateParams.Builder builder =
                ChatCompletionCreateParams.builder()
                        .model(ChatModel.of(azureDeployment))
                        .messages(OpenAIChatCompletionsUtils.convertToOpenAIMessages(messages));

        if (tools != null && !tools.isEmpty()) {
            builder.tools(convertTools(tools));
        }

        // Capability belongs to the model backing the deployment, so it is the input to the check;
        // the deployment name is chosen by the user and carries none. Native structured output
        // applies only for a POJO Class schema — a RowTypeInfo (wrapped in OutputSchema) keeps the
        // prompt-engineering fallback, as do an incapable model and an api-version below the floor.
        //
        // TODO(#912): the requested strategy is not visible here, so this re-check cannot tell an
        // explicit NATIVE request apart from one that merely resolved to native. A caller asking
        // for NATIVE therefore gets an unconstrained response instead of an error whenever this
        // branch is skipped, which on Azure also happens when the api-version is below the floor
        // or when model_of_azure_deployment is unset and capability cannot be resolved at all.
        // Once strategy resolution is wired up, NATIVE must either bypass this re-check or fail
        // explicitly.
        String nativeSchemaName = null;
        if (outputSchema instanceof Class
                && supportsNativeStructuredOutput(modelOfAzureDeployment)
                && apiVersionSupportsStructuredOutput()) {
            Class<?> schemaClass = (Class<?>) outputSchema;
            builder.responseFormat(toNativeResponseFormat(schemaClass));
            nativeSchemaName = schemaClass.getSimpleName();
        }

        Object temperature = mutableArgs.remove("temperature");
        if (temperature instanceof Number) {
            builder.temperature(((Number) temperature).doubleValue());
        }

        Object maxTokens = mutableArgs.remove("max_tokens");
        if (maxTokens instanceof Number) {
            builder.maxCompletionTokens(((Number) maxTokens).longValue());
        }

        Object logprobs = mutableArgs.remove("logprobs");
        if (Boolean.TRUE.equals(logprobs)) {
            builder.logprobs(true);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> additionalKwargs =
                (Map<String, Object>) mutableArgs.remove("additional_kwargs");
        if (additionalKwargs != null) {
            Set<String> collisions = new HashSet<>(additionalKwargs.keySet());
            collisions.retainAll(RESERVED_KWARG_KEYS);
            if (!collisions.isEmpty()) {
                throw new IllegalArgumentException(
                        "additional_kwargs must not contain reserved typed fields: "
                                + collisions
                                + ". Set these via the corresponding Setup field instead.");
            }
            // Only the branch that actually sent a schema may reject the caller's own
            // response_format; every path that skipped it leaves the value untouched.
            if (nativeSchemaName != null && additionalKwargs.containsKey("response_format")) {
                throw new IllegalArgumentException(
                        "The "
                                + nativeSchemaName
                                + " output schema is sent as response_format on deployment '"
                                + azureDeployment
                                + "', so response_format must not also be set in additional_kwargs."
                                + " Remove that value, or omit the output schema to set"
                                + " response_format directly.");
            }
            for (Map.Entry<String, Object> entry : additionalKwargs.entrySet()) {
                builder.putAdditionalBodyProperty(entry.getKey(), toJsonValue(entry.getValue()));
            }
        }

        return builder.build();
    }

    // Derives the strict json_schema response format from a POJO class via the SDK's typed
    // structured-output builder. The Kotlin-facade StructuredOutputsKt.responseFormatFromClass is
    // not callable from Java, so the response format is extracted through the typed builder, which
    // generates the same strict draft-2020-12 schema, and then reattached to the standard builder.
    private static <T> ResponseFormatJsonSchema toNativeResponseFormat(Class<T> schemaClass) {
        return ChatCompletionCreateParams.builder()
                .model(ChatModel.of(""))
                .addUserMessage("")
                .responseFormat(schemaClass, JsonSchemaLocalValidation.NO)
                .build()
                .rawParams()
                .responseFormat()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "OpenAI SDK did not produce a response_format for schema "
                                                + schemaClass.getName()))
                .asJsonSchema();
    }

    @Override
    public void close() throws Exception {
        this.client.close();
    }

    private List<ChatCompletionTool> convertTools(List<Tool> tools) {
        List<ChatCompletionTool> openaiTools = new ArrayList<>(tools.size());
        for (Tool tool : tools) {
            ToolMetadata metadata = tool.getMetadata();
            FunctionDefinition.Builder functionBuilder =
                    FunctionDefinition.builder()
                            .name(metadata.getName())
                            .description(metadata.getDescription());

            String schema = metadata.getInputSchema();
            if (schema != null && !schema.isBlank()) {
                functionBuilder.parameters(parseFunctionParameters(schema));
            }

            ChatCompletionFunctionTool functionTool =
                    ChatCompletionFunctionTool.builder()
                            .function(functionBuilder.build())
                            .type(JsonValue.from("function"))
                            .build();

            openaiTools.add(ChatCompletionTool.ofFunction(functionTool));
        }
        return openaiTools;
    }

    private FunctionParameters parseFunctionParameters(String schemaJson) {
        try {
            JsonNode root = mapper.readTree(schemaJson);
            if (root == null || !root.isObject()) {
                return FunctionParameters.builder().build();
            }
            FunctionParameters.Builder builder = FunctionParameters.builder();
            root.fields()
                    .forEachRemaining(
                            entry ->
                                    builder.putAdditionalProperty(
                                            entry.getKey(),
                                            JsonValue.fromJsonNode(entry.getValue())));
            return builder.build();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse tool schema JSON.", e);
        }
    }

    private JsonValue toJsonValue(Object value) {
        if (value instanceof JsonValue) {
            return (JsonValue) value;
        }
        if (value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value == null) {
            return JsonValue.from(value);
        }
        return JsonValue.fromJsonNode(mapper.valueToTree(value));
    }
}
