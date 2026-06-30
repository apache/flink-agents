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

import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ContextRetrievalRequestEvent;
import org.apache.flink.agents.api.event.ContextRetrievalResponseEvent;
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
                Map.of(
                        "InputEvent", InputEvent.EVENT_TYPE,
                        "OutputEvent", OutputEvent.EVENT_TYPE,
                        "ChatRequestEvent", ChatRequestEvent.EVENT_TYPE,
                        "ChatResponseEvent", ChatResponseEvent.EVENT_TYPE,
                        "ToolRequestEvent", ToolRequestEvent.EVENT_TYPE,
                        "ToolResponseEvent", ToolResponseEvent.EVENT_TYPE,
                        "ContextRetrievalRequestEvent", ContextRetrievalRequestEvent.EVENT_TYPE,
                        "ContextRetrievalResponseEvent", ContextRetrievalResponseEvent.EVENT_TYPE),
                EventType.allConstants());
        assertThrows(
                UnsupportedOperationException.class,
                () -> EventType.allConstants().put("custom", "custom"));
    }
}
