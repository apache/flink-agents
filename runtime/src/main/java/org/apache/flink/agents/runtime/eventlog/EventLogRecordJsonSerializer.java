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

import java.io.IOException;

/**
 * Custom JSON serializer for {@link EventLogRecord}.
 *
 * <p>This serializer handles the serialization of EventLogRecord instances to JSON format suitable
 * for structured logging. The serialization includes:
 *
 * <ul>
 *   <li>EventContext with eventType and timestamp information
 *   <li>Event data serialized as a standard JSON object
 * </ul>
 *
 * <p>The resulting JSON structure is:
 *
 * <pre>{@code
 * {
 *   "context": {
 *     "eventType": "org.apache.flink.agents.api.InputEvent",
 *     "timestamp": "2024-01-15T10:30:00Z"
 *   },
 *   "event": {
 *     // Event-specific fields serialized normally
 *   }
 * }
 * }</pre>
 */
public class EventLogRecordJsonSerializer extends JsonSerializer<EventLogRecord> {

    @Override
    public void serialize(EventLogRecord record, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {

        gen.writeStartObject();

        // Serialize context - contains eventType and timestamp
        gen.writeFieldName("context");
        serializers.defaultSerializeValue(record.getContext(), gen);

        // Serialize event - standard JSON serialization
        gen.writeFieldName("event");
        serializers.defaultSerializeValue(record.getEvent(), gen);

        gen.writeEndObject();
    }
}
