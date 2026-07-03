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

    public static final String ShortTermWriteEvent =
            org.apache.flink.agents.api.event.ShortTermWriteEvent.EVENT_TYPE;
    public static final String ShortTermReadEvent =
            org.apache.flink.agents.api.event.ShortTermReadEvent.EVENT_TYPE;
    public static final String SensoryWriteEvent =
            org.apache.flink.agents.api.event.SensoryWriteEvent.EVENT_TYPE;
    public static final String SensoryReadEvent =
            org.apache.flink.agents.api.event.SensoryReadEvent.EVENT_TYPE;
    public static final String LongTermUpdateEvent =
            org.apache.flink.agents.api.event.LongTermUpdateEvent.EVENT_TYPE;
    public static final String LongTermGetEvent =
            org.apache.flink.agents.api.event.LongTermGetEvent.EVENT_TYPE;
    public static final String LongTermSearchEvent =
            org.apache.flink.agents.api.event.LongTermSearchEvent.EVENT_TYPE;

    public static final String AgentRunBeginEvent =
            org.apache.flink.agents.api.event.AgentRunBeginEvent.EVENT_TYPE;

    private static final Map<String, String> ALL_CONSTANTS =
            Map.ofEntries(
                    Map.entry("InputEvent", InputEvent),
                    Map.entry("OutputEvent", OutputEvent),
                    Map.entry("ChatRequestEvent", ChatRequestEvent),
                    Map.entry("ChatResponseEvent", ChatResponseEvent),
                    Map.entry("ToolRequestEvent", ToolRequestEvent),
                    Map.entry("ToolResponseEvent", ToolResponseEvent),
                    Map.entry("ContextRetrievalRequestEvent", ContextRetrievalRequestEvent),
                    Map.entry("ContextRetrievalResponseEvent", ContextRetrievalResponseEvent),
                    Map.entry("ShortTermWriteEvent", ShortTermWriteEvent),
                    Map.entry("ShortTermReadEvent", ShortTermReadEvent),
                    Map.entry("SensoryWriteEvent", SensoryWriteEvent),
                    Map.entry("SensoryReadEvent", SensoryReadEvent),
                    Map.entry("LongTermUpdateEvent", LongTermUpdateEvent),
                    Map.entry("LongTermGetEvent", LongTermGetEvent),
                    Map.entry("LongTermSearchEvent", LongTermSearchEvent),
                    Map.entry("AgentRunBeginEvent", AgentRunBeginEvent));

    /**
     * Returns the built-in event type constants as a name-to-value map for condition expressions.
     */
    public static Map<String, String> allConstants() {
        return ALL_CONSTANTS;
    }

    private EventType() {}
}
