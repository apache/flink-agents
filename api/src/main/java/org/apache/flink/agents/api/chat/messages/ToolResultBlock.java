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
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Model-facing tool output containing ordered text and media blocks.
 *
 * <p>The call ID associates this result with a {@link ToolCallBlock}. Its content cannot contain
 * reasoning, tool calls, or nested tool results. Execution metrics remain in ToolResponse.
 */
public final class ToolResultBlock extends ContentBlock {
    private static final String TYPE_FIELD = "type";
    private static final String CALL_ID_FIELD = "call_id";
    private static final String BLOCKS_FIELD = "blocks";
    private static final String IS_ERROR_FIELD = "is_error";
    private static final String METADATA_FIELD = "metadata";

    private final String callId;
    private final List<DataContentBlock> blocks;
    private final boolean error;
    private final Map<String, Object> metadata;

    @JsonCreator
    public ToolResultBlock(
            @JsonProperty(CALL_ID_FIELD)
                    @JsonDeserialize(using = MediaFieldDeserializers.StringValue.class)
                    String callId,
            @JsonProperty(BLOCKS_FIELD) List<? extends DataContentBlock> blocks,
            @JsonProperty(IS_ERROR_FIELD) boolean error,
            @JsonProperty(METADATA_FIELD) Map<String, Object> metadata) {
        if (callId == null || callId.isEmpty()) {
            throw new IllegalArgumentException("call_id must not be empty");
        }
        this.callId = callId;
        // Kryo restores collections by adding elements to the backing list.
        this.blocks = new ArrayList<>(List.copyOf(Objects.requireNonNull(blocks, BLOCKS_FIELD)));
        this.error = error;
        this.metadata = metadata == null ? new HashMap<>() : metadata;
    }

    public ToolResultBlock(String callId, List<? extends DataContentBlock> blocks, boolean error) {
        this(callId, blocks, error, null);
    }

    @JsonProperty(CALL_ID_FIELD)
    public String getCallId() {
        return callId;
    }

    public List<DataContentBlock> getBlocks() {
        return Collections.unmodifiableList(blocks);
    }

    @JsonProperty(IS_ERROR_FIELD)
    public boolean isError() {
        return error;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    @JsonIgnore
    public String getText() {
        return blocks.stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .collect(Collectors.joining());
    }

    @Override
    public String getType() {
        return "tool_result";
    }

    @Override
    public Map<String, Object> sanitize() {
        return Map.of(
                TYPE_FIELD,
                getType(),
                CALL_ID_FIELD,
                callId,
                IS_ERROR_FIELD,
                error,
                BLOCKS_FIELD,
                blocks.stream().map(ContentBlock::sanitize).collect(Collectors.toList()),
                METADATA_FIELD,
                metadata);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ToolResultBlock)) {
            return false;
        }
        ToolResultBlock b = (ToolResultBlock) o;
        return callId.equals(b.callId)
                && blocks.equals(b.blocks)
                && error == b.error
                && metadata.equals(b.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(callId, blocks, error, metadata);
    }
}
