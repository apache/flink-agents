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

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RedactedThinkingBlockParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.ReasoningBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.TokenUsage;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.apache.flink.agents.api.tools.ToolMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Converts Flink Agents messages and tool definitions to and from Anthropic SDK types. */
final class AnthropicChatUtils {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ANTHROPIC_METADATA = "anthropic";
    private static final String BLOCK_TYPE = "type";
    private static final String THINKING = "thinking";
    private static final String SIGNATURE = "signature";
    private static final String REDACTED_THINKING = "redacted_thinking";
    private static final String REDACTED_DATA = "data";

    private AnthropicChatUtils() {}

    static List<TextBlockParam> extractSystemMessages(List<ChatMessage> messages) {
        return messages.stream()
                .filter(m -> m.getRole() == MessageRole.SYSTEM)
                .map(m -> TextBlockParam.builder().text(m.getText()).build())
                .collect(Collectors.toList());
    }

    static MessageParam convertToAnthropicMessage(ChatMessage message) {
        if (message.getRole() == MessageRole.TOOL) {
            return convertToolResult((ToolResultBlock) message.getBlocks().get(0));
        }
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (org.apache.flink.agents.api.chat.messages.ContentBlock block : message.getBlocks()) {
            if (block instanceof TextBlock) {
                blocks.add(
                        ContentBlockParam.ofText(
                                TextBlockParam.builder()
                                        .text(((TextBlock) block).getText())
                                        .build()));
            } else if (block instanceof ToolCallBlock) {
                ToolCallBlock call = (ToolCallBlock) block;
                blocks.add(
                        ContentBlockParam.ofToolUse(
                                ToolUseBlockParam.builder()
                                        .id(call.getCallId())
                                        .name(call.getName())
                                        .input(toJsonValue(call.getInput()))
                                        .build()));
            } else if (block instanceof ReasoningBlock) {
                ContentBlockParam reasoning = convertReasoning((ReasoningBlock) block);
                if (reasoning != null) {
                    blocks.add(reasoning);
                }
            } else {
                throw new IllegalArgumentException(
                        "Anthropic cannot send block type " + block.getType());
            }
        }
        return MessageParam.builder()
                .role(
                        message.getRole() == MessageRole.ASSISTANT
                                ? MessageParam.Role.ASSISTANT
                                : MessageParam.Role.USER)
                .contentOfBlockParams(blocks)
                .build();
    }

    private static MessageParam convertToolResult(ToolResultBlock result) {
        StringBuilder text = new StringBuilder();
        for (org.apache.flink.agents.api.chat.messages.ContentBlock block : result.getBlocks()) {
            if (!(block instanceof TextBlock)) {
                throw new IllegalArgumentException(
                        "Anthropic cannot send block type " + block.getType());
            }
            text.append(((TextBlock) block).getText());
        }
        return MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(
                        List.of(
                                ContentBlockParam.ofToolResult(
                                        ToolResultBlockParam.builder()
                                                .toolUseId(result.getCallId())
                                                .isError(result.isError())
                                                .content(text.toString())
                                                .build())))
                .build();
    }

    /** Returns null for reasoning without this provider's native metadata. */
    private static ContentBlockParam convertReasoning(ReasoningBlock reasoning) {
        Object nativeValue = reasoning.getMetadata().get(ANTHROPIC_METADATA);
        if (nativeValue == null) {
            return null;
        }
        Map<?, ?> metadata = (Map<?, ?>) nativeValue;
        if (THINKING.equals(metadata.get(BLOCK_TYPE))) {
            return ContentBlockParam.ofThinking(
                    ThinkingBlockParam.builder()
                            .thinking(reasoning.getText())
                            .signature((String) metadata.get(SIGNATURE))
                            .build());
        }
        if (REDACTED_THINKING.equals(metadata.get(BLOCK_TYPE))) {
            return ContentBlockParam.ofRedactedThinking(
                    RedactedThinkingBlockParam.builder()
                            .data((String) metadata.get(REDACTED_DATA))
                            .build());
        }
        throw new IllegalArgumentException(
                "Unsupported Anthropic reasoning type: " + metadata.get(BLOCK_TYPE));
    }

