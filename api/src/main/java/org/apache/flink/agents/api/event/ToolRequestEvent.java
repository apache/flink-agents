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
import org.apache.flink.agents.api.Event;

import java.util.List;
import java.util.Map;

/** Event representing a tool call request */
public class ToolRequestEvent extends Event {

    public static final String EVENT_TYPE = "_tool_request_event";

    public ToolRequestEvent(String model, List<Map<String, Object>> toolCalls) {
        super(EVENT_TYPE);
        setAttr("model", model);
        setAttr("tool_calls", toolCalls);
    }

    /**
     * Reconstructs a typed ToolRequestEvent from a base Event.
     *
     * @param event the base event containing tool request data in attributes
     * @return a typed ToolRequestEvent
     */
    @SuppressWarnings("unchecked")
    public static ToolRequestEvent fromEvent(Event event) {
        String model = (String) event.getAttr("model");
        List<Map<String, Object>> toolCalls =
                (List<Map<String, Object>>) event.getAttr("tool_calls");
        return new ToolRequestEvent(model, toolCalls);
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
