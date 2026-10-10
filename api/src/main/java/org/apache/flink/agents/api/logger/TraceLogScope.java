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

/** Preset scopes for trace log recording targets. */
public enum TraceLogScope {

    /** Select Events. */
    EVENT_ONLY,

    /** Select all entities. */
    ALL;

    /**
     * Parses a string value into a scope, ignoring case.
     *
     * @param value the scope name
     * @return the corresponding scope
     * @throws IllegalArgumentException if the value is null or unknown
     */
    public static TraceLogScope fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("TraceLogScope value cannot be null");
        }
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid TraceLogScope: '" + value + "'. Valid values are: EVENT_ONLY, ALL");
        }
    }
}
