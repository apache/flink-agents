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

package org.apache.flink.agents.api.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.chat.messages.ChatResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class ChatResponseEvent extends Event {

    public static final String EVENT_TYPE = "_chat_response_event";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    private static final String REQUEST_ID = "request_id";
    private static final String STATUS = "status";
    private static final String RESPONSE = "response";
    private static final String ERROR = "error";
    private static final String RETRY_COUNT = "retry_count";
    private static final String TOTAL_RETRY_WAIT_SEC = "total_retry_wait_sec";
    private static final String STRUCTURED_OUTPUT = "structured_output";
    private static final String MODEL_ROUTING = "model_routing";

    private static final List<BuiltInAttribute> ATTRIBUTE_SCHEMA =
            List.of(
                    BuiltInAttribute.requiredUntyped(REQUEST_ID),
                    BuiltInAttribute.requiredUntyped(STATUS),
                    BuiltInAttribute.optionalUntyped(RESPONSE),
                    BuiltInAttribute.optionalUntyped(ERROR),
                    BuiltInAttribute.optional(RETRY_COUNT, Number.class),
                    BuiltInAttribute.optional(TOTAL_RETRY_WAIT_SEC, Number.class),
                    BuiltInAttribute.optionalUntyped(STRUCTURED_OUTPUT),
                    BuiltInAttribute.optional(MODEL_ROUTING, Map.class));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ChatResponseEvent success(UUID requestId, ChatResult response) {
        return success(requestId, response, 0, 0);
    }

    public static ChatResponseEvent success(
            UUID requestId, ChatResult response, int retryCount, int totalRetryWaitSec) {
        return new ChatResponseEvent(
                requestId, SUCCESS, response, null, retryCount, totalRetryWaitSec);
    }

    public static ChatResponseEvent failed(
            UUID requestId, String error, int retryCount, int totalRetryWaitSec) {
        return new ChatResponseEvent(requestId, FAILED, null, error, retryCount, totalRetryWaitSec);
    }

    private ChatResponseEvent(
            UUID requestId,
            String status,
            ChatResult response,
            String error,
            int retryCount,
            int totalRetryWaitSec) {
        super(EVENT_TYPE);
        Objects.requireNonNull(requestId, REQUEST_ID);
        validate(status, response, error);
        setAttr(REQUEST_ID, requestId);
        setAttr(STATUS, status);
        setAttr(RESPONSE, response);
        setAttr(ERROR, error);
        setAttr(RETRY_COUNT, retryCount);
        setAttr(TOTAL_RETRY_WAIT_SEC, totalRetryWaitSec);
    }

    @JsonCreator
    public ChatResponseEvent(
            @JsonProperty("id") UUID id,
            @JsonProperty("attributes") Map<String, Object> attributes) {
        super(id, EVENT_TYPE, normalizeAttributes(attributes));
    }

    /** Converts nested attributes back to their typed forms. */
    private static Map<String, Object> normalizeAttributes(Map<String, Object> attributes) {
        Objects.requireNonNull(attributes.get(REQUEST_ID), REQUEST_ID);
        Object rawId = attributes.get(REQUEST_ID);
        if (rawId instanceof String) {
            attributes.put(REQUEST_ID, UUID.fromString((String) rawId));
        }
        Object rawResponse = attributes.get(RESPONSE);
        if (rawResponse instanceof Map) {
            attributes.put(RESPONSE, MAPPER.convertValue(rawResponse, ChatResult.class));
        }
        if (!(attributes.get(REQUEST_ID) instanceof UUID)) {
            throw new IllegalArgumentException("request_id must be a UUID");
        }
        validate(attributes.get(STATUS), attributes.get(RESPONSE), attributes.get(ERROR));
        return attributes;
    }

    private static void validate(Object status, Object response, Object error) {
        if (SUCCESS.equals(status) && response instanceof ChatResult && error == null) {
            return;
        }
        if (FAILED.equals(status)
                && response == null
                && error instanceof String
                && !((String) error).isEmpty()) {
            return;
        }
        throw new IllegalArgumentException(
                "Chat response requires SUCCESS with response or FAILED with error.");
    }

    public void setStructuredOutput(Object value) {
        setAttr(STRUCTURED_OUTPUT, value);
    }

    @JsonIgnore
    public Object getStructuredOutput() {
        return getAttr(STRUCTURED_OUTPUT);
    }

    public void setRoutingMetadata(Map<String, Object> value) {
        setAttr(MODEL_ROUTING, value);
    }

    @JsonIgnore
    public String getStatus() {
        return (String) getAttr(STATUS);
    }

    @JsonIgnore
    public boolean isSuccess() {
        return SUCCESS.equals(getStatus());
    }

    @JsonIgnore
    public boolean isFailed() {
        return FAILED.equals(getStatus());
    }

    @JsonIgnore
    public String getError() {
        if (!isFailed()) {
            throw new IllegalStateException("A successful chat response has no error.");
        }
        return (String) getAttr(ERROR);
    }

    /** Failure received from a chat request; the original exception is stored as text only. */
    public static class ChatResponseException extends RuntimeException {
        private final UUID requestId;

        public ChatResponseException(UUID requestId, String error) {
            super(error);
            this.requestId = requestId;
        }

        public UUID getRequestId() {
            return requestId;
        }
    }

    /**
     * Reconstructs a typed ChatResponseEvent from a base Event, deserializing nested types.
     *
     * <p>Enforces the fixed cross-language schema: {@code request_id} and {@code status} are
     * required and no attribute outside the schema is allowed. The {@code request_id} UUID type and
     * the SUCCESS-with-response / FAILED-with-error consistency are enforced by {@link
     * #normalizeAttributes(Map)} in the {@code @JsonCreator} constructor invoked below.
     *
     * @param event the base event containing chat response data in attributes
     * @return a typed ChatResponseEvent
     * @throws IllegalArgumentException if the event violates the schema
     */
    public static ChatResponseEvent fromEvent(Event event) {
        validateAttributeSchema(EVENT_TYPE, event.getAttributes(), ATTRIBUTE_SCHEMA);
        return reconstructFrom(event, ChatResponseEvent::new);
    }

    @JsonIgnore
    public UUID getRequestId() {
        Object val = getAttr(REQUEST_ID);
        if (val instanceof String) {
            return UUID.fromString((String) val);
        }
        return (UUID) val;
    }

    @JsonIgnore
    public ChatResult getResponse() {
        if (isFailed()) {
            throw new ChatResponseException(getRequestId(), getError());
        }
        return (ChatResult) getAttr(RESPONSE);
    }

    @JsonIgnore
    public int getRetryCount() {
        return ((Number) getAttr(RETRY_COUNT)).intValue();
    }

    @JsonIgnore
    public int getTotalRetryWaitSec() {
        return ((Number) getAttr(TOTAL_RETRY_WAIT_SEC)).intValue();
    }
}
