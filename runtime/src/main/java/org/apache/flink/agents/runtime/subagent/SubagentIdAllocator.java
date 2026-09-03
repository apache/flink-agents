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

package org.apache.flink.agents.runtime.subagent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.flink.agents.api.Event;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministically assigns sub-agent session and call ids for one action execution. The {@link
 * Namespace} of caller-side facts fixes the counting range, so a failover replay reproduces the
 * same id sequence.
 */
public final class SubagentIdAllocator {

    private final Namespace namespace;

    private int sessionOrdinal = 0;
    private final Map<String, Integer> perSessionCallOrdinals = new HashMap<>();

    /** Creates an allocator for one action execution from the execution's caller-side facts. */
    public SubagentIdAllocator(
            Object key, long sequenceNumber, String actionName, Event event, String subagentName) {
        this.namespace = new Namespace(key, sequenceNumber, actionName, event, subagentName);
    }

    /** Creates a new, ordinal-increasing session id scoped to this task's namespace. */
    public String nextSessionId() {
        return namespace.digest() + "-" + (sessionOrdinal++);
    }

    /**
     * Creates a new call id by appending the per-session ordinal (starting at 1) to the session id.
     * Ordinals restart per action execution, so ids assigned here stay valid only within it.
     */
    public String nextCallId(String sessionId) {
        int ordinal = perSessionCallOrdinals.merge(sessionId, 1, Integer::sum);
        return sessionId + "-" + ordinal;
    }

    /**
     * The caller-side identity of one action execution, seeding the deterministic ids of the
     * sub-agent calls it issues.
     *
     * <p>Key, sequence number, action name, and the event's type and attributes are facts of the
     * execution itself, identical for every sub-agent called from it. The subagent name
     * distinguishes the sub-agents called from one action, so it alone keeps their id ranges apart.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Namespace {

        /**
         * Sorts map entries and bean properties so the namespace bytes do not depend on map
         * iteration order, which is not guaranteed across JVMs.
         */
        private static final ObjectMapper DIGEST_MAPPER =
                JsonMapper.builder()
                        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                        .build();

        @JsonProperty("key")
        private final String key;

        @JsonProperty("sequenceNumber")
        private final long sequenceNumber;

        @JsonProperty("actionName")
        private final String actionName;

        @JsonProperty("eventType")
        private final String eventType;

        @JsonProperty("eventAttributes")
        private final Map<String, Object> eventAttributes;

        @JsonProperty("subagentName")
        private final String subagentName;

        /**
         * Computed lazily on the first allocation. Digesting is mailbox-confined, so it needs no
         * synchronization.
         */
        @JsonIgnore @Nullable private String digest;

        public Namespace(
                Object key,
                long sequenceNumber,
                String actionName,
                Event event,
                String subagentName) {
            this.key = key.toString();
            this.sequenceNumber = sequenceNumber;
            this.actionName = actionName;
            this.eventType = event.getType();
            this.eventAttributes = event.getAttributes();
            this.subagentName = subagentName;
        }

        /** Digests the id-bearing facts into a name-based UUID string, stable across replays. */
        public String digest() {
            if (digest == null) {
                try {
                    digest =
                            String.valueOf(
                                    UUID.nameUUIDFromBytes(DIGEST_MAPPER.writeValueAsBytes(this)));
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException(
                            "Failed to digest the sub-agent identity namespace", e);
                }
            }
            return digest;
        }
    }
}