    /** Converts native content and restores any JSON prefill applied to the request. */
    static ChatResult convertResponse(Message response, String model, boolean jsonPrefillApplied) {
        if (response.content().isEmpty()) {
            throw new IllegalStateException("Anthropic response did not contain any content.");
        }
        List<org.apache.flink.agents.api.chat.messages.ContentBlock> blocks = new ArrayList<>();
        boolean prefix = jsonPrefillApplied;
        for (ContentBlock block : response.content()) {
            if (block.isText()) {
                String text = (prefix ? "{" : "") + block.asText().text();
                prefix = false;
                blocks.add(new TextBlock(text));
            } else if (block.isToolUse()) {
                blocks.add(
                        new ToolCallBlock(
                                block.asToolUse().id(),
                                block.asToolUse().name(),
                                jsonValueToMap(block.asToolUse()._input())));
            } else if (block.isThinking()) {
                blocks.add(
                        new ReasoningBlock(
                                block.asThinking().thinking(),
                                Map.of(
                                        ANTHROPIC_METADATA,
                                        Map.of(
                                                BLOCK_TYPE,
                                                THINKING,
                                                SIGNATURE,
                                                block.asThinking().signature()))));
            } else if (block.isRedactedThinking()) {
                blocks.add(
                        new ReasoningBlock(
                                null,
                                Map.of(
                                        ANTHROPIC_METADATA,
                                        Map.of(
                                                BLOCK_TYPE,
                                                REDACTED_THINKING,
                                                REDACTED_DATA,
                                                block.asRedactedThinking().data()))));
            } else {
                throw new IllegalArgumentException("Unsupported Anthropic response content");
            }
        }
        if (prefix) {
            blocks.add(0, new TextBlock("{"));
        }
        if (blocks.stream().allMatch(block -> block instanceof TextBlock)) {
            String text =
                    blocks.stream()
                            .map(block -> ((TextBlock) block).getText())
                            .collect(Collectors.joining());
            blocks = List.of(new TextBlock(extractJsonFromMarkdown(text)));
        }
        return new ChatResult(
                ChatMessage.assistant(blocks),
                model,
                response.id(),
                new TokenUsage(response.usage().inputTokens(), response.usage().outputTokens()),
                response.stopReason().map(AnthropicChatUtils::toFinishReason).orElse(null),
                null);
    }

    /** Maps Anthropic's token-limit reason to the shared chat action's canonical value. */
    private static String toFinishReason(StopReason reason) {
        return StopReason.MAX_TOKENS.equals(reason) ? "length" : reason.asString();
    }

    /**
     * Extracts JSON content from a string that may contain markdown code blocks.
     *
     * <p>Claude often wraps JSON responses in markdown code blocks like {@code ```json ... ```},
     * especially on a response no JSON prefill was applied to, since an assistant turn already
     * opened with {@code "{"} cannot be continued into a fence. This method extracts the JSON
     * content from such responses. If no code block is found, the original content is returned
     * unchanged.
     *
     * @param content The response content that may contain markdown-wrapped JSON
     * @return The extracted JSON string, or the original content if no code block is found
     */
    private static String extractJsonFromMarkdown(String content) {
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

    private static Map<String, Object> jsonValueToMap(JsonValue jsonValue) {
        try {
            String jsonString = MAPPER.writeValueAsString(jsonValue);
            return MAPPER.readValue(jsonString, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to convert JsonValue to Map.", e);
        }
    }

    static List<Tool> convertTools(
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

    private static Tool.InputSchema parseToolInputSchema(String schemaJson) {
        try {
            JsonNode root = MAPPER.readTree(schemaJson);
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

    static JsonValue toJsonValue(Object value) {
        if (value instanceof JsonValue) {
            return (JsonValue) value;
        }
        if (value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value == null) {
            return JsonValue.from(value);
        }
        return JsonValue.fromJsonNode(MAPPER.valueToTree(value));
    }
}
