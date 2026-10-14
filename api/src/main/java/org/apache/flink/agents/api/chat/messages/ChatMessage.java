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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A message in a conversation, consisting of a role, an ordered list of content blocks, and
 * optional metadata.
 *
 * <p>The role identifies the source or purpose of the message. System messages contain text
 * instructions, user messages can combine text and media, and assistant messages can include text,
 * reasoning, and tool calls. A tool message contains exactly one {@link ToolResultBlock}, which
 * associates the result with its tool call.
 *
 * <p>Content blocks preserve their order within the message, allowing different kinds of content to
 * be represented together. {@link #getText()} concatenates only the top-level text blocks;
 * reasoning and content nested inside tool results are excluded. {@link #getToolCalls()} returns
 * the tool calls in block order. Tool call IDs must be unique within a message.
 *
 * <p>Metadata holds additional message-level information, such as provider-specific attributes. It
 * is separate from the content blocks and is not included by {@link #getText()}.
 */
public final class ChatMessage {
    private static final String ROLE_FIELD = "role";
    private static final String BLOCKS_FIELD = "blocks";
    private static final String METADATA_FIELD = "metadata";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final MessageRole role;
    private final List<ContentBlock> blocks;
    private final Map<String, Object> metadata;

    @JsonCreator
    public ChatMessage(
            @JsonProperty(ROLE_FIELD) MessageRole role,
            @JsonProperty(BLOCKS_FIELD) List<? extends ContentBlock> blocks,
            @JsonProperty(METADATA_FIELD) Map<String, Object> metadata) {
        this.role = Objects.requireNonNull(role, ROLE_FIELD);
        // Kryo restores collections by adding elements to the backing list.
        this.blocks = new ArrayList<>(List.copyOf(Objects.requireNonNull(blocks, BLOCKS_FIELD)));
        this.metadata = metadata == null ? new HashMap<>() : metadata;
        validate();
    }

    public ChatMessage(MessageRole role, List<? extends ContentBlock> blocks) {
        this(role, blocks, null);
    }

    public ChatMessage(MessageRole role, String text) {
        this(role, text == null || text.isEmpty() ? List.of() : List.of(new TextBlock(text)));
    }

    private void validate() {
        if (role == MessageRole.TOOL
                && (blocks.size() != 1 || !(blocks.get(0) instanceof ToolResultBlock))) {
            throw new IllegalArgumentException(
                    "A TOOL message requires exactly one ToolResultBlock");
        }
        Set<String> ids = new HashSet<>();
        for (ContentBlock b : blocks) {
            if (role != MessageRole.TOOL && b instanceof ToolResultBlock) {
                throw new IllegalArgumentException("ToolResultBlock requires TOOL role");
            }
            if (role == MessageRole.SYSTEM && !(b instanceof TextBlock)) {
                throw new IllegalArgumentException("SYSTEM messages accept only text");
            }
            if (role == MessageRole.USER && !(b instanceof TextBlock || b instanceof MediaBlock)) {
                throw new IllegalArgumentException("USER messages accept only text and media");
            }
            if (b instanceof ToolCallBlock && !ids.add(((ToolCallBlock) b).getCallId())) {
                throw new IllegalArgumentException("Duplicate tool call ID in one message");
            }
        }
    }

    public MessageRole getRole() {
        return role;
    }

    public List<ContentBlock> getBlocks() {
        return Collections.unmodifiableList(blocks);
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Concatenates top-level text blocks in order. Media, reasoning, tool calls, and nested tool
     * result text are excluded; use {@link #getBlocks()} for the complete message content.
     */
    @JsonIgnore
    public String getText() {
        return blocks.stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .collect(Collectors.joining());
    }

    @JsonIgnore
    public List<ToolCallBlock> getToolCalls() {
        return blocks.stream()
                .filter(b -> b instanceof ToolCallBlock)
                .map(b -> (ToolCallBlock) b)
                .collect(Collectors.toUnmodifiableList());
    }

    public ChatMessage withBlocks(List<? extends ContentBlock> replacement) {
        return new ChatMessage(role, replacement, metadata);
    }

    public ChatMessage withMetadata(Map<String, Object> replacement) {
        return new ChatMessage(role, blocks, replacement);
    }

    @JsonIgnore
    public Map<String, Object> toMap() {
        return MAPPER.convertValue(this, new TypeReference<Map<String, Object>>() {});
    }

    public static ChatMessage fromMap(Map<String, Object> value) {
        Map<String, Object> fields = new HashMap<>(value);
        fields.putIfAbsent(METADATA_FIELD, Map.of());
        if (!fields.containsKey(BLOCKS_FIELD)) {
            fields.put(BLOCKS_FIELD, List.of());
        }
        return MAPPER.convertValue(fields, ChatMessage.class);
    }

    public static ChatMessage user(String text) {
        return new ChatMessage(MessageRole.USER, text);
    }

    public static ChatMessage user(List<? extends ContentBlock> blocks) {
        return new ChatMessage(MessageRole.USER, blocks);
    }

    public static ChatMessage system(String text) {
        return new ChatMessage(MessageRole.SYSTEM, text);
    }

    public static ChatMessage assistant(String text) {
        return new ChatMessage(MessageRole.ASSISTANT, text);
    }

    public static ChatMessage assistant(List<? extends ContentBlock> blocks) {
        return new ChatMessage(MessageRole.ASSISTANT, blocks);
    }

    public static ChatMessage tool(ToolResultBlock result) {
        return new ChatMessage(MessageRole.TOOL, List.of(result));
    }

    public static int findFirstSystemMessage(List<ChatMessage> messages) {
        for (int i = 0; i < messages.size(); i++)
            if (messages.get(i).role == MessageRole.SYSTEM) {
                return i;
            }
        return -1;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ChatMessage)) {
            return false;
        }
        ChatMessage m = (ChatMessage) o;
        return role == m.role && blocks.equals(m.blocks) && metadata.equals(m.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, blocks, metadata);
    }

    @Override
    public String toString() {
        return role.getValue() + ": " + getText();
    }
}
