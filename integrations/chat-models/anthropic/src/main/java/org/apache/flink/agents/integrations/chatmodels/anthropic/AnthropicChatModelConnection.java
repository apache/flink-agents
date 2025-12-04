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

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.stream.Collectors;

/**
 * A chat model integration for the Anthropic Chat service using the official Java SDK.
 *
 * <p>Supported connection parameters:
 *
 * <ul>
 *   <li><b>api_key</b> (required): Anthropic API key
 *   <li><b>timeout</b> (optional): Timeout in seconds for API requests
 *   <li><b>max_retries</b> (optional): Maximum number of retry attempts (default: 2)
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public class MyAgent extends Agent {
 *   @ChatModelConnection
 *   public static ResourceDesc anthropic() {
 *     return ResourceDescriptor.Builder.newBuilder(AnthropicChatModelConnection.class.getName())
 *             .addInitialArgument("api_key", System.getenv("ANTHROPIC_API_KEY"))
 *             .addInitialArgument("timeout", 120)
 *             .addInitialArgument("max_retries", 3)
 *             .build();
 *   }
 * }
 * }</pre>
 */
public class AnthropicChatModelConnection extends BaseChatModelConnection {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper = new ObjectMapper();
    private final AnthropicClient client;
    private final String defaultModel;

    public AnthropicChatModelConnection(
            ResourceDescriptor descriptor, BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);

