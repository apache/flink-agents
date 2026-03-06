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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.logger.EventLogLevel;
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
 *   <li>Top-level timestamp, logLevel, and eventType
 *   <li>Event data serialized as a standard JSON object
 *   <li>String field truncation at STANDARD level when maxFieldLength is configured
 * </ul>
 *
 * <p>The resulting JSON structure is:
 *
 * <pre>{@code
 * {
 *   "timestamp": "2024-01-15T10:30:00Z",
 *   "logLevel": "STANDARD",
 *   "eventType": "org.apache.flink.agents.api.InputEvent",
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
        gen.writeStringField("logLevel", record.getLogLevel().name());
        gen.writeStringField("eventType", record.getContext().getEventType());

        gen.writeFieldName("event");
        JsonNode eventNode = buildEventNode(record.getEvent(), mapper);
        if (!eventNode.isObject()) {
            throw new IllegalStateException(
                    "Event log payload must be a JSON object, but was: " + eventNode.getNodeType());
        }
        eventNode = reorderEventFields((ObjectNode) eventNode, record.getEvent(), mapper);

        // Apply truncation for STANDARD level
        if (record.getLogLevel() == EventLogLevel.STANDARD && record.getMaxFieldLength() > 0) {
            eventNode = truncateStringFields((ObjectNode) eventNode, record.getMaxFieldLength());
        }

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

    /**
     * Truncates string fields in the JSON node that exceed the max length.
     *
     * <p>Traverses the object recursively. Text values longer than maxLength are replaced with a
     * truncated version plus a marker indicating the original length.
     */
    private ObjectNode truncateStringFields(ObjectNode node, int maxLength) {
        ObjectNode result = node.deepCopy();
        truncateRecursive(result, maxLength);
        return result;
    }

    private void truncateRecursive(ObjectNode node, int maxLength) {
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode value = entry.getValue();
            if (value.isTextual()) {
                String text = value.asText();
                if (text.length() > maxLength) {
                    String truncated =
                            text.substring(0, maxLength)
                                    + "... [truncated, "
                                    + text.length()
                                    + " chars total]";
                    node.set(entry.getKey(), new TextNode(truncated));
                }
            } else if (value.isObject()) {
                truncateRecursive((ObjectNode) value, maxLength);
            } else if (value.isArray()) {
                truncateArrayRecursive((ArrayNode) value, maxLength);
            }
        }
    }

    private void truncateArrayRecursive(ArrayNode array, int maxLength) {
        for (int i = 0; i < array.size(); i++) {
            JsonNode element = array.get(i);
            if (element.isTextual()) {
                String text = element.asText();
                if (text.length() > maxLength) {
                    String truncated =
                            text.substring(0, maxLength)
                                    + "... [truncated, "
                                    + text.length()
                                    + " chars total]";
                    array.set(i, new TextNode(truncated));
                }
            } else if (element.isObject()) {
                truncateRecursive((ObjectNode) element, maxLength);
            } else if (element.isArray()) {
                truncateArrayRecursive((ArrayNode) element, maxLength);
            }
        }
    }
}
