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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.trace.ExecutionLifecycleEvents;
import org.apache.flink.agents.api.trace.ExecutionTraceContext;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom JSON serializer for {@link EventLogRecord}.
 *
 * <p>This serializer emits the normalized Event Log record shape used by downstream trace and
 * aggregation consumers. Event identity, event type, attributes, input-run context, and execution
 * hierarchy context are flattened at the top level.
 *
 * <pre>{@code
 * {
 *   "timestamp": "2024-01-15T10:30:00Z",
 *   "inputRunId": "...",
 *   "businessKey": "...",
 *   "executionId": "...",
 *   "entityType": "action",
 *   "entityName": "process",
 *   "eventId": "...",
 *   "eventType": "_execution_started_event",
 *   "status": "started",
 *   "eventAttributes": {}
 * }
 * }</pre>
 */
public class EventLogRecordJsonSerializer extends JsonSerializer<EventLogRecord> {

    @Override
    public void serialize(EventLogRecord record, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {

        Event event = record.getEvent();
        ExecutionTraceContext traceContext = record.getExecutionTraceContext();
        gen.writeStartObject();
        gen.writeStringField("timestamp", record.getEventContext().getTimestamp());
        if (traceContext != null) {
            writeStringFieldIfPresent(gen, "inputRunId", traceContext.getInputRunId());
            writeStringFieldIfPresent(gen, "businessKey", traceContext.getBusinessKey());
            writeStringFieldIfPresent(gen, "agentName", traceContext.getAgentName());
            writeStringFieldIfPresent(gen, "executionId", traceContext.getExecutionId());
            writeStringFieldIfPresent(
                    gen, "parentExecutionId", traceContext.getParentExecutionId());
            writeStringFieldIfPresent(gen, "entityType", traceContext.getEntityType());
            writeStringFieldIfPresent(gen, "entityName", traceContext.getEntityName());
            writeMapFieldIfPresent(gen, "entityMetadata", traceContext.getEntityMetadata());
        }
        gen.writeStringField("eventId", event.getId().toString());
        gen.writeStringField("eventType", event.getType());
        writeStringFieldIfPresent(
                gen,
                "status",
                executionLifecycleAttribute(event, ExecutionLifecycleEvents.STATUS_ATTRIBUTE));
        writeStringFieldIfPresent(
                gen,
                "problemCategory",
                executionLifecycleAttribute(
                        event, ExecutionLifecycleEvents.PROBLEM_CATEGORY_ATTRIBUTE));
        gen.writeObjectField("eventAttributes", eventAttributes(event));
        gen.writeEndObject();
    }

    private static Map<String, Object> eventAttributes(Event event) {
        Map<String, Object> attributes = new LinkedHashMap<>(event.getAttributes());
        if (ExecutionLifecycleEvents.isExecutionLifecycleEvent(event.getType())) {
            attributes.remove(ExecutionLifecycleEvents.STATUS_ATTRIBUTE);
            attributes.remove(ExecutionLifecycleEvents.PROBLEM_CATEGORY_ATTRIBUTE);
        }
        return attributes;
    }

    private static String executionLifecycleAttribute(Event event, String name) {
        if (!ExecutionLifecycleEvents.isExecutionLifecycleEvent(event.getType())) {
            return null;
        }
        Object value = event.getAttr(name);
        return value == null ? null : String.valueOf(value);
    }

    private static void writeStringFieldIfPresent(JsonGenerator gen, String field, String value)
            throws IOException {
        if (value != null) {
            gen.writeStringField(field, value);
        }
    }

    private static void writeMapFieldIfPresent(
            JsonGenerator gen, String field, Map<String, Object> value) throws IOException {
        if (value != null && !value.isEmpty()) {
            gen.writeObjectField(field, value);
        }
    }
}
