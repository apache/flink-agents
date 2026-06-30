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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.configuration.CelEvaluationFailurePolicy;
import org.apache.flink.agents.plan.condition.ParsedCondition.CelExpression;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link CelConditionEvaluator}. */
class CelConditionEvaluatorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(new YAMLFactory());

    private CelConditionEvaluator evaluator;

    /** Test case from conformance JSON. */
    static class ConformanceTestCase {
        String name;
        String condition;
        Map<String, Object> event;
        boolean expected;

        public String getName() {
            return name;
        }

        public String getCondition() {
            return condition;
        }

        public Map<String, Object> getEvent() {
            return event;
        }

        public boolean isExpected() {
            return expected;
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        evaluator = new CelConditionEvaluator();
        // Pre-compile every condition from the conformance JSON.
        List<ConformanceTestCase> testCases = loadConformanceCases();
        Collection<CelExpression> conditions = new ArrayList<>();
        for (ConformanceTestCase tc : testCases) {
            if (tc.getCondition() != null && !tc.getCondition().isEmpty()) {
                conditions.add(new CelExpression(tc.getCondition()));
            }
        }
        evaluator.initPrograms(conditions);
    }

    private static List<ConformanceTestCase> loadConformanceCases() throws IOException {
        try (InputStream is =
                CelConditionEvaluatorTest.class.getResourceAsStream(
                        "/cel_conformance_cases.yaml")) {
            if (is == null) {
                throw new IOException("cel_conformance_cases.yaml not found");
            }
            return OBJECT_MAPPER.readValue(is, new TypeReference<List<ConformanceTestCase>>() {});
        }
    }

    private Event buildEvent(Map<String, Object> eventData) {
        String id = (String) eventData.get("id");
        String type = (String) eventData.get("type");
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes =
                (Map<String, Object>) eventData.getOrDefault("attributes", new HashMap<>());
        UUID uuid;
        try {
            // Reuse the raw id when it's a valid UUID so id-based filters work in conformance
            // cases.
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            uuid = UUID.nameUUIDFromBytes(id.getBytes());
        }
        Event event = new Event(uuid, type, attributes);
        return event;
    }

    static Stream<ConformanceTestCase> conformanceCases() throws IOException {
        return loadConformanceCases().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conformanceCases")
    void testConformanceCases(ConformanceTestCase testCase) {
        Event event = buildEvent(testCase.getEvent());
        Map<String, Object> activation = evaluator.createActivation(event);
        CelExpression expr =
                (testCase.getCondition() == null || testCase.getCondition().isEmpty())
                        ? null
                        : new CelExpression(testCase.getCondition());
        boolean result = evaluator.evaluate(expr, activation);
        assertThat(result).isEqualTo(testCase.isExpected());
    }

    @Test
    void testFailPolicyThrowsOnEvaluationError() {
        CelConditionEvaluator failEvaluator =
                new CelConditionEvaluator(CelEvaluationFailurePolicy.FAIL);
        // Pre-compile a condition that will fail at runtime
        CelExpression cond = new CelExpression("attributes.nonexistent > 3");
        failEvaluator.initPrograms(List.of(cond));

        Event event = new Event("test_type");
        Map<String, Object> activation = failEvaluator.createActivation(event);

        assertThatThrownBy(() -> failEvaluator.evaluate(cond, activation))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CEL condition evaluation failed");
    }

    @Test
    void testInitProgramsInvalidExpressionThrows() {
        CelConditionEvaluator testEvaluator = new CelConditionEvaluator();
        assertThatThrownBy(() -> testEvaluator.initPrograms(List.of(new CelExpression("type =="))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid CEL condition expression");
    }

    @Test
    void testNormalizeValueBigIntegerWithinLongRange() {
        // BigInteger within Long range should pass through normalizeValue cleanly.
        CelConditionEvaluator testEvaluator = new CelConditionEvaluator();
        CelExpression condition = new CelExpression("attributes.amount > 1000");
        testEvaluator.initPrograms(List.of(condition));

        Event event = new Event("test_type");
        event.getAttributes().put("amount", java.math.BigInteger.valueOf(9999999999999L));

        Map<String, Object> activation = testEvaluator.createActivation(event);
        assertThat(testEvaluator.evaluate(condition, activation)).isTrue();
    }

    @Test
    void testNormalizeValueBigIntegerOverflowThrows() {
        // BigInteger exceeding Long.MAX_VALUE should throw IllegalArgumentException
        Event event = new Event("test_type");
        java.math.BigInteger overflow =
                java.math.BigInteger.valueOf(Long.MAX_VALUE).multiply(java.math.BigInteger.TEN);
        event.getAttributes().put("amount", overflow);

        assertThatThrownBy(() -> evaluator.createActivation(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overflows int64");
    }

    @Test
    void testNormalizeValueBigDecimalConvertedToDouble() {
        CelConditionEvaluator testEvaluator = new CelConditionEvaluator();
        CelExpression condition = new CelExpression("attributes.score > 3.14");
        testEvaluator.initPrograms(List.of(condition));

        Event event = new Event("test_type");
        event.getAttributes().put("score", new java.math.BigDecimal("99.99"));

        Map<String, Object> activation = testEvaluator.createActivation(event);
        assertThat(testEvaluator.evaluate(condition, activation)).isTrue();
    }

    @Test
    void evaluate_warnAndSkipReturnsFalseOnRuntimeError() {
        // Positive assertion for the default WARN_AND_SKIP policy: runtime errors are swallowed
        // and the action is treated as not matching, with the failure surfaced via WARN log.
        CelConditionEvaluator e = new CelConditionEvaluator();
        CelExpression cond = new CelExpression("attributes.nope > 3");
        e.initPrograms(List.of(cond));

        Event event = new Event("test_type");
        Map<String, Object> activation = e.createActivation(event);
        assertThat(e.evaluate(cond, activation)).isFalse();
    }

    // ----- Nested has() short-circuit (has(a.b.c) desugaring) -----
    // These use the FAIL policy on purpose: under the default WARN_AND_SKIP a thrown
    // CelEvaluationException is swallowed and also reported as false, so it cannot tell a
    // genuine false apart from a silently-skipped error. FAIL re-throws, so asserting false
    // here proves the macro short-circuits a missing level instead of evaluating an operand
    // that errors with "key '...' is not present in map".

    @Test
    void evaluate_nestedHasIntermediateMissing_returnsFalseWithoutThrowing() {
        CelConditionEvaluator failEvaluator =
                new CelConditionEvaluator(CelEvaluationFailurePolicy.FAIL);
        CelExpression cond = new CelExpression("has(attributes.meta.source)");
        failEvaluator.initPrograms(List.of(cond));

        // No attributes ⇒ intermediate 'meta' absent. The old single-testOnly desugaring
        // evaluated attributes.meta and threw; the chain desugaring short-circuits to false.
        Event event = new Event("test_type");
        Map<String, Object> activation = failEvaluator.createActivation(event);
        assertThat(failEvaluator.evaluate(cond, activation)).isFalse();
    }

    @Test
    void evaluate_nestedHasBareRootWhollyAbsent_returnsFalseWithoutThrowing() {
        CelConditionEvaluator failEvaluator =
                new CelConditionEvaluator(CelEvaluationFailurePolicy.FAIL);
        CelExpression cond = new CelExpression("has(value.user.tier)");
        failEvaluator.initPrograms(List.of(cond));

        // Bare root 'value' is wholly absent: the has(value) guard short-circuits before the
        // unbound 'value' lookup can throw.
        Event event = new Event("test_type");
        Map<String, Object> activation = failEvaluator.createActivation(event);
        assertThat(failEvaluator.evaluate(cond, activation)).isFalse();
    }

    @Test
    void evaluate_nestedHasFullPathPresent_returnsTrue() {
        CelConditionEvaluator failEvaluator =
                new CelConditionEvaluator(CelEvaluationFailurePolicy.FAIL);
        CelExpression cond = new CelExpression("has(attributes.meta.source)");
        failEvaluator.initPrograms(List.of(cond));

        Event event = new Event("test_type");
        Map<String, Object> meta = new HashMap<>();
        meta.put("source", "api");
        event.getAttributes().put("meta", meta);
        Map<String, Object> activation = failEvaluator.createActivation(event);
        assertThat(failEvaluator.evaluate(cond, activation)).isTrue();
    }
}
