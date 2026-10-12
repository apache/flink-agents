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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Map;

/**
 * A single, typed part of a {@link ChatMessage}'s content.
 *
 * <p>Blocks preserve the order of text, media, reasoning, tool calls, and tool results within a
 * message. {@link DataContentBlock} groups the text and media types that may also appear inside a
 * {@link ToolResultBlock}. Block fields are read-only, while metadata and tool inputs use ordinary
 * maps.
 *
 * <p>The serialized form carries a {@code type} discriminator shared with the Python API: {@code
 * text}, {@code image}, {@code audio}, {@code video}, {@code document}, {@code reasoning}, {@code
 * tool_call}, or {@code tool_result}. This allows messages to cross the Java/Python boundary
 * without losing block types or ordering.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
    @JsonSubTypes.Type(value = ImageBlock.class, name = "image"),
    @JsonSubTypes.Type(value = AudioBlock.class, name = "audio"),
    @JsonSubTypes.Type(value = VideoBlock.class, name = "video"),
    @JsonSubTypes.Type(value = DocumentBlock.class, name = "document"),
    @JsonSubTypes.Type(value = ReasoningBlock.class, name = "reasoning"),
    @JsonSubTypes.Type(value = ToolCallBlock.class, name = "tool_call"),
    @JsonSubTypes.Type(value = ToolResultBlock.class, name = "tool_result")
})
public abstract class ContentBlock {

    /** The wire discriminator of this block: {@code text}, {@code image}, {@code audio}, ... */
    @JsonProperty("type")
    public abstract String getType();

    /**
     * The log-safe projection of this block, as a plain map in the wire's snake_case shape. Each
     * block type defines its own logging policy: text passes through unchanged (the Event Log's
     * level-dependent truncation still applies downstream), while media blocks whitelist their
     * metadata, omit inline payload bytes, and sanitize URLs. Normal Jackson serialization — the
     * Java/Python bridge, event serialization, state recovery — is unaffected and preserves the
     * complete payload.
     *
     * <p>The result is intentionally not a valid wire block (media payloads are gone for good), so
     * it must never be deserialized back into a {@link ContentBlock}.
     */
    public abstract Map<String, Object> sanitize();
}
