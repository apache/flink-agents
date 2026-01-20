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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.runtime.python.event.PythonEvent;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

/**
 * Custom JSON serializer for {@link EventLogRecord}.
 *
 * <p>This serializer handles the serialization of EventLogRecord instances to JSON format suitable
 * for structured logging. The serialization includes:
 *
 * <ul>
 *   <li>Top-level timestamp
 *   <li>Event data serialized as a standard JSON object
 * </ul>
 *
 * <p>The resulting JSON structure is:
 *
 * <pre>{@code
 * {
 *   "timestamp": "2024-01-15T10:30:00Z",
 *   "event": {
 *     "eventType": "org.apache.flink.agents.api.InputEvent"
 *     // Event-specific fields serialized normally
 *   }
 * }
 * }</pre>
 */
public class EventLogRecordJsonSerializer extends JsonSerializer<EventLogRecord> {

    @Override
    public void serialize(EventLogRecord record, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {

        ObjectMapper mapper = (ObjectMapper) gen.getCodec();
        if (mapper == null) {
            mapper = new ObjectMapper();
        }

        gen.writeStartObject();
        gen.writeStringField("timestamp", record.getContext().getTimestamp());

        gen.writeFieldName("event");
        JsonNode eventNode = buildEventNode(record.getEvent(), mapper);
        if (!eventNode.isObject()) {
            throw new IllegalStateException(
                    "Event log payload must be a JSON object, but was: " + eventNode.getNodeType());
        }
        eventNode = reorderEventFields((ObjectNode) eventNode, record.getEvent(), mapper);
        gen.writeTree(eventNode);
        gen.writeEndObject();
    }

    private JsonNode buildEventNode(Event event, ObjectMapper mapper) {
        if (event instanceof PythonEvent) {
            return buildPythonEventNode((PythonEvent) event, mapper);
        }
        JsonNode eventNode = mapper.valueToTree(event);
        if (eventNode.isObject()) {
            ObjectNode objectNode = (ObjectNode) eventNode;
            objectNode.put("eventType", event.getClass().getName());
            objectNode.remove("sourceTimestamp");
        }
        return eventNode;
    }

    private JsonNode buildPythonEventNode(PythonEvent event, ObjectMapper mapper) {
        String eventJsonStr = event.getEventJsonStr();
        if (eventJsonStr != null) {
            try {
                JsonNode parsed = mapper.readTree(eventJsonStr);
                if (parsed.isObject()) {
                    ObjectNode objectNode = (ObjectNode) parsed;
                    objectNode.remove("sourceTimestamp");
                    return objectNode;
                }
                return parsed;
            } catch (IOException ignored) {
                // Fallback to raw eventJsonStr
            }
        }
        ObjectNode fallback = mapper.createObjectNode();
        if (event.getEventType() != null) {
            fallback.put("eventType", event.getEventType());
        }
        fallback.put("id", event.getId().toString());
        fallback.put("rawEventJsonStr", eventJsonStr);
        return fallback;
    }

    private ObjectNode reorderEventFields(ObjectNode original, Event event, ObjectMapper mapper) {
        ObjectNode ordered = mapper.createObjectNode();

        JsonNode eventTypeNode = original.get("eventType");
        if (eventTypeNode != null) {
            ordered.set("eventType", eventTypeNode);
        } else if (event instanceof PythonEvent) {
            String eventType = ((PythonEvent) event).getEventType();
            if (eventType != null) {
                ordered.put("eventType", eventType);
            }
        } else {
            ordered.put("eventType", event.getClass().getName());
        }

        JsonNode idNode = original.get("id");
        if (idNode != null) {
            ordered.set("id", idNode);
        } else if (event.getId() != null) {
            ordered.put("id", event.getId().toString());
        }

        JsonNode attributesNode = original.get("attributes");
        if (attributesNode != null) {
            ordered.set("attributes", attributesNode);
        } else {
            ordered.putObject("attributes");
        }

        Iterator<Map.Entry<String, JsonNode>> fields = original.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String fieldName = entry.getKey();
            if ("sourceTimestamp".equals(fieldName)) {
                continue;
            }
            if (!ordered.has(fieldName)) {
                ordered.set(fieldName, entry.getValue());
            }
        }

        return ordered;
    }
}
