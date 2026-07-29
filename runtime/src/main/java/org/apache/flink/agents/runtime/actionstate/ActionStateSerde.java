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
import org.apache.flink.agents.api.context.MemoryUpdate;
import org.apache.flink.agents.runtime.operator.ActionTask;
import org.apache.flink.api.common.serialization.SerializerConfigImpl;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.core.memory.DataInputViewStreamWrapper;
import org.apache.flink.core.memory.DataOutputViewStreamWrapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
     * Recovery-compat contract for the {@link MemoryUpdate#getValue() MemoryUpdate.value} envelope.
     *
     * <p>A non-null value is written on disk as {@code
     * {"serde":"kryo","version":1,"payload":<b64>}} where {@code payload} is the base64 of the
     * Flink-native (Kryo) binary of the untyped value. That binary is only guaranteed readable by
     * the same code and same Flink version that wrote it — which is all that ever happens, because
     * the durable-execution journal is checkpoint-scoped and never survives a code or Flink
     * upgrade. Bump {@code version} if the payload encoding ever changes; Kryo is pinned within a
     * Flink major release (supplied by the cluster).
     */
    private static final String MEMORY_UPDATE_SERDE = "kryo";

    private static final int MEMORY_UPDATE_VERSION = 1;
    private static final String ENVELOPE_SERDE_FIELD = "serde";
    private static final String ENVELOPE_VERSION_FIELD = "version";
    private static final String ENVELOPE_PAYLOAD_FIELD = "payload";

    // The Flink-native serializer for an untyped Object resolves to Kryo
    // (GenericTypeInfo<Object> -> KryoSerializer).
    private static final TypeSerializer<Object> VALUE_SERIALIZER_TEMPLATE =
            TypeInformation.of(Object.class).createSerializer(new SerializerConfigImpl());

    // KryoSerializer is not thread-safe; only duplicate() (which is safe) is ever called on the
    // shared template, giving each thread an independent copy for serialize/deserialize. Because
    // this ThreadLocal is static, a thread's copy can retain (pin) the classes of the values it has
    // serialized for that thread's lifetime — an accepted tradeoff over rebuilding a heavy Kryo
    // instance on every call.
    private static final ThreadLocal<TypeSerializer<Object>> VALUE_SERIALIZER =
            ThreadLocal.withInitial(VALUE_SERIALIZER_TEMPLATE::duplicate);

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

        // Custom serializer for ActionTask - always serialize as null
        SimpleModule module = new SimpleModule();
        module.addSerializer(ActionTask.class, new ActionTaskSerializer());
        mapper.registerModule(module);

        // Preserve the concrete runtime type of the untyped MemoryUpdate.value across durable
        // recovery by wrapping it in a versioned Kryo envelope.
        mapper.addMixIn(MemoryUpdate.class, MemoryUpdateValueMixin.class);

        return mapper;
    }

    private static byte[] kryoSerialize(Object value) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        VALUE_SERIALIZER.get().serialize(value, new DataOutputViewStreamWrapper(baos));
        return baos.toByteArray();
    }

    private static Object kryoDeserialize(byte[] bytes) throws IOException {
        return VALUE_SERIALIZER
                .get()
                .deserialize(new DataInputViewStreamWrapper(new ByteArrayInputStream(bytes)));
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

    /**
     * Binds the Kryo envelope (de)serializer to the {@code value} property of {@link MemoryUpdate}.
     */
    abstract static class MemoryUpdateValueMixin {
        @JsonSerialize(using = MemoryUpdateValueSerializer.class)
        @JsonDeserialize(using = MemoryUpdateValueDeserializer.class)
        Object value;
    }

    /**
     * Writes a non-null {@link MemoryUpdate} value as the {@code {serde,version,payload}} envelope.
     */
    static class MemoryUpdateValueSerializer extends JsonSerializer<Object> {
        @Override
        public void serialize(Object value, JsonGenerator gen, SerializerProvider provider)
                throws IOException {
            gen.writeStartObject();
            gen.writeStringField(ENVELOPE_SERDE_FIELD, MEMORY_UPDATE_SERDE);
            gen.writeNumberField(ENVELOPE_VERSION_FIELD, MEMORY_UPDATE_VERSION);
            gen.writeStringField(
                    ENVELOPE_PAYLOAD_FIELD,
                    Base64.getEncoder().encodeToString(kryoSerialize(value)));
            gen.writeEndObject();
        }
    }

    /** Reads the Kryo envelope back to the concrete value and version-checks it. */
    static class MemoryUpdateValueDeserializer extends JsonDeserializer<Object> {
        @Override
        public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = ctxt.readTree(p);
            // Every non-null value this serializer writes is an envelope, and the durable journal
            // never outlives the code that wrote it (see the recovery-compat contract above), so a
            // non-envelope node is an unsupported cross-version restore rather than legacy data to
            // tolerate. Reject it instead of silently binding it to a wrong-typed stock value.
            if (!isEnvelope(node)) {
                throw new IOException(
                        "Expected a Kryo-envelope MemoryUpdate value but found: "
                                + node.getNodeType());
            }
            int version = node.get(ENVELOPE_VERSION_FIELD).asInt();
            if (version != MEMORY_UPDATE_VERSION) {
                throw new IOException(
                        "Unsupported MemoryUpdate value envelope version: " + version);
            }
            return kryoDeserialize(
                    Base64.getDecoder().decode(node.get(ENVELOPE_PAYLOAD_FIELD).asText()));
        }

        private static boolean isEnvelope(JsonNode n) {
            return n.isObject()
                    && MEMORY_UPDATE_SERDE.equals(n.path(ENVELOPE_SERDE_FIELD).asText())
                    && n.has(ENVELOPE_VERSION_FIELD)
                    && n.path(ENVELOPE_PAYLOAD_FIELD).isTextual();
        }
    }
}
