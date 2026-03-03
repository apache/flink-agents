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
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventContext;

import java.io.IOException;

/**
 * Custom JSON deserializer for {@link EventLogRecord}.
 *
 * <p>This deserializer reconstructs EventLogRecord instances from JSON format by:
 *
 * <ul>
 *   <li>Deserializing the EventContext normally (contains eventType, eventClass, and timestamp)
 *   <li>Using the {@code eventClass} from context to determine the concrete Event class for
 *       deserialization
 *   <li>Falling back to {@code eventType} if {@code eventClass} is not present (backward compat)
 * </ul>
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

        // Deserialize event using eventType/eventClass from event node
        JsonNode eventNode = rootNode.get("event");
        if (eventNode == null) {
            throw new IOException("Missing 'event' field in EventLogRecord JSON");
        }
        String eventType = getEventType(eventNode);
        String eventClass = getEventClass(eventNode, eventType);

        Event event = deserializeEvent(mapper, stripMetaFields(eventNode), eventClass);
        EventContext eventContext = new EventContext(eventType, eventClass, timestampNode.asText());

        return new EventLogRecord(eventContext, event);
    }

    /**
     * Deserializes an Event from JSON using the eventClass.
     *
     * @param mapper the ObjectMapper to use for deserialization
     * @param eventNode the JSON node containing the event data
     * @param eventClass the fully qualified Java class name to deserialize into
     * @return the deserialized Event instance
     */
    private Event deserializeEvent(ObjectMapper mapper, JsonNode eventNode, String eventClass)
            throws IOException {
        try {
            // Load the concrete event class
            Class<?> clazz =
                    Class.forName(eventClass, true, Thread.currentThread().getContextClassLoader());

            // Verify it's actually an Event subclass (or Event itself)
            if (!Event.class.isAssignableFrom(clazz)) {
                throw new IOException(
                        String.format("Class '%s' is not a subclass of Event", eventClass));
            }

            // Deserialize to the concrete event type
            @SuppressWarnings("unchecked")
            Class<? extends Event> concreteEventClass = (Class<? extends Event>) clazz;
            return mapper.treeToValue(eventNode, concreteEventClass);
        } catch (Exception e) {
            throw new IOException(
                    String.format("Failed to deserialize event of class '%s'", eventClass), e);
        }
    }

    private static String getEventType(JsonNode eventNode) throws IOException {
        JsonNode eventTypeNode = eventNode.get("eventType");
        if (eventTypeNode == null || !eventTypeNode.isTextual()) {
            throw new IOException("Missing 'eventType' field in event JSON");
        }
        return eventTypeNode.asText();
    }

    /**
     * Gets the eventClass from the event node, falling back to eventType for backward
     * compatibility.
     */
    private static String getEventClass(JsonNode eventNode, String eventType) {
        JsonNode eventClassNode = eventNode.get("eventClass");
        if (eventClassNode != null && eventClassNode.isTextual()) {
            return eventClassNode.asText();
        }
        // Backward compat: old logs don't have eventClass, fall back to eventType
        return eventType;
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
}
