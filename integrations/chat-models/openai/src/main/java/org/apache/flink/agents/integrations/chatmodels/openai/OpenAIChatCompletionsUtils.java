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
import com.openai.core.Timeout;
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
import org.apache.flink.agents.api.resource.ResourceDescriptor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Static helpers for converting between Flink Agents {@link ChatMessage} and OpenAI Chat
 * Completions API message types, plus shared parsing/validation of common connection arguments
 * ({@code timeout}, {@code max_retries}). No tool-definition conversion — that stays
 * per-connection.
 *
 * <p>Used by both {@code OpenAICompletionsConnection} (OpenAI / OpenAI-compatible providers) and
 * {@code AzureOpenAIChatModelConnection} (Azure OpenAI). Both rely on the same openai-java SDK
 * message types.
 */
final class OpenAIChatCompletionsUtils {

    private static final BigDecimal MAX_TIMEOUT_SECONDS =
            BigDecimal.valueOf(Integer.MAX_VALUE).movePointLeft(3);

    /** Default timeout in seconds for OpenAI API requests (aligned with Python SDK). */
    static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /** Default max retries for OpenAI API requests (aligned with Python SDK). */
    static final int DEFAULT_MAX_RETRIES = 3;

    private OpenAIChatCompletionsUtils() {}

    /**
     * Resolve and validate the {@code timeout} argument (in seconds). The raw value is validated
     * before any numeric conversion so that e.g. {@code -0.5} cannot truncate to {@code 0} and
     * bypass the non-negative check. Fractional values are rounded up to the SDK's millisecond
     * precision so that a positive value can never become an unlimited timeout.
     */
    static Duration parseTimeout(ResourceDescriptor descriptor) {
        Number raw = descriptor.getArgument("timeout");
        if (raw == null) {
            return Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS);
        }
        BigDecimal seconds = toBigDecimal(raw, "timeout");
        if (seconds.signum() < 0) {
            throw new IllegalArgumentException("timeout must be >= 0, got: " + raw);
        }
        if (seconds.compareTo(MAX_TIMEOUT_SECONDS) > 0) {
            throw new IllegalArgumentException(
                    "timeout exceeds the SDK maximum of "
                            + MAX_TIMEOUT_SECONDS.toPlainString()
                            + " seconds, got: "
                            + raw);
        }
        try {
            // The SDK's OkHttp transport accepts millisecond precision. Round positive values up
            // so a valid nonzero timeout cannot become Duration.ZERO, which disables timeouts.
            BigInteger milliseconds =
                    seconds.multiply(BigDecimal.valueOf(1_000L))
                            .setScale(0, RoundingMode.CEILING)
                            .toBigIntegerExact();
            return Duration.ofMillis(milliseconds.longValueExact());
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "timeout is outside the supported range, got: " + raw, e);
        }
    }

    /**
     * Configure every SDK timeout component from the connection timeout. A zero duration means no
     * timeout in openai-java, so all components must be set explicitly; setting only the request
     * timeout leaves the SDK's default connection timeout in effect.
     */
    static Timeout toSdkTimeout(Duration timeout) {
        return Timeout.builder()
                .connect(timeout)
                .read(timeout)
                .write(timeout)
                .request(timeout)
                .build();
    }

    /**
     * Resolve and validate the {@code max_retries} argument. Requires an exact non-negative integer
     * within int range, matching Python-side validation (pydantic rejects fractional values for int
     * fields).
     */
    static int parseMaxRetries(ResourceDescriptor descriptor) {
        Number raw = descriptor.getArgument("max_retries");
        if (raw == null) {
            return DEFAULT_MAX_RETRIES;
        }
        BigDecimal value = toBigDecimal(raw, "max_retries");
        try {
            BigInteger retries = value.toBigIntegerExact();
            if (retries.signum() < 0
                    || retries.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                throw new IllegalArgumentException(
                        "max_retries must be a non-negative integer, got: " + raw);
            }
            return retries.intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "max_retries must be a non-negative integer, got: " + raw, e);
        }
    }

    private static BigDecimal toBigDecimal(Number raw, String argumentName) {
        if ((raw instanceof Double || raw instanceof Float)
                && !Double.isFinite(raw.doubleValue())) {
            throw new IllegalArgumentException(argumentName + " must be finite, got: " + raw);
        }
        try {
            return new BigDecimal(raw.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    argumentName + " must be a finite number, got: " + raw, e);
        }
    }

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
