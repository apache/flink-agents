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
import com.fasterxml.jackson.core.type.TypeReference;
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
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

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
 *     return ResourceDescriptor.Builder.newBuilder(OpenAIChatModelConnection.class.getName())
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
public class OpenAIChatModelConnection extends BaseChatModelConnection {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenAIClient client;
    private final String defaultModel;

    public OpenAIChatModelConnection(
            ResourceDescriptor descriptor, BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);

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
            List<ChatMessage> messages, List<Tool> tools, Map<String, Object> arguments) {
        try {
            ChatCompletionCreateParams params = buildRequest(messages, tools, arguments);
            ChatCompletion completion = client.chat().completions().create(params);
            ChatMessage response = convertResponse(completion);

            // Record token metrics
            if (completion.usage().isPresent()) {
                String modelName = arguments != null ? (String) arguments.get("model") : null;
                if (modelName == null || modelName.isBlank()) {
                    modelName = this.defaultModel;
                }
                if (modelName != null && !modelName.isBlank()) {
                    recordTokenMetrics(
                            modelName,
                            completion.usage().get().promptTokens(),
                            completion.usage().get().completionTokens());
                }
            }

            return response;
        } catch (Exception e) {
            throw new RuntimeException("Failed to call OpenAI chat completions API.", e);
        }
    }

    private ChatCompletionCreateParams buildRequest(
            List<ChatMessage> messages, List<Tool> tools, Map<String, Object> rawArguments) {
        Map<String, Object> arguments =
                rawArguments != null ? new HashMap<>(rawArguments) : new HashMap<>();

        boolean strictMode = Boolean.TRUE.equals(arguments.remove("strict"));
        String modelName = (String) arguments.remove("model");
        if (modelName == null || modelName.isBlank()) {
            modelName = this.defaultModel;
        }

        ChatCompletionCreateParams.Builder builder =
                ChatCompletionCreateParams.builder()
                        .model(ChatModel.of(modelName))
                        .messages(
                                messages.stream()
                                        .map(this::convertToOpenAIMessage)
                                        .collect(Collectors.toList()));

        if (tools != null && !tools.isEmpty()) {
            builder.tools(convertTools(tools, strictMode));
        }

        Object temperature = arguments.remove("temperature");
        if (temperature instanceof Number) {
            builder.temperature(((Number) temperature).doubleValue());
        }

        Object maxTokens = arguments.remove("max_tokens");
        if (maxTokens instanceof Number) {
            builder.maxCompletionTokens(((Number) maxTokens).longValue());
        }

        Object logprobs = arguments.remove("logprobs");
        boolean logprobsEnabled = Boolean.TRUE.equals(logprobs);
        if (logprobsEnabled) {
            builder.logprobs(true);
            Object topLogprobs = arguments.remove("top_logprobs");
            if (topLogprobs instanceof Number) {
                builder.topLogprobs(((Number) topLogprobs).longValue());
            }
        } else {
            arguments.remove("top_logprobs");
        }

        Object reasoningEffort = arguments.remove("reasoning_effort");
        if (reasoningEffort instanceof String) {
            builder.reasoningEffort(ReasoningEffort.of((String) reasoningEffort));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> additionalKwargs =
                (Map<String, Object>) arguments.remove("additional_kwargs");
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

    private ChatCompletionMessageParam convertToOpenAIMessage(ChatMessage message) {
        MessageRole role = message.getRole();
        String content = Optional.ofNullable(message.getContent()).orElse("");

        switch (role) {
            case SYSTEM:
                return ChatCompletionMessageParam.ofSystem(
                        ChatCompletionSystemMessageParam.builder().content(content).build());
            case USER:
                return ChatCompletionMessageParam.ofUser(
                        ChatCompletionUserMessageParam.builder().content(content).build());
            case ASSISTANT:
                ChatCompletionAssistantMessageParam.Builder assistantBuilder =
                        ChatCompletionAssistantMessageParam.builder();
                if (!content.isEmpty()) {
                    assistantBuilder.content(content);
                }
                List<Map<String, Object>> toolCalls = message.getToolCalls();
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    assistantBuilder.toolCalls(convertAssistantToolCalls(toolCalls));
                }
                Object refusal = message.getExtraArgs().get("refusal");
                if (refusal instanceof String) {
                    assistantBuilder.refusal((String) refusal);
                }
                return ChatCompletionMessageParam.ofAssistant(assistantBuilder.build());
            case TOOL:
                ChatCompletionToolMessageParam.Builder toolBuilder =
                        ChatCompletionToolMessageParam.builder().content(content);
                Object toolCallId = message.getExtraArgs().get("externalId");
                if (toolCallId == null) {
                    throw new IllegalArgumentException(
                            "Tool message must have an externalId in extraArgs.");
                }
                toolBuilder.toolCallId(toolCallId.toString());
                return ChatCompletionMessageParam.ofTool(toolBuilder.build());
            default:
                throw new IllegalArgumentException("Unsupported role: " + role);
        }
    }

    private List<ChatCompletionMessageToolCall> convertAssistantToolCalls(
            List<Map<String, Object>> toolCalls) {
        List<ChatCompletionMessageToolCall> result = new ArrayList<>(toolCalls.size());
        for (Map<String, Object> call : toolCalls) {
            Object type = call.getOrDefault("type", "function");
            if (!"function".equals(String.valueOf(type))) {
                continue;
            }

            Map<String, Object> functionPayload = toMap(call.get("function"));
            ChatCompletionMessageFunctionToolCall.Function.Builder functionBuilder =
                    ChatCompletionMessageFunctionToolCall.Function.builder();

            Object functionName = functionPayload.get("name");
            if (functionName != null) {
                functionBuilder.name(functionName.toString());
            }

            Object arguments = functionPayload.get("arguments");
            functionBuilder.arguments(serializeArguments(arguments));

            Object idObj = call.get("id");
            if (idObj == null) {
                throw new IllegalArgumentException("Tool call must have an id.");
            }
            String toolCallId = idObj.toString();

            ChatCompletionMessageFunctionToolCall.Builder toolCallBuilder =
                    ChatCompletionMessageFunctionToolCall.builder()
                            .id(toolCallId)
                            .function(functionBuilder.build())
                            .type(JsonValue.from(String.valueOf(type)));

            result.add(ChatCompletionMessageToolCall.ofFunction(toolCallBuilder.build()));
        }
        return result;
    }

    private ChatMessage convertResponse(ChatCompletion completion) {
        List<ChatCompletion.Choice> choices = completion.choices();
        if (choices.isEmpty()) {
            throw new IllegalStateException("OpenAI response did not contain any choices.");
        }

        ChatCompletionMessage message = choices.get(0).message();
        String content = message.content().orElse("");
        ChatMessage response = ChatMessage.assistant(content);

        message.refusal().ifPresent(refusal -> response.getExtraArgs().put("refusal", refusal));

        List<ChatCompletionMessageToolCall> toolCalls = message.toolCalls().orElse(List.of());
        if (!toolCalls.isEmpty()) {
            response.setToolCalls(convertResponseToolCalls(toolCalls));
        }

        return response;
    }

    private List<Map<String, Object>> convertResponseToolCalls(
            List<ChatCompletionMessageToolCall> toolCalls) {
        List<Map<String, Object>> result = new ArrayList<>(toolCalls.size());
        for (ChatCompletionMessageToolCall toolCall : toolCalls) {
            if (!toolCall.isFunction()) {
                continue;
            }

            ChatCompletionMessageFunctionToolCall functionToolCall = toolCall.asFunction();
            Map<String, Object> callMap = new LinkedHashMap<>();
            String toolCallId = functionToolCall.id();
            if (toolCallId == null || toolCallId.isBlank()) {
                throw new IllegalStateException("OpenAI tool call ID is null or empty.");
            }

            callMap.put("id", toolCallId);
            callMap.put("type", "function");

            ChatCompletionMessageFunctionToolCall.Function function = functionToolCall.function();
            Map<String, Object> functionMap = new LinkedHashMap<>();
            functionMap.put("name", function.name());
            functionMap.put("arguments", parseArguments(function.arguments()));
            callMap.put("function", functionMap);
            callMap.put("original_id", toolCallId);
            result.add(callMap);
        }
        return result;
    }

    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(arguments, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse tool arguments: " + arguments, e);
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

    private String serializeArguments(Object arguments) {
        if (arguments == null) {
            return "{}";
        }
        if (arguments instanceof String) {
            return (String) arguments;
        }
        try {
            return mapper.writeValueAsString(arguments);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize tool call arguments.", e);
        }
    }

    private Map<String, Object> toMap(Object value) {
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> casted = (Map<String, Object>) value;
            return new LinkedHashMap<>(casted);
        }
        if (value == null) {
            return new LinkedHashMap<>();
        }
        return mapper.convertValue(value, MAP_TYPE);
    }
}