        String apiKey = descriptor.getArgument("api_key");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("api_key should not be null or empty.");
        }

        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().apiKey(apiKey);

        Integer timeoutSeconds = descriptor.getArgument("timeout");
        if (timeoutSeconds != null && timeoutSeconds > 0) {
            builder.timeout(Duration.ofSeconds(timeoutSeconds));
        }

        Integer maxRetries = descriptor.getArgument("max_retries");
        if (maxRetries != null && maxRetries >= 0) {
            builder.maxRetries(maxRetries);
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
            // Check if JSON prefill is requested before building request (arguments may be
            // modified).
            boolean jsonPrefillRequested =
                    arguments != null && Boolean.TRUE.equals(arguments.get("json_prefill"));
            // JSON prefill is automatically disabled when tools are passed in the request,
            // because it interferes with native tool calling.
            boolean hasToolsInRequest = tools != null && !tools.isEmpty();
            boolean jsonPrefillApplied = jsonPrefillRequested && !hasToolsInRequest;

            MessageCreateParams params = buildRequest(messages, tools, arguments);
            Message response = client.messages().create(params);
            return convertResponse(response, jsonPrefillApplied);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call Anthropic messages API.", e);
        }
    }

    private MessageCreateParams buildRequest(
            List<ChatMessage> messages,
            List<org.apache.flink.agents.api.tools.Tool> tools,
            Map<String, Object> rawArguments) {
        Map<String, Object> arguments =
                rawArguments != null ? new HashMap<>(rawArguments) : new HashMap<>();

        Object modelObj = arguments.remove("model");
        String modelName = modelObj != null ? modelObj.toString() : this.defaultModel;
        if (modelName == null || modelName.isBlank()) {
            modelName = this.defaultModel;
        }

        List<TextBlockParam> systemBlocks = extractSystemMessages(messages);

        List<MessageParam> anthropicMessages =
                messages.stream()
                        .filter(m -> m.getRole() != MessageRole.SYSTEM)
                        .map(this::convertToAnthropicMessage)
                        .collect(Collectors.toList());

        MessageCreateParams.Builder builder =
                MessageCreateParams.builder()
                        .model(Model.of(modelName))
                        .messages(anthropicMessages);

        if (!systemBlocks.isEmpty()) {
            builder.systemOfTextBlockParams(systemBlocks);
        }

        // Handle strict tools - enables structured outputs for tool use
        Object strictTools = arguments.remove("strict_tools");
        boolean strictToolsEnabled = Boolean.TRUE.equals(strictTools);

        if (tools != null && !tools.isEmpty()) {
            for (Tool tool : convertTools(tools, strictToolsEnabled)) {
                builder.addTool(tool);
            }
        }

        // Add beta header for strict tool use
        // https://platform.claude.com/docs/en/build-with-claude/structured-outputs#strict-tool-use
        if (strictToolsEnabled) {
            builder.putAdditionalHeader("anthropic-beta", "structured-outputs-2025-11-13");
        }

        Object maxTokens = arguments.remove("max_tokens");
        if (maxTokens instanceof Number) {
            builder.maxTokens(((Number) maxTokens).longValue());
        }

        Object temperature = arguments.remove("temperature");
        if (temperature instanceof Number) {
            builder.temperature(((Number) temperature).doubleValue());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> additionalKwargs =
                (Map<String, Object>) arguments.remove("additional_kwargs");
        if (additionalKwargs != null) {
            applyAdditionalKwargs(builder, additionalKwargs);
        }

        // Handle JSON prefill - append a prefilled assistant message with "{" to enforce JSON
        // output. Note: JSON prefill is incompatible with tool use as it forces the model to output
        // JSON text instead of using native tool_use content blocks. Automatically disable
        // json_prefill when tools are actually passed in the request.
        Object jsonPrefill = arguments.remove("json_prefill");
        boolean hasToolsInRequest = tools != null && !tools.isEmpty();
        if (Boolean.TRUE.equals(jsonPrefill) && !hasToolsInRequest) {
            anthropicMessages.add(
                    MessageParam.builder().role(MessageParam.Role.ASSISTANT).content("{").build());
            builder.messages(anthropicMessages);
        }

        return builder.build();
    }

    private List<TextBlockParam> extractSystemMessages(List<ChatMessage> messages) {
        return messages.stream()
                .filter(m -> m.getRole() == MessageRole.SYSTEM)
                .map(m -> TextBlockParam.builder().text(m.getContent()).build())
                .collect(Collectors.toList());
    }

    private MessageParam convertToAnthropicMessage(ChatMessage message) {
        MessageRole role = message.getRole();
        String content = Optional.ofNullable(message.getContent()).orElse("");

        switch (role) {
            case USER:
                return MessageParam.builder().role(MessageParam.Role.USER).content(content).build();

            case ASSISTANT:
                List<Map<String, Object>> toolCalls = message.getToolCalls();
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    List<ContentBlockParam> contentBlocks = new ArrayList<>();
                    if (!content.isEmpty()) {
                        contentBlocks.add(
                                ContentBlockParam.ofText(
                                        TextBlockParam.builder().text(content).build()));
                    }
                    contentBlocks.addAll(convertToolCallsToToolUse(toolCalls));
                    return MessageParam.builder()
                            .role(MessageParam.Role.ASSISTANT)
                            .contentOfBlockParams(contentBlocks)
                            .build();
                } else {
                    return MessageParam.builder()
                            .role(MessageParam.Role.ASSISTANT)
                            .content(content)
                            .build();
                }

            case TOOL:
                Object toolCallId = message.getExtraArgs().get("externalId");
                if (toolCallId == null) {
                    throw new IllegalArgumentException(
                            "Tool message must have an externalId in extraArgs.");
                }
                ToolResultBlockParam toolResult =
                        ToolResultBlockParam.builder()
                                .toolUseId(toolCallId.toString())
                                .content(content)
                                .build();
                return MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(List.of(ContentBlockParam.ofToolResult(toolResult)))
                        .build();

            default:
                throw new IllegalArgumentException("Unsupported role: " + role);
        }
    }

    private List<ContentBlockParam> convertToolCallsToToolUse(List<Map<String, Object>> toolCalls) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (Map<String, Object> call : toolCalls) {
            Object type = call.getOrDefault("type", "function");
            if (!"function".equals(String.valueOf(type))) {
                continue;
            }

            Map<String, Object> functionPayload = toMap(call.get("function"));
            String functionName = String.valueOf(functionPayload.get("name"));
            Object arguments = functionPayload.get("arguments");
            Map<String, Object> inputMap = toMap(arguments);

            Object originalIdObj = call.get("original_id");
            if (originalIdObj == null) {
                throw new IllegalArgumentException(
                        "Tool call must have an original_id for Anthropic.");
            }

            ToolUseBlockParam toolUse =
                    ToolUseBlockParam.builder()
                            .id(originalIdObj.toString())
                            .name(functionName)
                            .input(toJsonValue(inputMap))
                            .build();

            blocks.add(ContentBlockParam.ofToolUse(toolUse));
        }
        return blocks;
    }

    private List<Tool> convertTools(
            List<org.apache.flink.agents.api.tools.Tool> tools, boolean strictToolsEnabled) {
        List<Tool> anthropicTools = new ArrayList<>(tools.size());
        for (org.apache.flink.agents.api.tools.Tool tool : tools) {
            ToolMetadata metadata = tool.getMetadata();
            Tool.Builder toolBuilder =
                    Tool.builder().name(metadata.getName()).description(metadata.getDescription());

            String schema = metadata.getInputSchema();
            if (schema != null && !schema.isBlank()) {
                toolBuilder.inputSchema(parseToolInputSchema(schema));
            }

            if (strictToolsEnabled) {
                toolBuilder.putAdditionalProperty("strict", JsonValue.from(true));
            }

            anthropicTools.add(toolBuilder.build());
        }
        return anthropicTools;
    }

    private Tool.InputSchema parseToolInputSchema(String schemaJson) {
        try {
            JsonNode root = mapper.readTree(schemaJson);
            if (root == null || !root.isObject()) {
                return Tool.InputSchema.builder().build();
            }

            Tool.InputSchema.Builder builder = Tool.InputSchema.builder();
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

    private ChatMessage convertResponse(Message response, boolean jsonPrefillApplied) {
        List<ContentBlock> contentBlocks = response.content();
        if (contentBlocks.isEmpty()) {
            throw new IllegalStateException("Anthropic response did not contain any content.");
        }

        StringBuilder textContent = new StringBuilder();
        // If JSON prefill was used, prepend "{" since the response only contains the continuation
        if (jsonPrefillApplied) {
            textContent.append("{");
        }
        List<Map<String, Object>> toolCalls = new ArrayList<>();

        for (ContentBlock block : contentBlocks) {
            if (block.isText()) {
                block.text()
                        .ifPresent(
                                textBlock -> {
                                    textContent.append(textBlock.text());
                                });
            } else if (block.isToolUse()) {
                block.toolUse()
                        .ifPresent(
                                toolUse -> {
                                    String toolUseId = toolUse.id();
                                    Map<String, Object> toolCall = new LinkedHashMap<>();
                                    toolCall.put("id", toolUseId);
                                    toolCall.put("type", "function");

                                    Map<String, Object> functionMap = new LinkedHashMap<>();
                                    functionMap.put("name", toolUse.name());
                                    JsonValue inputValue = toolUse._input();
                                    Map<String, Object> inputMap = jsonValueToMap(inputValue);
                                    functionMap.put("arguments", inputMap);
                                    toolCall.put("function", functionMap);
                                    toolCall.put("original_id", toolUseId);

                                    toolCalls.add(toolCall);
                                });
            }
        }

        String finalText = textContent.toString();

        // If the response has no tool calls, try to extract JSON from markdown code blocks.
        if (toolCalls.isEmpty()) {
            finalText = extractJsonFromMarkdown(finalText);
        }

        ChatMessage chatMessage = ChatMessage.assistant(finalText);
        if (!toolCalls.isEmpty()) {
            chatMessage.setToolCalls(toolCalls);
        }

        return chatMessage;
    }

    /**
     * Extracts JSON content from a string that may contain markdown code blocks.
     *
     * <p>Claude often wraps JSON responses in markdown code blocks like {@code ```json ... ```},
     * especially when tools are configured (since json_prefill is disabled). This method extracts
     * the JSON content from such responses. If no code block is found, the original content is
     * returned unchanged.
     *
     * @param content The response content that may contain markdown-wrapped JSON
     * @return The extracted JSON string, or the original content if no code block is found
     */
    private String extractJsonFromMarkdown(String content) {
        if (content == null) {
            return null;
        }

        String trimmed = content.trim();

        // Try to find JSON in markdown code block (```json ... ``` or ``` ... ```)
        int jsonBlockStart = trimmed.indexOf("```json");
        int genericBlockStart = trimmed.indexOf("```");

        int contentStart;

        if (jsonBlockStart != -1) {
            contentStart = jsonBlockStart + 7; // length of "```json"
        } else if (genericBlockStart != -1) {
            contentStart = genericBlockStart + 3; // length of "```"
        } else {
            return content;
        }

        // Find the closing ```
        int blockEnd = trimmed.indexOf("```", contentStart);
        if (blockEnd == -1) {
            return content;
        }

        // Extract content between the markers
        return trimmed.substring(contentStart, blockEnd).trim();
    }

    private void applyAdditionalKwargs(
            MessageCreateParams.Builder builder, Map<String, Object> kwargs) {
        for (Map.Entry<String, Object> entry : kwargs.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            switch (key) {
                case "top_k":
                    if (value instanceof Number) {
                        builder.topK(((Number) value).longValue());
                    }
                    break;
                case "top_p":
                    if (value instanceof Number) {
                        builder.topP(((Number) value).doubleValue());
                    }
                    break;
                case "stop_sequences":
                    if (value instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<String> stopSequences = (List<String>) value;
                        builder.stopSequences(stopSequences);
                    }
                    break;
                default:
                    builder.putAdditionalBodyProperty(key, toJsonValue(value));
                    break;
            }
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

    private Map<String, Object> jsonValueToMap(JsonValue jsonValue) {
        try {
            String jsonString = mapper.writeValueAsString(jsonValue);
            return mapper.readValue(jsonString, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to convert JsonValue to Map.", e);
        }
    }
}
