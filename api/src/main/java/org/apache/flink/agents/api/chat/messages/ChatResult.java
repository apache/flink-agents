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
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The result of a large language model (LLM) invocation.
 *
 * <p>Contains the generated assistant message, along with optional model information, a response
 * ID, token usage, a finish reason, and metadata about the invocation.
 */
public final class ChatResult {
    private static final String MESSAGE_FIELD = "message";
    private static final String MODEL_FIELD = "model";
    private static final String RESPONSE_ID_FIELD = "response_id";
    private static final String USAGE_FIELD = "usage";
    private static final String FINISH_REASON_FIELD = "finish_reason";
    private static final String METADATA_FIELD = "metadata";

    private final ChatMessage message;
    private final String model;
    private final String responseId;
    private final TokenUsage usage;
    private final String finishReason;
    private final Map<String, Object> metadata;

    @JsonCreator
    public ChatResult(
            @JsonProperty(MESSAGE_FIELD) ChatMessage message,
            @JsonProperty(MODEL_FIELD)
                    @JsonDeserialize(using = MediaFieldDeserializers.StringValue.class)
                    String model,
            @JsonProperty(RESPONSE_ID_FIELD)
                    @JsonDeserialize(using = MediaFieldDeserializers.StringValue.class)
                    String responseId,
            @JsonProperty(USAGE_FIELD) TokenUsage usage,
            @JsonProperty(FINISH_REASON_FIELD)
                    @JsonDeserialize(using = MediaFieldDeserializers.StringValue.class)
                    String finishReason,
            @JsonProperty(METADATA_FIELD) Map<String, Object> metadata) {
        this.message = Objects.requireNonNull(message, MESSAGE_FIELD);
        this.model = model;
        this.responseId = responseId;
        this.usage = usage;
        this.finishReason = finishReason;
        this.metadata = metadata == null ? new HashMap<>() : metadata;
        validate();
    }

    public ChatResult(ChatMessage message) {
        this(message, null, null, null, null, null);
    }

    private void validate() {
        if (message.getRole() != MessageRole.ASSISTANT) {
            throw new IllegalArgumentException("ChatResult requires an ASSISTANT message");
        }
    }

    @JsonIgnore
    public String getText() {
        return message.getText();
    }

    @JsonIgnore
    public List<ToolCallBlock> getToolCalls() {
        return message.getToolCalls();
    }

    public ChatMessage getMessage() {
        return message;
    }

    public String getModel() {
        return model;
    }

    @JsonProperty(RESPONSE_ID_FIELD)
    public String getResponseId() {
        return responseId;
    }

    public TokenUsage getUsage() {
        return usage;
    }

    @JsonProperty(FINISH_REASON_FIELD)
    public String getFinishReason() {
        return finishReason;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    @JsonIgnore
    public Map<String, Object> toMap() {
        return new ObjectMapper().convertValue(this, new TypeReference<Map<String, Object>>() {});
    }

    public static ChatResult fromMap(Map<String, Object> map) {
        return new ObjectMapper().convertValue(map, ChatResult.class);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ChatResult)) {
            return false;
        }
        ChatResult that = (ChatResult) other;
        return Objects.equals(message, that.message)
                && Objects.equals(model, that.model)
                && Objects.equals(responseId, that.responseId)
                && Objects.equals(usage, that.usage)
                && Objects.equals(finishReason, that.finishReason)
                && Objects.equals(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(message, model, responseId, usage, finishReason, metadata);
    }
}
