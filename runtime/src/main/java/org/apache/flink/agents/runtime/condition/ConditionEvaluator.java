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

package org.apache.flink.agents.runtime.condition;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cel.common.values.NullValue;
import dev.cel.runtime.CelEvaluationException;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.configuration.AgentConfigOptions.ConditionEvaluationFailureStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Evaluates trigger condition expressions against event data. */
final class ConditionEvaluator {

    private static final Logger LOG = LoggerFactory.getLogger(ConditionEvaluator.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ConditionEvaluationFailureStrategy failureStrategy;

    ConditionEvaluator(ConditionEvaluationFailureStrategy failureStrategy) {
        this.failureStrategy = failureStrategy;
    }

    /** Builds only the variables needed by one reached condition, then evaluates it. */
    boolean evaluate(ConditionExpressionCompiler.CompiledCondition condition, Event event) {
        Map<String, Object> conditionVariables;
        try {
            conditionVariables = buildConditionVariables(event, condition);
        } catch (RuntimeException e) {
            return handleConditionFailure(
                    "Building trigger condition variables failed for event " + event.getId(), e);
        }

        String source = condition.source();
        Object result;
        try {
            result = condition.program().eval(conditionVariables);
        } catch (CelEvaluationException e) {
            return handleConditionFailure(
                    "Trigger condition evaluation failed for '" + source + "'", e);
        }
        if (result instanceof Boolean) {
            return (Boolean) result;
        }
        return handleConditionFailure(
                String.format(
                        "Trigger condition '%s' returned non-boolean type %s",
                        source, result == null ? "null" : result.getClass().getName()),
                null);
    }

    /**
     * Applies the failure strategy to one condition failure: {@code FAIL} throws, otherwise the
     * failure is logged and the condition is treated as false.
     */
    private boolean handleConditionFailure(String message, Throwable cause) {
        if (failureStrategy == ConditionEvaluationFailureStrategy.FAIL) {
            throw new IllegalStateException(message, cause);
        }
        LOG.warn("{}, treating this condition as false", message, cause);
        return false;
    }

    /**
     * Builds the condition variables required for {@code event} from the referenced keys of {@code
     * condition}.
     *
     * <p>The {@code attributes} entry remains the explicit root namespace for the event attribute
     * map. This is needed for literal keys that cannot be represented as identifiers, such as
     * {@code attributes["www.andriod.com"].ip}; simple keys are also promoted for bare-identifier
     * access.
     */
    Map<String, Object> buildConditionVariables(
            Event event, ConditionExpressionCompiler.CompiledCondition condition) {
        Set<String> referencedTopLevelAttributeKeys = condition.referencedTopLevelAttributeKeys();

        Map<String, Object> conditionVariables = new HashMap<>();
        conditionVariables.put("type", event.getType());
        conditionVariables.put("EventType", EventType.allConstants());
        if (event.getId() != null) {
            conditionVariables.put("id", event.getId().toString());
        }

        Map<String, Object> attrs = event.getAttributes();
        Map<String, Object> normalizedAttributes = new HashMap<>();
        for (String key : referencedTopLevelAttributeKeys) {
            if (attrs.containsKey(key)) {
                normalizedAttributes.put(key, normalizeValue(attrs.get(key)));
            }
        }

        conditionVariables.put("attributes", normalizedAttributes);
        // Promote to top level for bare-identifier access; framework keys win on collision.
        normalizedAttributes.forEach(conditionVariables::putIfAbsent);
        return conditionVariables;
    }

    /**
     * Normalizes Java values for CEL, converting nulls, numbers, collections, and
     * Jackson-serializable objects while preserving CEL-native values.
     */
    @SuppressWarnings("unchecked")
    private static Object normalizeValue(Object value) {
        if (value == null) {
            return NullValue.NULL_VALUE;
        }
        if (value instanceof Map) {
            Map<String, Object> src = (Map<String, Object>) value;
            Map<String, Object> dst = new HashMap<>(src.size());
            for (Map.Entry<String, Object> entry : src.entrySet()) {
                dst.put(entry.getKey(), normalizeValue(entry.getValue()));
            }
            return dst;
        }
        if (value instanceof List) {
            List<Object> src = (List<Object>) value;
            List<Object> dst = new ArrayList<>(src.size());
            for (Object item : src) {
                dst.add(normalizeValue(item));
            }
            return dst;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float) {
            return ((Float) value).doubleValue();
        }
        if (value instanceof BigInteger) {
            BigInteger bigInt = (BigInteger) value;
            if (bigInt.bitLength() < 64) {
                return bigInt.longValue();
            }
            LOG.debug(
                    "normalizeValue: BigInteger overflows int64, converting to double (possible precision loss): {}",
                    bigInt);
            return bigInt.doubleValue();
        }
        if (value instanceof BigDecimal) {
            BigDecimal bigDec = (BigDecimal) value;
            LOG.debug(
                    "normalizeValue: converting BigDecimal to double (possible precision loss): {}",
                    bigDec);
            return bigDec.doubleValue();
        }
        if (value == NullValue.NULL_VALUE
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Long
                || value instanceof Double
                || value instanceof byte[]) {
            return value;
        }

        Object converted = OBJECT_MAPPER.convertValue(value, Object.class);
        if (converted != null && converted.getClass() == value.getClass()) {
            throw new IllegalArgumentException(
                    "Unsupported condition attribute type: " + value.getClass().getName());
        }
        return normalizeValue(converted);
    }
}
