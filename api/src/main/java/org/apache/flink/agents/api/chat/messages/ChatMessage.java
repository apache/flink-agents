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

package org.apache.flink.agents.api.chat.messages;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Chat message class that represents all message types (user, system, assistant, tool) with
 * different roles.
 *
 * <p>Message content is an ordered list of typed {@link ContentBlock}s ({@link TextBlock} plus the
 * media blocks); a text-only message simply carries one {@link TextBlock}. The string convenience
 * constructors and factories preserve the text-message experience, and {@link #getText()} is the
 * ordered concatenation of the text blocks.
 */
public class ChatMessage {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MessageRole role;
    private List<ContentBlock> blocks;

    @JsonProperty("tool_calls")
    private List<Map<String, Object>> toolCalls;

    @JsonProperty("extra_args")
    private Map<String, Object> extraArgs;

    /** Default constructor with SYSTEM role */
    public ChatMessage() {
        this(MessageRole.SYSTEM, (List<ContentBlock>) null, null, null);
    }

    /** Constructor with role and text content */
    public ChatMessage(MessageRole role, String text) {
        this(role, blocksOf(text), null, null);
    }

    /** Constructor with role and content blocks */
    public ChatMessage(MessageRole role, List<ContentBlock> blocks) {
        this(role, blocks, null, null);
    }

    public ChatMessage(MessageRole role, String text, Map<String, Object> extraArgs) {
        this(role, blocksOf(text), null, extraArgs);
    }

    public ChatMessage(MessageRole role, String text, List<Map<String, Object>> toolCalls) {
        this(role, blocksOf(text), toolCalls, null);
    }

    public ChatMessage(
            MessageRole role,
            String text,
            List<Map<String, Object>> toolCalls,
            Map<String, Object> extraArgs) {
        this(role, blocksOf(text), toolCalls, extraArgs);
    }

    /** Full constructor */
    public ChatMessage(
            MessageRole role,
            List<ContentBlock> blocks,
            List<Map<String, Object>> toolCalls,
            Map<String, Object> extraArgs) {
        this.role = role != null ? role : MessageRole.SYSTEM;
        this.blocks = blocks != null ? new ArrayList<>(blocks) : new ArrayList<>();
        this.toolCalls = toolCalls != null ? toolCalls : new ArrayList<>();
        this.extraArgs = extraArgs != null ? new HashMap<>(extraArgs) : new HashMap<>();
    }

    /** An empty or null text becomes an empty block list rather than an empty text block. */
    private static List<ContentBlock> blocksOf(String text) {
        return text == null || text.isEmpty()
                ? Collections.emptyList()
                : Collections.singletonList(new TextBlock(text));
    }

    public MessageRole getRole() {
        return role;
    }

    public void setRole(MessageRole role) {
        this.role = role;
    }

    public List<ContentBlock> getBlocks() {
        return blocks;
    }

    public void setBlocks(List<ContentBlock> blocks) {
        this.blocks = blocks != null ? blocks : new ArrayList<>();
    }

    /** Replaces the content with a single text block (empty text clears the content). */
    @JsonIgnore
    public void setText(String text) {
        this.blocks = new ArrayList<>(blocksOf(text));
    }

    @JsonProperty("tool_calls")
    public List<Map<String, Object>> getToolCalls() {
        return toolCalls;
    }

    @JsonProperty("tool_calls")
    public void setToolCalls(List<Map<String, Object>> toolCalls) {
        this.toolCalls = toolCalls;
    }

    @JsonProperty("extra_args")
    public Map<String, Object> getExtraArgs() {
        return extraArgs;
    }

    @JsonProperty("extra_args")
    public void setExtraArgs(Map<String, Object> extraArgs) {
        this.extraArgs = extraArgs != null ? extraArgs : new HashMap<>();
    }

    /**
     * The content blocks as plain maps in the serialized (snake_case, discriminated) shape — the
     * same representation {@code tool_calls} uses. This is how blocks cross the Python bridge,
     * which exchanges JSON-friendly lists and maps rather than typed Java objects.
     */
    @JsonIgnore
    public List<Map<String, Object>> getBlocksAsMaps() {
        return blocks.stream()
                .map(
                        block ->
                                MAPPER.<Map<String, Object>>convertValue(
                                        block, new TypeReference<Map<String, Object>>() {}))
                .collect(Collectors.toList());
    }

    /** Replaces the content with blocks given as plain maps — see {@link #getBlocksAsMaps()}. */
    @JsonIgnore
    public void setBlocksFromMaps(List<Map<String, Object>> blockMaps) {
        this.blocks =
                blockMaps == null
                        ? new ArrayList<>()
                        : blockMaps.stream()
                                .map(map -> MAPPER.convertValue(map, ContentBlock.class))
                                .collect(Collectors.toCollection(ArrayList::new));
    }

    /** The text projection: the ordered concatenation of this message's {@link TextBlock}s. */
    @JsonIgnore
    public String getText() {
        return blocks.stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .collect(Collectors.joining());
    }

    @JsonIgnore
    public Map<String, Object> getMetadata() {
        return this.extraArgs;
    }

    @JsonIgnore
    public MessageRole getMessageType() {
        return this.role;
    }

    // Static factory methods for convenience
    public static ChatMessage user(String text) {
        return new ChatMessage(MessageRole.USER, text);
    }

    public static ChatMessage user(List<ContentBlock> blocks) {
        return new ChatMessage(MessageRole.USER, blocks);
    }

    public static ChatMessage system(String text) {
        return new ChatMessage(MessageRole.SYSTEM, text);
    }

    public static ChatMessage assistant(String text) {
        return new ChatMessage(MessageRole.ASSISTANT, text);
    }

    public static ChatMessage assistant(String text, List<Map<String, Object>> toolCalls) {
        return new ChatMessage(MessageRole.ASSISTANT, text, toolCalls, new HashMap<>());
    }

    public static ChatMessage tool(String text) {
        return new ChatMessage(MessageRole.TOOL, text);
    }

    public static ChatMessage tool(List<ContentBlock> blocks) {
        return new ChatMessage(MessageRole.TOOL, blocks);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChatMessage)) return false;
        ChatMessage that = (ChatMessage) o;
        return Objects.equals(role, that.role)
                && Objects.equals(blocks, that.blocks)
                && Objects.equals(toolCalls, that.toolCalls)
                && Objects.equals(extraArgs, that.extraArgs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, blocks, toolCalls, extraArgs);
    }

    @Override
    public String toString() {
        return role.getValue() + ": " + getText();
    }

    /** Return the index of the first system message in the list, or -1 if none. */
    public static int findFirstSystemMessage(List<ChatMessage> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getRole() == MessageRole.SYSTEM) {
                return i;
            }
        }
        return -1;
    }
}
