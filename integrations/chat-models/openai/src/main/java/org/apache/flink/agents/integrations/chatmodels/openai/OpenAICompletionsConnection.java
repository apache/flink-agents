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
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonSchemaLocalValidation;
import com.openai.core.JsonValue;
import com.openai.models.ChatModel;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.ReasoningEffort;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A chat model integration for the OpenAI Chat Completions service using the official Java SDK.
 *
 * <p>Supported connection parameters:
 *
 * <ul>
 *   <li><b>api_key</b> (required): OpenAI API key
 *   <li><b>api_base_url</b> (optional): Base URL for OpenAI API (defaults to
 *       https://api.openai.com/v1)
 *   <li><b>timeout</b> (optional): Timeout in seconds for API requests
 *   <li><b>max_retries</b> (optional): Maximum number of retry attempts (default: 2)
 *   <li><b>default_headers</b> (optional): Map of default headers to include in all requests
 *   <li><b>model</b> (optional): Default model to use if not specified in setup
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public class MyAgent extends Agent {
 *   @ChatModelConnection
 *   public static ResourceDesc openAI() {
 *     return ResourceDescriptor.Builder.newBuilder(OpenAICompletionsConnection.class.getName())
 *             .addInitialArgument("api_key", System.getenv("OPENAI_API_KEY"))
 *             .addInitialArgument("api_base_url", "https://api.openai.com/v1")
 *             .addInitialArgument("timeout", 120)
 *             .addInitialArgument("max_retries", 3)
 *             .addInitialArgument("default_headers", Map.of("X-Custom-Header", "value"))
 *             .build();
 *   }
 * }
 * }</pre>
 */
public class OpenAICompletionsConnection extends BaseChatModelConnection {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final OpenAIClient client;
    private final String defaultModel;
    private final Duration timeout;
    private final int maxRetries;

    public OpenAICompletionsConnection(
            ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);

