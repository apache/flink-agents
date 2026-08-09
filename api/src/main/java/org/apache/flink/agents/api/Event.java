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

package org.apache.flink.agents.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.apache.flink.agents.api.context.MemoryRef;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Base class for all event types in the system. */
public class Event {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UUID id;
    private final String type;
    private final Map<String, Object> attributes;

    // Keep the annotation on the field as well as the creator parameter so it also applies when
    // Jackson constructs Event subclasses whose creators do not declare attachments.
    @JsonDeserialize(contentUsing = AttachmentValueDeserializer.class)
    private final Map<String, Object> attachments;

    /**
     * Runtime-internal timestamp from the source record. Not part of the cross-language event
     * contract; used by the Flink runtime for timestamp propagation.
     */
    private Long sourceTimestamp;

    /** Unified event with user-defined type and attributes. */
    public Event(String type, Map<String, Object> attributes) {
        this(UUID.randomUUID(), type, attributes, new HashMap<>());
    }

    /** Unified event with user-defined type and empty attributes. */
    public Event(String type) {
        this(type, new HashMap<>());
    }

    public Event(
            @JsonProperty("id") UUID id,
            @JsonProperty("type") String type,
            @JsonProperty("attributes") Map<String, Object> attributes) {
        this(id, type, attributes, new HashMap<>());
    }

    @JsonCreator
    public Event(
            @JsonProperty("id") UUID id,
            @JsonProperty("type") String type,
            @JsonProperty("attributes") Map<String, Object> attributes,
            @JsonProperty("attachments")
                    @JsonDeserialize(contentUsing = AttachmentValueDeserializer.class)
                    Map<String, Object> attachments) {
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("Event 'type' must not be null or empty.");
        }
        this.id = id;
        this.type = type;
        this.attributes = attributes != null ? new HashMap<>(attributes) : new HashMap<>();
        this.attachments = attachments != null ? new HashMap<>(attachments) : new HashMap<>();
    }

    public UUID getId() {
        return id;
    }

    /** Returns the event type string used for routing. */
    @JsonProperty("type")
    public String getType() {
        return type;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public Map<String, Object> getAttachments() {
        return attachments;
    }

    public Object getAttr(String name) {
        return attributes.get(name);
    }

    public void setAttr(String name, Object value) {
        attributes.put(name, value);
    }

    public Object getAttachment(String name) {
        return attachments.get(name);
    }

    public void setAttachment(String name, Object value) {
        attachments.put(name, value);
    }

    @JsonIgnore
    public boolean hasSourceTimestamp() {
        return sourceTimestamp != null;
    }

    @JsonIgnore
    public Long getSourceTimestamp() {
        return sourceTimestamp;
    }

    @JsonIgnore
    public void setSourceTimestamp(long timestamp) {
        this.sourceTimestamp = timestamp;
    }

    /**
     * Creates a base Event from another Event, copying id, type, attributes, and attachments.
     * Subclasses override this to reconstruct typed event objects with proper field
     * deserialization.
     */
    public static Event fromEvent(Event event) {
        Event copy =
                new Event(
                        event.getId(),
                        event.getType(),
                        new HashMap<>(event.getAttributes()),
                        new HashMap<>(event.attachments));
        if (event.hasSourceTimestamp()) {
            copy.setSourceTimestamp(event.getSourceTimestamp());
        }
        return copy;
    }

    /**
     * Creates an Event from a JSON string.
     *
     * @param json the JSON string to deserialize
     * @return the deserialized Event
     * @throws IOException if JSON parsing fails or the 'type' field is missing or empty
     */
    public static Event fromJson(String json) throws IOException {
        return MAPPER.readValue(json, Event.class);
    }

    /** Deserializes one attachment value, preserving explicitly tagged memory references. */
    static final class AttachmentValueDeserializer extends JsonDeserializer<Object> {

        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            JsonNode node = parser.getCodec().readTree(parser);
            if (node.isObject()
                    && MemoryRef.TYPE_VALUE.equals(node.path(MemoryRef.TYPE_FIELD).asText())) {
                return parser.getCodec().treeToValue(node, MemoryRef.class);
            }
            return parser.getCodec().treeToValue(node, Object.class);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Event other = (Event) o;
        return Objects.equals(this.id, other.id)
                && Objects.equals(this.getType(), other.getType())
                && Objects.equals(this.attributes, other.attributes)
                && Objects.equals(this.attachments, other.attachments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, getType(), attributes, attachments);
    }
}
