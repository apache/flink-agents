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
import org.apache.flink.agents.api.Event;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Event representing a tool call request */
public class ToolRequestEvent extends Event {

    public static final String EVENT_TYPE = "_tool_request_event";

    private static final List<BuiltInAttribute> ATTRIBUTE_SCHEMA =
            List.of(
                    BuiltInAttribute.required("model", String.class),
                    BuiltInAttribute.requiredList("tool_calls", "a map", Map.class));

    public ToolRequestEvent(String model, List<Map<String, Object>> toolCalls) {
        super(EVENT_TYPE);
        setAttr("model", model);
        setAttr("tool_calls", toolCalls);
    }

    @JsonCreator
    public ToolRequestEvent(
            @JsonProperty("id") UUID id,
            @JsonProperty("attributes") Map<String, Object> attributes) {
        super(id, EVENT_TYPE, attributes);
    }

    /**
     * Reconstructs a typed ToolRequestEvent from a base Event.
     *
     * <p>Enforces the fixed cross-language schema: {@code model} and {@code tool_calls} are
     * required, no other attribute is allowed, {@code model} must be a string, and every {@code
     * tool_calls} element must be a map.
     *
     * @param event the base event containing tool request data in attributes
     * @return a typed ToolRequestEvent
     * @throws IllegalArgumentException if the event violates the schema
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
    public List<Map<String, Object>> getToolCalls() {
        return (List<Map<String, Object>>) getAttr("tool_calls");
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
