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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.chat.messages.ChatMessage;

import java.util.Map;
import java.util.UUID;

public class ChatResponseEvent extends Event {

    public static final String EVENT_TYPE = "_chat_response_event";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ChatResponseEvent(UUID requestId, ChatMessage response) {
        this(requestId, response, 0, 0);
    }

    public ChatResponseEvent(
            UUID requestId, ChatMessage response, int retryCount, int totalRetryWaitSec) {
        super(EVENT_TYPE);
        setAttr("request_id", requestId);
        setAttr("response", response);
        setAttr("retry_count", retryCount);
        setAttr("total_retry_wait_sec", totalRetryWaitSec);
    }

    /**
     * Reconstructs a typed ChatResponseEvent from a base Event, deserializing nested types.
     *
     * @param event the base event containing chat response data in attributes
     * @return a typed ChatResponseEvent
     */
    @SuppressWarnings("unchecked")
    public static ChatResponseEvent fromEvent(Event event) {
        Object rawId = event.getAttr("request_id");
        UUID requestId = rawId instanceof String ? UUID.fromString((String) rawId) : (UUID) rawId;

        Object rawResponse = event.getAttr("response");
        ChatMessage response;
        if (rawResponse instanceof ChatMessage) {
            response = (ChatMessage) rawResponse;
        } else if (rawResponse instanceof Map) {
            response = MAPPER.convertValue(rawResponse, ChatMessage.class);
        } else {
            response = null;
        }

        int retryCount = ((Number) event.getAttr("retry_count")).intValue();
        int totalRetryWaitSec = ((Number) event.getAttr("total_retry_wait_sec")).intValue();
        return new ChatResponseEvent(requestId, response, retryCount, totalRetryWaitSec);
    }

    @JsonIgnore
    public UUID getRequestId() {
        Object val = getAttr("request_id");
        if (val instanceof String) {
            return UUID.fromString((String) val);
        }
        return (UUID) val;
    }

    @JsonIgnore
    public ChatMessage getResponse() {
        return (ChatMessage) getAttr("response");
    }

    @JsonIgnore
    public int getRetryCount() {
        return ((Number) getAttr("retry_count")).intValue();
    }

    @JsonIgnore
    public int getTotalRetryWaitSec() {
        return ((Number) getAttr("total_retry_wait_sec")).intValue();
    }
}
