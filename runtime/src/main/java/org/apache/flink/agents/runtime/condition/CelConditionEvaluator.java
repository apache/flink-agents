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
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.plan.condition.ParsedCondition.CelExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Evaluates CEL condition expressions against event data. */
public class CelConditionEvaluator {

    private static final Logger LOG = LoggerFactory.getLogger(CelConditionEvaluator.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Behaviour for both runtime exceptions and non-Boolean return values — both mean "no verdict".
     */
    public enum EvaluationFailurePolicy {
        /** Log WARN and treat the action as not matching. */
        WARN_AND_SKIP,
        /** Rethrow as {@link IllegalStateException}; triggers Flink task failover. */
        FAIL
    }

    /** Frozen after {@link #initPrograms}; cleared by {@link #close}. */
    @Nullable private Map<String, CelRuntime.Program> programCache;

    private final EvaluationFailurePolicy failurePolicy;

    public CelConditionEvaluator() {
        this(EvaluationFailurePolicy.WARN_AND_SKIP);
    }

    public CelConditionEvaluator(EvaluationFailurePolicy failurePolicy) {
        this.failurePolicy = failurePolicy;
    }

    /** Pre-compiles {@code expressions} and freezes the cache. Nulls are skipped. */
    public void initPrograms(Collection<CelExpression> expressions) {
        Map<String, CelRuntime.Program> programs = new HashMap<>();
        for (CelExpression expression : expressions) {
            if (expression == null) {
                continue;
            }
            String source = expression.source();
            programs.computeIfAbsent(source, CelExpressionFacade::toProgram);
        }
        this.programCache = Collections.unmodifiableMap(programs);
    }

    public void close() {
        programCache = null;
    }

    /** Evaluates {@code expression} (which must have been pre-compiled). Null returns true. */
    public boolean evaluate(@Nullable CelExpression expression, Map<String, Object> activation) {
        if (expression == null) {
            return true;
        }
        String source = expression.source();
        try {
            CelRuntime.Program program = programCache.get(source);
            if (program == null) {
                throw new IllegalStateException(
                        "CEL condition was not pre-compiled via initPrograms(): \""
                                + source
                                + "\"");
            }
            return evaluateProgram(source, program, activation);
        } catch (CelEvaluationException e) {
            if (failurePolicy == EvaluationFailurePolicy.FAIL) {
                throw new IllegalStateException(
                        "CEL condition evaluation failed for '" + source + "'", e);
            }
            LOG.warn("CEL condition evaluation failed for '{}', skipping action", source, e);
            return false;
        }
    }

    private boolean evaluateProgram(
            String condition, CelRuntime.Program program, Map<String, Object> activation)
            throws CelEvaluationException {
        Object result = program.eval(activation);
        if (result instanceof Boolean) {
            return (Boolean) result;
        }
        String msg =
                String.format(
                        "CEL condition '%s' returned non-boolean type %s, treating as false",
                        condition, result == null ? "null" : result.getClass().getName());
        if (failurePolicy == EvaluationFailurePolicy.FAIL) {
            throw new IllegalStateException(msg);
        }
        LOG.warn(msg);
        return false;
    }

    /**
     * Builds the CEL activation. Contract (mirror of Python {@code cel_facade}):
     *
     * <ul>
     *   <li>{@code type} and {@code EventType} are framework-owned and always win.
     *   <li>{@code attributes} holds the single-level merge of user data: {@code output.*} subkeys,
     *       then root attribute fields, then {@code input.*} subkeys ({@code output > root > input}
     *       on collision, via {@link Map#putIfAbsent}). Only one level is flattened — nested fields
     *       stay nested ({@code mylist.name}, not {@code name}).
     *   <li>Every merged attribute is also promoted to the activation top level, so conditions can
     *       use bare identifiers ({@code score > 0.8}) without any AST rewriting. Framework keys
     *       are never shadowed.
     *   <li>{@code id} is the user-supplied {@code id} attribute when present, otherwise falls back
     *       to the event UUID.
     * </ul>
     *
     * <p>JSON-shaped strings auto-parse first; narrow numerics widen to long/double.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createActivation(Event event) {
        Map<String, Object> activation = new HashMap<>();
        activation.put("type", event.getType());
        activation.put("EventType", CelExpressionFacade.EVENT_TYPE_CONSTANTS);

        Object normalizedAttrs = normalizeValue(event.getAttributes(), 0);
        Map<String, Object> merged = new HashMap<>();
        if (normalizedAttrs instanceof Map) {
            Map<String, Object> attrs = (Map<String, Object>) normalizedAttrs;

            // Precedence: output subkeys > root attributes > input subkeys (putIfAbsent keeps the
            // earliest insertion). Root iteration includes the "input"/"output" maps themselves,
            // so nested paths like input.region.width keep working.
            Object outputObj = attrs.get("output");
            if (outputObj instanceof Map) {
                ((Map<String, Object>) outputObj).forEach(merged::putIfAbsent);
            }
            attrs.forEach(merged::putIfAbsent);
            Object inputObj = attrs.get("input");
            if (inputObj instanceof Map) {
                ((Map<String, Object>) inputObj).forEach(merged::putIfAbsent);
            }
        }

        activation.put("attributes", merged);
        // Promote to top level for bare-identifier access; framework keys win on collision.
        merged.forEach(activation::putIfAbsent);
        // Event UUID only as fallback — a user-supplied id attribute takes precedence.
        activation.putIfAbsent("id", event.getId().toString());

        return activation;
    }

    /**
     * Maximum recursion depth for {@link #normalizeValue}. Past this depth, strings are kept as
     * plain strings rather than parsed as JSON (graceful degrade, mirror of Python {@code
     * _MAX_NORMALIZE_DEPTH}). Prevents stack blow-up on adversarial nested JSON input.
     */
    static final int MAX_NORMALIZE_DEPTH = 16;

    /** JSON-looking strings → Map/List; narrow numerics widened to long/double for CEL. */
    @SuppressWarnings("unchecked")
    private static Object normalizeValue(Object value, int depth) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            // Past MAX_NORMALIZE_DEPTH we stop expanding and keep the raw string,
            // matching Python's _MAX_NORMALIZE_DEPTH graceful-degrade policy.
            if (depth >= MAX_NORMALIZE_DEPTH) {
                return value;
            }
            String s = ((String) value).trim();
            if (s.length() >= 2
                    && ((s.charAt(0) == '{' && s.charAt(s.length() - 1) == '}')
                            || (s.charAt(0) == '[' && s.charAt(s.length() - 1) == ']'))) {
                try {
                    return normalizeValue(MAPPER.readValue(s, Object.class), depth + 1);
                } catch (Exception ignored) {
                    // Not valid JSON — fall through as plain string.
                }
            }
            return value;
        }
        if (value instanceof Map) {
            Map<String, Object> src = (Map<String, Object>) value;
            Map<String, Object> dst = new HashMap<>(src.size());
            for (Map.Entry<String, Object> entry : src.entrySet()) {
                dst.put(entry.getKey(), normalizeValue(entry.getValue(), depth + 1));
            }
            return dst;
        }
        if (value instanceof List) {
            List<Object> src = (List<Object>) value;
            List<Object> dst = new ArrayList<>(src.size());
            for (Object item : src) {
                dst.add(normalizeValue(item, depth + 1));
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
            throw new IllegalArgumentException(
                    "CEL normalizeValue: BigInteger value overflows int64: " + bigInt);
        }
        if (value instanceof BigDecimal) {
            BigDecimal bigDec = (BigDecimal) value;
            LOG.debug(
                    "CEL normalizeValue: converting BigDecimal to double (possible precision loss): {}",
                    bigDec);
            return bigDec.doubleValue();
        }
        return value;
    }
}
