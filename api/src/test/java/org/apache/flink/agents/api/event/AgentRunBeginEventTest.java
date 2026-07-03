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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests for {@link AgentRunBeginEvent}. */
public class AgentRunBeginEventTest {

    @Test
    void testTypeAndAggregateConstant() {
        assertEquals("_agent_run_begin_event", AgentRunBeginEvent.EVENT_TYPE);
        assertEquals(AgentRunBeginEvent.EVENT_TYPE, EventType.AgentRunBeginEvent);
        // lifecycle event, NOT a memory operation type (no observation suppression)
        assertFalse(MemoryEvent.isMemoryType(AgentRunBeginEvent.EVENT_TYPE));
    }

    @Test
    void testCarriesKeyAndStmValues() {
        Map<String, Object> stm = new LinkedHashMap<>();
        stm.put("user.tier", "gold");
        stm.put("user.address.city", "SF");
        AgentRunBeginEvent event = new AgentRunBeginEvent("user-42", stm);

        assertEquals("user-42", event.getKey());
        assertEquals(stm, event.getValue());
        assertEquals("user-42", event.getAttributes().get("key"));
    }

    @Test
    void testLiveConstructorNormalizesValuesToJsonEquivalentObjects() {
        Map<String, Object> stm = new LinkedHashMap<>();
        stm.put("bytes", new byte[] {1, 2, 3});
        stm.put("pojo", new MemoryEventTest.ObservationValuePojo("hello", 7));

        AgentRunBeginEvent event = new AgentRunBeginEvent("user-42", stm);

        assertEquals("AQID", event.getValue().get("bytes"));
        assertEquals(Map.of("name", "hello", "count", 7), event.getValue().get("pojo"));
        assertInstanceOf(byte[].class, stm.get("bytes"));
        assertInstanceOf(MemoryEventTest.ObservationValuePojo.class, stm.get("pojo"));
    }

    @Test
    void testLiveConstructorRejectsPojoWithNonFiniteNumber() {
        Map<String, Object> stm =
                Map.of("pojo", new MemoryEventTest.NonFiniteValuePojo(Double.POSITIVE_INFINITY));

        assertThrows(IllegalArgumentException.class, () -> new AgentRunBeginEvent("user-42", stm));
    }

    @Test
    void testFromEventNormalizesRawValue() {
        Map<String, Object> stm = new LinkedHashMap<>();
        stm.put("pojo", new MemoryEventTest.ObservationValuePojo("hello", 7));
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("key", "k");
        attributes.put("value", stm);
        Event generic = new Event(AgentRunBeginEvent.EVENT_TYPE, attributes);

        AgentRunBeginEvent restored = AgentRunBeginEvent.fromEvent(generic);

        assertEquals("k", restored.getKey());
        assertEquals(Map.of("name", "hello", "count", 7), restored.getValue().get("pojo"));
        assertInstanceOf(MemoryEventTest.ObservationValuePojo.class, stm.get("pojo"));
    }

    @Test
    void testFromEventRejectsRawNonFiniteNumber() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("key", "k");
        attributes.put("value", Map.of("nested", List.of(Float.NEGATIVE_INFINITY)));
        Event generic = new Event(AgentRunBeginEvent.EVENT_TYPE, attributes);

        assertThrows(IllegalArgumentException.class, () -> AgentRunBeginEvent.fromEvent(generic));
    }

    @Test
    void testFromEventRejectsIncompleteAttributes() {
        Event missingValue = new Event(AgentRunBeginEvent.EVENT_TYPE, Map.of("key", "user-42"));

        assertThrows(
                IllegalArgumentException.class, () -> AgentRunBeginEvent.fromEvent(missingValue));
    }

    @Test
    void testJsonSerdeRoundTrip() throws Exception {
        // Exercises the typed @JsonCreator(id, attributes) path via direct JSON roundtrip.
        // ActionStateSerdeTest covers the real durable-state restoration path.
        Map<String, Object> stm = new LinkedHashMap<>();
        stm.put("user.tier", "gold");
        stm.put("user.address.city", "SF");
        AgentRunBeginEvent original = new AgentRunBeginEvent("user-42", stm);

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(original);
        AgentRunBeginEvent restored = mapper.readValue(json, AgentRunBeginEvent.class);

        assertEquals(original.getId(), restored.getId());
        assertEquals("user-42", restored.getKey());
        assertEquals(stm, restored.getValue());
    }
}
