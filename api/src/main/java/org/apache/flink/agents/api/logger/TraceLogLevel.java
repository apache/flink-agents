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

import java.util.Locale;

/**
 * Recording level for matched trace log records. When a record is written, identities,
 * relationships, timestamps, and statuses are preserved in full.
 *
 * <ul>
 *   <li>{@link #OFF} - Matching records are not written.
 *   <li>{@link #STANDARD} - Attributes are truncated or summarized according to configured limits.
 *   <li>{@link #VERBOSE} - Attributes are not truncated or summarized.
 * </ul>
 */
public enum TraceLogLevel {

    /** Matching records are not written. */
    OFF,

    /**
     * Attributes are truncated or summarized according to configured limits. This is the default.
     */
    STANDARD,

    /** Attributes are not truncated or summarized. */
    VERBOSE;

    /**
     * Parses a string value into a level setting, ignoring case.
     *
     * @param value the level name
     * @return the corresponding level setting
     * @throws IllegalArgumentException if the value is null or unknown
     */
    public static TraceLogLevel fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("TraceLogLevel value cannot be null");
        }
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid TraceLogLevel: '"
                            + value
                            + "'. Valid values are: OFF, STANDARD, VERBOSE");
        }
    }
}
