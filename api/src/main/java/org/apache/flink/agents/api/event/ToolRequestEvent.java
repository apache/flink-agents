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
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Event representing a tool call request */
public class ToolRequestEvent extends Event {

    public static final String EVENT_TYPE = "_tool_request_event";

    private static final List<BuiltInAttribute> ATTRIBUTE_SCHEMA =
            List.of(
                    BuiltInAttribute.required("model", String.class),
                    BuiltInAttribute.requiredList(
                            "tool_calls", "a tool call", Map.class, ToolCallBlock.class));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ToolRequestEvent(String model, List<ToolCallBlock> toolCalls) {
        super(EVENT_TYPE);
        setAttr("model", model);
        setAttr("tool_calls", validate(toolCalls));
    }

    @JsonCreator
    public ToolRequestEvent(
            @JsonProperty("id") UUID id,
            @JsonProperty("attributes") Map<String, Object> attributes) {
        super(id, EVENT_TYPE, attributes);
        setAttr("tool_calls", validate(restoreToolCalls((List<?>) getAttr("tool_calls"))));
    }

    /**
     * Reconstructs a typed ToolRequestEvent from a base Event.
     *
     * @param event the base event containing tool request data in attributes
     * @return a typed ToolRequestEvent
     */
    public static ToolRequestEvent fromEvent(Event event) {
        validateAttributeSchema(EVENT_TYPE, event.getAttributes(), ATTRIBUTE_SCHEMA);
        return reconstructFrom(event, ToolRequestEvent::new);
    }

    @JsonIgnore
    public String getModel() {
        return (String) getAttr("model");
    }

    @JsonIgnore
    @SuppressWarnings("unchecked")
    public List<ToolCallBlock> getToolCalls() {
        return Collections.unmodifiableList((List<ToolCallBlock>) getAttr("tool_calls"));
    }

    private static List<ToolCallBlock> restoreToolCalls(List<?> values) {
        List<ToolCallBlock> calls = new ArrayList<>();
        for (Object value : values) {
            calls.add(
                    value instanceof ToolCallBlock
                            ? (ToolCallBlock) value
                            : MAPPER.convertValue(value, ToolCallBlock.class));
        }
        return calls;
    }

    private static List<ToolCallBlock> validate(List<ToolCallBlock> calls) {
        HashSet<String> ids = new HashSet<>();
        for (ToolCallBlock call : calls) {
            if (!ids.add(call.getCallId())) {
                throw new IllegalArgumentException("Duplicate tool call ID in one request");
            }
        }
        return new ArrayList<>(calls);
    }

    @Override
    public String toString() {
        return "ToolRequestEvent{"
                + "model='"
                + getModel()
                + '\''
                + ", toolCalls="
                + getToolCalls()
                + '}';
    }
}
