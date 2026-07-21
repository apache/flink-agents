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
import dev.cel.common.values.NullValue;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.configuration.AgentConfigOptions.ConditionEvaluationFailureStrategy;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.plan.condition.TriggerCondition;
import org.apache.flink.agents.plan.condition.TriggerCondition.ExpressionCondition;
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

/** Unit tests for {@link ConditionEvaluator}. */
class ConditionEvaluatorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private EvaluatorHarness evaluator;

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

    /** Keeps the existing source-oriented tests focused while production evaluation stays pure. */
    private static final class EvaluatorHarness {
        private final ConditionEvaluator delegate;
        private final Map<String, ConditionExpressionCompiler.CompiledCondition> conditions;

        private EvaluatorHarness(Collection<String> sources) {
            this(sources, ConditionEvaluationFailureStrategy.WARN_AND_SKIP);
        }

        private EvaluatorHarness(
                Collection<String> sources, ConditionEvaluationFailureStrategy failureStrategy) {
            this.delegate = new ConditionEvaluator(failureStrategy);
            this.conditions = new HashMap<>();
            for (String source : sources) {
                conditions.computeIfAbsent(
                        source,
                        ignored ->
                                ConditionExpressionCompiler.compile(conditionExpression(source)));
            }
        }

        private Map<String, Object> buildConditionVariables(Event event, String source) {
            return delegate.buildConditionVariables(event, compiled(source));
        }

        private boolean evaluate(String source, Event event) {
            return delegate.evaluate(compiled(source), event);
        }

        private ConditionExpressionCompiler.CompiledCondition compiled(String source) {
            ConditionExpressionCompiler.CompiledCondition condition = conditions.get(source);
            if (condition == null) {
                throw new IllegalArgumentException("Condition was not compiled: " + source);
            }
            return condition;
        }
    }

    private static ExpressionCondition conditionExpression(String source) {
        TriggerCondition triggerCondition = TriggerCondition.classify(source);
        assertThat(triggerCondition).isInstanceOf(ExpressionCondition.class);
        return (ExpressionCondition) triggerCondition;
    }

    @BeforeEach
    void setUp() throws IOException {
        // Compile every condition from the conformance JSON.
        List<ConformanceTestCase> testCases = loadConformanceCases();
        List<String> conditionSources = new ArrayList<>();
        for (ConformanceTestCase tc : testCases) {
            if (tc.getCondition() != null && !tc.getCondition().isEmpty()) {
                conditionSources.add(tc.getCondition());
            }
        }
        evaluator = new EvaluatorHarness(conditionSources);
    }

    private static List<ConformanceTestCase> loadConformanceCases() throws IOException {
        try (InputStream is =
                ConditionEvaluatorTest.class.getResourceAsStream("/cel_conformance_cases.yaml")) {
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

    @Test
    void typedEventAttributesMatchJsonRoundTrip() throws Exception {
        UUID requestId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        ChatResponseEvent typed = new ChatResponseEvent(requestId, ChatMessage.assistant("hello"));
        Event jsonShaped = Event.fromJson(JSON_MAPPER.writeValueAsString(typed));
        List<String> sources =
                List.of(
                        "request_id == '550e8400-e29b-41d4-a716-446655440000'",
                        "response.content == 'hello'",
                        "response.role == 'assistant'");
        EvaluatorHarness testEvaluator =
                new EvaluatorHarness(sources, ConditionEvaluationFailureStrategy.FAIL);

        for (String source : sources) {
            Map<String, Object> typedVariables =
                    testEvaluator.buildConditionVariables(typed, source);
            Map<String, Object> jsonVariables =
                    testEvaluator.buildConditionVariables(jsonShaped, source);

            assertThat(typedVariables).isEqualTo(jsonVariables);
            assertThat(testEvaluator.evaluate(source, typed)).as(source).isTrue();
            assertThat(testEvaluator.evaluate(source, jsonShaped)).as(source).isTrue();
        }
    }

    @Test
    void missingEventIdDoesNotAbortVariableBuilding() throws Exception {
        String source = "score > 80";
        Event event = Event.fromJson("{\"type\":\"test\",\"attributes\":{\"score\":90}}");
        EvaluatorHarness testEvaluator =
                new EvaluatorHarness(List.of(source), ConditionEvaluationFailureStrategy.FAIL);

        Map<String, Object> conditionVariables =
                testEvaluator.buildConditionVariables(event, source);

        assertThat(conditionVariables).isNotNull().doesNotContainKey("id");
        assertThat(testEvaluator.evaluate(source, event)).isTrue();
    }

    @Test
    void userIdWorksWithoutEventId() throws Exception {
        String source = "id == 'tenant-42'";
        Event event = Event.fromJson("{\"type\":\"test\",\"attributes\":{\"id\":\"tenant-42\"}}");
        EvaluatorHarness testEvaluator =
                new EvaluatorHarness(List.of(source), ConditionEvaluationFailureStrategy.FAIL);

        Map<String, Object> conditionVariables =
                testEvaluator.buildConditionVariables(event, source);

        assertThat(conditionVariables).isNotNull().containsEntry("id", "tenant-42");
        assertThat(testEvaluator.evaluate(source, event)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conformanceCases")
    void conformanceCaseMatchesExpected(ConformanceTestCase testCase) {
        Event event = buildEvent(testCase.getEvent());
        boolean result = evaluator.evaluate(testCase.getCondition(), event);
        assertThat(result).isEqualTo(testCase.isExpected());
    }

    @Test
    void failStrategyThrowsOnEvaluationError() {
        EvaluatorHarness failEvaluator =
                new EvaluatorHarness(
                        List.of("attributes.nonexistent > 3"),
                        ConditionEvaluationFailureStrategy.FAIL);
        // Pre-compile a condition that will fail at runtime
        String source = "attributes.nonexistent > 3";

        Event event = new Event("test_type");

        assertThatThrownBy(() -> failEvaluator.evaluate(source, event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Trigger condition evaluation failed");
    }

    @Test
    void warnAndSkipHandlesVariableBuildFailure() {
        String source = "broken == null";
        EvaluatorHarness warnEvaluator = new EvaluatorHarness(List.of(source));
        Event event = new Event("test_type", Map.of("broken", new BrokenAttribute()));

        assertThat(warnEvaluator.evaluate(source, event)).isFalse();
    }

    @Test
    void failStrategyThrowsOnVariableBuildFailure() {
        String source = "broken == null";
        EvaluatorHarness failEvaluator =
                new EvaluatorHarness(List.of(source), ConditionEvaluationFailureStrategy.FAIL);
        Event event = new Event("test_type", Map.of("broken", new BrokenAttribute()));

        assertThatThrownBy(() -> failEvaluator.evaluate(source, event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Building trigger condition variables failed");
    }

    @Test
    void nonBooleanWarnStrategyReturnsFalse() {
        // A dynamic result can still be non-Boolean when Plan validation was bypassed.
        String source = "attributes['value']";
        EvaluatorHarness warnEvaluator =
                new EvaluatorHarness(
                        List.of(source), ConditionEvaluationFailureStrategy.WARN_AND_SKIP);

        Event event = new Event("test_type", Map.of("value", 7));
        assertThat(warnEvaluator.evaluate(source, event)).isFalse();
    }

    @Test
    void nonBooleanFailStrategyThrows() {
        String source = "attributes['value']";
        EvaluatorHarness failEvaluator =
                new EvaluatorHarness(List.of(source), ConditionEvaluationFailureStrategy.FAIL);

        Event event = new Event("test_type", Map.of("value", 7));
        assertThatThrownBy(() -> failEvaluator.evaluate(source, event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-boolean");
    }

    @Test
    void normalizeShortToLong() {
        String source = "attributes.count + 1 == 8";
        EvaluatorHarness testEvaluator =
                new EvaluatorHarness(List.of(source), ConditionEvaluationFailureStrategy.FAIL);
        Event event = new Event("test_type");
        event.getAttributes().put("count", Short.valueOf((short) 7));

        Map<String, Object> conditionVariables =
                testEvaluator.buildConditionVariables(event, source);

        assertThat(conditionVariables.get("count")).isEqualTo(7L);
        assertThat(((Map<?, ?>) conditionVariables.get("attributes")).get("count")).isEqualTo(7L);
        assertThat(testEvaluator.evaluate(source, event)).isTrue();
    }

    @Test
    void normalizeFloatToDouble() {
        String source = "attributes.score + 0.5 == 5.0";
        EvaluatorHarness testEvaluator =
                new EvaluatorHarness(List.of(source), ConditionEvaluationFailureStrategy.FAIL);
        Event event = new Event("test_type");
        event.getAttributes().put("score", Float.valueOf(4.5F));

        Map<String, Object> conditionVariables =
                testEvaluator.buildConditionVariables(event, source);

        assertThat(conditionVariables.get("score")).isInstanceOf(Double.class).isEqualTo(4.5D);
        assertThat(((Map<?, ?>) conditionVariables.get("attributes")).get("score"))
                .isInstanceOf(Double.class)
                .isEqualTo(4.5D);
        assertThat(testEvaluator.evaluate(source, event)).isTrue();
    }

    @Test
    void normalizeBigIntegerInLongRange() {
        // BigInteger within Long range should pass through normalizeValue cleanly.
        String source = "attributes.amount > 1000";
        EvaluatorHarness testEvaluator = new EvaluatorHarness(List.of(source));

        Event event = new Event("test_type");
        event.getAttributes().put("amount", java.math.BigInteger.valueOf(9999999999999L));

        assertThat(testEvaluator.evaluate(source, event)).isTrue();
    }

    @Test
    void normalizeBigIntegerOverflowConvertsToDouble() {
        // A BigInteger past int64 widens to double so building the variables succeeds and the
        // event's other conditions still evaluate.
        String source = "amount > 1000";
        EvaluatorHarness testEvaluator = new EvaluatorHarness(List.of(source));
        Event event = new Event("test_type");
        event.getAttributes().put("amount", new java.math.BigInteger("99999999999999999999"));

        Map<String, Object> conditionVariables =
                testEvaluator.buildConditionVariables(event, source);

        assertThat(conditionVariables.get("amount")).isInstanceOf(Double.class);
    }

    @Test
    void normalizeBigDecimalToDouble() {
        String source = "attributes.score > 3.14";
        EvaluatorHarness testEvaluator = new EvaluatorHarness(List.of(source));

        Event event = new Event("test_type");
        event.getAttributes().put("score", new java.math.BigDecimal("99.99"));

        assertThat(testEvaluator.evaluate(source, event)).isTrue();
    }

    // ----- Nested has() short-circuit (has(a.b.c) desugaring) -----
    // These use the FAIL strategy on purpose: under the default WARN_AND_SKIP a thrown
    // CelEvaluationException is swallowed and also reported as false, so it cannot tell a
    // genuine false apart from a silently-skipped error. FAIL re-throws, so asserting false
    // here proves the macro short-circuits a missing level instead of evaluating an operand
    // that errors with "key '...' is not present in map".

    @Test
    void nestedHasMissingMiddleReturnsFalse() {
        EvaluatorHarness failEvaluator =
                new EvaluatorHarness(
                        List.of("has(attributes.meta.source)"),
                        ConditionEvaluationFailureStrategy.FAIL);
        String source = "has(attributes.meta.source)";

        // No attributes ⇒ intermediate 'meta' is absent; the has() chain desugaring
        // short-circuits to false instead of throwing on the deeper select.
        Event event = new Event("test_type");
        assertThat(failEvaluator.evaluate(source, event)).isFalse();
    }

    @Test
    void nestedHasMissingRootReturnsFalse() {
        EvaluatorHarness failEvaluator =
                new EvaluatorHarness(
                        List.of("has(value.user.tier)"), ConditionEvaluationFailureStrategy.FAIL);
        String source = "has(value.user.tier)";

        // Bare root 'value' is wholly absent: the has(value) guard short-circuits before the
        // unbound 'value' lookup can throw.
        Event event = new Event("test_type");
        assertThat(failEvaluator.evaluate(source, event)).isFalse();
    }

    @Test
    void nestedHasFullPathReturnsTrue() {
        EvaluatorHarness failEvaluator =
                new EvaluatorHarness(
                        List.of("has(attributes.meta.source)"),
                        ConditionEvaluationFailureStrategy.FAIL);
        String source = "has(attributes.meta.source)";

        Event event = new Event("test_type");
        Map<String, Object> meta = new HashMap<>();
        meta.put("source", "api");
        event.getAttributes().put("meta", meta);
        assertThat(failEvaluator.evaluate(source, event)).isTrue();
    }

    // ---- Trimmed condition variables preserve the Event.attributes envelope ----

    @Test
    void payloadChildrenNeedExplicitPaths() {
        List<String> sources = List.of("input.region == 1", "output.region == 2", "has(region)");
        EvaluatorHarness testEvaluator =
                new EvaluatorHarness(sources, ConditionEvaluationFailureStrategy.FAIL);
        Event inputEvent = new Event(InputEvent.EVENT_TYPE, Map.of("input", Map.of("region", 1)));
        Event outputEvent =
                new Event(OutputEvent.EVENT_TYPE, Map.of("output", Map.of("region", 2)));

        assertThat(testEvaluator.evaluate("input.region == 1", inputEvent)).isTrue();
        assertThat(testEvaluator.evaluate("output.region == 2", outputEvent)).isTrue();
        assertThat(testEvaluator.evaluate("has(region)", inputEvent)).isFalse();
        assertThat(testEvaluator.evaluate("has(region)", outputEvent)).isFalse();
    }

    @Test
    void topLevelNullEvaluatesAsCelNull() {
        List<String> sources =
                List.of("has(attributes.score)", "attributes.score == null", "score == null");
        EvaluatorHarness testEvaluator =
                new EvaluatorHarness(sources, ConditionEvaluationFailureStrategy.FAIL);
        Event event = new Event("custom");
        event.getAttributes().put("score", null);

        for (String source : sources) {
            Map<String, Object> conditionVariables =
                    testEvaluator.buildConditionVariables(event, source);

            assertThat(conditionVariables.get("score")).isSameAs(NullValue.NULL_VALUE);
            assertThat(((Map<?, ?>) conditionVariables.get("attributes")).get("score"))
                    .isSameAs(NullValue.NULL_VALUE);
            assertThat(testEvaluator.evaluate(source, event)).as(source).isTrue();
        }
    }

    @Test
    void missingKeyMakesHasReturnFalse() {
        String source = "has(score)";
        EvaluatorHarness testEvaluator =
                new EvaluatorHarness(List.of(source), ConditionEvaluationFailureStrategy.FAIL);
        Event event = new Event(OutputEvent.EVENT_TYPE, Map.of("output", Map.of("score", 99)));

        Map<String, Object> conditionVariables =
                testEvaluator.buildConditionVariables(event, source);

        assertThat(conditionVariables).doesNotContainKey("score");
        assertThat(((Map<?, ?>) conditionVariables.get("attributes")).containsKey("score"))
                .isFalse();
        assertThat(testEvaluator.evaluate(source, event)).isFalse();
    }

    @Test
    void evaluatesDottedLiteralKey() {
        List<String> sources = List.of("attributes['a.b.c'] == 7", "'a.b.c' in attributes");
        EvaluatorHarness testEvaluator =
                new EvaluatorHarness(sources, ConditionEvaluationFailureStrategy.FAIL);
        Event event = new Event("test_type", Map.of("a.b.c", 7));

        for (String source : sources) {
            Map<String, Object> conditionVariables =
                    testEvaluator.buildConditionVariables(event, source);

            assertThat(conditionVariables).isNotNull();
            assertThat(conditionVariables.get("attributes")).isEqualTo(Map.of("a.b.c", 7L));
            assertThat(testEvaluator.evaluate(source, event)).as(source).isTrue();
        }
    }

    @Test
    void pojoUsesExplicitAttributePath() {
        List<String> sources =
                List.of("input.status == 'ok'", "attributes.input.status == 'ok'", "has(status)");
        EvaluatorHarness testEvaluator =
                new EvaluatorHarness(sources, ConditionEvaluationFailureStrategy.FAIL);
        Event event = new InputEvent(new ConditionInput("ok", 42));

        Map<String, Object> conditionVariables =
                testEvaluator.buildConditionVariables(event, "input.status == 'ok'");

        assertThat(conditionVariables).containsKey("input").doesNotContainKey("status");
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) conditionVariables.get("attributes");
        assertThat(attributes).containsOnlyKeys("input").doesNotContainKey("status");
        assertThat(testEvaluator.evaluate("input.status == 'ok'", event)).isTrue();
        assertThat(testEvaluator.evaluate("attributes.input.status == 'ok'", event)).isTrue();
        assertThat(testEvaluator.evaluate("has(status)", event)).isFalse();
    }

    @Test
    void scalarAndListAccessibleViaInput() {
        List<String> sources = List.of("input == 'ready'", "input[0] == 'ready'");
        EvaluatorHarness testEvaluator =
                new EvaluatorHarness(sources, ConditionEvaluationFailureStrategy.FAIL);

        assertThat(testEvaluator.evaluate("input == 'ready'", new InputEvent("ready"))).isTrue();
        assertThat(testEvaluator.evaluate("input[0] == 'ready'", new InputEvent(List.of("ready"))))
                .isTrue();
    }

    @Test
    void typeOnlySkipsUnneededPayload() {
        String source = "type == EventType.InputEvent";
        EvaluatorHarness testEvaluator =
                new EvaluatorHarness(List.of(source), ConditionEvaluationFailureStrategy.FAIL);

        assertThat(testEvaluator.evaluate(source, new InputEvent(new BrokenAttribute()))).isTrue();
    }

    @Test
    void trimsUnreferencedLargeAttribute() {
        String source = "score > 1";
        EvaluatorHarness testEvaluator = new EvaluatorHarness(List.of(source));
        Event event = new Event("t");
        event.getAttributes().put("score", 5);
        event.getAttributes().put("big_blob", "{\"a\":1,\"b\":2}");

        Map<String, Object> conditionVariables =
                testEvaluator.buildConditionVariables(event, source);
        assertThat(conditionVariables).containsKey("score");
        assertThat(conditionVariables).doesNotContainKey("big_blob"); // never normalized
        assertThat(testEvaluator.evaluate(source, event)).isTrue();
    }

    private static final class BrokenAttribute {
        public String getValue() {
            throw new IllegalStateException("broken attribute");
        }
    }

    public static final class ConditionInput {
        public String status;
        public int value;

        public ConditionInput() {}

        public ConditionInput(String status, int value) {
            this.status = status;
            this.value = value;
        }
    }
}
