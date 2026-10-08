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
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartInputAudio;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import org.apache.flink.agents.api.chat.messages.AudioBlock;
import org.apache.flink.agents.api.chat.messages.Base64Source;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ContentBlock;
import org.apache.flink.agents.api.chat.messages.DocumentBlock;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.MediaBlock;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.UnsupportedContentBlockException;
import org.apache.flink.agents.api.chat.messages.UrlSource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    /**
     * Convert a single Flink Agents ChatMessage to an OpenAI ChatCompletionMessageParam.
     *
     * <p>Only user messages can carry media; a media block in any other role, or one the Chat
     * Completions API has no content part for, throws {@link UnsupportedContentBlockException}.
     */
    public static ChatCompletionMessageParam convertToOpenAIMessage(ChatMessage message) {
        MessageRole role = message.getRole();
        String content = Optional.ofNullable(message.getText()).orElse("");
        if (role != MessageRole.USER) {
            requireTextOnly(message);
        }

        switch (role) {
            case SYSTEM:
                return ChatCompletionMessageParam.ofSystem(
                        ChatCompletionSystemMessageParam.builder().content(content).build());
            case USER:
                return ChatCompletionMessageParam.ofUser(convertUserMessage(message, content));
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
     * A text-only user message keeps the plain string content, as before; a message with media is
     * sent as content parts in block order.
     */
    private static ChatCompletionUserMessageParam convertUserMessage(
            ChatMessage message, String text) {
        List<ContentBlock> blocks = message.getBlocks();
        if (blocks.stream().noneMatch(block -> block instanceof MediaBlock)) {
            return ChatCompletionUserMessageParam.builder().content(text).build();
        }
        List<ChatCompletionContentPart> parts = new ArrayList<>(blocks.size());
        for (ContentBlock block : blocks) {
            parts.add(toContentPart(block));
        }
        return ChatCompletionUserMessageParam.builder().contentOfArrayOfContentParts(parts).build();
    }

    private static ChatCompletionContentPart toContentPart(ContentBlock block) {
        if (block instanceof TextBlock) {
            return ChatCompletionContentPart.ofText(
                    ChatCompletionContentPartText.builder()
                            .text(((TextBlock) block).getText())
                            .build());
        }
        if (block instanceof ImageBlock) {
            ImageBlock image = (ImageBlock) block;
            String url =
                    image.getSource() instanceof UrlSource
                            ? ((UrlSource) image.getSource()).getUrl()
                            : dataUri(image);
            return ChatCompletionContentPart.ofImageUrl(
                    ChatCompletionContentPartImage.builder()
                            .imageUrl(
                                    ChatCompletionContentPartImage.ImageUrl.builder()
                                            .url(url)
                                            .build())
                            .build());
        }
        if (block instanceof AudioBlock) {
            AudioBlock audio = (AudioBlock) block;
            if (!(audio.getSource() instanceof Base64Source)) {
                throw unsupported(block, "audio input takes base64 data, not a URL");
            }
            ChatCompletionContentPartInputAudio.InputAudio.Format format =
                    audioFormat(audio.getMediaType());
            if (format == null) {
                throw unsupported(block, "audio input takes WAV or MP3 only");
            }
            return ChatCompletionContentPart.ofInputAudio(
                    ChatCompletionContentPartInputAudio.builder()
                            .inputAudio(
                                    ChatCompletionContentPartInputAudio.InputAudio.builder()
                                            .data(((Base64Source) audio.getSource()).getData())
                                            .format(format)
                                            .build())
                            .build());
        }
        if (block instanceof DocumentBlock) {
            DocumentBlock document = (DocumentBlock) block;
            if (!(document.getSource() instanceof Base64Source)) {
                throw unsupported(block, "file input takes base64 data, not a URL");
            }
            // OpenAI accepts PDF documents only; other types are left for the server to reject.
            return ChatCompletionContentPart.ofFile(
                    ChatCompletionContentPart.File.builder()
                            .file(
                                    ChatCompletionContentPart.File.FileObject.builder()
                                            .fileData(dataUri(document))
                                            .filename(
                                                    document.getName() != null
                                                            ? document.getName()
                                                            : "document")
                                            .build())
                            .build());
        }
        throw unsupported(block, "there is no content part for it");
    }

    private static void requireTextOnly(ChatMessage message) {
        for (ContentBlock block : message.getBlocks()) {
            if (block instanceof MediaBlock) {
                throw unsupported(
                        block,
                        "only user messages can carry media, not "
                                + message.getRole().getValue()
                                + " messages");
            }
        }
    }

    /** Maps an audio media type onto the formats Chat Completions accepts, or null. */
    private static ChatCompletionContentPartInputAudio.InputAudio.Format audioFormat(
            String mediaType) {
        String essence = mediaType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        switch (essence) {
            case "audio/wav":
            case "audio/wave":
            case "audio/x-wav":
            case "audio/vnd.wave":
                return ChatCompletionContentPartInputAudio.InputAudio.Format.WAV;
            case "audio/mpeg":
            case "audio/mp3":
                return ChatCompletionContentPartInputAudio.InputAudio.Format.MP3;
            default:
                return null;
        }
    }

    private static String dataUri(MediaBlock block) {
        return "data:"
                + block.getMediaType()
                + ";base64,"
                + ((Base64Source) block.getSource()).getData();
    }

    private static UnsupportedContentBlockException unsupported(ContentBlock block, String reason) {
        return UnsupportedContentBlockException.forBlock("OpenAI Chat Completions", block, reason);
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
