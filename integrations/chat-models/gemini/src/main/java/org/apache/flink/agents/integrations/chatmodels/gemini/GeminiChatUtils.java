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
package org.apache.flink.agents.integrations.chatmodels.gemini;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponsePart;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;
import org.apache.flink.agents.api.chat.messages.Base64Source;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ContentBlock;
import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.chat.messages.MediaBlock;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.ReasoningBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.apache.flink.agents.api.chat.messages.UnsupportedContentBlockException;
import org.apache.flink.agents.api.chat.messages.UrlSource;
import org.apache.flink.agents.api.tools.ToolMetadata;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/** Converts Flink Agents messages and tool definitions to and from Gemini SDK types. */
final class GeminiChatUtils {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String THOUGHT_SIGNATURE = "thought_signature";
    private static final String SYNTHETIC_ID = "synthetic_id";

    private GeminiChatUtils() {}

    // Package-visible for testing. Walks ASSISTANT messages and records every tool-call's
    // call ID to function name mapping so tool results can resolve their function name.
    static Map<String, String> buildToolCallIdToNameMap(List<ChatMessage> messages) {
        Map<String, String> map = new HashMap<>();
        for (ChatMessage message : messages) {
            if (message.getRole() != MessageRole.ASSISTANT) {
                continue;
            }
            for (ToolCallBlock call : message.getToolCalls()) {
                map.put(call.getCallId(), call.getName());
            }
        }
        return map;
    }

    static Content extractSystemInstruction(List<ChatMessage> messages) {
        Part[] parts =
                messages.stream()
                        .filter(m -> m.getRole() == MessageRole.SYSTEM)
                        .map(m -> Part.fromText(Optional.ofNullable(m.getText()).orElse("")))
                        .toArray(Part[]::new);
        return parts.length == 0 ? null : Content.fromParts(parts);
    }

    static Part convertToolCallToPart(ToolCallBlock call) {
        FunctionCall.Builder function =
                FunctionCall.builder().name(call.getName()).args(call.getInput());
        if (!Boolean.TRUE.equals(call.getMetadata().get(SYNTHETIC_ID))) {
            function.id(call.getCallId());
        }
        Part.Builder part = Part.builder().functionCall(function.build());
        Object signature = call.getMetadata().get(THOUGHT_SIGNATURE);
        if (signature != null) {
            part.thoughtSignature(Base64.getDecoder().decode(signature.toString()));
        }
        return part.build();
    }

