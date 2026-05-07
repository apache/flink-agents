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
 *   <li>Deserializing the EventContext (contains eventType and timestamp)
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

        // Deserialize event as base Event
        JsonNode eventNode = rootNode.get("event");
        if (eventNode == null) {
            throw new IOException("Missing 'event' field in EventLogRecord JSON");
        }
        String eventType = getEventType(eventNode);

        Event event = mapper.treeToValue(stripMetaFields(eventNode), Event.class);
        EventContext eventContext = new EventContext(eventType, timestampNode.asText());

        return new EventLogRecord(eventContext, event);
    }

    private static String getEventType(JsonNode eventNode) throws IOException {
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
}
