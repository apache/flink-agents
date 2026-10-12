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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Memory observation event: a long-term memory update (adds and deletes). Item changes are grouped
 * by memory set in {@code value}; {@code cleared_sets} identifies whole-set deletes. See {@link
 * MemoryEvent} for the common attribute contract.
 */
public class LongTermUpdateEvent extends MemoryEvent {

    public static final String EVENT_TYPE = "_long_term_update_event";

    public LongTermUpdateEvent(String key, Map<String, Object> value) {
        this(key, value, Collections.emptyList());
    }

    public LongTermUpdateEvent(String key, Map<String, Object> value, List<String> clearedSets) {
        super(EVENT_TYPE, attributes(key, value, clearedSets));
    }

    /** Deserialization constructor ({@code @JsonCreator}: see {@link MemoryEvent}'s note). */
    @JsonCreator
    public LongTermUpdateEvent(
            @JsonProperty("id") UUID id,
            @JsonProperty("attributes") Map<String, Object> attributes) {
        super(id, EVENT_TYPE, normalizeUpdateAttributes(attributes));
    }

    private static Map<String, Object> attributes(
            String key, Map<String, Object> value, List<String> clearedSets) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("key", key);
        attributes.put("value", value);
        // Store cleared_sets in a mutable list: the runtime keeps events in operator state that
        // Flink 1.20's Kryo rebuilds by calling add() per element, which immutable copies reject.
        attributes.put(
                "cleared_sets",
                clearedSets == null ? new ArrayList<>() : new ArrayList<>(clearedSets));
        return attributes;
    }

    private static Map<String, Object> normalizeUpdateAttributes(Map<String, Object> attributes) {
        Map<String, Object> normalized = new LinkedHashMap<>(attributes);
        Object clearedSets = normalized.get("cleared_sets");
        if (clearedSets == null) {
            normalized.put("cleared_sets", new ArrayList<>());
        } else if (clearedSets instanceof List
                && ((List<?>) clearedSets).stream().allMatch(String.class::isInstance)) {
            normalized.put("cleared_sets", new ArrayList<>((List<?>) clearedSets));
        } else {
            throw new IllegalArgumentException(
                    "Long-term update attribute 'cleared_sets' must be a list of strings.");
        }
        return normalized;
    }

    @SuppressWarnings("unchecked")
    @JsonIgnore
    public List<String> getClearedSets() {
        Object clearedSets = getAttr("cleared_sets");
        // clearedSets is the mutable list stored in the attributes (kept mutable for Flink 1.20's
        // Kryo; see attributes() above). Wrap it in a read-only view so callers cannot mutate the
        // event's stored state through the returned list.
        return clearedSets instanceof List
                ? Collections.unmodifiableList((List<String>) clearedSets)
                : Collections.emptyList();
    }
}
