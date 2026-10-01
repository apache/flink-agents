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
import com.openai.core.JsonValue;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.Tool;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ContentBlock;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.ReasoningBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.apache.flink.agents.api.tools.ToolMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Converts Flink Agents messages and tool definitions to and from OpenAI Responses SDK types. */
final class OpenAIResponsesUtils {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final ObjectMapper MAPPER = com.openai.core.ObjectMappers.jsonMapper();

    private static final String REASONING_METADATA = "openai_responses";
    private static final String ITEM_ID = "item_id";
    private static final String REFUSAL = "refusal";

    private OpenAIResponsesUtils() {}

    static List<ResponseInputItem> convertInputItems(List<ChatMessage> messages) {
        List<ResponseInputItem> items = new ArrayList<>();
        for (ChatMessage message : messages) {
            items.addAll(convertSingleMessage(message));
        }
        return items;
    }

    static List<ResponseInputItem> convertSingleMessage(ChatMessage message) {
        List<ResponseInputItem> items = new ArrayList<>();
        List<ContentBlock> contentBlocks =
                message.getRole() == MessageRole.TOOL
                        ? ((ToolResultBlock) message.getBlocks().get(0)).getBlocks()
                        : message.getBlocks();
        for (ContentBlock block : contentBlocks) {
            if (block instanceof org.apache.flink.agents.api.chat.messages.MediaBlock) {
                throw new IllegalArgumentException(
                        "OpenAI Responses does not support " + block.getType() + " content yet");
            }
        }
        MessageRole role = message.getRole();
        String content = message.getText();

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
                items.addAll(convertAssistantMessage(message));
                break;

            case TOOL:
                ToolResultBlock toolResult = (ToolResultBlock) message.getBlocks().get(0);
                String toolCallId = toolResult.getCallId();
                content =
                        toolResult.getBlocks().stream()
                                .filter(block -> block instanceof TextBlock)
                                .map(block -> ((TextBlock) block).getText())
                                .collect(Collectors.joining());
                items.add(
                        ResponseInputItem.ofFunctionCallOutput(
                                ResponseInputItem.FunctionCallOutput.builder()
                                        .callId(toolCallId)
                                        .output(content)
                                        .build()));
                break;

            default:
                throw new IllegalArgumentException("Unsupported role: " + role);
        }
        return items;
    }

    private static List<ResponseInputItem> convertAssistantMessage(ChatMessage message) {
        List<ResponseInputItem> items = new ArrayList<>();
        for (ContentBlock block : message.getBlocks()) {
            if (block instanceof ToolCallBlock) {
                ToolCallBlock call = (ToolCallBlock) block;
                ResponseFunctionToolCall.Builder builder =
                        ResponseFunctionToolCall.builder()
                                .callId(call.getCallId())
                                .name(call.getName())
                                .arguments(serializeArguments(call.getInput()))
                                .status(ResponseFunctionToolCall.Status.COMPLETED);
                Object itemId = call.getMetadata().get(ITEM_ID);
                if (itemId != null) {
                    builder.id(itemId.toString());
                }
                items.add(ResponseInputItem.ofFunctionCall(builder.build()));
            } else if (block instanceof TextBlock) {
                items.add(
                        ResponseInputItem.ofEasyInputMessage(
                                EasyInputMessage.builder()
                                        .role(EasyInputMessage.Role.ASSISTANT)
                                        .content(((TextBlock) block).getText())
                                        .build()));
            } else if (block instanceof ReasoningBlock) {
                Object nativeItem = ((ReasoningBlock) block).getMetadata().get(REASONING_METADATA);
                if (nativeItem != null) {
                    items.add(
                            ResponseInputItem.ofReasoning(
                                    MAPPER.convertValue(nativeItem, ResponseReasoningItem.class)));
                }
            }
        }
        return items;
    }

    static ChatMessage convertResponse(Response response) {
        List<ResponseOutputItem> output = response.output();
        if (output == null || output.isEmpty()) {
            throw new IllegalStateException("OpenAI Responses API did not return any output.");
        }

        List<ContentBlock> blocks = new ArrayList<>();
        StringBuilder refusal = new StringBuilder();
        for (ResponseOutputItem item : output) {
            if (item.isMessage()) {
                for (ResponseOutputMessage.Content content : item.asMessage().content()) {
                    if (content.isOutputText()) {
                        blocks.add(new TextBlock(content.asOutputText().text()));
                    } else if (content.isRefusal()) {
                        refusal.append(content.asRefusal().refusal());
                    }
                }
            } else if (item.isReasoning()) {
                ResponseReasoningItem reasoning = item.asReasoning();
                String text =
                        reasoning.summary().stream()
                                .map(ResponseReasoningItem.Summary::text)
                                .collect(Collectors.joining());
                blocks.add(
                        new ReasoningBlock(
                                text.isEmpty() ? null : text,
                                Map.of(
                                        REASONING_METADATA,
                                        MAPPER.convertValue(reasoning, MAP_TYPE))));
            } else if (item.isFunctionCall()) {
                ResponseFunctionToolCall call = item.asFunctionCall();
                Map<String, Object> metadata = new LinkedHashMap<>();
                call.id().ifPresent(id -> metadata.put(ITEM_ID, id));
                blocks.add(
                        new ToolCallBlock(
                                call.callId(),
                                call.name(),
                                parseArguments(call.arguments()),
                                metadata));
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (refusal.length() > 0) {
            metadata.put(REFUSAL, refusal.toString());
        }
        return ChatMessage.assistant(blocks).withMetadata(metadata);
    }

    private static Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(arguments, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse tool arguments: " + arguments, e);
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

    private static String serializeArguments(Map<String, Object> arguments) {
        try {
            return MAPPER.writeValueAsString(arguments);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize tool call arguments.", e);
        }
    }

    static List<Tool> convertTools(
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

    private static FunctionTool.Parameters parseToolParameters(String schemaJson) {
        try {
            JsonNode root = MAPPER.readTree(schemaJson);
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
}
