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
import org.apache.flink.agents.api.trace.ExecutionLifecycleEvents;
import org.apache.flink.agents.api.trace.ExecutionTraceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        InputEvent inputEvent = new InputEvent("test input data");
        EventLogRecord record = record(inputEvent, null);

        String json = objectMapper.writeValueAsString(record);
        JsonNode jsonNode = objectMapper.readTree(json);

        assertTrue(jsonNode.has("timestamp"));
        assertTrue(jsonNode.has("event_id"));
        assertEquals(InputEvent.EVENT_TYPE, jsonNode.get("event_type").asText());
        assertEquals("test input data", jsonNode.get("event_attributes").get("input").asText());
        assertFalse(jsonNode.has("event"));
        assertFalse(jsonNode.has("input_run_id"));
        assertFalse(jsonNode.has("execution_id"));
    }

    @Test
    void testRecordUsesEventContextTimestamp() throws Exception {
        InputEvent inputEvent = new InputEvent("test input data");
        EventContext eventContext = new EventContext(InputEvent.EVENT_TYPE, "2026-01-01T00:00:00Z");

        EventLogRecord record = record(eventContext, inputEvent, null);
        String json = objectMapper.writeValueAsString(record);
        JsonNode jsonNode = objectMapper.readTree(json);

        assertEquals(InputEvent.EVENT_TYPE, record.getEventContext().getEventType());
        assertEquals("2026-01-01T00:00:00Z", record.getEventContext().getTimestamp());
        assertEquals("2026-01-01T00:00:00Z", jsonNode.get("timestamp").asText());
    }

    @Test
    void testRecordRejectsMismatchedEventContextType() {
        InputEvent inputEvent = new InputEvent("test input data");
        EventContext eventContext = new EventContext("OtherEvent", "2026-01-01T00:00:00Z");

        assertThrows(IllegalArgumentException.class, () -> record(eventContext, inputEvent, null));
    }

    @Test
    void testSerializeOutputEvent() throws Exception {
        OutputEvent outputEvent = new OutputEvent("test output data");
        EventLogRecord record = record(outputEvent, null);

        String json = objectMapper.writeValueAsString(record);
        JsonNode jsonNode = objectMapper.readTree(json);

        assertEquals(OutputEvent.EVENT_TYPE, jsonNode.get("event_type").asText());
        assertEquals("test output data", jsonNode.get("event_attributes").get("output").asText());
    }

    @Test
    void testSerializeCustomEvent() throws Exception {
        CustomTestEvent customEvent = new CustomTestEvent("custom data", 42, true);
        EventLogRecord record = record(customEvent, null);

        String json = objectMapper.writeValueAsString(record);
        JsonNode jsonNode = objectMapper.readTree(json);

        assertEquals(CustomTestEvent.EVENT_TYPE, jsonNode.get("event_type").asText());
        JsonNode attrsNode = jsonNode.get("event_attributes");
        assertEquals("custom data", attrsNode.get("customData").asText());
        assertEquals(42, attrsNode.get("customNumber").asInt());
        assertTrue(attrsNode.get("customFlag").asBoolean());
    }

    @Test
    void testBusinessEventKeepsStatusAttributesAsPayload() throws Exception {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(ExecutionLifecycleEvents.STATUS_ATTRIBUTE, "business-status");
        attributes.put(ExecutionLifecycleEvents.PROBLEM_CATEGORY_ATTRIBUTE, "business-category");
        Event businessEvent = new Event("BusinessStatusEvent", attributes);
        ExecutionTraceContext traceContext =
                ExecutionTraceContext.fromExistingIds(
                        "input-run-1",
                        "business-key-1",
                        "agent-a",
                        "execution-1",
                        "parent-execution-1",
                        "action",
                        "process",
                        Map.of());
        EventLogRecord record = record(businessEvent, traceContext);

        String json = objectMapper.writeValueAsString(record);
        JsonNode jsonNode = objectMapper.readTree(json);

        assertFalse(jsonNode.has("status"));
        assertFalse(jsonNode.has("problem_category"));
        assertEquals(
                "business-status",
                jsonNode.get("event_attributes")
                        .get(ExecutionLifecycleEvents.STATUS_ATTRIBUTE)
                        .asText());
        assertEquals(
                "business-category",
                jsonNode.get("event_attributes")
                        .get(ExecutionLifecycleEvents.PROBLEM_CATEGORY_ATTRIBUTE)
                        .asText());

        EventLogRecord deserializedRecord = objectMapper.readValue(json, EventLogRecord.class);
        assertEquals(
                "business-status",
                deserializedRecord.getEvent().getAttr(ExecutionLifecycleEvents.STATUS_ATTRIBUTE));
        assertEquals(
                "business-category",
                deserializedRecord
                        .getEvent()
                        .getAttr(ExecutionLifecycleEvents.PROBLEM_CATEGORY_ATTRIBUTE));
    }

    @Test
    void testSerializeAndDeserializeExecutionLifecycleFields() throws Exception {
        Map<String, Object> entityMetadata = new LinkedHashMap<>();
        entityMetadata.put("mcp_server", "demo-server");

        Event lifecycleEvent =
                ExecutionLifecycleEvents.executionFailed(
                        new IllegalStateException("model returned malformed JSON"),
                        "model_output_parse_error");
        ExecutionTraceContext traceContext =
                ExecutionTraceContext.fromExistingIds(
                        "input-run-1",
                        "business-key-1",
                        "agent-a",
                        "execution-1",
                        "parent-execution-1",
                        "action",
                        "process",
                        entityMetadata);
        EventLogRecord record = record(lifecycleEvent, traceContext);
        assertEquals(traceContext, record.getExecutionTraceContext());

        String json = objectMapper.writeValueAsString(record);
        JsonNode jsonNode = objectMapper.readTree(json);

        assertEquals("input-run-1", jsonNode.get("input_run_id").asText());
        assertEquals("business-key-1", jsonNode.get("business_key").asText());
        assertEquals("agent-a", jsonNode.get("agent_name").asText());
        assertEquals("execution-1", jsonNode.get("execution_id").asText());
        assertEquals("parent-execution-1", jsonNode.get("parent_execution_id").asText());
        assertEquals("action", jsonNode.get("entity_type").asText());
        assertEquals("process", jsonNode.get("entity_name").asText());
        assertEquals("demo-server", jsonNode.get("entity_metadata").get("mcp_server").asText());
        assertEquals(
                ExecutionLifecycleEvents.EXECUTION_FAILED_EVENT_TYPE,
                jsonNode.get("event_type").asText());
        assertEquals("failed", jsonNode.get("status").asText());
        assertEquals("model_output_parse_error", jsonNode.get("problem_category").asText());
        assertFalse(
                jsonNode.get("event_attributes").has(ExecutionLifecycleEvents.STATUS_ATTRIBUTE));
        assertFalse(
                jsonNode.get("event_attributes")
                        .has(ExecutionLifecycleEvents.PROBLEM_CATEGORY_ATTRIBUTE));
        assertEquals(
                IllegalStateException.class.getName(),
                jsonNode.get("event_attributes").get("error_type").asText());

        EventLogRecord deserializedRecord = objectMapper.readValue(json, EventLogRecord.class);
        ExecutionTraceContext deserializedTraceContext =
                deserializedRecord.getExecutionTraceContext();
        assertEquals(traceContext, deserializedTraceContext);
        assertNotNull(deserializedTraceContext);
        assertEquals("input-run-1", deserializedTraceContext.getInputRunId());
        assertEquals("business-key-1", deserializedTraceContext.getBusinessKey());
        assertEquals("agent-a", deserializedTraceContext.getAgentName());
        assertEquals("execution-1", deserializedTraceContext.getExecutionId());
        assertEquals("parent-execution-1", deserializedTraceContext.getParentExecutionId());
        assertEquals("action", deserializedTraceContext.getEntityType());
        assertEquals("process", deserializedTraceContext.getEntityName());
        assertEquals("demo-server", deserializedTraceContext.getEntityMetadata().get("mcp_server"));
        assertEquals(
                "failed",
                deserializedRecord.getEvent().getAttr(ExecutionLifecycleEvents.STATUS_ATTRIBUTE));
        assertEquals(
                "model_output_parse_error",
                deserializedRecord
                        .getEvent()
                        .getAttr(ExecutionLifecycleEvents.PROBLEM_CATEGORY_ATTRIBUTE));
    }

    @Test
    void testDeserializeInputEvent() throws Exception {
        InputEvent originalEvent = new InputEvent("test input data");
        EventLogRecord originalRecord = record(originalEvent, null);
        String json = objectMapper.writeValueAsString(originalRecord);

        EventLogRecord deserializedRecord = objectMapper.readValue(json, EventLogRecord.class);

        assertNotNull(deserializedRecord.getEventContext().getTimestamp());
        assertEquals(InputEvent.EVENT_TYPE, deserializedRecord.getEventContext().getEventType());
        assertNull(deserializedRecord.getExecutionTraceContext());
        Event deserializedEvent = deserializedRecord.getEvent();
        assertEquals(InputEvent.EVENT_TYPE, deserializedEvent.getType());
        assertEquals("test input data", InputEvent.fromEvent(deserializedEvent).getInput());
    }

    @Test
    void testDeserializeOutputEvent() throws Exception {
        OutputEvent originalEvent = new OutputEvent("test output data");
        EventLogRecord originalRecord = record(originalEvent, null);
        String json = objectMapper.writeValueAsString(originalRecord);

        EventLogRecord deserializedRecord = objectMapper.readValue(json, EventLogRecord.class);

        Event deserializedEvent = deserializedRecord.getEvent();
        assertEquals(OutputEvent.EVENT_TYPE, deserializedEvent.getType());
        assertEquals("test output data", OutputEvent.fromEvent(deserializedEvent).getOutput());
    }

    @Test
    void testDeserializeCustomEvent() throws Exception {
        CustomTestEvent originalEvent = new CustomTestEvent("custom data", 42, true);
        EventLogRecord originalRecord = record(originalEvent, null);
        String json = objectMapper.writeValueAsString(originalRecord);

        EventLogRecord deserializedRecord = objectMapper.readValue(json, EventLogRecord.class);

        Event deserializedEvent = deserializedRecord.getEvent();
        assertEquals(CustomTestEvent.EVENT_TYPE, deserializedEvent.getType());
        CustomTestEvent customEvent = CustomTestEvent.fromEvent(deserializedEvent);
        assertEquals("custom data", customEvent.getCustomData());
        assertEquals(42, customEvent.getCustomNumber());
        assertTrue(customEvent.isCustomFlag());
    }

    @Test
    void testRoundTripUnifiedEvent() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("x", 1);
        attrs.put("y", "two");
        Event originalEvent = new Event("RoundTripEvent", attrs);
        EventLogRecord record = record(originalEvent, null);

        String json = objectMapper.writeValueAsString(record);
        EventLogRecord deserialized = objectMapper.readValue(json, EventLogRecord.class);

        assertNotNull(deserialized.getEventContext().getTimestamp());
        Event event = deserialized.getEvent();
        assertEquals("RoundTripEvent", event.getType());
        assertEquals(1, ((Number) event.getAttr("x")).intValue());
        assertEquals("two", event.getAttr("y"));
    }

    @Test
    void testDeserializeLegacyRecord() throws Exception {
        UUID eventId = UUID.randomUUID();
        String json =
                "{"
                        + "\"timestamp\":\"2026-01-01T00:00:00Z\","
                        + "\"event\":{"
                        + "\"id\":\""
                        + eventId
                        + "\","
                        + "\"type\":\"LegacyType\","
                        + "\"eventType\":\"LegacyType\","
                        + "\"eventClass\":\"LegacyEvent\","
                        + "\"attributes\":{\"key\":\"value\"}"
                        + "}"
                        + "}";

        EventLogRecord record = objectMapper.readValue(json, EventLogRecord.class);

        assertEquals("2026-01-01T00:00:00Z", record.getEventContext().getTimestamp());
        assertEquals("LegacyType", record.getEventContext().getEventType());
        assertEquals("LegacyType", record.getEvent().getType());
        assertEquals(eventId, record.getEvent().getId());
        assertEquals("value", record.getEvent().getAttr("key"));
        assertNull(record.getExecutionTraceContext());
    }

    private static EventLogRecord record(Event event, ExecutionTraceContext executionTraceContext) {
        return record(new EventContext(event), event, executionTraceContext);
    }

    private static EventLogRecord record(
            EventContext eventContext, Event event, ExecutionTraceContext executionTraceContext) {
        return new EventLogRecord(eventContext, executionTraceContext, event);
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

        private CustomTestEvent(UUID id, Map<String, Object> attributes) {
            super(id, EVENT_TYPE, attributes);
        }

        public static CustomTestEvent fromEvent(Event event) {
            CustomTestEvent result =
                    new CustomTestEvent(event.getId(), new HashMap<>(event.getAttributes()));
            if (event.hasSourceTimestamp()) {
                result.setSourceTimestamp(event.getSourceTimestamp());
            }
            return result;
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
