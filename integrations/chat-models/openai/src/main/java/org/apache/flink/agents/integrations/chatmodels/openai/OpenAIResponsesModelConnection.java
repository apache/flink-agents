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
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.*;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.ToolMetadata;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * A <b>dedicated</b> OpenAI chat model integration using the Responses API.
 *
 * <p>Unlike {@link OpenAICompletionsConnection} which uses the Chat Completions API and works with
 * any OpenAI-compatible provider (DeepSeek, DashScope, etc.), this implementation uses OpenAI's
 * Responses API which is specific to OpenAI.
 *
 * <p>For OpenAI-compatible providers that only support the Chat Completions API, use {@link
 * OpenAICompletionsConnection} instead.
 *
 * <p>Supported connection parameters:
 *
 * <ul>
 *   <li><b>api_key</b> (required): OpenAI API key
 *   <li><b>api_base_url</b> (optional): Base URL for OpenAI API (useful for proxies)
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
 *   public static ResourceDesc openAIResponses() {
 *     return ResourceDescriptor.Builder.newBuilder(OpenAIResponsesModelConnection.class.getName())
 *             .addInitialArgument("api_key", System.getenv("OPENAI_API_KEY"))
 *             .addInitialArgument("timeout", 120)
 *             .addInitialArgument("max_retries", 3)
 *             .build();
 *   }
 * }
 * }</pre>
 */
public class OpenAIResponsesModelConnection extends BaseChatModelConnection {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final ObjectMapper mapper = new ObjectMapper();

    private final OpenAIClient client;
    private final String defaultModel;

    public OpenAIResponsesModelConnection(
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
            List<ChatMessage> messages,
            List<org.apache.flink.agents.api.tools.Tool> tools,
            Map<String, Object> arguments) {
        try {
            ResponseCreateParams params = buildRequest(messages, tools, arguments);
            Response response = client.responses().create(params);
            ChatMessage result = convertResponse(response);

            if (response.usage().isPresent()) {
                String modelName = arguments != null ? (String) arguments.get("model") : null;
                if (modelName == null || modelName.isBlank()) {
                    modelName = this.defaultModel;
                }
                if (modelName != null && !modelName.isBlank()) {
                    recordTokenMetrics(
                            modelName,
                            response.usage().get().inputTokens(),
                            response.usage().get().outputTokens());
                }
            }

            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to call OpenAI Responses API.", e);
        }
    }

