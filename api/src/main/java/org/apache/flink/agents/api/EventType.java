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

package org.apache.flink.agents.api;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compile-time constants for built-in event types, sourced from each {@code XxxEvent.EVENT_TYPE}.
 *
 * <p>Usage: {@code @Action(EventType.InputEvent)}.
 */
public final class EventType {

    public static final String InputEvent = org.apache.flink.agents.api.InputEvent.EVENT_TYPE;
    public static final String OutputEvent = org.apache.flink.agents.api.OutputEvent.EVENT_TYPE;
    public static final String ChatRequestEvent =
            org.apache.flink.agents.api.event.ChatRequestEvent.EVENT_TYPE;
    public static final String ChatResponseEvent =
            org.apache.flink.agents.api.event.ChatResponseEvent.EVENT_TYPE;
    public static final String ToolRequestEvent =
            org.apache.flink.agents.api.event.ToolRequestEvent.EVENT_TYPE;
    public static final String ToolResponseEvent =
            org.apache.flink.agents.api.event.ToolResponseEvent.EVENT_TYPE;
    public static final String ContextRetrievalRequestEvent =
            org.apache.flink.agents.api.event.ContextRetrievalRequestEvent.EVENT_TYPE;
    public static final String ContextRetrievalResponseEvent =
            org.apache.flink.agents.api.event.ContextRetrievalResponseEvent.EVENT_TYPE;

    /**
     * Returns all built-in constants as an unmodifiable {@code name → event-type value} map.
     * Enumerated reflectively from the {@code public static final String} fields of this class so
     * newly added constants are picked up automatically. Iteration order is unspecified.
     */
    public static Map<String, String> allConstants() {
        Map<String, String> constants = new LinkedHashMap<>();
        for (Field field : EventType.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                try {
                    constants.put(field.getName(), (String) field.get(null));
                } catch (IllegalAccessException e) {
                    // Unreachable: getFields() only returns public fields.
                    throw new IllegalStateException("Cannot read EventType." + field.getName(), e);
                }
            }
        }
        return Collections.unmodifiableMap(constants);
    }

    private EventType() {}
}
