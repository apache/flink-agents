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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Bedrock Converse API chat model connection for flink-agents.
 *
 * <p>Supported connection parameters:
 * <ul>
 *   <li><b>region</b> (optional): AWS region (defaults to us-east-1)</li>
 *   <li><b>model</b> (optional): Default model ID (e.g. us.anthropic.claude-sonnet-4-20250514-v1:0)</li>
 * </ul>
 */
public class BedrockChatModelConnection extends BaseChatModelConnection {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final BedrockRuntimeClient client;
    private final String defaultModel;

    public BedrockChatModelConnection(
            ResourceDescriptor descriptor,
            BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);

        String region = descriptor.getArgument("region");
        if (region == null || region.isBlank()) {
            region = "us-east-1";
        }

        this.client = BedrockRuntimeClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();

        this.defaultModel = descriptor.getArgument("model");
    }

    @Override
    public ChatMessage chat(
            List<ChatMessage> messages, List<Tool> tools, Map<String, Object> arguments) {
        try {
            String modelId = resolveModel(arguments);

            // Separate system messages from conversation messages
            List<ChatMessage> systemMsgs = messages.stream()
                    .filter(m -> m.getRole() == MessageRole.SYSTEM)
                    .collect(Collectors.toList());
            List<ChatMessage> conversationMsgs = messages.stream()
                    .filter(m -> m.getRole() != MessageRole.SYSTEM)
                    .collect(Collectors.toList());

            ConverseRequest.Builder requestBuilder = ConverseRequest.builder()
                    .modelId(modelId)
                    .messages(mergeMessages(conversationMsgs));

            // System prompt
            if (!systemMsgs.isEmpty()) {
                requestBuilder.system(systemMsgs.stream()
                        .map(m -> SystemContentBlock.builder().text(m.getContent()).build())
                        .collect(Collectors.toList()));
            }

            // Tools
            if (tools != null && !tools.isEmpty()) {
                requestBuilder.toolConfig(ToolConfiguration.builder()
                        .tools(tools.stream().map(this::toBedrockTool).collect(Collectors.toList()))
                        .build());
            }

            // Temperature
            if (arguments != null) {
                Object temp = arguments.get("temperature");
                if (temp instanceof Number) {
                    requestBuilder.inferenceConfig(InferenceConfiguration.builder()
                            .temperature(((Number) temp).floatValue())
                            .build());
                }
            }

            ConverseResponse response = client.converse(requestBuilder.build());

            // Record token metrics
            if (response.usage() != null) {
                recordTokenMetrics(modelId,
                        response.usage().inputTokens(),
                        response.usage().outputTokens());
            }

            return convertResponse(response);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call Bedrock Converse API.", e);
        }
    }

    private String resolveModel(Map<String, Object> arguments) {
        String model = arguments != null ? (String) arguments.get("model") : null;
        if (model == null || model.isBlank()) {
            model = this.defaultModel;
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("No model specified for Bedrock.");
        }
        return model;
    }

    /**
     * Merge consecutive TOOL messages into a single USER message with multiple
     * toolResult content blocks, as required by Bedrock Converse API.
     */
    private List<Message> mergeMessages(List<ChatMessage> msgs) {
        List<Message> result = new ArrayList<>();
        int i = 0;
        while (i < msgs.size()) {
            ChatMessage msg = msgs.get(i);
            if (msg.getRole() == MessageRole.TOOL) {
                // Collect all consecutive TOOL messages into one USER message
                List<ContentBlock> toolResultBlocks = new ArrayList<>();
                while (i < msgs.size() && msgs.get(i).getRole() == MessageRole.TOOL) {
                    ChatMessage toolMsg = msgs.get(i);
                    String toolCallId = (String) toolMsg.getExtraArgs().get("externalId");
                    toolResultBlocks.add(ContentBlock.fromToolResult(ToolResultBlock.builder()
                            .toolUseId(toolCallId)
                            .content(ToolResultContentBlock.builder()
                                    .text(toolMsg.getContent())
                                    .build())
                            .build()));
                    i++;
                }
                result.add(Message.builder()
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

    private Message toBedrockMessage(ChatMessage msg) {
        switch (msg.getRole()) {
            case USER:
                return Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(msg.getContent()))
                        .build();
            case ASSISTANT:
                List<ContentBlock> blocks = new ArrayList<>();
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    blocks.add(ContentBlock.fromText(msg.getContent()));
                }
                // Re-emit tool use blocks for multi-turn tool calling
                if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                    for (Map<String, Object> call : msg.getToolCalls()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> fn = (Map<String, Object>) call.get("function");
                        String toolUseId = (String) call.get("id");
                        String name = (String) fn.get("name");
                        Object args = fn.get("arguments");
                        blocks.add(ContentBlock.fromToolUse(ToolUseBlock.builder()
                                .toolUseId(toolUseId)
                                .name(name)
                                .input(toDocument(args))
                                .build()));
                    }
                }
                return Message.builder()
                        .role(ConversationRole.ASSISTANT)
                        .content(blocks)
                        .build();
            case TOOL:
                String toolCallId = (String) msg.getExtraArgs().get("externalId");
                return Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromToolResult(ToolResultBlock.builder()
                                .toolUseId(toolCallId)
                                .content(ToolResultContentBlock.builder()
                                        .text(msg.getContent())
                                        .build())
                                .build()))
                        .build();
            default:
                throw new IllegalArgumentException("Unsupported role for Bedrock: " + msg.getRole());
        }
    }

    private software.amazon.awssdk.services.bedrockruntime.model.Tool toBedrockTool(Tool tool) {
        ToolMetadata meta = tool.getMetadata();
        software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification.Builder specBuilder =
                software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification.builder()
                        .name(meta.getName())
                        .description(meta.getDescription());

        String schema = meta.getInputSchema();
        if (schema != null && !schema.isBlank()) {
            try {
                Map<String, Object> schemaMap = MAPPER.readValue(schema,
                        new TypeReference<Map<String, Object>>() {});
                specBuilder.inputSchema(ToolInputSchema.fromJson(toDocument(schemaMap)));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to parse tool schema.", e);
            }
        }

        return software.amazon.awssdk.services.bedrockruntime.model.Tool.builder()
                .toolSpec(specBuilder.build())
                .build();
    }

    private ChatMessage convertResponse(ConverseResponse response) {
        List<ContentBlock> outputBlocks = response.output().message().content();
        StringBuilder textContent = new StringBuilder();
        List<Map<String, Object>> toolCalls = new ArrayList<>();

        for (ContentBlock block : outputBlocks) {
            if (block.text() != null) {
                textContent.append(block.text());
            }
            if (block.toolUse() != null) {
                ToolUseBlock toolUse = block.toolUse();
                Map<String, Object> callMap = new LinkedHashMap<>();
                callMap.put("id", toolUse.toolUseId());
                callMap.put("type", "function");
                Map<String, Object> fnMap = new LinkedHashMap<>();
                fnMap.put("name", toolUse.name());
                fnMap.put("arguments", documentToMap(toolUse.input()));
                callMap.put("function", fnMap);
                callMap.put("original_id", toolUse.toolUseId());
                toolCalls.add(callMap);
            }
        }

        ChatMessage result = ChatMessage.assistant(stripMarkdownFences(textContent.toString()));
        if (!toolCalls.isEmpty()) {
            result.setToolCalls(toolCalls);
        }
        return result;
    }

    /** Strip markdown code fences and extract JSON from mixed text responses. */
    private static String stripMarkdownFences(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        // Strip ```json ... ``` fences
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
        // Extract first JSON object from mixed text
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    @SuppressWarnings("unchecked")
    private software.amazon.awssdk.core.document.Document toDocument(Object obj) {
        if (obj == null) {
            return software.amazon.awssdk.core.document.Document.fromNull();
        }
        if (obj instanceof Map) {
            Map<String, software.amazon.awssdk.core.document.Document> docMap = new LinkedHashMap<>();
            ((Map<String, Object>) obj).forEach((k, v) -> docMap.put(k, toDocument(v)));
            return software.amazon.awssdk.core.document.Document.fromMap(docMap);
        }
        if (obj instanceof List) {
            return software.amazon.awssdk.core.document.Document.fromList(
                    ((List<Object>) obj).stream().map(this::toDocument).collect(Collectors.toList()));
        }
        if (obj instanceof String) {
            return software.amazon.awssdk.core.document.Document.fromString((String) obj);
        }
        if (obj instanceof Number) {
            return software.amazon.awssdk.core.document.Document.fromNumber(
                    software.amazon.awssdk.core.SdkNumber.fromBigDecimal(
                            new java.math.BigDecimal(obj.toString())));
        }
        if (obj instanceof Boolean) {
            return software.amazon.awssdk.core.document.Document.fromBoolean((Boolean) obj);
        }
        return software.amazon.awssdk.core.document.Document.fromString(obj.toString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> documentToMap(software.amazon.awssdk.core.document.Document doc) {
        if (doc == null || !doc.isMap()) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        doc.asMap().forEach((k, v) -> result.put(k, documentToObject(v)));
        return result;
    }

    private Object documentToObject(software.amazon.awssdk.core.document.Document doc) {
        if (doc == null || doc.isNull()) return null;
        if (doc.isString()) return doc.asString();
        if (doc.isNumber()) return doc.asNumber().bigDecimalValue();
        if (doc.isBoolean()) return doc.asBoolean();
        if (doc.isList()) {
            return doc.asList().stream().map(this::documentToObject).collect(Collectors.toList());
        }
        if (doc.isMap()) return documentToMap(doc);
        return doc.toString();
    }
}