    private ResponseCreateParams buildRequest(
            List<ChatMessage> messages,
            List<org.apache.flink.agents.api.tools.Tool> tools,
            Map<String, Object> rawArguments) {
        Map<String, Object> arguments =
                rawArguments != null ? new HashMap<>(rawArguments) : new HashMap<>();

        boolean strictMode = Boolean.TRUE.equals(arguments.remove("strict"));
        String modelName = (String) arguments.remove("model");
        if (modelName == null || modelName.isBlank()) {
            modelName = this.defaultModel;
        }

        List<ResponseInputItem> inputItems = convertInputItems(messages);

        ResponseCreateParams.Builder builder =
                ResponseCreateParams.builder()
                        .model(ChatModel.of(modelName))
                        .inputOfResponse(inputItems);

        if (tools != null && !tools.isEmpty()) {
            builder.tools(convertTools(tools, strictMode));
        }

        Object temperature = arguments.remove("temperature");
        if (temperature instanceof Number) {
            builder.temperature(((Number) temperature).doubleValue());
        }

        Object maxTokens = arguments.remove("max_tokens");
        if (maxTokens instanceof Number) {
            builder.maxOutputTokens(((Number) maxTokens).longValue());
        }

        Object reasoningEffort = arguments.remove("reasoning_effort");
        if (reasoningEffort instanceof String) {
            builder.reasoning(
                    Reasoning.builder()
                            .effort(ReasoningEffort.of((String) reasoningEffort))
                            .build());
        }

        Object store = arguments.remove("store");
        if (Boolean.TRUE.equals(store)) {
            builder.store(true);
        }

        Object instructions = arguments.remove("instructions");
        if (instructions instanceof String) {
            builder.instructions((String) instructions);
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

    private List<ResponseInputItem> convertInputItems(List<ChatMessage> messages) {
        List<ResponseInputItem> items = new ArrayList<>();
        for (ChatMessage message : messages) {
            items.addAll(convertSingleMessage(message));
        }
        return items;
    }

    private List<ResponseInputItem> convertSingleMessage(ChatMessage message) {
        List<ResponseInputItem> items = new ArrayList<>();
        MessageRole role = message.getRole();
        String content = Optional.ofNullable(message.getContent()).orElse("");

        switch (role) {
            case SYSTEM:
                items.add(
                        ResponseInputItem.ofMessage(
                                ResponseInputItem.Message.builder()
                                        .role(ResponseInputItem.Message.Role.SYSTEM)
                                        .addInputTextContent(content)
                                        .build()));
                break;

            case USER:
                items.add(
                        ResponseInputItem.ofMessage(
                                ResponseInputItem.Message.builder()
                                        .role(ResponseInputItem.Message.Role.USER)
                                        .addInputTextContent(content)
                                        .build()));
                break;

            case ASSISTANT:
                List<Map<String, Object>> toolCalls = message.getToolCalls();
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    for (Map<String, Object> call : toolCalls) {
                        Map<String, Object> functionPayload = toMap(call.get("function"));
                        String responseId = String.valueOf(call.get("id"));
                        String callId = String.valueOf(call.get("original_id"));
                        String name = String.valueOf(functionPayload.get("name"));
                        String args = serializeArguments(functionPayload.get("arguments"));

                        items.add(
                                ResponseInputItem.ofFunctionCall(
                                        ResponseFunctionToolCall.builder()
                                                .id(responseId)
                                                .callId(callId)
                                                .name(name)
                                                .arguments(args)
                                                .status(ResponseFunctionToolCall.Status.COMPLETED)
                                                .build()));
                    }
                }
                if (!content.isEmpty()) {
                    items.add(
                            ResponseInputItem.ofEasyInputMessage(
                                    EasyInputMessage.builder()
                                            .role(EasyInputMessage.Role.ASSISTANT)
                                            .content(content)
                                            .build()));
                }
                break;

            case TOOL:
                Object toolCallId = message.getExtraArgs().get("externalId");
                if (toolCallId == null) {
                    throw new IllegalArgumentException(
                            "Tool message must have an externalId in extraArgs.");
                }
                items.add(
                        ResponseInputItem.ofFunctionCallOutput(
                                ResponseInputItem.FunctionCallOutput.builder()
                                        .callId(toolCallId.toString())
                                        .output(content)
                                        .build()));
                break;

            default:
                throw new IllegalArgumentException("Unsupported role: " + role);
        }
        return items;
    }

    private List<Tool> convertTools(
            List<org.apache.flink.agents.api.tools.Tool> tools, boolean strictMode) {
        List<Tool> responsesTools = new ArrayList<>(tools.size());
        for (org.apache.flink.agents.api.tools.Tool tool : tools) {
            ToolMetadata metadata = tool.getMetadata();
            FunctionTool.Builder functionBuilder =
                    FunctionTool.builder()
                            .name(metadata.getName())
                            .description(metadata.getDescription());

            String schema = metadata.getInputSchema();
            if (schema != null && !schema.isBlank()) {
                functionBuilder.parameters(parseToolParameters(schema));
            }

            functionBuilder.strict(strictMode);

            responsesTools.add(Tool.ofFunction(functionBuilder.build()));
        }
        return responsesTools;
    }

    private ChatMessage convertResponse(Response response) {
        List<ResponseOutputItem> output = response.output();
        if (output == null || output.isEmpty()) {
            throw new IllegalStateException("OpenAI Responses API did not return any output.");
        }

        StringBuilder textContent = new StringBuilder();
        StringBuilder refusalContent = new StringBuilder();
        List<Map<String, Object>> toolCalls = new ArrayList<>();

        for (ResponseOutputItem item : output) {
            if (item.isMessage()) {
                ResponseOutputMessage msg = item.asMessage();
                for (ResponseOutputMessage.Content contentBlock : msg.content()) {
                    if (contentBlock.isOutputText()) {
                        textContent.append(contentBlock.asOutputText().text());
                    } else if (contentBlock.isRefusal()) {
                        refusalContent.append(contentBlock.asRefusal().refusal());
                    }
                }
            } else if (item.isFunctionCall()) {
                ResponseFunctionToolCall fc = item.asFunctionCall();
                Map<String, Object> callMap = new LinkedHashMap<>();

                String callId = fc.callId();
                if (callId == null || callId.isBlank()) {
                    throw new IllegalStateException(
                            "OpenAI Responses API returned a function call without a call_id.");
                }

                callMap.put(
                        "id",
                        fc.id()
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "OpenAI Responses API returned a function call without an id.")));
                callMap.put("type", "function");

                Map<String, Object> functionMap = new LinkedHashMap<>();
                functionMap.put("name", fc.name());
                functionMap.put("arguments", parseArguments(fc.arguments()));
                callMap.put("function", functionMap);
                callMap.put("original_id", callId);

                toolCalls.add(callMap);
            }
        }

        ChatMessage result = ChatMessage.assistant(textContent.toString());
        if (!toolCalls.isEmpty()) {
            result.setToolCalls(toolCalls);
        }

        if (refusalContent.length() > 0) {
            result.getExtraArgs().put("refusal", refusalContent.toString());
        }

        result.getExtraArgs().put("response_id", response.id());

        return result;
    }

    private FunctionTool.Parameters parseToolParameters(String schemaJson) {
        try {
            JsonNode root = mapper.readTree(schemaJson);
            if (root == null || !root.isObject()) {
                return FunctionTool.Parameters.builder().build();
            }
            FunctionTool.Parameters.Builder builder = FunctionTool.Parameters.builder();
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

    @Override
    public void close() throws Exception {
        this.client.close();
    }
}
