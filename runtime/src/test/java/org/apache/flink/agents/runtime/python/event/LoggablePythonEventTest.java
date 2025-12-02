/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.flink.agents.runtime.python.event;

import org.apache.flink.agents.api.EventContext;
import org.apache.flink.agents.runtime.eventlog.EventLogRecord;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link LoggablePythonEvent}. */
class LoggablePythonEventTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void testCreateLoggablePythonEvent() {
        // Given
        UUID expectedId = UUID.randomUUID();
        Map<String, Object> expectedAttributes = new HashMap<>();
        expectedAttributes.put("key1", "value1");
        expectedAttributes.put("key2", 123);
        String expectedEventString = "InputEvent(input='test data')";
        String expectedEventType = "flink_agents.api.events.event.InputEvent";

        // When
        LoggablePythonEvent event =
                new LoggablePythonEvent(
                        expectedId, expectedAttributes, expectedEventString, expectedEventType);

        // Then
        assertThat(event.getId()).isEqualTo(expectedId);
        assertThat(event.getAttributes()).isEqualTo(expectedAttributes);
        assertThat(event.getEvent()).isEqualTo(expectedEventString);
        assertThat(event.getEventType()).isEqualTo(expectedEventType);
    }

    @Test
    void testSourceTimestampPreservation() {
        // Given
        UUID id = UUID.randomUUID();
        LoggablePythonEvent event =
                new LoggablePythonEvent(
                        id,
                        new HashMap<>(),
                        "TestEvent()",
                        "flink_agents.api.events.event.TestEvent");

        // When
        long expectedTimestamp = System.currentTimeMillis();
        event.setSourceTimestamp(expectedTimestamp);

        // Then
        assertThat(event.hasSourceTimestamp()).isTrue();
        assertThat(event.getSourceTimestamp()).isEqualTo(expectedTimestamp);
    }

    @Test
    void testJsonSerialization() throws Exception {
        // Given
        UUID expectedId = UUID.randomUUID();
        Map<String, Object> expectedAttributes = new HashMap<>();
        expectedAttributes.put("testKey", "testValue");
        String expectedEventString = "OutputEvent(output='result')";
        String expectedEventType = "flink_agents.api.events.event.OutputEvent";

        LoggablePythonEvent event =
                new LoggablePythonEvent(
                        expectedId, expectedAttributes, expectedEventString, expectedEventType);

        // When
        String json = objectMapper.writeValueAsString(event);

        // Then
        JsonNode jsonNode = objectMapper.readTree(json);
        assertThat(jsonNode.has("id")).isTrue();
        assertThat(jsonNode.has("event")).isTrue();
        assertThat(jsonNode.has("eventType")).isTrue();
        assertThat(jsonNode.has("attributes")).isTrue();
        assertThat(jsonNode.get("event").asText()).isEqualTo(expectedEventString);
        assertThat(jsonNode.get("eventType").asText()).isEqualTo(expectedEventType);
        assertThat(jsonNode.get("attributes").get("testKey").asText()).isEqualTo("testValue");
    }

    @Test
    void testJsonDeserialization() throws Exception {
        // Given
        UUID expectedId = UUID.randomUUID();
        Map<String, Object> expectedAttributes = new HashMap<>();
        expectedAttributes.put("attrKey", "attrValue");
        String expectedEventString = "CustomEvent(data='hello')";
        String expectedEventType = "flink_agents.api.events.event.CustomEvent";

        LoggablePythonEvent originalEvent =
                new LoggablePythonEvent(
                        expectedId, expectedAttributes, expectedEventString, expectedEventType);

        String json = objectMapper.writeValueAsString(originalEvent);

        // When
        LoggablePythonEvent deserializedEvent =
                objectMapper.readValue(json, LoggablePythonEvent.class);

        // Then
        assertThat(deserializedEvent.getId()).isEqualTo(expectedId);
        assertThat(deserializedEvent.getEvent()).isEqualTo(expectedEventString);
        assertThat(deserializedEvent.getEventType()).isEqualTo(expectedEventType);
        assertThat(deserializedEvent.getAttributes()).containsEntry("attrKey", "attrValue");
    }

    @Test
    void testEventLogRecordSerialization() throws Exception {
        // Given - simulate how LoggablePythonEvent is used in EventLogger
        UUID eventId = UUID.randomUUID();
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("source", "python");
        String eventString = "InputEvent(input={'key': 'value'})";
        String eventType = "flink_agents.api.events.event.InputEvent";

        LoggablePythonEvent loggableEvent =
                new LoggablePythonEvent(eventId, attributes, eventString, eventType);
        loggableEvent.setSourceTimestamp(1234567890L);

        EventContext context = new EventContext(loggableEvent);
        EventLogRecord record = new EventLogRecord(context, loggableEvent);

        // When
        String json = objectMapper.writeValueAsString(record);

        // Then
        JsonNode jsonNode = objectMapper.readTree(json);

        // Verify context contains LoggablePythonEvent type
        assertThat(jsonNode.get("context").get("eventType").asText())
                .isEqualTo("org.apache.flink.agents.runtime.python.event.LoggablePythonEvent");

        // Verify event contains human-readable string
        JsonNode eventNode = jsonNode.get("event");
        assertThat(eventNode.get("event").asText()).isEqualTo(eventString);
        assertThat(eventNode.get("eventType").asText()).isEqualTo(eventType);
        assertThat(eventNode.get("id").asText()).isEqualTo(eventId.toString());
    }

    @Test
    void testPreservesOriginalPythonEventMetadata() {
        // Given - simulate converting from PythonEvent to LoggablePythonEvent
        UUID originalId = UUID.randomUUID();
        Map<String, Object> originalAttributes = new HashMap<>();
        originalAttributes.put("action", "process");
        originalAttributes.put("priority", 1);
        long originalTimestamp = 9876543210L;
        String pythonEventType = "flink_agents.api.events.event.InputEvent";

        // When - create LoggablePythonEvent preserving original metadata
        LoggablePythonEvent loggableEvent =
                new LoggablePythonEvent(
                        originalId,
                        originalAttributes,
                        "InputEvent(input='preserved metadata test')",
                        pythonEventType);
        loggableEvent.setSourceTimestamp(originalTimestamp);

        // Then - verify all original metadata is preserved
        assertThat(loggableEvent.getId()).isEqualTo(originalId);
        assertThat(loggableEvent.getAttributes()).isEqualTo(originalAttributes);
        assertThat(loggableEvent.getSourceTimestamp()).isEqualTo(originalTimestamp);
        assertThat(loggableEvent.getEventType()).isEqualTo(pythonEventType);
    }
}