    static ChatMessage convertResponse(GenerateContentResponse response) {
        List<Candidate> candidates = response.candidates().orElseGet(List::of);
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "Gemini response did not contain any candidates (likely safety-blocked or filtered).");
        }
        response.checkFinishReason();
        List<ContentBlock> blocks = new ArrayList<>();
        for (Part part : candidates.get(0).content().flatMap(Content::parts).orElseGet(List::of)) {
            if (part.text().isPresent()) {
                if (part.thought().orElse(false)) {
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    part.thoughtSignature()
                            .ifPresent(
                                    sig ->
                                            metadata.put(
                                                    THOUGHT_SIGNATURE,
                                                    Base64.getEncoder().encodeToString(sig)));
                    blocks.add(new ReasoningBlock(part.text().get(), metadata));
                } else {
                    blocks.add(new TextBlock(part.text().get()));
                }
            }
            part.functionCall()
                    .ifPresent(
                            call ->
                                    blocks.add(
                                            convertFunctionCall(
                                                    call, part.thoughtSignature().orElse(null))));
        }
        return ChatMessage.assistant(blocks);
    }

    static ToolCallBlock convertFunctionCall(FunctionCall call, byte[] signature) {
        String id = call.id().orElse(null);
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            metadata.put(SYNTHETIC_ID, true);
        }
        if (signature != null) {
            metadata.put(THOUGHT_SIGNATURE, Base64.getEncoder().encodeToString(signature));
        }
        return new ToolCallBlock(
                id, call.name().orElse(""), call.args().orElseGet(LinkedHashMap::new), metadata);
    }

    static Tool convertTools(List<org.apache.flink.agents.api.tools.Tool> tools) {
        List<FunctionDeclaration> declarations = new ArrayList<>(tools.size());
        for (org.apache.flink.agents.api.tools.Tool tool : tools) {
            ToolMetadata metadata = tool.getMetadata();
            FunctionDeclaration.Builder builder =
                    FunctionDeclaration.builder()
                            .name(metadata.getName())
                            .description(metadata.getDescription());

            String schema = metadata.getInputSchema();
            if (schema != null && !schema.isBlank()) {
                builder.parametersJsonSchema(parseSchema(schema));
            }

            declarations.add(builder.build());
        }
        return Tool.builder().functionDeclarations(declarations).build();
    }

    /**
     * Converts a message to a Gemini turn. Only user messages and tool results can carry media:
     * image, audio, video and document blocks become inline data (Base64) or file data (URL) with
     * the block's media type. A tool result's media become the parts of its function response, in
     * block order, and its text stays in the response's {@code result}. Media in an assistant
     * message throws {@link UnsupportedContentBlockException}.
     */
    // Package-visible for unit testing of the message conversion.
    static Content convertToContent(ChatMessage message, Map<String, String> toolCallIdToName) {
        if (message.getRole() == MessageRole.TOOL) {
            ToolResultBlock result = (ToolResultBlock) message.getBlocks().get(0);
            List<FunctionResponsePart> media = new ArrayList<>();
            for (DataContentBlock block : result.getBlocks()) {
                if (block instanceof MediaBlock) {
                    media.add(
                            convertMedia(
                                    (MediaBlock) block,
                                    FunctionResponsePart::fromUri,
                                    FunctionResponsePart::fromBytes));
                } else if (!(block instanceof TextBlock)) {
                    throw new IllegalArgumentException(
                            "Gemini cannot send tool result block " + block.getType());
                }
            }
            return Content.builder()
                    .role("user")
                    .parts(
                            List.of(
                                    Part.fromFunctionResponse(
                                            resolveToolFunctionName(message, toolCallIdToName),
                                            Map.of("result", result.getText()),
                                            media.toArray(new FunctionResponsePart[0]))))
                    .build();
        }
        List<Part> parts = new ArrayList<>();
        for (ContentBlock block : message.getBlocks()) {
            if (block instanceof TextBlock) {
                parts.add(Part.fromText(((TextBlock) block).getText()));
            } else if (block instanceof ToolCallBlock) {
                parts.add(convertToolCallToPart((ToolCallBlock) block));
            } else if (block instanceof ReasoningBlock) {
                ReasoningBlock reasoning = (ReasoningBlock) block;
                Object signature = reasoning.getMetadata().get(THOUGHT_SIGNATURE);
                if (signature != null) {
                    Part.Builder part = Part.builder().thought(true).text(reasoning.getText());
                    part.thoughtSignature(Base64.getDecoder().decode(signature.toString()));
                    parts.add(part.build());
                }
            } else if (block instanceof MediaBlock) {
                if (message.getRole() != MessageRole.USER) {
                    throw UnsupportedContentBlockException.forBlock(
                            "Gemini",
                            block,
                            "only user messages and tool results can carry media, not "
                                    + message.getRole().getValue()
                                    + " messages");
                }
                parts.add(convertMedia((MediaBlock) block, Part::fromUri, Part::fromBytes));
            } else {
                throw new IllegalArgumentException("Gemini cannot send block " + block.getType());
            }
        }
        if (parts.isEmpty()) {
            parts.add(Part.fromText(""));
        }
        return Content.builder()
                .role(message.getRole() == MessageRole.ASSISTANT ? "model" : "user")
                .parts(parts)
                .build();
    }

    /**
     * Converts a media block the same way in a user message and in a tool result, which take
     * different SDK types: a URL becomes file data, and Base64 data is decoded into inline data.
     * Both carry the block's media type. Malformed Base64 data throws {@link
     * IllegalArgumentException}.
     */
    private static <T> T convertMedia(
            MediaBlock block,
            BiFunction<String, String, T> fromUri,
            BiFunction<byte[], String, T> fromBytes) {
        if (block.getSource() instanceof UrlSource) {
            return fromUri.apply(((UrlSource) block.getSource()).getUrl(), block.getMediaType());
        }
        return fromBytes.apply(
                Base64.getDecoder().decode(((Base64Source) block.getSource()).getData()),
                block.getMediaType());
    }

    private static String resolveToolFunctionName(ChatMessage message, Map<String, String> names) {
        Object explicit = message.getMetadata().get("name");
        if (explicit != null) {
            return explicit.toString();
        }
        String id = ((ToolResultBlock) message.getBlocks().get(0)).getCallId();
        String name = names == null ? null : names.get(id);
        if (name == null) {
            throw new IllegalArgumentException("Tool result has no matching tool call name: " + id);
        }
        return name;
    }

    private static Object parseSchema(String schemaJson) {
        try {
            return MAPPER.readValue(schemaJson, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse tool schema JSON.", e);
        }
    }
}
