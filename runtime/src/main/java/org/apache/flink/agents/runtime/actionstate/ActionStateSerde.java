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
package org.apache.flink.agents.runtime.actionstate;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.context.MemoryUpdate;
import org.apache.flink.agents.runtime.operator.ActionTask;

import java.io.IOException;
import java.util.Base64;

/**
 * Backend-agnostic serializer/deserializer for {@link ActionState}.
 *
 * <p>Uses Jackson {@link ObjectMapper} configured with polymorphic type information for the {@link
 * Event} hierarchy and a custom null-serializer for {@link ActionTask}. Both Kafka and Fluss
 * ActionStateStore backends delegate to this class for consistent serialization format.
 */
public final class ActionStateSerde {

    /**
     * Reserved envelope key marking a base64-encoded {@code byte[]} {@link MemoryUpdate} value. A
     * namespaced key keeps the residual collision with a genuine single-entry user {@code Map}
     * negligible and clearly framework-reserved.
     */
    private static final String MEMORY_UPDATE_BYTES_KEY = "__flink_agents_bytes__";

    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();

    private ActionStateSerde() {}

    /** Serializes an {@link ActionState} to a JSON byte array. */
    public static byte[] serialize(ActionState state) {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(state);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize ActionState", e);
        }
    }

    /** Deserializes an {@link ActionState} from a JSON byte array. */
    public static ActionState deserialize(byte[] data) {
        try {
            return OBJECT_MAPPER.readValue(data, ActionState.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize ActionState", e);
        }
    }

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // Add type information for polymorphic Event deserialization
        mapper.addMixIn(Event.class, EventTypeInfoMixin.class);
        mapper.addMixIn(InputEvent.class, EventTypeInfoMixin.class);
        mapper.addMixIn(OutputEvent.class, EventTypeInfoMixin.class);

        // Custom serializer for ActionTask - always serialize as null
        SimpleModule module = new SimpleModule();
        module.addSerializer(ActionTask.class, new ActionTaskSerializer());
        mapper.registerModule(module);

        // Preserve byte[] MemoryUpdate values across the untyped Object round-trip
        mapper.addMixIn(MemoryUpdate.class, MemoryUpdateValueMixin.class);

        return mapper;
    }

    /** Mixin to add type information for Event hierarchy. */
    @JsonTypeInfo(
            use = JsonTypeInfo.Id.CLASS,
            include = JsonTypeInfo.As.PROPERTY,
            property = "@class")
    abstract static class EventTypeInfoMixin {}

    /** Custom serializer for ActionTask that always serializes as null. */
    static class ActionTaskSerializer extends JsonSerializer<ActionTask> {
        @Override
        public void serialize(ActionTask value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeNull();
        }
    }

    /** Binds the byte[]-preserving (de)serializer to {@link MemoryUpdate#getValue()} only. */
    abstract static class MemoryUpdateValueMixin {
        @JsonSerialize(using = MemoryUpdateValueSerializer.class)
        @JsonDeserialize(using = MemoryUpdateValueDeserializer.class)
        Object value;
    }

    /**
     * Serializes a {@code byte[]} {@link MemoryUpdate} value as a one-key base64 envelope so it can
     * be recovered as a {@code byte[]} rather than the base64 {@code String} that untyped {@code
     * Object} serialization would yield; all other types delegate to default serialization and stay
     * byte-identical on disk.
     *
     * <p>Only a top-level {@code byte[]} value is preserved; a {@code byte[]} nested inside a
     * {@code List} or {@code Map} value goes through the default serializers and is not preserved.
     */
    static class MemoryUpdateValueSerializer extends JsonSerializer<Object> {
        @Override
        public void serialize(Object value, JsonGenerator gen, SerializerProvider provider)
                throws IOException {
            if (value instanceof byte[]) {
                gen.writeStartObject();
                gen.writeStringField(
                        MEMORY_UPDATE_BYTES_KEY,
                        Base64.getEncoder().encodeToString((byte[]) value));
                gen.writeEndObject();
            } else {
                provider.defaultSerializeValue(value, gen);
            }
        }
    }

    /**
     * Reverses the base64 envelope written by {@link MemoryUpdateValueSerializer} back to a {@code
     * byte[]}; every other shape reproduces stock untyped {@code Object} binding.
     *
     * <p>Only a top-level {@code byte[]} value is recovered; a {@code byte[]} nested inside a
     * {@code List} or {@code Map} value is not.
     */
    static class MemoryUpdateValueDeserializer extends JsonDeserializer<Object> {
        @Override
        public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = ctxt.readTree(p);
            if (node.isObject()
                    && node.size() == 1
                    && node.has(MEMORY_UPDATE_BYTES_KEY)
                    && node.get(MEMORY_UPDATE_BYTES_KEY).isTextual()) {
                try {
                    return Base64.getDecoder().decode(node.get(MEMORY_UPDATE_BYTES_KEY).asText());
                } catch (IllegalArgumentException notBase64) {
                    // Not a real envelope - fall through to generic binding.
                }
            }
            return ctxt.readTreeAsValue(node, Object.class);
        }
    }
}
