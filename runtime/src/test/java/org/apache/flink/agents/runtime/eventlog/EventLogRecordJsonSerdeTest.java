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
        assertTrue(jsonNode.has("context"));
        assertTrue(jsonNode.has("event"));

        // Verify context
        JsonNode contextNode = jsonNode.get("context");
        assertTrue(contextNode.has("eventType"));
        assertTrue(contextNode.has("timestamp"));
        assertEquals(
                "org.apache.flink.agents.api.InputEvent", contextNode.get("eventType").asText());

        // Verify event
        JsonNode eventNode = jsonNode.get("event");
        assertTrue(eventNode.has("input"));
        assertEquals("test input data", eventNode.get("input").asText());
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
        assertEquals(
                "org.apache.flink.agents.api.OutputEvent",
                jsonNode.get("context").get("eventType").asText());
        assertEquals("test output data", jsonNode.get("event").get("output").asText());
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
        assertEquals(
                "org.apache.flink.agents.runtime.eventlog.EventLogRecordJsonSerdeTest$CustomTestEvent",
                jsonNode.get("context").get("eventType").asText());

        JsonNode eventNode = jsonNode.get("event");
        assertEquals("custom data", eventNode.get("customData").asText());
        assertEquals(42, eventNode.get("customNumber").asInt());
        assertEquals(true, eventNode.get("customFlag").asBoolean());
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
        assertEquals("org.apache.flink.agents.api.InputEvent", deserializedContext.getEventType());
        assertNotNull(deserializedContext.getTimestamp());

        // Verify event
        Event deserializedEvent = deserializedRecord.getEvent();
        assertTrue(deserializedEvent instanceof InputEvent);
        InputEvent deserializedInputEvent = (InputEvent) deserializedEvent;
        assertEquals("test input data", deserializedInputEvent.getInput());
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
        assertTrue(deserializedEvent instanceof OutputEvent);
        OutputEvent deserializedOutputEvent = (OutputEvent) deserializedEvent;
        assertEquals("test output data", deserializedOutputEvent.getOutput());
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

        // Then
        Event deserializedEvent = deserializedRecord.getEvent();
        assertInstanceOf(CustomTestEvent.class, deserializedEvent);
        CustomTestEvent deserializedCustomEvent = (CustomTestEvent) deserializedEvent;
        assertEquals("custom data", deserializedCustomEvent.getCustomData());
        assertEquals(42, deserializedCustomEvent.getCustomNumber());
        assertTrue(deserializedCustomEvent.isCustomFlag());
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
        assertInstanceOf(InputEvent.class, deserializedRecord.getEvent());

        InputEvent deserializedEvent = (InputEvent) deserializedRecord.getEvent();
        InputEvent originalInputEvent = (InputEvent) originalRecord.getEvent();
        assertEquals(originalInputEvent.getInput(), deserializedEvent.getInput());
    }

    /** Custom test event class for testing polymorphic serialization. */
    public static class CustomTestEvent extends Event {
        private String customData;
        private int customNumber;
        private boolean customFlag;

        // Default constructor for Jackson
        public CustomTestEvent() {}

        public CustomTestEvent(String customData, int customNumber, boolean customFlag) {
            this.customData = customData;
            this.customNumber = customNumber;
            this.customFlag = customFlag;
        }

        public String getCustomData() {
            return customData;
        }

        public void setCustomData(String customData) {
            this.customData = customData;
        }

        public int getCustomNumber() {
            return customNumber;
        }

        public void setCustomNumber(int customNumber) {
            this.customNumber = customNumber;
        }

        public boolean isCustomFlag() {
            return customFlag;
        }

        public void setCustomFlag(boolean customFlag) {
            this.customFlag = customFlag;
        }
    }
}
