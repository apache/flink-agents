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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the unified {@link Event} design. */
class EventTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    // ── Unified event construction ─────────────────────────────────────────

    @Test
    void testUnifiedEventWithTypeAndAttributes() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("field1", "hello");
        attrs.put("field2", 42);

        Event event = new Event("MyCustomEvent", attrs);

        assertEquals("MyCustomEvent", event.getType());
        assertEquals("hello", event.getAttr("field1"));
        assertEquals(42, event.getAttr("field2"));
        assertNotNull(event.getId());
    }

    @Test
    void testUnifiedEventWithTypeOnly() {
        Event event = new Event("SimpleEvent");

        assertEquals("SimpleEvent", event.getType());
        assertTrue(event.getAttributes().isEmpty());
    }

    // ── Subclassed event uses EVENT_TYPE ────────────────────────────────────

    @Test
    void testSubclassedEventGetTypeReturnsEventType() {
        InputEvent inputEvent = new InputEvent("data");

        assertEquals(InputEvent.EVENT_TYPE, inputEvent.getType());
        assertEquals("_input_event", inputEvent.getType());
    }

    @Test
    void testOutputEventGetTypeReturnsEventType() {
        OutputEvent outputEvent = new OutputEvent("result");

        assertEquals(OutputEvent.EVENT_TYPE, outputEvent.getType());
        assertEquals("_output_event", outputEvent.getType());
    }

    // ── JSON serialization ─────────────────────────────────────────────────

    @Test
    void testUnifiedEventJsonSerialization() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("key", "value");

        Event event = new Event("TestEvent", attrs);
        String json = objectMapper.writeValueAsString(event);
        JsonNode node = objectMapper.readTree(json);

        assertTrue(node.has("type"));
        assertEquals("TestEvent", node.get("type").asText());

        assertTrue(node.has("attributes"));
        assertEquals("value", node.get("attributes").get("key").asText());

        assertTrue(node.has("id"));
    }

    @Test
    void testSubclassedEventJsonSerializationHasType() throws Exception {
        InputEvent event = new InputEvent("test data");
        String json = objectMapper.writeValueAsString(event);
        JsonNode node = objectMapper.readTree(json);

        assertTrue(node.has("type"));
        assertEquals("_input_event", node.get("type").asText());

        assertTrue(node.has("attributes"));
        assertEquals("test data", node.get("attributes").get("input").asText());
        assertTrue(node.has("id"));
    }

    @Test
    void testUnifiedEventJsonDeserialization() throws Exception {
        UUID id = UUID.randomUUID();
        String json =
                String.format(
                        "{\"id\":\"%s\",\"type\":\"MyEvent\",\"attributes\":{\"x\":1}}",
                        id.toString());

        Event event = objectMapper.readValue(json, Event.class);

        assertEquals(id, event.getId());
        assertEquals("MyEvent", event.getType());
        assertEquals(1, event.getAttr("x"));
    }

    @Test
    void testSubclassedEventJsonRoundTrip() throws Exception {
        InputEvent original = new InputEvent("round trip");
        String json = objectMapper.writeValueAsString(original);

        InputEvent deserialized = objectMapper.readValue(json, InputEvent.class);

        assertEquals(original.getId(), deserialized.getId());
        assertEquals("round trip", deserialized.getInput());
        assertEquals(InputEvent.EVENT_TYPE, deserialized.getType());
    }

    // ── fromJson ───────────────────────────────────────────────────────────

    @Test
    void testFromJsonWithValidUnifiedEvent() throws IOException {
        String json = "{\"type\":\"MyCustomEvent\",\"attributes\":{\"msg\":\"hello\"}}";
        Event event = Event.fromJson(json);

        assertEquals("MyCustomEvent", event.getType());
        assertEquals("hello", event.getAttr("msg"));
    }

    @Test
    void testFromJsonMissingType() {
        String json = "{\"attributes\":{\"msg\":\"hello\"}}";
        assertThrows(IOException.class, () -> Event.fromJson(json));
    }

    @Test
    void testFromJsonEmptyType() {
        String json = "{\"type\":\"\",\"attributes\":{}}";
        assertThrows(IOException.class, () -> Event.fromJson(json));
    }

    @Test
    void testFromJsonInvalidJson() {
        assertThrows(IOException.class, () -> Event.fromJson("{invalid}"));
    }

    // ── Attributes ─────────────────────────────────────────────────────────

    @Test
    void testSetAndGetAttr() {
        Event event = new Event("Test");
        event.setAttr("key1", "value1");
        event.setAttr("key2", 42);

        assertEquals("value1", event.getAttr("key1"));
        assertEquals(42, event.getAttr("key2"));
        assertNull(event.getAttr("nonexistent"));
    }

    // ── Equality ───────────────────────────────────────────────────────────

    @Test
    void testUnifiedEventEquality() {
        UUID id = UUID.randomUUID();
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("k", "v");

        Event a = new Event(id, "T", attrs);
        Event b = new Event(id, "T", new HashMap<>(attrs));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void testUnifiedEventInequality() {
        Event a = new Event("TypeA");
        Event b = new Event("TypeB");

        assertNotEquals(a, b);
    }

    // ── Source timestamp ───────────────────────────────────────────────────

    @Test
    void testSourceTimestamp() {
        Event event = new Event("Test");
        assertFalse(event.hasSourceTimestamp());

        event.setSourceTimestamp(123456789L);
        assertTrue(event.hasSourceTimestamp());
        assertEquals(123456789L, event.getSourceTimestamp());
    }
}
