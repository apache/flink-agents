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
import com.openai.core.JsonValue;
import com.openai.models.ChatModel;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.ReasoningEffort;
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

        Integer timeoutSeconds = descriptor.getArgument("timeout");
        if (timeoutSeconds != null && timeoutSeconds > 0) {
            builder.timeout(Duration.ofSeconds(timeoutSeconds));
        }

        Integer maxRetries = descriptor.getArgument("max_retries");
        if (maxRetries != null && maxRetries >= 0) {
            builder.maxRetries(maxRetries);
        }

        Map<String, String> defaultHeaders = descriptor.getArgument("default_headers");
        if (defaultHeaders != null && !defaultHeaders.isEmpty()) {
            for (Map.Entry<String, String> header : defaultHeaders.entrySet()) {
                builder.putHeader(header.getKey(), header.getValue());
            }
        }

        this.defaultModel = descriptor.getArgument("model");
        this.client = builder.build();
    }

    @Override
    public ChatMessage chat(
            List<ChatMessage> messages, List<Tool> tools, Map<String, Object> modelParams) {
        try {
            ChatCompletionCreateParams params = buildRequest(messages, tools, modelParams);
            ChatCompletion completion = client.chat().completions().create(params);
            ChatMessage response =
                    OpenAIChatCompletionsUtils.convertFromOpenAIMessage(
                            completion.choices().get(0).message());

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
        } catch (Exception e) {
            throw new RuntimeException("Failed to call OpenAI chat completions API.", e);
        }
    }

    private ChatCompletionCreateParams buildRequest(
            List<ChatMessage> messages, List<Tool> tools, Map<String, Object> rawModelParams) {
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
