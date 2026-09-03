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
import java.util.Map;
import java.util.Objects;
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
     * <p>The lineage values support deserialization and reconstruction. When an Action emits this
     * Event, the runtime overwrites them with the current trigger Event ID and Action name.
     *
     * @param id the existing Event ID
     * @param type the Event type used for routing
     * @param attributes the Event payload
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
     * <p>The runtime overwrites this value when an Action emits the Event.
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
     * <p>The runtime overwrites this value when an Action emits the Event.
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
