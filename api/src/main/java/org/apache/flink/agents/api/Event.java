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
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Base class for all event types in the system.
 *
 * <p>This class serves dual purposes:
 *
 * <ul>
 *   <li><b>Unified events</b>: Instantiated directly with a user-defined {@code type} string and
 *       arbitrary key-value {@code attributes}. No subclassing required.
 *   <li><b>Subclassed events</b>: Traditional usage where concrete subclasses (e.g., {@link
 *       InputEvent}) extend this class. The {@code type} defaults to the fully qualified class
 *       name.
 * </ul>
 */
public class Event {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UUID id;
    private final String type;
    private final Map<String, Object> attributes;
    /** The timestamp of the source record. */
    private Long sourceTimestamp;

    /** Unified event with user-defined type and properties. */
    public Event(String type, Map<String, Object> attributes) {
        this(UUID.randomUUID(), type, attributes);
    }

    /** Unified event with user-defined type and empty properties. */
    public Event(String type) {
        this(type, new HashMap<>());
    }

    /** Subclasses that don't set type (defaults to class name). */
    public Event() {
        this(UUID.randomUUID(), null, new HashMap<>());
    }

    /** Subclasses with id and attributes but no explicit type. */
    public Event(UUID id, Map<String, Object> attributes) {
        this(id, null, attributes);
    }

    @JsonCreator
    public Event(
            @JsonProperty("id") UUID id,
            @JsonProperty("type") String type,
            @JsonProperty("attributes") Map<String, Object> attributes) {
        this.id = id;
        this.type = type;
        this.attributes = attributes != null ? attributes : new HashMap<>();
    }

    public UUID getId() {
        return id;
    }

    /**
     * Returns the event type used for routing.
     *
     * <p>For unified events, returns the user-defined type string. For subclasses, defaults to the
     * fully qualified class name.
     *
     * <p>Note: This method is {@link JsonIgnore}d so Jackson uses {@link #getRawType()} for the
     * "type" JSON property, preserving null for subclassed events.
     */
    @JsonIgnore
    public String getType() {
        return type != null ? type : this.getClass().getName();
    }

    /**
     * Returns the raw type field value, which may be null for subclassed events. This is used by
     * Jackson for serialization of the "type" JSON property.
     */
    @JsonProperty("type")
    public String getRawType() {
        return type;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public Object getAttr(String name) {
        return attributes.get(name);
    }

    public void setAttr(String name, Object value) {
        attributes.put(name, value);
    }

    public boolean hasSourceTimestamp() {
        return sourceTimestamp != null;
    }

    public Long getSourceTimestamp() {
        return sourceTimestamp;
    }

    public void setSourceTimestamp(long timestamp) {
        this.sourceTimestamp = timestamp;
    }

    /**
     * Creates an Event from a JSON string.
     *
     * @param json the JSON string to deserialize
     * @return the deserialized Event
     * @throws IOException if parsing fails or the 'type' field is missing
     */
    public static Event fromJson(String json) throws IOException {
        Event event = MAPPER.readValue(json, Event.class);
        if (event.type == null || event.type.isEmpty()) {
            throw new IllegalArgumentException("Event JSON must contain a 'type' field.");
        }
        return event;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Event other = (Event) o;
        return Objects.equals(this.id, other.id)
                && Objects.equals(this.getType(), other.getType())
                && Objects.equals(this.attributes, other.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, getType(), attributes);
    }
}
