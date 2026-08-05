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
 * Standalone utility that deterministically assigns sub-agent session and call ids for one action
 * execution. Sub-agent implementations that want framework-style id assignment construct one per
 * task and use it actively; the framework never attaches it implicitly.
 *
 * <p>The namespace is derived purely from caller-side facts (record key, sequence number, caller
 * action name, triggering event, and an optional agent name), so a failover replay reproduces the
 * same digest and therefore the same id sequence. The allocator is transient per-task heap state:
 * continuation resume carries it forward (ordinals continue), failover rebuilds it (ordinals
 * restart). The digest is computed lazily on the first allocation.
 *
 * <p>The first four facts pin one durable action execution, which is the counting range shared by
 * every sub-agent that execution invokes. The optional {@code agentName} then tells which sub-agent
 * inside that shared range is issuing calls, so sub-agents of one caller never hand out the same
 * ids. The framework injects the setup's (qualified) resource name as its agent name at
 * materialization. A {@code null} agent name is omitted from the digest, keeping ids stable for
 * executions invoking a single sub-agent.
 */
public final class SubagentIdAllocator {

    /**
     * Caller-side facts identifying one action execution, used as the namespace for deterministic
     * sub-agent id assignment: record key, sequence number, caller action name, the triggering
     * event (represented by its type and attributes, so two replays of the same logical event map
     * to the same namespace regardless of the event instance id), and the optional (qualified)
     * resource name of the sub-agent issuing the calls.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Namespace {

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

        @Nullable
        @JsonProperty("agentName")
        private final String agentName;

        public Namespace(
                Object key,
                long sequenceNumber,
                String actionName,
                Event event,
                @Nullable String agentName) {
            this.key = key.toString();
            this.sequenceNumber = sequenceNumber;
            this.actionName = actionName;
            this.eventType = event.getType();
            this.eventAttributes = event.getAttributes();
            this.agentName = agentName;
        }
    }

    /**
     * Sorts map entries and bean properties so the namespace bytes do not depend on map iteration
     * order, which is not guaranteed across JVMs.
     */
    private static final ObjectMapper DIGEST_MAPPER =
            JsonMapper.builder()
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                    .build();

    private final Namespace namespace;

    /** Computed lazily on the first allocation; mailbox-confined, no synchronization. */
    @Nullable private String namespaceDigest;

    private int sessionOrdinal;
    private final Map<String, Integer> perSessionCallOrdinals = new HashMap<>();

    /** Creates an allocator without an agent name for one action execution. */
    public SubagentIdAllocator(Object key, long sequenceNumber, String actionName, Event event) {
        this(key, sequenceNumber, actionName, event, null);
    }

    /**
     * Creates an allocator carrying the agent name of the sub-agent issuing calls within one action
     * execution. A {@code null} agent name behaves like the unnamed constructor and produces
     * identical ids.
     */
    public SubagentIdAllocator(
            Object key,
            long sequenceNumber,
            String actionName,
            Event event,
            @Nullable String agentName) {
        this.namespace = new Namespace(key, sequenceNumber, actionName, event, agentName);
    }

    /** Creates a new, ordinal-increasing session id scoped to this task's namespace. */
    public String nextSessionId() {
        return namespaceDigest() + "-" + (sessionOrdinal++);
    }

    /**
     * Creates a new call id by appending the per-session ordinal (starting at 1) to the session id.
     * Cross-task uniqueness relies on session ids not being shared between action executions.
     */
    public String nextCallId(String sessionId) {
        int ordinal = perSessionCallOrdinals.merge(sessionId, 1, Integer::sum);
        return sessionId + "-" + ordinal;
    }

    private String namespaceDigest() {
        if (namespaceDigest == null) {
            try {
                namespaceDigest =
                        String.valueOf(
                                UUID.nameUUIDFromBytes(DIGEST_MAPPER.writeValueAsBytes(namespace)));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(
                        "Failed to digest the sub-agent identity namespace", e);
            }
        }
        return namespaceDigest;
    }
}
