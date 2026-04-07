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

package org.apache.flink.agents.runtime.eventlog;

import org.apache.flink.agents.api.logger.EventLogLevel;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the effective {@link EventLogLevel} for a given event type string using hierarchical
 * config key inheritance.
 *
 * <p>Resolution order for a given event type (e.g., {@code org.apache.flink.agents.api.event
 * .ChatRequestEvent}):
 *
 * <ol>
 *   <li>Exact match in explicit per-type configuration
 *   <li>Walk up dot-separated segments (e.g., {@code org.apache.flink.agents.api.event}, then
 *       {@code org.apache.flink.agents.api}, etc.)
 *   <li>Root default from {@code event-log.level} config key
 *   <li>Built-in default: {@link EventLogLevel#STANDARD}
 * </ol>
 *
 * <p>This mirrors Log4j's hierarchical logger configuration pattern. Resolved levels are cached in
 * a {@link ConcurrentHashMap} for efficient repeated lookups.
 *
 * <p>Config keys are expected in the form:
 *
 * <pre>
 *   event-log.level = STANDARD            (root default)
 *   event-log.type.&lt;EVENT_TYPE&gt;.level = OFF   (per-type override)
 * </pre>
 */
public class EventLogLevelResolver {

    /** Config key for the root default log level. */
    static final String ROOT_LEVEL_KEY = "event-log.level";

    /** Prefix for per-event-type log level config keys. */
    static final String TYPE_PREFIX = "event-log.type.";

    /** Suffix for per-event-type log level config keys. */
    static final String TYPE_SUFFIX = ".level";

    /** Built-in default when no configuration is provided. */
    static final EventLogLevel BUILT_IN_DEFAULT = EventLogLevel.STANDARD;

    private final EventLogLevel rootDefault;
    private final Map<String, EventLogLevel> explicitLevels;
    private final ConcurrentHashMap<String, EventLogLevel> cache;

    /**
     * Creates a resolver from a configuration data map.
     *
     * <p>The map is scanned for keys matching {@code event-log.type.<EVENT_TYPE>.level} to build
     * the explicit per-type level mappings, and {@code event-log.level} for the root default.
     *
     * @param confData the flat configuration key-value map (e.g., from {@code
     *     AgentConfiguration.getConfData()})
     */
    public EventLogLevelResolver(Map<String, Object> confData) {
        Map<String, Object> data = confData != null ? confData : Collections.emptyMap();

        // Parse root default
        Object rootValue = data.get(ROOT_LEVEL_KEY);
        this.rootDefault =
                rootValue != null
                        ? EventLogLevel.fromString(rootValue.toString())
                        : BUILT_IN_DEFAULT;

        // Scan for per-type overrides
        Map<String, EventLogLevel> levels = new HashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(TYPE_PREFIX) && key.endsWith(TYPE_SUFFIX)) {
                String eventType =
                        key.substring(TYPE_PREFIX.length(), key.length() - TYPE_SUFFIX.length());
                if (!eventType.isEmpty()) {
                    levels.put(eventType, EventLogLevel.fromString(entry.getValue().toString()));
                }
            }
        }
        this.explicitLevels = Collections.unmodifiableMap(levels);
        this.cache = new ConcurrentHashMap<>();
    }

    /**
     * Resolves the effective log level for the given event type string.
     *
     * <p>Uses hierarchical inheritance: exact match first, then walks up dot-separated segments,
     * then falls back to root default, then built-in default ({@link EventLogLevel#STANDARD}).
     *
     * @param eventType the fully qualified event type string (e.g., {@code
     *     org.apache.flink.agents.api.event.ChatRequestEvent})
     * @return the resolved {@link EventLogLevel}, never null
     */
    public EventLogLevel resolve(String eventType) {
        if (eventType == null || eventType.isEmpty()) {
            return rootDefault;
        }
        return cache.computeIfAbsent(eventType, this::doResolve);
    }

    private EventLogLevel doResolve(String eventType) {
        // 1. Exact match
        EventLogLevel level = explicitLevels.get(eventType);
        if (level != null) {
            return level;
        }

        // 2. Walk up dot-separated segments
        String current = eventType;
        int lastDot = current.lastIndexOf('.');
        while (lastDot > 0) {
            current = current.substring(0, lastDot);
            level = explicitLevels.get(current);
            if (level != null) {
                return level;
            }
            lastDot = current.lastIndexOf('.');
        }

        // 3. Root default (already parsed in constructor, falls through to built-in if not set)
        return rootDefault;
    }
}
