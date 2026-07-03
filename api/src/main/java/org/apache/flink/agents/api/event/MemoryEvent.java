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

package org.apache.flink.agents.api.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.flink.agents.api.Event;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Base class of the memory observation events. One event per (memory scope x operation) is emitted
 * at the action finish boundary; each concrete subclass pins one of the seven operation kinds as
 * its {@code type}.
 *
 * <p>Attributes: {@code key} — the String Flink key the operation belongs to; {@code value} — the
 * operation's folded JSON value map. Framework observation events are skipped for non-String keyed
 * streams. On the wire the event serializes as {@code {id, type, attributes: {key, value}}}.
 */
public abstract class MemoryEvent extends Event {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().disable(JsonWriteFeature.WRITE_NAN_AS_STRINGS).build();

    /**
     * The seven memory event types, each mapped to its deserialization constructor ({@code (id,
     * attributes)}). Single source of truth for both {@link #isMemoryType} and {@link #fromEvent}.
     */
    private static final Map<String, BiFunction<UUID, Map<String, Object>, MemoryEvent>> FACTORIES =
            Map.<String, BiFunction<UUID, Map<String, Object>, MemoryEvent>>of(
                    ShortTermWriteEvent.EVENT_TYPE, ShortTermWriteEvent::new,
                    ShortTermReadEvent.EVENT_TYPE, ShortTermReadEvent::new,
                    SensoryWriteEvent.EVENT_TYPE, SensoryWriteEvent::new,
                    SensoryReadEvent.EVENT_TYPE, SensoryReadEvent::new,
                    LongTermUpdateEvent.EVENT_TYPE, LongTermUpdateEvent::new,
                    LongTermGetEvent.EVENT_TYPE, LongTermGetEvent::new,
                    LongTermSearchEvent.EVENT_TYPE, LongTermSearchEvent::new);

    /** Returns true iff {@code type} is one of the seven memory operation event types. */
    public static boolean isMemoryType(String type) {
        return type != null && FACTORIES.containsKey(type);
    }

    protected MemoryEvent(String type, String key, Map<String, Object> value) {
        super(type, normalizeAttributes(key, value));
    }

    protected MemoryEvent(String type, Map<String, Object> attributes) {
        super(type, normalizeAttributes(attributes));
    }

    /**
     * Deserialization constructor path. The subclass {@code @JsonCreator}s are REQUIRED: instances
     * land in ActionState.outputEvents and deserialize polymorphically via the {@code @class} mixin
     * in ActionStateSerde — without an explicit creator Jackson cannot instantiate the subtype.
     */
    protected MemoryEvent(UUID id, String type, Map<String, Object> attributes) {
        super(id, type, normalizeAttributes(attributes));
    }

    static Map<String, Object> normalizeAttributes(Map<String, Object> attributes) {
        if (attributes == null) {
            throw new IllegalArgumentException("Memory observation attributes must not be null.");
        }
        Object key = attributes.get("key");
        if (!(key instanceof String)) {
            throw new IllegalArgumentException(
                    "Memory observation attribute 'key' must be a string.");
        }
        Object value = attributes.get("value");
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(
                    "Memory observation attribute 'value' must be a map.");
        }
        Map<String, Object> normalized = new LinkedHashMap<>(attributes);
        try {
            normalized.put(
                    "value", MAPPER.readValue(MAPPER.writeValueAsBytes(value), Object.class));
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException(
                    "Memory observation value must be JSON serializable.", e);
        }
        return normalized;
    }

    static Map<String, Object> normalizeAttributes(String key, Map<String, Object> value) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("key", key);
        attributes.put("value", value);
        return normalizeAttributes(attributes);
    }

    /** Converts a generic {@link Event} carrying a memory type into its typed subclass view. */
    public static MemoryEvent fromEvent(Event event) {
        BiFunction<UUID, Map<String, Object>, MemoryEvent> factory = FACTORIES.get(event.getType());
        if (factory == null) {
            throw new IllegalArgumentException("Not a memory event type: " + event.getType());
        }
        MemoryEvent result = factory.apply(event.getId(), event.getAttributes());
        if (event.hasSourceTimestamp()) {
            result.setSourceTimestamp(event.getSourceTimestamp());
        }
        return result;
    }

    @JsonIgnore
    public String getKey() {
        return (String) getAttr("key");
    }

    @SuppressWarnings("unchecked")
    @JsonIgnore
    public Map<String, Object> getValue() {
        return (Map<String, Object>) getAttr("value");
    }
}
