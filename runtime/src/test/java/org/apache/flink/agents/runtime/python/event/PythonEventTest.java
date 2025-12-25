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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.EventContext;
import org.apache.flink.agents.runtime.eventlog.EventLogRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link PythonEvent}. */
class PythonEventTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void testCreatePythonEventWithEventString() {
        // Given
        byte[] eventBytes = new byte[] {1, 2, 3, 4, 5};
        String eventType = "flink_agents.api.events.event.InputEvent";
        String eventString = "InputEvent(input='test data')";

        // When
        PythonEvent event = new PythonEvent(eventBytes, eventType, eventString);

        // Then
        assertThat(event.getEvent()).isEqualTo(eventBytes);
        assertThat(event.getEventType()).isEqualTo(eventType);
        assertThat(event.getEventString()).isEqualTo(eventString);
    }

    @Test
    void testJsonSerializationWithEventString() throws Exception {
        // Given
        UUID expectedId = UUID.randomUUID();
        Map<String, Object> expectedAttributes = new HashMap<>();
        expectedAttributes.put("testKey", "testValue");
        byte[] eventBytes = "test_bytes".getBytes();
        String eventType = "flink_agents.api.events.event.OutputEvent";
        String eventString = "OutputEvent(output={'key': 'value'})";

        PythonEvent event =
                new PythonEvent(expectedId, expectedAttributes, eventBytes, eventType, eventString);

        // When
        String json = objectMapper.writeValueAsString(event);

        // Then
        JsonNode jsonNode = objectMapper.readTree(json);
        assertThat(jsonNode.has("id")).isTrue();
        assertThat(jsonNode.has("eventType")).isTrue();
        assertThat(jsonNode.has("eventString")).isTrue();
        assertThat(jsonNode.has("attributes")).isTrue();
        // event bytes should not be serialized
        assertThat(jsonNode.has("event")).isFalse();
        assertThat(jsonNode.get("eventType").asText()).isEqualTo(eventType);
        assertThat(jsonNode.get("eventString").asText()).isEqualTo(eventString);
        assertThat(jsonNode.get("attributes").get("testKey").asText()).isEqualTo("testValue");
    }

    @Test
    void testEventLogRecordSerializationWithEventString() throws Exception {
        // Given - simulate how PythonEvent is used in EventLogger
        UUID eventId = UUID.randomUUID();
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("source", "python");
        byte[] eventBytes = "serialized_event".getBytes();
        String eventType = "flink_agents.api.events.event.InputEvent";
        String eventString = "InputEvent(input={'key': 'value', 'count': 42})";

        PythonEvent pythonEvent =
                new PythonEvent(eventId, attributes, eventBytes, eventType, eventString);
        pythonEvent.setSourceTimestamp(1234567890L);

        EventContext context = new EventContext(pythonEvent);
        EventLogRecord record = new EventLogRecord(context, pythonEvent);

        // When
        String json = objectMapper.writeValueAsString(record);

        // Then
        JsonNode jsonNode = objectMapper.readTree(json);

        // Verify context contains PythonEvent type
        assertThat(jsonNode.get("context").get("eventType").asText())
                .isEqualTo("org.apache.flink.agents.runtime.python.event.PythonEvent");

        // Verify event contains human-readable eventString
        JsonNode eventNode = jsonNode.get("event");
        assertThat(eventNode.get("eventString").asText()).isEqualTo(eventString);
        assertThat(eventNode.get("eventType").asText()).isEqualTo(eventType);
        assertThat(eventNode.get("id").asText()).isEqualTo(eventId.toString());
        // Byte array should not be in the log
        assertThat(eventNode.has("event")).isFalse();
    }
}
