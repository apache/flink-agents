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

import org.apache.flink.agents.api.event.AgentRunBeginEvent;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ContextRetrievalRequestEvent;
import org.apache.flink.agents.api.event.ContextRetrievalResponseEvent;
import org.apache.flink.agents.api.event.LongTermGetEvent;
import org.apache.flink.agents.api.event.LongTermSearchEvent;
import org.apache.flink.agents.api.event.LongTermUpdateEvent;
import org.apache.flink.agents.api.event.ModelRoutingEvent;
import org.apache.flink.agents.api.event.SensoryReadEvent;
import org.apache.flink.agents.api.event.SensoryWriteEvent;
import org.apache.flink.agents.api.event.ShortTermReadEvent;
import org.apache.flink.agents.api.event.ShortTermWriteEvent;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests for {@link EventType}. */
class EventTypeTest {

    @Test
    void allConstantsProvidesAnUnmodifiableNameToValueMap() {
        assertEquals(
                Map.ofEntries(
                        Map.entry("InputEvent", InputEvent.EVENT_TYPE),
                        Map.entry("OutputEvent", OutputEvent.EVENT_TYPE),
                        Map.entry("ChatRequestEvent", ChatRequestEvent.EVENT_TYPE),
                        Map.entry("ChatResponseEvent", ChatResponseEvent.EVENT_TYPE),
                        Map.entry("ToolRequestEvent", ToolRequestEvent.EVENT_TYPE),
                        Map.entry("ToolResponseEvent", ToolResponseEvent.EVENT_TYPE),
                        Map.entry(
                                "ContextRetrievalRequestEvent",
                                ContextRetrievalRequestEvent.EVENT_TYPE),
                        Map.entry(
                                "ContextRetrievalResponseEvent",
                                ContextRetrievalResponseEvent.EVENT_TYPE),
                        Map.entry("ModelRoutingEvent", ModelRoutingEvent.EVENT_TYPE),
                        Map.entry("ShortTermWriteEvent", ShortTermWriteEvent.EVENT_TYPE),
                        Map.entry("ShortTermReadEvent", ShortTermReadEvent.EVENT_TYPE),
                        Map.entry("SensoryWriteEvent", SensoryWriteEvent.EVENT_TYPE),
                        Map.entry("SensoryReadEvent", SensoryReadEvent.EVENT_TYPE),
                        Map.entry("LongTermUpdateEvent", LongTermUpdateEvent.EVENT_TYPE),
                        Map.entry("LongTermGetEvent", LongTermGetEvent.EVENT_TYPE),
                        Map.entry("LongTermSearchEvent", LongTermSearchEvent.EVENT_TYPE),
                        Map.entry("AgentRunBeginEvent", AgentRunBeginEvent.EVENT_TYPE)),
                EventType.allConstants());
        assertThrows(
                UnsupportedOperationException.class,
                () -> EventType.allConstants().put("custom", "custom"));
    }
}
