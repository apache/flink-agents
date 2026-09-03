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

package org.apache.flink.agents.plan.utils;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.annotation.Nullable;

import java.lang.reflect.Array;
import java.util.List;
import java.util.Map;

/**
 * Turns a sub-agent result into something a chat model can be told.
 *
 * <p>A sub-agent result reaches the caller as an opaque object, but from here on it is carried in a
 * tool message and re-bound after a failover, so it must hold nothing that JSON cannot express.
 * Such a payload is rejected with the path where it was found instead of being dropped or silently
 * stringified.
 *
 * <p>A sub-agent that declares a result type is read through it: the type says how to interpret a
 * result the checks below would refuse, and what comes out is only what the type declares.
 */
public final class ToolResultUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Reads a result as the type its sub-agent declares. A property the type does not declare is
     * ignored rather than refused, so that a sub-agent returning more than it declares is narrowed
     * to what it declares instead of failing the call; the Python side reads through pydantic,
     * which ignores extra fields the same way.
     */
    private static final ObjectMapper TYPED_MAPPER =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private ToolResultUtils() {}

    /**
     * Rejects a payload JSON cannot express, and reduces the rest to plain maps, lists and scalars.
     *
     * @param raw the result a sub-agent produced
     * @return the same value in generic form
     */
    public static Object normalizeAgentResult(Object raw) {
        return normalizeAgentResult(raw, null);
    }

    /**
     * As {@link #normalizeAgentResult(Object)}, but read through {@code resultType} first when the
     * sub-agent declares one.
     *
     * @param raw the result a sub-agent produced
     * @param resultType the type the sub-agent declares for its result, or {@code null} or {@link
     *     Object} when it declares none, in which case {@code raw} is taken as it arrived
     * @return the result in generic form
     */
    public static Object normalizeAgentResult(Object raw, @Nullable Class<?> resultType) {
        if (resultType == null || resultType == Object.class) {
            requireJsonCompatible(raw, "result");
            return MAPPER.convertValue(MAPPER.valueToTree(raw), Object.class);
        }
        // Two conversions: the first reads the result as the declared type, which is what admits a
        // payload the compatibility check below would refuse and what drops everything the type
        // does not declare; the second reduces that back to plain maps, lists and scalars, so what
        // is reported and re-bound after a failover is the same generic form the undeclared path
        // produces.
        Object typed = TYPED_MAPPER.convertValue(raw, resultType);
        Object generic = MAPPER.convertValue(typed, Object.class);
        // Still required: a declared type can render a field JSON cannot express, and the result
        // outlives this call in a tool message and in state.
        requireJsonCompatible(generic, "result");
        return generic;
    }

    /** Renders a value as the content of the tool message handed back to the model. */
    public static String toChatMessageContent(Object value) throws Exception {
        if (value == null) {
            return "null";
        }
        if (value instanceof List
                || value instanceof Map
                || value instanceof JsonNode
                || value.getClass().isArray()) {
            return MAPPER.writeValueAsString(value);
        }
        return String.valueOf(value);
    }

    private static void requireJsonCompatible(Object value, String path) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return;
        }
        if (value instanceof Number) {
            requireFiniteNumber((Number) value, path);
            return;
        }
        if (value instanceof JsonNode) {
            requireJsonNodeCompatible((JsonNode) value, path);
            return;
        }
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IllegalArgumentException(
                            "Map keys in sub-agent result must be strings at " + path);
                }
                requireJsonCompatible(entry.getValue(), path + "." + entry.getKey());
            }
            return;
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++) {
                requireJsonCompatible(list.get(i), path + "[" + i + "]");
            }
            return;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                requireJsonCompatible(Array.get(value, i), path + "[" + i + "]");
            }
            return;
        }
        throw invalid(path, "found " + value.getClass().getName());
    }

    private static void requireJsonNodeCompatible(JsonNode node, String path) {
        if (node.isPojo()) {
            throw invalid(path, "POJONode is not supported");
        }
        if (node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue())) {
            throw new IllegalArgumentException("Non-finite number in sub-agent result at " + path);
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                requireJsonNodeCompatible(node.get(i), path + "[" + i + "]");
            }
            return;
        }
        if (node.isObject()) {
            node.fields()
                    .forEachRemaining(
                            entry ->
                                    requireJsonNodeCompatible(
                                            entry.getValue(), path + "." + entry.getKey()));
        }
    }

    private static IllegalArgumentException invalid(String path, String detail) {
        return new IllegalArgumentException(
                "Sub-agent result must be JSON-compatible at " + path + ", " + detail);
    }

    private static void requireFiniteNumber(Number number, String path) {
        if (number instanceof Double && !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("Non-finite number in sub-agent result at " + path);
        }
        if (number instanceof Float && !Float.isFinite(number.floatValue())) {
            throw new IllegalArgumentException("Non-finite number in sub-agent result at " + path);
        }
    }
}
