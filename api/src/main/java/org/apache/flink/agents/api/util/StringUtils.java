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

package org.apache.flink.agents.api.util;

/** Utility methods for String operations in the Flink Agents framework. */
public final class StringUtils {

    private StringUtils() {}

    /**
     * Check whether the given String contains actual text.
     *
     * @param str the String to check (may be null)
     * @return true if the String is not null, its length is greater than 0, and it contains at
     *     least one non-whitespace character
     */
    public static boolean hasText(String str) {
        return str != null && !str.trim().isEmpty();
    }

    /**
     * Check whether the given String has actual length.
     *
     * @param str the String to check (may be null)
     * @return true if the String is not null and has length
     */
    public static boolean hasLength(String str) {
        return str != null && !str.isEmpty();
    }

    /**
     * Check if a String is empty ("") or null.
     *
     * @param str the String to check, may be null
     * @return true if the String is empty or null
     */
    public static boolean isEmpty(String str) {
        return str == null || str.isEmpty();
    }

    /**
     * Check if a String is not empty ("") and not null.
     *
     * @param str the String to check, may be null
     * @return true if the String is not empty and not null
     */
    public static boolean isNotEmpty(String str) {
        return !isEmpty(str);
    }

    /**
     * Remove leading and trailing whitespace from the given String.
     *
     * @param str the String to trim
     * @return the trimmed String, or null if input was null
     */
    public static String trimWhitespace(String str) {
        if (!hasLength(str)) {
            return str;
        }
        return str.trim();
    }
}
