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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Static helpers for converting between Flink Agents {@link ChatMessage} and OpenAI Chat
 * Completions API message types. Restricted to message conversion (no tool-definition conversion —
 * that stays per-connection).
 *
 * <p>Used by both {@code OpenAICompletionsConnection} (OpenAI / OpenAI-compatible providers) and
 * {@code AzureOpenAIChatModelConnection} (Azure OpenAI). Both rely on the same openai-java SDK
 * message types.
 */
final class OpenAIChatCompletionsUtils {

    private OpenAIChatCompletionsUtils() {}

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /** Convert a list of Flink Agents ChatMessages to OpenAI ChatCompletionMessageParams. */
    public static List<ChatCompletionMessageParam> convertToOpenAIMessages(
            List<ChatMessage> messages) {
        return messages.stream()
                .map(OpenAIChatCompletionsUtils::convertToOpenAIMessage)
                .collect(Collectors.toList());
    }

    /** Convert a single Flink Agents ChatMessage to an OpenAI ChatCompletionMessageParam. */
    public static ChatCompletionMessageParam convertToOpenAIMessage(ChatMessage message) {
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

    /**
     * Convert an OpenAI {@link ChatCompletionMessage} to a Flink Agents {@link ChatMessage}. {@code
     * message.refusal()} is written as {@code extraArgs["refusal"]} on the returned ChatMessage
     * when present, preserving prior Java behavior.
     */
    public static ChatMessage convertFromOpenAIMessage(ChatCompletionMessage message) {
        String content = message.content().orElse("");
        ChatMessage response = ChatMessage.assistant(content);

        message.refusal().ifPresent(refusal -> response.getExtraArgs().put("refusal", refusal));

        List<ChatCompletionMessageToolCall> toolCalls = message.toolCalls().orElse(List.of());
        if (!toolCalls.isEmpty()) {
            response.setToolCalls(convertResponseToolCalls(toolCalls));
        }
        return response;
    }

    private static List<ChatCompletionMessageToolCall> convertAssistantToolCalls(
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

    private static List<Map<String, Object>> convertResponseToolCalls(
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

    private static Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(arguments, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse tool arguments: " + arguments, e);
        }
    }

    private static String serializeArguments(Object arguments) {
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

    private static Map<String, Object> toMap(Object value) {
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