        String apiKey = descriptor.getArgument("api_key");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("api_key should not be null or empty.");
        }

        OpenAIOkHttpClient.Builder builder = new OpenAIOkHttpClient.Builder().apiKey(apiKey);

        String apiBaseUrl = descriptor.getArgument("api_base_url");
        if (apiBaseUrl != null && !apiBaseUrl.isBlank()) {
            builder.baseUrl(apiBaseUrl);
        }

        this.timeout = OpenAIChatCompletionsUtils.parseTimeout(descriptor);
        builder.timeout(OpenAIChatCompletionsUtils.toSdkTimeout(this.timeout));

        this.maxRetries = OpenAIChatCompletionsUtils.parseMaxRetries(descriptor);
        builder.maxRetries(this.maxRetries);

        Map<String, String> defaultHeaders = descriptor.getArgument("default_headers");
        if (defaultHeaders != null && !defaultHeaders.isEmpty()) {
            for (Map.Entry<String, String> header : defaultHeaders.entrySet()) {
                builder.putHeader(header.getKey(), header.getValue());
            }
        }

        this.defaultModel = descriptor.getArgument("model");
        this.client = builder.build();
    }

    // visible for testing
    Duration getTimeout() {
        return timeout;
    }

    // visible for testing
    int getMaxRetries() {
        return maxRetries;
    }

    // Models for which OpenAI documents json_schema strict Structured Outputs support.
    // Source of truth: https://platform.openai.com/docs/guides/structured-outputs
    //
    // A name carrying a non-text modality marker is rejected before anything else. Audio, realtime,
    // speech and transcription variants expose no json_schema response format even though they
    // share the name prefix of a capable text family, so the marker check has to win over a prefix
    // match rather than merely coexist with it.
    //
    // A text family whose entire lifetime post-dates the Structured Outputs cutoff is matched by
    // name prefix, so its dated snapshots and size variants resolve without enumerating each one.
    //
    // Two cases cannot use a prefix and are matched exactly instead. The gpt-4o family straddles
    // the cutoff — gpt-4o-2024-05-13 predates it and is not capable — so the boundary there is
    // temporal rather than nominal. The o1 family is not uniform: o1 is capable while o1-mini is
    // not, so an "o1" prefix would admit an incapable sibling.
    //
    // A name outside every listed family reports not-capable and degrades to the prompt fallback
    // rather than failing at the provider. Within a listed family the prefix assumes capability,
    // so a family variant that ships without json_schema support has to be excluded explicitly,
    // either by a marker that appears in no capable name or by replacing the family prefix with
    // exact names.
    private static final Set<String> NON_TEXT_MODALITY_MARKERS =
            Set.of("-audio", "-realtime", "-tts", "-transcribe");
    private static final Set<String> NATIVE_STRUCTURED_OUTPUT_FAMILY_PREFIXES =
            Set.of("gpt-4o-mini", "gpt-4o-search-preview", "gpt-4.1", "gpt-5", "o3", "o4-mini");
    private static final Set<String> NATIVE_STRUCTURED_OUTPUT_MODELS =
            Set.of("gpt-4o", "gpt-4o-2024-08-06", "gpt-4o-2024-11-20", "o1", "o1-2024-12-17");

    @Override
    protected boolean supportsNativeStructuredOutput(String effectiveModel) {
        if (effectiveModel == null) {
            return false;
        }
        if (NON_TEXT_MODALITY_MARKERS.stream().anyMatch(effectiveModel::contains)) {
            return false;
        }
        return NATIVE_STRUCTURED_OUTPUT_FAMILY_PREFIXES.stream()
                        .anyMatch(effectiveModel::startsWith)
                || NATIVE_STRUCTURED_OUTPUT_MODELS.contains(effectiveModel);
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
     * Returns the model response. When the provider reports a finish reason it is carried verbatim
     * in {@code extraArgs} under {@code finish_reason}, including values outside the documented
     * set, and the entry is absent when the provider reports none.
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
        ChatCompletion completion = client.chat().completions().create(params);
        ChatCompletion.Choice choice = completion.choices().get(0);
        ChatMessage response =
                OpenAIChatCompletionsUtils.convertFromOpenAIMessage(choice.message());

        // ChatCompletion.Choice#finishReason throws OpenAIInvalidDataException when the member is
        // absent or null, so the value is read through the raw field.
        choice._finishReason()
                .asKnown()
                .ifPresent(
                        reason -> response.getExtraArgs().put("finish_reason", reason.asString()));

        // Stash token usage
        if (completion.usage().isPresent()) {
            String modelName = modelParams != null ? (String) modelParams.get("model") : null;
            if (modelName == null || modelName.isBlank()) {
                modelName = this.defaultModel;
            }
            if (modelName != null && !modelName.isBlank()) {
                response.getExtraArgs().put("model_name", modelName);
                response.getExtraArgs()
                        .put("promptTokens", completion.usage().get().promptTokens());
                response.getExtraArgs()
                        .put("completionTokens", completion.usage().get().completionTokens());
            }
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
        Map<String, Object> modelParams =
                rawModelParams != null ? new HashMap<>(rawModelParams) : new HashMap<>();

        boolean strictMode = Boolean.TRUE.equals(modelParams.remove("strict"));
        String modelName = (String) modelParams.remove("model");
        if (modelName == null || modelName.isBlank()) {
            modelName = this.defaultModel;
        }

        ChatCompletionCreateParams.Builder builder =
                ChatCompletionCreateParams.builder()
                        .model(ChatModel.of(modelName))
                        .messages(OpenAIChatCompletionsUtils.convertToOpenAIMessages(messages));

        if (tools != null && !tools.isEmpty()) {
            builder.tools(convertTools(tools, strictMode));
        }

        // Native structured output applies only for a POJO Class schema on a model the provider
        // documents as capable; a RowTypeInfo (wrapped in OutputSchema) or an incapable model keeps
        // the prompt-engineering fallback.
        //
        // TODO(#912): the requested strategy is not visible here, so this re-check cannot tell an
        // explicit NATIVE request apart from one that merely resolved to native. A caller asking
        // for NATIVE on a model this predicate rejects therefore gets an unconstrained response
        // instead of an error. Once strategy resolution is wired up, NATIVE must either bypass
        // this capability re-check or fail explicitly.
        if (outputSchema instanceof Class && supportsNativeStructuredOutput(modelName)) {
            builder.responseFormat(toNativeResponseFormat((Class<?>) outputSchema));
        }

        Object temperature = modelParams.remove("temperature");
        if (temperature instanceof Number) {
            builder.temperature(((Number) temperature).doubleValue());
        }

        Object maxTokens = modelParams.remove("max_tokens");
        if (maxTokens instanceof Number) {
            builder.maxCompletionTokens(((Number) maxTokens).longValue());
        }

        Object logprobs = modelParams.remove("logprobs");
        boolean logprobsEnabled = Boolean.TRUE.equals(logprobs);
        if (logprobsEnabled) {
            builder.logprobs(true);
            Object topLogprobs = modelParams.remove("top_logprobs");
            if (topLogprobs instanceof Number) {
                builder.topLogprobs(((Number) topLogprobs).longValue());
            }
        } else {
            modelParams.remove("top_logprobs");
        }

        Object reasoningEffort = modelParams.remove("reasoning_effort");
        if (reasoningEffort instanceof String) {
            builder.reasoningEffort(ReasoningEffort.of((String) reasoningEffort));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> additionalKwargs =
                (Map<String, Object>) modelParams.remove("additional_kwargs");
        if (additionalKwargs != null) {
            additionalKwargs.forEach(
                    (key, value) -> builder.putAdditionalBodyProperty(key, toJsonValue(value)));
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

    private List<ChatCompletionTool> convertTools(List<Tool> tools, boolean strictMode) {
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

            if (strictMode) {
                functionBuilder.strict(true);
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

    @Override
    public void close() throws Exception {
        this.client.close();
    }
}
