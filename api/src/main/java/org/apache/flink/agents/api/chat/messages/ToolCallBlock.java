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
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** A model tool call. The ID is unique within its tool request, not globally. */
public final class ToolCallBlock extends ContentBlock {
    private static final String TYPE_FIELD = "type";
    private static final String CALL_ID_FIELD = "call_id";
    private static final String NAME_FIELD = "name";
    private static final String INPUT_FIELD = "input";
    private static final String METADATA_FIELD = "metadata";

    private final String callId;
    private final String name;
    private final Map<String, Object> input;
    private final Map<String, Object> metadata;

    @JsonCreator
    public ToolCallBlock(
            @JsonProperty(CALL_ID_FIELD)
                    @JsonDeserialize(using = MediaFieldDeserializers.StringValue.class)
                    String callId,
            @JsonProperty(NAME_FIELD)
                    @JsonDeserialize(using = MediaFieldDeserializers.StringValue.class)
                    String name,
            @JsonProperty(INPUT_FIELD) Map<String, Object> input,
            @JsonProperty(METADATA_FIELD) Map<String, Object> metadata) {
        if (callId == null || callId.isEmpty()) {
            throw new IllegalArgumentException("call_id must not be empty");
        }
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        this.callId = callId;
        this.name = name;
        this.input = input == null ? new HashMap<>() : input;
        this.metadata = metadata == null ? new HashMap<>() : metadata;
    }

    public ToolCallBlock(String callId, String name, Map<String, Object> input) {
        this(callId, name, input, null);
    }

    @JsonProperty(CALL_ID_FIELD)
    public String getCallId() {
        return callId;
    }

    public String getName() {
        return name;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    @Override
    public String getType() {
        return "tool_call";
    }

    @Override
    public Map<String, Object> sanitize() {
        return Map.of(
                TYPE_FIELD, getType(),
                CALL_ID_FIELD, callId,
                NAME_FIELD, name,
                INPUT_FIELD, input,
                METADATA_FIELD, metadata);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ToolCallBlock)) {
            return false;
        }
        ToolCallBlock b = (ToolCallBlock) o;
        return callId.equals(b.callId)
                && name.equals(b.name)
                && input.equals(b.input)
                && metadata.equals(b.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(callId, name, input, metadata);
    }
}
