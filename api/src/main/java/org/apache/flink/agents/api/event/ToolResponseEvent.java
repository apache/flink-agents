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
import org.apache.flink.agents.api.tools.ToolResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Event representing a result from tool call */
public class ToolResponseEvent extends Event {

    public static final String EVENT_TYPE = "_tool_response_event";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ToolResponseEvent(
            UUID requestId,
            Map<String, ToolResponse> responses,
            Map<String, Boolean> success,
            Map<String, String> error,
            Map<String, String> externalIds) {
        super(EVENT_TYPE);
        setAttr("request_id", requestId);
        setAttr("responses", responses);
        setAttr("success", success);
        setAttr("error", error);
        setAttr("external_ids", externalIds);
        setAttr("timestamp", System.currentTimeMillis());
    }

    public ToolResponseEvent(
            UUID requestId,
            Map<String, ToolResponse> responses,
            Map<String, Boolean> success,
            Map<String, String> error) {
        this(requestId, responses, success, error, Map.of());
    }

    /**
     * Reconstructs a typed ToolResponseEvent from a base Event, deserializing nested types.
     *
     * @param event the base event containing tool response data in attributes
     * @return a typed ToolResponseEvent
     */
    @SuppressWarnings("unchecked")
    public static ToolResponseEvent fromEvent(Event event) {
        Object rawId = event.getAttr("request_id");
        UUID requestId = rawId instanceof String ? UUID.fromString((String) rawId) : (UUID) rawId;

        Map<String, ToolResponse> responses = new HashMap<>();
        Map<String, ?> rawResponses = (Map<String, ?>) event.getAttr("responses");
        if (rawResponses != null) {
            for (Map.Entry<String, ?> entry : rawResponses.entrySet()) {
                Object v = entry.getValue();
                if (v instanceof ToolResponse) {
                    responses.put(entry.getKey(), (ToolResponse) v);
                } else if (v instanceof Map) {
                    responses.put(entry.getKey(), MAPPER.convertValue(v, ToolResponse.class));
                }
            }
        }

        Map<String, Boolean> success = (Map<String, Boolean>) event.getAttr("success");
        Map<String, String> error = (Map<String, String>) event.getAttr("error");
        Map<String, String> externalIds = (Map<String, String>) event.getAttr("external_ids");
        return new ToolResponseEvent(
                requestId,
                responses,
                success != null ? success : Map.of(),
                error != null ? error : Map.of(),
                externalIds != null ? externalIds : Map.of());
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
    @SuppressWarnings("unchecked")
    public Map<String, ToolResponse> getResponses() {
        return (Map<String, ToolResponse>) getAttr("responses");
    }

    @JsonIgnore
    @SuppressWarnings("unchecked")
    public Map<String, String> getExternalIds() {
        return (Map<String, String>) getAttr("external_ids");
    }

    @JsonIgnore
    @SuppressWarnings("unchecked")
    public Map<String, Boolean> getSuccess() {
        return (Map<String, Boolean>) getAttr("success");
    }

    @JsonIgnore
    @SuppressWarnings("unchecked")
    public Map<String, String> getError() {
        return (Map<String, String>) getAttr("error");
    }

    @JsonIgnore
    public long getTimestamp() {
        return ((Number) getAttr("timestamp")).longValue();
    }

    @Override
    public String toString() {
        return "ToolResponseEvent{"
                + "requestId="
                + getRequestId()
                + ", response="
                + getResponses()
                + ", success=true"
                + ", timestamp="
                + getTimestamp()
                + '}';
    }
}
