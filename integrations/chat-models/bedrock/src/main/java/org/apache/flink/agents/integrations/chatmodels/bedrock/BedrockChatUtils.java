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

package org.apache.flink.agents.integrations.chatmodels.bedrock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.ReasoningBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.SdkNumber;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningTextBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Converts Flink Agents messages and tool definitions to and from Bedrock SDK types. */
final class BedrockChatUtils {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BEDROCK_METADATA = "bedrock";
    private static final String SIGNATURE = "signature";
    private static final String REDACTED_CONTENT = "redacted_content";

    private BedrockChatUtils() {}

    private static Message toBedrockMessage(ChatMessage message) {
        if (message.getRole() == MessageRole.TOOL) {
            return Message.builder()
                    .role(ConversationRole.USER)
                    .content(ContentBlock.fromToolResult(toBedrockToolResult(message)))
                    .build();
        }
        List<ContentBlock> blocks = new ArrayList<>();
        for (org.apache.flink.agents.api.chat.messages.ContentBlock block : message.getBlocks()) {
            if (block instanceof TextBlock) {
                blocks.add(ContentBlock.fromText(((TextBlock) block).getText()));
            } else if (block instanceof ToolCallBlock) {
                ToolCallBlock call = (ToolCallBlock) block;
                blocks.add(
                        ContentBlock.fromToolUse(
                                ToolUseBlock.builder()
                                        .toolUseId(call.getCallId())
                                        .name(call.getName())
                                        .input(toDocument(call.getInput()))
                                        .build()));
            } else if (block instanceof ReasoningBlock) {
                ReasoningContentBlock reasoning = toBedrockReasoning((ReasoningBlock) block);
                if (reasoning != null) {
                    blocks.add(ContentBlock.fromReasoningContent(reasoning));
                }
            } else {
                throw new IllegalArgumentException("Bedrock cannot send block " + block.getType());
            }
        }
        return Message.builder()
                .role(
                        message.getRole() == MessageRole.ASSISTANT
                                ? ConversationRole.ASSISTANT
                                : ConversationRole.USER)
                .content(blocks)
                .build();
    }

    /** Returns null for reasoning without this provider's native metadata. */
    private static ReasoningContentBlock toBedrockReasoning(ReasoningBlock reasoning) {
        Object nativeMetadata = reasoning.getMetadata().get(BEDROCK_METADATA);
        if (!(nativeMetadata instanceof Map)) {
            return null;
        }
        Map<?, ?> metadata = (Map<?, ?>) nativeMetadata;
        if (metadata.containsKey(REDACTED_CONTENT)) {
            byte[] data = Base64.getDecoder().decode(metadata.get(REDACTED_CONTENT).toString());
            return ReasoningContentBlock.fromRedactedContent(SdkBytes.fromByteArray(data));
        }
        return ReasoningContentBlock.fromReasoningText(
                ReasoningTextBlock.builder()
                        .text(reasoning.getText())
                        .signature((String) metadata.get(SIGNATURE))
                        .build());
    }

    static software.amazon.awssdk.services.bedrockruntime.model.Tool toBedrockTool(Tool tool) {
        ToolMetadata meta = tool.getMetadata();
        ToolSpecification.Builder specBuilder =
                ToolSpecification.builder().name(meta.getName()).description(meta.getDescription());

        String schema = meta.getInputSchema();
        if (schema != null && !schema.isBlank()) {
            try {
                Map<String, Object> schemaMap =
                        MAPPER.readValue(schema, new TypeReference<Map<String, Object>>() {});
                specBuilder.inputSchema(ToolInputSchema.fromJson(toDocument(schemaMap)));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to parse tool schema.", e);
            }
        }

        return software.amazon.awssdk.services.bedrockruntime.model.Tool.builder()
                .toolSpec(specBuilder.build())
                .build();
    }

