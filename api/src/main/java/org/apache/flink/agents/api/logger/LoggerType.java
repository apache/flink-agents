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

/**
 * Enumeration of built-in EventLogger types.
 *
 * <p>Each {@code LoggerType} corresponds to a built-in {@link EventLogger} implementation
 * registered with the {@link EventLoggerFactory}. The {@link #getType()} string is the identifier
 * used by the factory registry, the configuration system, and {@link
 * EventLoggerConfig#getLoggerType()}.
 */
public enum LoggerType {
    /** SLF4J-based event logger; outputs to Flink Web UI via log4j2. */
    SLF4J("slf4j"),

    /** File-based event logger; writes events to per-subtask log files. */
    FILE("file");

    private final String type;

    LoggerType(String type) {
        this.type = type;
    }

    /**
     * Gets the string identifier used to register and look up this logger type in {@link
     * EventLoggerFactory}.
     *
     * @return the logger type identifier
     */
    public String getType() {
        return type;
    }

    /**
     * Resolves a {@link LoggerType} from its string identifier.
     *
     * @param type the logger type identifier (e.g., "slf4j", "file")
     * @return the matching {@link LoggerType}
     * @throws IllegalArgumentException if no matching type exists
     */
    public static LoggerType fromType(String type) {
        if (type == null) {
            throw new IllegalArgumentException("Logger type cannot be null");
        }
        String trimmed = type.trim();
        for (LoggerType value : values()) {
            if (value.type.equalsIgnoreCase(trimmed)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown logger type: " + type);
    }
}
