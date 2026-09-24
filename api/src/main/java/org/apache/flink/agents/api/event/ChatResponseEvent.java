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
import org.apache.flink.agents.api.chat.messages.ChatMessage;

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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ChatResponseEvent success(UUID requestId, ChatMessage response) {
        return success(requestId, response, 0, 0);
    }

    public static ChatResponseEvent success(
            UUID requestId, ChatMessage response, int retryCount, int totalRetryWaitSec) {
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
            ChatMessage response,
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
            attributes.put(RESPONSE, MAPPER.convertValue(rawResponse, ChatMessage.class));
        }
        if (!(attributes.get(REQUEST_ID) instanceof UUID)) {
            throw new IllegalArgumentException("request_id must be a UUID");
        }
        validate(attributes.get(STATUS), attributes.get(RESPONSE), attributes.get(ERROR));
        return attributes;
    }

    private static void validate(Object status, Object response, Object error) {
        if (SUCCESS.equals(status) && response instanceof ChatMessage && error == null) {
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
     * @param event the base event containing chat response data in attributes
     * @return a typed ChatResponseEvent
     */
    public static ChatResponseEvent fromEvent(Event event) {
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
    public ChatMessage getResponse() {
        if (isFailed()) {
            throw new ChatResponseException(getRequestId(), getError());
        }
        return (ChatMessage) getAttr(RESPONSE);
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
