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
import org.apache.flink.agents.api.Event;

import java.util.Map;
import java.util.UUID;

/**
 * Lifecycle event marking the begin of one agent run. When enabled, it is emitted after an
 * InputEvent arrives for a String key and before any action of that run executes. Carries the key's
 * short-term VALUE nodes as a dot-key flat map. Object nodes and empty-object structure are
 * intentionally outside this event contract.
 *
 * <p>This event is opt-in through {@code agent-run.begin-event} (default false), independent of the
 * {@code memory.generate-event} master switch. On the wire: {@code {id, type, attributes: {key,
 * value}}}.
 */
public class AgentRunBeginEvent extends Event {

    public static final String EVENT_TYPE = "_agent_run_begin_event";

    public AgentRunBeginEvent(String key, Map<String, Object> value) {
        super(EVENT_TYPE, MemoryEvent.normalizeAttributes(key, value));
    }

    /** Deserialization constructor ({@code @JsonCreator}: see {@code MemoryEvent}'s note). */
    @JsonCreator
    public AgentRunBeginEvent(
            @JsonProperty("id") UUID id,
            @JsonProperty("attributes") Map<String, Object> attributes) {
        super(id, EVENT_TYPE, MemoryEvent.normalizeAttributes(attributes));
    }

    /** Converts a generic {@link Event} of this type into the typed view. */
    public static AgentRunBeginEvent fromEvent(Event event) {
        AgentRunBeginEvent result = new AgentRunBeginEvent(event.getId(), event.getAttributes());
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
