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
package org.apache.flink.agents.runtime.memory;

import org.apache.flink.agents.api.configuration.ConfigOption;
import org.apache.flink.agents.api.configuration.MemoryEventOptions;
import org.apache.flink.agents.api.event.LongTermGetEvent;
import org.apache.flink.agents.api.event.LongTermSearchEvent;
import org.apache.flink.agents.api.event.LongTermUpdateEvent;
import org.apache.flink.agents.api.event.MemoryEvent;
import org.apache.flink.agents.api.event.SensoryReadEvent;
import org.apache.flink.agents.api.event.SensoryWriteEvent;
import org.apache.flink.agents.api.event.ShortTermReadEvent;
import org.apache.flink.agents.api.event.ShortTermWriteEvent;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Resolved per-operation memory-event switches. Resolution per op: sub-key explicit → master switch
 * explicit → per-op built-in default.
 */
public final class MemoryEventSettings {

    /**
     * The seven observable memory operations: config option, built-in default, and event subclass.
     */
    public enum MemoryOp {
        SHORT_TERM_WRITE(MemoryEventOptions.SHORT_TERM_WRITE, true, ShortTermWriteEvent::new),
        SHORT_TERM_READ(MemoryEventOptions.SHORT_TERM_READ, false, ShortTermReadEvent::new),
        SENSORY_WRITE(MemoryEventOptions.SENSORY_WRITE, true, SensoryWriteEvent::new),
        SENSORY_READ(MemoryEventOptions.SENSORY_READ, false, SensoryReadEvent::new),
        LONG_TERM_UPDATE(MemoryEventOptions.LONG_TERM_UPDATE, true, LongTermUpdateEvent::new),
        LONG_TERM_GET(MemoryEventOptions.LONG_TERM_GET, true, LongTermGetEvent::new),
        LONG_TERM_SEARCH(MemoryEventOptions.LONG_TERM_SEARCH, true, LongTermSearchEvent::new);

        final ConfigOption<Boolean> option;
        final boolean defaultEnabled;
        final BiFunction<String, Map<String, Object>, MemoryEvent> factory;

        MemoryOp(
                ConfigOption<Boolean> option,
                boolean defaultEnabled,
                BiFunction<String, Map<String, Object>, MemoryEvent> factory) {
            this.option = option;
            this.defaultEnabled = defaultEnabled;
            this.factory = factory;
        }

        /** Constructs this operation's event subclass with the given key and value. */
        public MemoryEvent createEvent(String key, Map<String, Object> value) {
            return factory.apply(key, value);
        }
    }

    private final Map<MemoryOp, Boolean> resolved;
    private final boolean anyEnabled;

    private MemoryEventSettings(Map<MemoryOp, Boolean> resolved) {
        this.resolved = resolved;
        this.anyEnabled = resolved.containsValue(true);
    }

    /** Builds settings from the raw agent config map ({@code AgentConfiguration.getConfData()}). */
    public static MemoryEventSettings from(Map<String, Object> confData) {
        Object master = confData.get(MemoryEventOptions.MEMORY_GENERATE_EVENT.getKey());
        Map<MemoryOp, Boolean> resolved = new EnumMap<>(MemoryOp.class);
        for (MemoryOp op : MemoryOp.values()) {
            Object sub = confData.get(op.option.getKey());
            boolean enabled;
            if (sub != null) {
                enabled = Boolean.parseBoolean(sub.toString());
            } else if (master != null) {
                enabled = Boolean.parseBoolean(master.toString());
            } else {
                enabled = op.defaultEnabled;
            }
            resolved.put(op, enabled);
        }
        return new MemoryEventSettings(resolved);
    }

    public boolean generate(MemoryOp op) {
        return resolved.get(op);
    }

    /** False only when every operation is disabled. */
    public boolean anyEnabled() {
        return anyEnabled;
    }
}
