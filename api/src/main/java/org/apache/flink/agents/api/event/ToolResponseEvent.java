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

    @JsonCreator
    public ToolResponseEvent(
            @JsonProperty("id") UUID id,
            @JsonProperty("attributes") Map<String, Object> attributes) {
        super(id, EVENT_TYPE, normalizeAttributes(attributes));
    }

    /**
     * Converts the {@code request_id} back to a {@link UUID} after JSON deserialization.
     *
     * <p>Response values are deliberately left in their serialized form so the event round-trips
     * faithfully across the language boundary: the Python runtime records the raw value it shows
     * the model, while native Java records {@link ToolResponse} objects. Rewriting them here would
     * turn a Python event that merely transits a Java operator into the Java object form on
     * re-serialization, which Python then reads back as a map instead of the raw value. {@link
     * #getResponses()} normalizes both forms on read instead.
     */
    private static Map<String, Object> normalizeAttributes(Map<String, Object> attributes) {
        Object rawId = attributes.get("request_id");
        if (rawId instanceof String) {
            attributes.put("request_id", UUID.fromString((String) rawId));
        }
        return attributes;
    }

    /**
     * Reconstructs a typed ToolResponseEvent from a base Event, deserializing nested types.
     *
     * @param event the base event containing tool response data in attributes
     * @return a typed ToolResponseEvent
     */
    public static ToolResponseEvent fromEvent(Event event) {
        return reconstructFrom(event, ToolResponseEvent::new);
    }

    @JsonIgnore
    public UUID getRequestId() {
        Object val = getAttr("request_id");
        if (val instanceof String) {
            return UUID.fromString((String) val);
        }
        return (UUID) val;
    }

    /**
     * Returns the tool responses keyed by call id, normalizing the serialized form on read.
     *
     * <p>Responses arrive either as {@link ToolResponse} objects (native Java), as maps (Java
     * JSON), or as raw scalars (the Python runtime records the value it shows the model). A call
     * Python marked as failed — a scalar with {@code success} false and the diagnostic in {@code
     * error} — is kept as {@link ToolResponse#error} so Java consumers show the model the same
     * text.
     */
    @JsonIgnore
    @SuppressWarnings("unchecked")
    public Map<String, ToolResponse> getResponses() {
        Object raw = getAttr("responses");
        if (!(raw instanceof Map)) {
            return Map.of();
        }
        Map<String, ?> rawResponses = (Map<String, ?>) raw;
        Object rawSuccess = getAttr("success");
        Map<?, ?> success = rawSuccess instanceof Map ? (Map<?, ?>) rawSuccess : Map.of();
        Map<String, ToolResponse> responses = new HashMap<>();
        for (Map.Entry<String, ?> entry : rawResponses.entrySet()) {
            Object v = entry.getValue();
            if (v instanceof ToolResponse) {
                responses.put(entry.getKey(), (ToolResponse) v);
            } else if (v instanceof Map) {
                responses.put(entry.getKey(), MAPPER.convertValue(v, ToolResponse.class));
            } else if (Boolean.FALSE.equals(success.get(entry.getKey()))) {
                responses.put(entry.getKey(), ToolResponse.error(String.valueOf(v)));
            } else {
                responses.put(entry.getKey(), ToolResponse.success(v));
            }
        }
        return responses;
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
                + ", success="
                + getAttr("success")
                + ", timestamp="
                + getAttr("timestamp")
                + '}';
    }
}