    /**
     * Strip markdown code fences from text responses. Some Bedrock models wrap JSON output in
     * markdown fences like {@code ```json ... ```}.
     *
     * <p>Only strips code fences; does not extract JSON from arbitrary text, as that could corrupt
     * normal prose responses containing braces.
     */
    static String stripMarkdownFences(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline >= 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
            }
            return trimmed;
        }
        return trimmed;
    }

    @SuppressWarnings("unchecked")
    private static Document toDocument(Object obj) {
        if (obj == null) {
            return Document.fromNull();
        }
        if (obj instanceof Map) {
            Map<String, Document> docMap = new LinkedHashMap<>();
            ((Map<String, Object>) obj).forEach((k, v) -> docMap.put(k, toDocument(v)));
            return Document.fromMap(docMap);
        }
        if (obj instanceof List) {
            return Document.fromList(
                    ((List<Object>) obj)
                            .stream()
                                    .map(BedrockChatUtils::toDocument)
                                    .collect(Collectors.toList()));
        }
        if (obj instanceof String) {
            return Document.fromString((String) obj);
        }
        if (obj instanceof Number) {
            return Document.fromNumber(SdkNumber.fromBigDecimal(new BigDecimal(obj.toString())));
        }
        if (obj instanceof Boolean) {
            return Document.fromBoolean((Boolean) obj);
        }
        return Document.fromString(obj.toString());
    }

    /**
     * Merge consecutive TOOL messages into a single USER message with multiple toolResult content
     * blocks, as required by Bedrock Converse API.
     */
    static List<Message> mergeMessages(List<ChatMessage> msgs) {
        List<Message> result = new ArrayList<>();
        int i = 0;
        while (i < msgs.size()) {
            ChatMessage msg = msgs.get(i);
            if (msg.getRole() == MessageRole.TOOL) {
                List<ContentBlock> toolResultBlocks = new ArrayList<>();
                while (i < msgs.size() && msgs.get(i).getRole() == MessageRole.TOOL) {
                    ChatMessage toolMsg = msgs.get(i);
                    toolResultBlocks.add(ContentBlock.fromToolResult(toBedrockToolResult(toolMsg)));
                    i++;
                }
                result.add(
                        Message.builder()
                                .role(ConversationRole.USER)
                                .content(toolResultBlocks)
                                .build());
            } else {
                result.add(toBedrockMessage(msg));
                i++;
            }
        }
        return result;
    }

    private static ToolResultBlock toBedrockToolResult(ChatMessage message) {
        org.apache.flink.agents.api.chat.messages.ToolResultBlock result =
                (org.apache.flink.agents.api.chat.messages.ToolResultBlock)
                        message.getBlocks().get(0);
        List<ToolResultContentBlock> content = new ArrayList<>();
        for (org.apache.flink.agents.api.chat.messages.ContentBlock block : result.getBlocks()) {
            if (!(block instanceof TextBlock)) {
                throw new IllegalArgumentException(
                        "Bedrock cannot send tool result block " + block.getType());
            }
            content.add(
                    ToolResultContentBlock.builder().text(((TextBlock) block).getText()).build());
        }
        return ToolResultBlock.builder()
                .toolUseId(result.getCallId())
                .content(content)
                .status(result.isError() ? "error" : "success")
                .build();
    }

    static ChatMessage convertResponse(ConverseResponse response) {
        List<org.apache.flink.agents.api.chat.messages.ContentBlock> blocks = new ArrayList<>();
        for (ContentBlock block : response.output().message().content()) {
            if (block.text() != null) {
                blocks.add(new TextBlock(block.text()));
            } else if (block.reasoningContent() != null) {
                ReasoningContentBlock reasoning = block.reasoningContent();
                Map<String, Object> metadata = new LinkedHashMap<>();
                String text = null;
                if (reasoning.reasoningText() != null) {
                    text = reasoning.reasoningText().text();
                    if (reasoning.reasoningText().signature() != null) {
                        metadata.put(SIGNATURE, reasoning.reasoningText().signature());
                    }
                } else if (reasoning.redactedContent() != null) {
                    metadata.put(
                            REDACTED_CONTENT,
                            Base64.getEncoder()
                                    .encodeToString(reasoning.redactedContent().asByteArray()));
                }
                blocks.add(new ReasoningBlock(text, Map.of(BEDROCK_METADATA, metadata)));
            } else if (block.toolUse() != null) {
                ToolUseBlock call = block.toolUse();
                blocks.add(
                        new ToolCallBlock(
                                call.toolUseId(), call.name(), documentToMap(call.input())));
            }
        }
        if (blocks.stream().allMatch(block -> block instanceof TextBlock)) {
            String text =
                    blocks.stream()
                            .map(block -> ((TextBlock) block).getText())
                            .collect(Collectors.joining());
            blocks = List.of(new TextBlock(stripMarkdownFences(text)));
        }
        return ChatMessage.assistant(blocks);
    }

    private static Map<String, Object> documentToMap(Document doc) {
        if (doc == null || !doc.isMap()) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new LinkedHashMap<>();

        doc.asMap().forEach((k, v) -> result.put(k, documentToObject(v)));
        return result;
    }

    private static Object documentToObject(Document doc) {
        if (doc == null || doc.isNull()) {
            return null;
        }
        if (doc.isString()) {
            return doc.asString();
        }
        if (doc.isNumber()) {
            return doc.asNumber().bigDecimalValue();
        }
        if (doc.isBoolean()) {
            return doc.asBoolean();
        }
        if (doc.isList()) {
            return doc.asList().stream()
                    .map(BedrockChatUtils::documentToObject)
                    .collect(Collectors.toList());
        }
        if (doc.isMap()) {
            return documentToMap(doc);
        }
        return doc.toString();
    }
}
