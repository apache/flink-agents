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

package org.apache.flink.agents.api.decision;

import org.apache.flink.annotation.Internal;

import javax.annotation.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validates and snapshots JSON content used by decision requests and results. */
@Internal
public final class DecisionJson {
    private DecisionJson() {}

    @Nullable
    public static Object copy(@Nullable Object value, String path) {
        return copy(value, path, new IdentityHashMap<>());
    }

    @Nullable
    private static Object copy(
            @Nullable Object value, String path, IdentityHashMap<Object, Boolean> visiting) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof BigInteger
                || value instanceof BigDecimal) {
            return value;
        }
        if ((value instanceof Double && Double.isFinite((Double) value))
                || (value instanceof Float && Float.isFinite((Float) value))) {
            return value;
        }
        if (value instanceof Map || value instanceof List) {
            if (visiting.put(value, Boolean.TRUE) != null) {
                throw new IllegalArgumentException(path + " must not contain a cycle");
            }
            try {
                if (value instanceof Map) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                        if (!(entry.getKey() instanceof String)) {
                            throw new IllegalArgumentException(
                                    path + " requires string object keys");
                        }
                        String key = (String) entry.getKey();
                        result.put(key, copy(entry.getValue(), path + "." + key, visiting));
                    }
                    return Collections.unmodifiableMap(result);
                }
                List<Object> result = new ArrayList<>();
                for (Object item : (List<?>) value) {
                    result.add(copy(item, path + "[" + result.size() + "]", visiting));
                }
                return Collections.unmodifiableList(result);
            } finally {
                visiting.remove(value);
            }
        }
        throw new IllegalArgumentException(path + " must be finite JSON content");
    }

    static String string(@Nullable Object value, String field) {
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return (String) value;
    }

    @Nullable
    static String optionalString(@Nullable Object value, String field) {
        return value == null ? null : string(value, field);
    }

    @Nullable
    static Long optionalTokenCount(@Nullable Object value, String field) {
        if (value == null) {
            return null;
        }
        if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger) {
            try {
                return ((BigInteger) value).longValueExact();
            } catch (ArithmeticException ignored) {
                // Reject token counts that cannot be represented by a long.
            }
        }
        throw new IllegalArgumentException(field + " must be a 64-bit integer");
    }

    static double number(@Nullable Object value, String field) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        return ((Number) value).doubleValue();
    }

    @Nullable
    static Double optionalNumber(@Nullable Object value, String field) {
        return value == null ? null : number(value, field);
    }

    static int index(@Nullable Object value) {
        if (!(value instanceof Integer) || (Integer) value < 0) {
            throw new IllegalArgumentException("level index must be a nonnegative integer");
        }
        return (Integer) value;
    }

    static int indexKey(String key) {
        try {
            int index = Integer.parseInt(key);
            if (index >= 0 && Integer.toString(index).equals(key)) {
                return index;
            }
        } catch (NumberFormatException ignored) {
            // Reject representations that cannot denote a supported level index.
        }
        throw new IllegalArgumentException("level index key must be a nonnegative integer: " + key);
    }

    static void requireProbability(double probability, String field) {
        if (!Double.isFinite(probability) || probability < 0 || probability > 1) {
            throw new IllegalArgumentException(field + " must be finite and between 0 and 1");
        }
    }
}
