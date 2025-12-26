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
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventContext;

import java.io.IOException;

/**
 * Custom JSON deserializer for {@link EventLogRecord}.
 *
 * <p>This deserializer reconstructs EventLogRecord instances from JSON format by:
 *
 * <ul>
 *   <li>Deserializing the EventContext normally (contains eventType and timestamp)
 *   <li>Using the eventType from context to determine the concrete Event class
 *   <li>Deserializing the event JSON to the appropriate concrete Event type
 * </ul>
 *
 * <p>This approach leverages the eventType information stored in EventContext to handle polymorphic
 * Event deserialization without requiring type annotations on the Event objects themselves.
 */
public class EventLogRecordJsonDeserializer extends JsonDeserializer<EventLogRecord> {

    @Override
    public EventLogRecord deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {

        ObjectMapper mapper = (ObjectMapper) parser.getCodec();
        JsonNode rootNode = mapper.readTree(parser);

        // Deserialize context
        JsonNode contextNode = rootNode.get("context");
        if (contextNode == null) {
            throw new IOException("Missing 'context' field in EventLogRecord JSON");
        }

        EventContext eventContext = mapper.treeToValue(contextNode, EventContext.class);
        if (eventContext == null) {
            throw new IOException("Failed to deserialize EventContext");
        }

        // Deserialize event using eventType from context
        JsonNode eventNode = rootNode.get("event");
        if (eventNode == null) {
            throw new IOException("Missing 'event' field in EventLogRecord JSON");
        }

        Event event = deserializeEvent(mapper, eventNode, eventContext.getEventType());

        return new EventLogRecord(eventContext, event);
    }

    /**
     * Deserializes an Event from JSON using the provided event type.
     *
     * @param mapper the ObjectMapper to use for deserialization
     * @param eventNode the JSON node containing the event data
     * @param eventType the fully qualified class name of the event type
     * @return the deserialized Event instance, or base Event if deserialization fails
     */
    private Event deserializeEvent(ObjectMapper mapper, JsonNode eventNode, String eventType)
            throws IOException {
        try {
            // Load the concrete event class
            Class<?> eventClass = Class.forName(eventType);

            // Verify it's actually an Event subclass
            if (!Event.class.isAssignableFrom(eventClass)) {
                throw new IOException(
                        String.format("Class '%s' is not a subclass of Event", eventType));
            }

            // Deserialize to the concrete event type
            @SuppressWarnings("unchecked")
            Class<? extends Event> concreteEventClass = (Class<? extends Event>) eventClass;
            return mapper.treeToValue(eventNode, concreteEventClass);
        } catch (Exception e) {
            throw new IOException(
                    String.format("Failed to deserialize event of type '%s'", eventType), e);
        }
    }
}
