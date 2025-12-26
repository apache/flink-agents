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
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Base class for all event types in the system. */
public abstract class Event {
    private final UUID id;
    private final Map<String, Object> attributes;
    /** The timestamp of the source record. */
    private Long sourceTimestamp;

    public Event() {
        this(UUID.randomUUID(), new HashMap<>());
    }

    @JsonCreator
    public Event(
            @JsonProperty("id") UUID id,
            @JsonProperty("attributes") Map<String, Object> attributes) {
        this.id = id;
        this.attributes = attributes;
    }

    public UUID getId() {
        return id;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Event other = (Event) o;
        return Objects.equals(this.id, other.id)
                && Objects.equals(this.attributes, other.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, attributes);
    }
}
