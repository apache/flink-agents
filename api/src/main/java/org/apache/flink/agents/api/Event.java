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
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.apache.flink.agents.api.context.MemoryRef;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiFunction;

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

    @Nullable private UUID upstreamEventId;
    @Nullable private String upstreamActionName;

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

    /**
     * Reconstructs an Event with an existing identity and optional framework-managed lineage.
     *
     * <p>The lineage values support deserialization and reconstruction. {@code
     * RunnerContext#sendEvent} rejects an Event that carries them; the runtime sets lineage to the
     * current trigger Event ID and Action name when it finalizes the Action's outputs.
     *
     * @param id the existing Event ID
     * @param type the Event type used for routing
     * @param attributes the Event payload
     * @param attachments key-value data passed between Actions through sensory memory
     * @param upstreamEventId the ID of the direct upstream Event, or {@code null}
     * @param upstreamActionName the name of the emitting Action, or {@code null}
     */
    @JsonCreator
    public Event(
            @JsonProperty("id") UUID id,
            @JsonProperty("type") String type,
            @JsonProperty("attributes") Map<String, Object> attributes,
            @JsonProperty("attachments")
                    @JsonDeserialize(contentUsing = AttachmentValueDeserializer.class)
                    Map<String, Object> attachments,
            @JsonProperty("upstreamEventId") @Nullable UUID upstreamEventId,
            @JsonProperty("upstreamActionName") @Nullable String upstreamActionName) {
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("Event 'type' must not be null or empty.");
        }
        // Explicit null matches an omitted id: both mint a per-occurrence UUID.
        this.id = id != null ? id : UUID.randomUUID();
        this.type = type;
        this.attributes = attributes != null ? new HashMap<>(attributes) : new HashMap<>();
        this.attachments = attachments != null ? new HashMap<>(attachments) : new HashMap<>();
        this.upstreamEventId = upstreamEventId;
        this.upstreamActionName = upstreamActionName;
    }

    /** Reconstructs an Event with an existing identity, attachments, and no upstream lineage. */
    public Event(
            UUID id, String type, Map<String, Object> attributes, Map<String, Object> attachments) {
        this(id, type, attributes, attachments, null, null);
    }

    /** Reconstructs an Event with an existing identity and optional framework-managed lineage. */
    public Event(
            UUID id,
            String type,
            Map<String, Object> attributes,
            @Nullable UUID upstreamEventId,
            @Nullable String upstreamActionName) {
        this(id, type, attributes, new HashMap<>(), upstreamEventId, upstreamActionName);
    }

    /** Reconstructs an Event with an existing identity and no upstream lineage. */
    public Event(UUID id, String type, Map<String, Object> attributes) {
        this(id, type, attributes, new HashMap<>(), null, null);
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

    /** Returns the ID of the Event consumed by the Action that emitted this Event. */
    @Nullable
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public UUID getUpstreamEventId() {
        return upstreamEventId;
    }

    /**
     * Sets the framework-managed ID of the Event consumed by the emitting Action.
     *
     * <p>The runtime sets this value when it finalizes an Action's outputs. {@code
     * RunnerContext#sendEvent} rejects an Event that already carries it.
     */
    public void setUpstreamEventId(@Nullable UUID upstreamEventId) {
        this.upstreamEventId = upstreamEventId;
    }

    /** Returns the name of the Action that emitted this Event. */
    @Nullable
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getUpstreamActionName() {
        return upstreamActionName;
    }

    /**
     * Sets the framework-managed name of the Action that emitted this Event.
     *
     * <p>The runtime sets this value when it finalizes an Action's outputs. {@code
     * RunnerContext#sendEvent} rejects an Event that already carries it.
     */
    public void setUpstreamActionName(@Nullable String upstreamActionName) {
        this.upstreamActionName = upstreamActionName;
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

    /**
     * Sets an attachment on this Event.
     *
     * <p>If {@code value} is a {@link MemoryRef}, it must reference sensory memory and will not be
     * wrapped again.
     */
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
     * Creates a base Event from another Event, copying its identity, data, attachments, and
     * framework metadata. Subclasses override this to reconstruct typed event objects with proper
     * field deserialization.
     */
    public static Event fromEvent(Event event) {
        return reconstructFrom(
                event, (id, attributes) -> new Event(id, event.getType(), attributes));
    }

    /**
     * Reconstructs a typed Event while preserving the source identity and framework metadata. The
     * factory receives the source ID and a copy of its attributes.
     */
    protected static <T extends Event> T reconstructFrom(
            Event source, BiFunction<UUID, Map<String, Object>, T> factory) {
        Objects.requireNonNull(source, "source Event must not be null");
        Objects.requireNonNull(factory, "Event reconstruction factory must not be null");

        T reconstructed =
                Objects.requireNonNull(
                        factory.apply(source.getId(), new HashMap<>(source.getAttributes())),
                        "Event reconstruction factory must not return null");
        if (!Objects.equals(source.getId(), reconstructed.getId())) {
            throw new IllegalStateException(
                    "Reconstructing the same Event occurrence must preserve Event ID "
                            + source.getId());
        }
        Event reconstructedEvent = reconstructed;
        reconstructedEvent.attachments.clear();
        reconstructedEvent.attachments.putAll(source.attachments);
        reconstructedEvent.sourceTimestamp = source.sourceTimestamp;
        reconstructedEvent.upstreamEventId = source.upstreamEventId;
        reconstructedEvent.upstreamActionName = source.upstreamActionName;
        return reconstructed;
    }

    /**
     * Enforces the shape half of a built-in attribute schema: every name in {@code required} must
     * be present, and no name outside {@code known} is allowed. Called by {@link
     * #validateAttributeSchema} before the per-attribute type checks.
     *
     * @param type the built-in event type, used in error messages
     * @param attributes the raw attributes to validate
     * @param required attribute names that must be present
     * @param known every attribute name the schema allows; any other name is rejected
     * @throws IllegalArgumentException if a required attribute is absent or an unknown attribute is
     *     present
     */
    private static void validateBuiltInAttributes(
            String type, Map<String, Object> attributes, Set<String> required, Set<String> known) {
        for (String name : required) {
            if (!attributes.containsKey(name)) {
                throw new IllegalArgumentException(
                        "Missing required attribute '"
                                + name
                                + "' for built-in event type '"
                                + type
                                + "'.");
            }
        }
        for (String key : attributes.keySet()) {
            if (!known.contains(key)) {
                throw new IllegalArgumentException(
                        "Unknown attribute '"
                                + key
                                + "' for built-in event type '"
                                + type
                                + "'; allowed attributes are "
                                + new TreeSet<>(known)
                                + ".");
            }
        }
    }

    /**
     * Returns a required built-in event attribute, asserting its runtime type.
     *
     * @throws IllegalArgumentException if the attribute is missing or not an instance of {@code
     *     expected}
     */
    private static <T> T requireBuiltInAttribute(
            String type, Map<String, Object> attributes, String name, Class<T> expected) {
        Object value = attributes.get(name);
        if (!expected.isInstance(value)) {
            throw new IllegalArgumentException(
                    builtInAttributeTypeMessage(type, name, expected, value));
        }
        return expected.cast(value);
    }

    /**
     * Returns a required built-in event list attribute, asserting that every element is an instance
     * of one of {@code allowedElementTypes}. A nested typed value crosses the JSON boundary as
     * either its concrete type or its serialized {@link Map}, so callers typically allow both.
     *
     * @param elementDescription the element phrasing used in the rejection message, e.g. {@code "a
     *     ChatMessage or its serialized map"}
     * @throws IllegalArgumentException if the attribute is missing, is not a list, or holds an
     *     element of an unexpected type
     */
    private static List<?> requireBuiltInListAttribute(
            String type,
            Map<String, Object> attributes,
            String name,
            String elementDescription,
            Class<?>... allowedElementTypes) {
        List<?> values = requireBuiltInAttribute(type, attributes, name, List.class);
        for (Object element : values) {
            boolean allowed = false;
            for (Class<?> allowedType : allowedElementTypes) {
                allowed |= allowedType.isInstance(element);
            }
            if (!allowed) {
                throw new IllegalArgumentException(
                        "Each '"
                                + name
                                + "' element of built-in event type '"
                                + type
                                + "' must be "
                                + elementDescription
                                + ", but was "
                                + (element == null ? "null" : element.getClass().getSimpleName())
                                + ".");
            }
        }
        return values;
    }

    /**
     * Asserts the runtime type of an optional built-in event attribute when it is present
     * (non-null).
     *
     * @throws IllegalArgumentException if the attribute is present but not an instance of {@code
     *     expected}
     */
    private static void checkBuiltInAttributeType(
            String type, Map<String, Object> attributes, String name, Class<?> expected) {
        Object value = attributes.get(name);
        if (value != null && !expected.isInstance(value)) {
            throw new IllegalArgumentException(
                    builtInAttributeTypeMessage(type, name, expected, value));
        }
    }

    /**
     * Asserts a required built-in event attribute is a {@link UUID} or a {@link UUID} string, the
     * two forms it takes natively versus after JSON deserialization.
     *
     * @throws IllegalArgumentException if the attribute is missing or not a UUID / UUID string
     */
    private static void requireUuidBuiltInAttribute(
            String type, Map<String, Object> attributes, String name) {
        Object value = attributes.get(name);
        if (value instanceof UUID) {
            return;
        }
        if (value instanceof String) {
            try {
                UUID.fromString((String) value);
                return;
            } catch (IllegalArgumentException ignored) {
                // Fall through to the shared rejection below.
            }
        }
        throw new IllegalArgumentException(
                "Attribute '"
                        + name
                        + "' of built-in event type '"
                        + type
                        + "' must be a UUID or UUID string, but was "
                        + (value == null ? "null" : value.getClass().getSimpleName())
                        + ".");
    }

    private static String builtInAttributeTypeMessage(
            String type, String name, Class<?> expected, Object value) {
        return "Attribute '"
                + name
                + "' of built-in event type '"
                + type
                + "' must be a "
                + expected.getSimpleName()
                + ", but was "
                + (value == null ? "null" : value.getClass().getSimpleName())
                + ".";
    }

    /**
     * Validates a built-in event's attributes against its declared {@link BuiltInAttribute} schema
     * in a single pass: every required attribute must be present, no attribute outside the schema
     * is allowed, and each present attribute must match its declared shape. Each built-in event's
     * {@code fromEvent} reconstruction method calls this at the JSON / cross-language boundary
     * ({@link #fromJson(String)} -&gt; {@link BuiltInEvents#restore(Event)}), so a malformed
     * built-in event fails clearly instead of being reconstructed with silently dropped or mistyped
     * fields.
     *
     * <p>It is deliberately NOT called from the {@code @JsonCreator} constructors: checkpoint
     * recovery ({@code ActionStateSerde}) deserializes concrete events directly through those
     * constructors and must keep tolerating framework-internal attributes that are not part of the
     * cross-language schema.
     *
     * <p>This is the declarative counterpart to Flink connector factories' {@code
     * requiredOptions()} / {@code optionalOptions()} plus {@code helper.validate()}: the schema is
     * stated once as data and enforcement is uniform. It delegates to the low-level helpers above,
     * so the rejection messages and the {@link IllegalArgumentException} type are unchanged.
     *
     * @throws IllegalArgumentException if the attributes violate the attribute schema
     */
    protected static void validateAttributeSchema(
            String type, Map<String, Object> attributes, List<BuiltInAttribute> schema) {
        Set<String> required = new LinkedHashSet<>();
        Set<String> known = new LinkedHashSet<>();
        for (BuiltInAttribute attribute : schema) {
            known.add(attribute.name);
            if (attribute.required) {
                required.add(attribute.name);
            }
        }
        validateBuiltInAttributes(type, attributes, required, known);
        for (BuiltInAttribute attribute : schema) {
            String name = attribute.name;
            if (!attribute.required && !attributes.containsKey(name)) {
                continue;
            }
            Class<?>[] types = attribute.types;
            switch (attribute.kind) {
                case SCALAR:
                    if (types.length == 0) {
                        break;
                    }
                    if (attribute.required) {
                        requireBuiltInAttribute(type, attributes, name, types[0]);
                    } else {
                        checkBuiltInAttributeType(type, attributes, name, types[0]);
                    }
                    break;
                case LIST:
                    requireBuiltInListAttribute(
                            type, attributes, name, attribute.elementDescription, types);
                    break;
                case UUID:
                    requireUuidBuiltInAttribute(type, attributes, name);
                    break;
            }
        }
    }

    /**
     * Declares one attribute of a built-in event's fixed cross-language attribute schema, in the
     * declarative style of Flink connector factories' {@code requiredOptions()} / {@code
     * optionalOptions()}. A built-in event states its attributes once as an ordered list of these,
     * and {@link #validateAttributeSchema} enforces presence, unknown-key rejection, and
     * per-attribute shape in a single pass at the JSON boundary.
     *
     * <p>Instances are created through the static factories. The fields are private because only
     * the enclosing {@link Event} reads them back while validating; callers outside this class only
     * ever assemble an attribute schema and hand it to their {@code fromEvent} reconstruction
     * method.
     */
    public static final class BuiltInAttribute {

        /** The shape a built-in attribute value takes at the JSON boundary. */
        private enum Kind {
            /** A single typed value, or any value when no type is declared. */
            SCALAR,
            /** A list whose elements must each match one of the declared element types. */
            LIST,
            /** A UUID, accepted natively or as its serialized UUID string. */
            UUID
        }

        private final String name;
        private final boolean required;
        private final Kind kind;
        private final Class<?>[] types;
        private final String elementDescription;

        private BuiltInAttribute(
                String name,
                boolean required,
                Kind kind,
                Class<?>[] types,
                String elementDescription) {
            this.name = name;
            this.required = required;
            this.kind = kind;
            this.types = types;
            this.elementDescription = elementDescription;
        }

        /** A required scalar attribute that must be an instance of {@code type}. */
        public static BuiltInAttribute required(String name, Class<?> type) {
            return new BuiltInAttribute(name, true, Kind.SCALAR, new Class<?>[] {type}, null);
        }

        /** An optional scalar attribute that, when present, must be an instance of {@code type}. */
        public static BuiltInAttribute optional(String name, Class<?> type) {
            return new BuiltInAttribute(name, false, Kind.SCALAR, new Class<?>[] {type}, null);
        }

        /**
         * An optional attribute that is only checked for being a known key, accepting any type.
         * Used for values such as {@code output_schema}, whose shape is interpreted by the consumer
         * rather than asserted at the boundary.
         */
        public static BuiltInAttribute optionalUntyped(String name) {
            return new BuiltInAttribute(name, false, Kind.SCALAR, new Class<?>[0], null);
        }

        /**
         * A required attribute that is only checked for presence and being a known key, accepting
         * any type. Used for values such as {@code InputEvent.input}, whose shape is interpreted by
         * the consumer rather than asserted at the boundary.
         */
        public static BuiltInAttribute requiredUntyped(String name) {
            return new BuiltInAttribute(name, true, Kind.SCALAR, new Class<?>[0], null);
        }

        /**
         * A required list attribute whose elements must each be an instance of one of {@code
         * elementTypes}.
         *
         * @param elementDescription the element phrasing used in the rejection message, e.g. {@code
         *     "a ChatMessage or its serialized map"}
         */
        public static BuiltInAttribute requiredList(
                String name, String elementDescription, Class<?>... elementTypes) {
            return new BuiltInAttribute(name, true, Kind.LIST, elementTypes, elementDescription);
        }

        /** A required UUID attribute, accepted as a {@link java.util.UUID} or a UUID string. */
        public static BuiltInAttribute requiredUuid(String name) {
            return new BuiltInAttribute(name, true, Kind.UUID, new Class<?>[0], null);
        }
    }

    /**
     * Creates an Event from a JSON string.
     *
     * <p>Known built-in event types are restored to their concrete subclass via {@link
     * BuiltInEvents#restore(Event)}, so nested typed values survive the cross-language boundary;
     * unknown or user-defined types are returned as a generic {@link Event}.
     *
     * @param json the JSON string to deserialize
     * @return the deserialized Event, or its concrete built-in subclass
     * @throws IOException if JSON parsing fails or the 'type' field is missing or empty
     * @throws IllegalArgumentException if a built-in event is malformed and cannot be reconstructed
     */
    public static Event fromJson(String json) throws IOException {
        return BuiltInEvents.restore(MAPPER.readValue(json, Event.class));
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
