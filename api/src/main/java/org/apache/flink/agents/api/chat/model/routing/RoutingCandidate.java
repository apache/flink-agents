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

package org.apache.flink.agents.api.chat.model.routing;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A routable target of a {@link ChatModelRouter}.
 *
 * <p>A candidate names a chat-model setup that was registered as a {@link
 * org.apache.flink.agents.api.resource.ResourceType#CHAT_MODEL} resource, together with an optional
 * human-readable {@code description} (consumed by LLM-as-router strategies to reason about which
 * model fits a request) and free-form {@code metadata} (e.g. {@code cost}, {@code tags},
 * capabilities) that rule-based or custom strategies can match against.
 */
public class RoutingCandidate {

    private final String name;
    private final String description;
    private final Map<String, Object> metadata;

    public RoutingCandidate(String name, String description, Map<String, Object> metadata) {
        this.name = Objects.requireNonNull(name, "candidate name must not be null");
        this.description = description != null ? description : "";
        this.metadata =
                metadata != null
                        ? Collections.unmodifiableMap(new HashMap<>(metadata))
                        : Collections.emptyMap();
    }

    public RoutingCandidate(String name, String description) {
        this(name, description, Collections.emptyMap());
    }

    public RoutingCandidate(String name) {
        this(name, "", Collections.emptyMap());
    }

    /** The registered {@code CHAT_MODEL} resource name this candidate routes to. */
    public String getName() {
        return name;
    }

    /** Human-readable description of when this model should be used (may be empty). */
    public String getDescription() {
        return description;
    }

    /** Free-form metadata strategies may match against (never null). */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Normalize a single user-supplied candidate spec into a {@link RoutingCandidate}.
     *
     * <p>Accepts an existing {@link RoutingCandidate}, a plain {@link String} (name only), or a
     * {@link Map} with keys {@code name} (required), {@code description}, and {@code metadata}.
     * This keeps the router descriptor easy to author from Java and tolerant of values that have
     * round-tripped through serialization.
     */
    @SuppressWarnings("unchecked")
    public static RoutingCandidate from(Object spec) {
        if (spec instanceof RoutingCandidate) {
            return (RoutingCandidate) spec;
        }
        if (spec instanceof CharSequence) {
            return new RoutingCandidate(spec.toString());
        }
        if (spec instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) spec;
            Object name = map.get("name");
            if (name == null) {
                throw new IllegalArgumentException(
                        "Routing candidate map must contain a 'name' entry: " + map);
            }
            Object description = map.get("description");
            Object metadata = map.get("metadata");
            return new RoutingCandidate(
                    name.toString(),
                    description != null ? description.toString() : "",
                    metadata instanceof Map ? (Map<String, Object>) metadata : null);
        }
        throw new IllegalArgumentException(
                "Unsupported routing candidate spec: "
                        + (spec == null ? "null" : spec.getClass().getName()));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RoutingCandidate)) {
            return false;
        }
        RoutingCandidate that = (RoutingCandidate) o;
        return name.equals(that.name)
                && description.equals(that.description)
                && metadata.equals(that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description, metadata);
    }

    @Override
    public String toString() {
        return "RoutingCandidate{name='" + name + "', description='" + description + "'}";
    }
}
