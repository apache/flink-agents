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

package org.apache.flink.agents.runtime.eventlog;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventContext;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EventLogRecordJsonSerializer} and {@link EventLogRecordJsonDeserializer}.
 */
class EventLogRecordJsonSerdeTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void testSerializeInputEvent() throws Exception {
        // Given
        InputEvent inputEvent = new InputEvent("test input data");
        EventContext context = new EventContext(inputEvent);
        EventLogRecord record = new EventLogRecord(context, inputEvent);

        // When
        String json = objectMapper.writeValueAsString(record);

        // Then
        assertNotNull(json);
        JsonNode jsonNode = objectMapper.readTree(json);

        // Verify structure
        assertTrue(jsonNode.has("timestamp"));
        assertTrue(jsonNode.has("event"));

        // Verify event
        JsonNode eventNode = jsonNode.get("event");
        assertTrue(eventNode.has("eventType"));
        assertEquals(InputEvent.EVENT_TYPE, eventNode.get("eventType").asText());
        assertFalse(eventNode.has("sourceTimestamp"));
        // Data is stored in attributes
        assertTrue(eventNode.has("attributes"));
        assertEquals("test input data", eventNode.get("attributes").get("input").asText());
    }

    @Test
    void testSerializeOutputEvent() throws Exception {
        // Given
        OutputEvent outputEvent = new OutputEvent("test output data");
        EventContext context = new EventContext(outputEvent);
        EventLogRecord record = new EventLogRecord(context, outputEvent);

        // When
        String json = objectMapper.writeValueAsString(record);

        // Then
        JsonNode jsonNode = objectMapper.readTree(json);
        assertEquals(OutputEvent.EVENT_TYPE, jsonNode.get("event").get("eventType").asText());
        assertEquals(
                "test output data", jsonNode.get("event").get("attributes").get("output").asText());
    }

    @Test
    void testSerializeCustomEvent() throws Exception {
        // Given
        CustomTestEvent customEvent = new CustomTestEvent("custom data", 42, true);
        EventContext context = new EventContext(customEvent);
        EventLogRecord record = new EventLogRecord(context, customEvent);

        // When
        String json = objectMapper.writeValueAsString(record);

        // Then
        JsonNode jsonNode = objectMapper.readTree(json);
        assertEquals(CustomTestEvent.EVENT_TYPE, jsonNode.get("event").get("eventType").asText());

        JsonNode attrsNode = jsonNode.get("event").get("attributes");
        assertEquals("custom data", attrsNode.get("customData").asText());
        assertEquals(42, attrsNode.get("customNumber").asInt());
        assertEquals(true, attrsNode.get("customFlag").asBoolean());
    }

    @Test
    void testDeserializeInputEvent() throws Exception {
        // Given
        InputEvent originalEvent = new InputEvent("test input data");
        EventContext originalContext = new EventContext(originalEvent);
        EventLogRecord originalRecord = new EventLogRecord(originalContext, originalEvent);
        String json = objectMapper.writeValueAsString(originalRecord);

        // When
        EventLogRecord deserializedRecord = objectMapper.readValue(json, EventLogRecord.class);

        // Then
        assertNotNull(deserializedRecord);
        assertNotNull(deserializedRecord.getContext());
        assertNotNull(deserializedRecord.getEvent());

        // Verify context
        EventContext deserializedContext = deserializedRecord.getContext();
        assertEquals(InputEvent.EVENT_TYPE, deserializedContext.getEventType());
        assertNotNull(deserializedContext.getTimestamp());

        // Verify event via fromEvent
        Event deserializedEvent = deserializedRecord.getEvent();
        assertEquals(InputEvent.EVENT_TYPE, deserializedEvent.getType());
        InputEvent inputEvent = InputEvent.fromEvent(deserializedEvent);
        assertEquals("test input data", inputEvent.getInput());
    }

    @Test
    void testDeserializeOutputEvent() throws Exception {
        // Given
        OutputEvent originalEvent = new OutputEvent("test output data");
        EventContext originalContext = new EventContext(originalEvent);
        EventLogRecord originalRecord = new EventLogRecord(originalContext, originalEvent);
        String json = objectMapper.writeValueAsString(originalRecord);

        // When
        EventLogRecord deserializedRecord = objectMapper.readValue(json, EventLogRecord.class);

        // Then
        Event deserializedEvent = deserializedRecord.getEvent();
        assertEquals(OutputEvent.EVENT_TYPE, deserializedEvent.getType());
        OutputEvent outputEvent = OutputEvent.fromEvent(deserializedEvent);
        assertEquals("test output data", outputEvent.getOutput());
    }

    @Test
    void testDeserializeCustomEvent() throws Exception {
        // Given
        CustomTestEvent originalEvent = new CustomTestEvent("custom data", 42, true);
        EventContext originalContext = new EventContext(originalEvent);
        EventLogRecord originalRecord = new EventLogRecord(originalContext, originalEvent);
        String json = objectMapper.writeValueAsString(originalRecord);

        // When
        EventLogRecord deserializedRecord = objectMapper.readValue(json, EventLogRecord.class);

        // Then — deserialized as base Event; use fromEvent for typed access
        Event deserializedEvent = deserializedRecord.getEvent();
        assertEquals(CustomTestEvent.EVENT_TYPE, deserializedEvent.getType());
        CustomTestEvent customEvent = CustomTestEvent.fromEvent(deserializedEvent);
        assertEquals("custom data", customEvent.getCustomData());
        assertEquals(42, customEvent.getCustomNumber());
        assertTrue(customEvent.isCustomFlag());
    }

    @Test
    void testRoundTripSerialization() throws Exception {
        // Given
        InputEvent originalEvent = new InputEvent("round trip test");
        EventContext originalContext = new EventContext(originalEvent);
        EventLogRecord originalRecord = new EventLogRecord(originalContext, originalEvent);

        // When - serialize and deserialize
        String json = objectMapper.writeValueAsString(originalRecord);
        EventLogRecord deserializedRecord = objectMapper.readValue(json, EventLogRecord.class);

        // Then - verify all data is preserved
        assertEquals(
                originalContext.getEventType(), deserializedRecord.getContext().getEventType());
        assertEquals(InputEvent.EVENT_TYPE, deserializedRecord.getEvent().getType());

        InputEvent deserializedEvent = InputEvent.fromEvent(deserializedRecord.getEvent());
        assertEquals(originalEvent.getInput(), deserializedEvent.getInput());
    }

    @Test
    void testSerializeUnifiedEvent() throws Exception {
        // Given - a unified event with user-defined type
        java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("msg", "hello");
        Event unifiedEvent = new Event("MyCustomEvent", attrs);
        EventContext context = new EventContext(unifiedEvent);
        EventLogRecord record = new EventLogRecord(context, unifiedEvent);

        // When
        String json = objectMapper.writeValueAsString(record);

        // Then
        JsonNode jsonNode = objectMapper.readTree(json);
        JsonNode eventNode = jsonNode.get("event");

        // eventType should be the user-defined type string
        assertEquals("MyCustomEvent", eventNode.get("eventType").asText());
        // attributes should be present
        assertEquals("hello", eventNode.get("attributes").get("msg").asText());
    }

    @Test
    void testDeserializeUnifiedEvent() throws Exception {
        // Given - a unified event serialized via the EventLogRecord serializer
        java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("key", "value");
        attrs.put("count", 42);
        Event originalEvent = new Event("CustomType", attrs);
        EventContext originalContext = new EventContext(originalEvent);
        EventLogRecord originalRecord = new EventLogRecord(originalContext, originalEvent);
        String json = objectMapper.writeValueAsString(originalRecord);

        // When
        EventLogRecord deserializedRecord = objectMapper.readValue(json, EventLogRecord.class);

        // Then
        EventContext deserializedContext = deserializedRecord.getContext();
        // eventType is the routing key (user-defined string)
        assertEquals("CustomType", deserializedContext.getEventType());

        // The event should be deserialized as a base Event with the type field set
        Event deserializedEvent = deserializedRecord.getEvent();
        assertEquals("CustomType", deserializedEvent.getType());
    }

    @Test
    void testRoundTripUnifiedEvent() throws Exception {
        // Given
        java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("x", 1);
        attrs.put("y", "two");
        Event originalEvent = new Event("RoundTripEvent", attrs);
        EventContext context = new EventContext(originalEvent);
        EventLogRecord record = new EventLogRecord(context, originalEvent);

        // When - serialize and deserialize
        String json = objectMapper.writeValueAsString(record);
        EventLogRecord deserialized = objectMapper.readValue(json, EventLogRecord.class);

        // Then
        assertEquals("RoundTripEvent", deserialized.getContext().getEventType());
        Event event = deserialized.getEvent();
        assertEquals("RoundTripEvent", event.getType());
    }

    /** Custom test event class using the attributes-based pattern. */
    public static class CustomTestEvent extends Event {
        public static final String EVENT_TYPE = "CustomTestEvent";

        public CustomTestEvent(String customData, int customNumber, boolean customFlag) {
            super(EVENT_TYPE);
            setAttr("customData", customData);
            setAttr("customNumber", customNumber);
            setAttr("customFlag", customFlag);
        }

        public static CustomTestEvent fromEvent(Event event) {
            String data = (String) event.getAttr("customData");
            int number = ((Number) event.getAttr("customNumber")).intValue();
            boolean flag = (Boolean) event.getAttr("customFlag");
            return new CustomTestEvent(data, number, flag);
        }

        @JsonIgnore
        public String getCustomData() {
            return (String) getAttr("customData");
        }

        @JsonIgnore
        public int getCustomNumber() {
            return ((Number) getAttr("customNumber")).intValue();
        }

        @JsonIgnore
        public boolean isCustomFlag() {
            return (Boolean) getAttr("customFlag");
        }
    }
}
