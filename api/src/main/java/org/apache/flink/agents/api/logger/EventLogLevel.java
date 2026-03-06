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

package org.apache.flink.agents.api.logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Log levels for the event logging system.
 *
 * <p>Event log levels control which events are recorded and at what detail level. Levels can be
 * configured globally (as a default) and overridden per event type.
 *
 * <ul>
 *   <li>{@link #OFF} - Do not log the event
 *   <li>{@link #STANDARD} - Log the event with standard detail
 *   <li>{@link #VERBOSE} - Log the event with full detail
 * </ul>
 */
public enum EventLogLevel {

    /** Do not log the event. */
    OFF,

    /** Log the event with standard detail. */
    STANDARD,

    /** Log the event with full detail. */
    VERBOSE;

    /**
     * Returns whether logging is enabled at this level.
     *
     * @return {@code true} if this level is not {@link #OFF}
     */
    public boolean isEnabled() {
        return this != OFF;
    }

    /**
     * Parses a comma-separated string of per-event-type log level overrides.
     *
     * <p>The expected format is:
     *
     * <pre>
     * SimpleClassName=LEVEL,SimpleClassName=LEVEL,...
     * </pre>
     *
     * <p>For example: {@code "ChatRequestEvent=VERBOSE,InputEvent=OFF"}
     *
     * @param configValue the comma-separated configuration string, may be {@code null} or empty
     * @return an unmodifiable map of simple event type names to log levels; empty if input is null
     *     or empty
     * @throws IllegalArgumentException if a level value is not a valid {@link EventLogLevel}
     */
    public static Map<String, EventLogLevel> parseLogLevels(String configValue) {
        if (configValue == null || configValue.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, EventLogLevel> result = new HashMap<>();
        String[] entries = configValue.split(",");
        for (String entry : entries) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eqIdx = trimmed.indexOf('=');
            if (eqIdx <= 0 || eqIdx >= trimmed.length() - 1) {
                throw new IllegalArgumentException(
                        "Invalid event log level entry: '"
                                + trimmed
                                + "'. Expected format: EventTypeName=LEVEL");
            }
            String eventTypeName = trimmed.substring(0, eqIdx).trim();
            String levelStr = trimmed.substring(eqIdx + 1).trim();
            result.put(eventTypeName, EventLogLevel.valueOf(levelStr.toUpperCase()));
        }
        return Collections.unmodifiableMap(result);
    }
}
