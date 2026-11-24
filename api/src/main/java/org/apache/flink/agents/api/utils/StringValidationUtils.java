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

package org.apache.flink.agents.api.utils;

import org.apache.flink.annotation.Internal;

import javax.annotation.Nullable;

/** Shared string validation helpers. */
@Internal
public final class StringValidationUtils {
    private StringValidationUtils() {}

    /** Validate that {@code value} is a non-blank string for {@code fieldName}. */
    public static String requireNonBlank(@Nullable Object value, String fieldName) {
        if (!(value instanceof String) || ((String) value).isBlank()) {
            throw new IllegalArgumentException("'" + fieldName + "' must be a non-blank string.");
        }
        return (String) value;
    }

    /** Validate {@code value} when non-null; {@code null} returns {@code null}. */
    @Nullable
    public static String optionalNonBlank(@Nullable Object value, String fieldName) {
        return value == null ? null : requireNonBlank(value, fieldName);
    }
}
