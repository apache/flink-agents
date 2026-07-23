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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventContext;
import org.apache.flink.agents.api.trace.ExecutionLifecycleEvents;
import org.apache.flink.agents.api.trace.ExecutionTraceContext;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Custom JSON deserializer for {@link EventLogRecord}.
 *
 * <p>This deserializer reconstructs EventLogRecord instances from JSON format by:
 *
 * <ul>
 *   <li>Deserializing normalized Event Log fields
 *   <li>Deserializing the event as a base {@link Event} with type and attributes
 * </ul>
 *
 * <p>Users who need typed event subclasses should use the corresponding {@code fromEvent(Event)}
 * factory method on the specific event class.
 */
public class EventLogRecordJsonDeserializer extends JsonDeserializer<EventLogRecord> {

    @Override
    public EventLogRecord deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {

        ObjectMapper mapper = (ObjectMapper) parser.getCodec();
        JsonNode rootNode = mapper.readTree(parser);

        // Deserialize timestamp
        JsonNode timestampNode = rootNode.get("timestamp");
        if (timestampNode == null || !timestampNode.isTextual()) {
            throw new IOException("Missing 'timestamp' field in EventLogRecord JSON");
        }

        if (rootNode.has("event_attributes")) {
            return deserializeNormalizedRecord(mapper, rootNode, timestampNode.asText());
        }

        return deserializeLegacyRecord(mapper, rootNode, timestampNode.asText());
    }

    private static EventLogRecord deserializeNormalizedRecord(
            ObjectMapper mapper, JsonNode rootNode, String timestamp) throws IOException {
        String eventType = getText(rootNode, "event_type", true);
        UUID eventId = parseUuid(getText(rootNode, "event_id", true));
        JsonNode attributesNode = rootNode.get("event_attributes");
        if (attributesNode == null || !attributesNode.isObject()) {
            throw new IOException(
                    "Field 'event_attributes' must be an object in EventLogRecord JSON");
        }
        Map<String, Object> attributes =
                mapper.convertValue(attributesNode, new TypeReference<Map<String, Object>>() {});
        boolean executionLifecycleEvent =
                ExecutionLifecycleEvents.isExecutionLifecycleEvent(eventType);
        String status = executionLifecycleEvent ? getText(rootNode, "status", false) : null;
        String problemCategory =
                executionLifecycleEvent ? getText(rootNode, "problem_category", false) : null;
        if (executionLifecycleEvent) {
            if (status != null) {
                attributes.putIfAbsent(ExecutionLifecycleEvents.STATUS_ATTRIBUTE, status);
            }
            if (problemCategory != null) {
                attributes.putIfAbsent(
                        ExecutionLifecycleEvents.PROBLEM_CATEGORY_ATTRIBUTE, problemCategory);
            }
        }
        Event event = new Event(eventId, eventType, attributes);
        return new EventLogRecord(
                new EventContext(eventType, timestamp), traceContext(mapper, rootNode), event);
    }

    private static EventLogRecord deserializeLegacyRecord(
            ObjectMapper mapper, JsonNode rootNode, String timestamp) throws IOException {
        // Deserialize event as base Event. Any top-level "logLevel" field present in older log
        // files is silently ignored — it is not part of EventLogRecord.
        JsonNode eventNode = rootNode.get("event");
        if (eventNode == null) {
            throw new IOException("Missing 'event' field in EventLogRecord JSON");
        }
        String eventType = getLegacyEventType(eventNode);
        Event event = mapper.treeToValue(stripMetaFields(eventNode), Event.class);
        return new EventLogRecord(new EventContext(eventType, timestamp), null, event);
    }

    private static ExecutionTraceContext traceContext(ObjectMapper mapper, JsonNode rootNode)
            throws IOException {
        String inputRunId = getText(rootNode, "input_run_id", false);
        String businessKey = getText(rootNode, "business_key", false);
        String agentName = getText(rootNode, "agent_name", false);
        String executionId = getText(rootNode, "execution_id", false);
        String parentExecutionId = getText(rootNode, "parent_execution_id", false);
        String entityType = getText(rootNode, "entity_type", false);
        String entityName = getText(rootNode, "entity_name", false);
        Map<String, Object> entityMetadata = getObjectMap(mapper, rootNode.get("entity_metadata"));
        if (inputRunId == null
                && businessKey == null
                && agentName == null
                && executionId == null
                && parentExecutionId == null
                && entityType == null
                && entityName == null
                && (entityMetadata == null || entityMetadata.isEmpty())) {
            return null;
        }
        return ExecutionTraceContext.fromExistingIds(
                inputRunId,
                businessKey,
                agentName,
                executionId,
                parentExecutionId,
                entityType,
                entityName,
                entityMetadata);
    }

    private static String getLegacyEventType(JsonNode eventNode) throws IOException {
        JsonNode eventTypeNode = eventNode.get("eventType");
        if (eventTypeNode == null || !eventTypeNode.isTextual()) {
            throw new IOException("Missing 'eventType' field in event JSON");
        }
        return eventTypeNode.asText();
    }

    private static JsonNode stripMetaFields(JsonNode eventNode) {
        if (eventNode.isObject()) {
            ObjectNode copy = ((ObjectNode) eventNode).deepCopy();
            copy.remove("eventType");
            copy.remove("eventClass");
            return copy;
        }
        return eventNode;
    }

    private static String getText(JsonNode node, String field, boolean required)
            throws IOException {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            if (required) {
                throw new IOException("Missing '" + field + "' field in EventLogRecord JSON");
            }
            return null;
        }
        if (!value.isTextual()) {
            throw new IOException("Field '" + field + "' must be textual in EventLogRecord JSON");
        }
        return value.asText();
    }

    private static UUID parseUuid(String value) throws IOException {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid 'event_id' field in EventLogRecord JSON", e);
        }
    }

    private static Map<String, Object> getObjectMap(ObjectMapper mapper, JsonNode node)
            throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IOException(
                    "Field 'entity_metadata' must be an object in EventLogRecord JSON");
        }
        Map<String, Object> result =
                mapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
        return result == null ? new HashMap<>() : result;
    }
}
