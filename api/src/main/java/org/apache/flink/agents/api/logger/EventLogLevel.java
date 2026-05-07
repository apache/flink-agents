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
 * Log level for event logging, controlling the verbosity of event log output.
 *
 * <ul>
 *   <li>{@link #OFF} - No events of this type are logged.
 *   <li>{@link #STANDARD} - Events are logged with truncated/summarized payloads (default).
 *   <li>{@link #VERBOSE} - Events are logged with full, untruncated payloads.
 * </ul>
 */
public enum EventLogLevel {

    /** No events of this type are logged. */
    OFF,

    /** Events are logged with truncated/summarized payloads. This is the default level. */
    STANDARD,

    /** Events are logged with full, untruncated payloads. */
    VERBOSE;

    /**
     * Parses a string value into an {@link EventLogLevel}, case-insensitively.
     *
     * @param value the string representation of the log level (e.g., "off", "STANDARD", "Verbose")
     * @return the corresponding {@link EventLogLevel}
     * @throws IllegalArgumentException if the value does not match any log level
     */
    public static EventLogLevel fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("EventLogLevel value cannot be null");
        }
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid EventLogLevel: '"
                            + value
                            + "'. Valid values are: OFF, STANDARD, VERBOSE");
        }
    }
}
