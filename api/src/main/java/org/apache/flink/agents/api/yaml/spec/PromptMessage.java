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

package org.apache.flink.agents.api.yaml.spec;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.chat.messages.ContentBlock;
import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.chat.messages.MessageRole;

import java.util.ArrayList;
import java.util.List;

/**
 * One message in a multi-turn prompt template. {@code content} is either text or a non-empty list
 * of content blocks in the {@code ChatMessage} block format ({@code text}, {@code image}, {@code
 * audio}, {@code video} or {@code document}), so a prompt can carry media next to its text.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public final class PromptMessage {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MessageRole role;
    private final String content;
    private final List<DataContentBlock> blocks;

    @JsonCreator
    public PromptMessage(
            @JsonProperty("role") String role,
            @JsonProperty(value = "content", required = true) JsonNode content) {
        this.role = role == null ? MessageRole.USER : MessageRole.fromValue(role);
        if (content != null && content.isArray()) {
            this.content = null;
            this.blocks = parseBlocks(content);
        } else if (content != null && content.isValueNode() && !content.isNull()) {
            this.content = content.asText();
            this.blocks = null;
        } else {
            throw new IllegalArgumentException(
                    "prompt message 'content' must be text or a list of content blocks");
        }
    }

    public MessageRole getRole() {
        return role;
    }

    /** The text content, or {@code null} when the content is a list of blocks. */
    public String getContent() {
        return content;
    }

    /** The content blocks, or {@code null} when the content is text. */
    public List<DataContentBlock> getBlocks() {
        return blocks;
    }

    private static List<DataContentBlock> parseBlocks(JsonNode node) {
        if (node.isEmpty()) {
            throw new IllegalArgumentException(
                    "prompt message 'content' must not be an empty list of content blocks");
        }
        List<DataContentBlock> blocks = new ArrayList<>(node.size());
        for (JsonNode element : node) {
            ContentBlock block;
            try {
                block = MAPPER.treeToValue(element, ContentBlock.class);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "invalid content block in prompt message: " + e.getMessage(), e);
            }
            if (!(block instanceof DataContentBlock)) {
                throw new IllegalArgumentException(
                        "prompt message content blocks must be text, image, audio, video or"
                                + " document blocks, not "
                                + block.getType());
            }
            blocks.add((DataContentBlock) block);
        }
        return List.copyOf(blocks);
    }
}
