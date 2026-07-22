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

import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.plan.condition.ConditionExpressionValidator;
import org.apache.flink.agents.plan.condition.TriggerCondition;
import org.apache.flink.agents.plan.condition.TriggerCondition.ExpressionCondition;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runtime-only compilation tests for Plan-classified expression conditions. */
class ConditionExpressionCompilerTest {

    private static ConditionExpressionCompiler.CompiledCondition compileValidated(String source) {
        ExpressionCondition condition = conditionExpression(source);
        ConditionExpressionValidator.validate(condition);
        return ConditionExpressionCompiler.compile(condition);
    }

    private static ConditionExpressionCompiler.CompiledCondition compileRuntimeOnly(String source) {
        return ConditionExpressionCompiler.compile(conditionExpression(source));
    }

    private static ExpressionCondition conditionExpression(String source) {
        TriggerCondition triggerCondition = TriggerCondition.classify(source);
        assertThat(triggerCondition).isInstanceOf(ExpressionCondition.class);
        return (ExpressionCondition) triggerCondition;
    }

    @Test
    void createsRunnableBooleanProgram() throws CelEvaluationException {
        CelRuntime.Program program = compileValidated("type == 'a'").program();
        Map<String, Object> activation = new HashMap<>();
        activation.put("type", "a");
        activation.put("attributes", new HashMap<String, Object>());

        assertThat(program.eval(activation)).isEqualTo(true);
    }

    @Test
    void resolvesKnownEventTypeConstants() throws CelEvaluationException {
        CelRuntime.Program program = compileValidated("type == EventType.InputEvent").program();
        Map<String, Object> activation = new HashMap<>();
        activation.put("type", "_input_event");
        activation.put("attributes", new HashMap<String, Object>());
        activation.put("EventType", EventType.allConstants());

        assertThat(program.eval(activation)).isEqualTo(true);
    }

    @Test
    void supportsDynamicAttributeTypes() throws CelEvaluationException {
        CelRuntime.Program program = compileValidated("score > 0").program();

        assertThat(program.eval(Map.of("score", 42L))).isEqualTo(true);
    }

    @Test
    void rejectsUnknownFunctionAtRuntime() {
        ConditionExpressionValidator.validate(conditionExpression("noSuchFn(1)"));

        assertThatThrownBy(() -> compileRuntimeOnly("noSuchFn(1)"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Runtime type-check");
    }

    @ParameterizedTest(name = "Runtime rejects statically non-Boolean result: {0}")
    @ValueSource(
            strings = {
                "null",
                "42",
                "'not a routable name'",
                "[1, 2]",
                "{'key': 1}",
                "1 + 2",
                "size(attributes)"
            })
    void rejectsStaticNonBooleanResults(String source) {
        assertThatThrownBy(() -> compileValidated(source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Boolean result");
    }

    @Test
    void runtimeDefensivelyRejectsStandaloneEventTypeConstant() {
        assertThatThrownBy(() -> compileRuntimeOnly("EventType.InputEvent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Boolean result");
    }

    @Test
    void reportsRuntimeReparseInvariantFailure() {
        assertThatThrownBy(() -> compileRuntimeOnly("type =="))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Plan-validated trigger condition")
                .hasMessageContaining("reparsed at Runtime");
    }

    @Test
    void supportsHasDuringRuntimeReparse() throws CelEvaluationException {
        CelRuntime.Program program = compileValidated("has(score)").program();

        assertThat(program.eval(Map.of("attributes", Map.of("score", 1L)))).isEqualTo(true);
        assertThat(program.eval(Map.of("attributes", Map.of()))).isEqualTo(false);
    }

    static Stream<Arguments> stringFunctionCases() {
        return Stream.of(
                Arguments.of("name.contains(\"foo\")", "hello_foo_bar", true),
                Arguments.of("name.contains(\"xyz\")", "hello_foo_bar", false),
                Arguments.of("name.startsWith(\"pre_\")", "pre_order_123", true),
                Arguments.of("name.startsWith(\"pre_\")", "order_pre_123", false),
                Arguments.of("name.endsWith(\".json\")", "config.json", true),
                Arguments.of("name.endsWith(\".json\")", "config.yaml", false),
                Arguments.of("name.matches(\"^order_[0-9]+$\")", "order_42", true),
                Arguments.of("name.matches(\"^order_[0-9]+$\")", "order_abc", false),
                Arguments.of("name.matches(\"[\")", "test", CelEvaluationException.class));
    }

    @ParameterizedTest(name = "{0} with name={1} -> {2}")
    @MethodSource("stringFunctionCases")
    void stringFunctionsEvaluateOrThrow(String source, String value, Object expected)
            throws CelEvaluationException {
        CelRuntime.Program program = compileValidated(source).program();
        Map<String, Object> activation = Map.of("attributes", Map.of("name", value), "name", value);

        if (expected instanceof Class) {
            @SuppressWarnings("unchecked")
            Class<? extends Throwable> exceptionClass = (Class<? extends Throwable>) expected;
            assertThatThrownBy(() -> program.eval(activation)).isInstanceOf(exceptionClass);
        } else {
            assertThat(program.eval(activation)).isEqualTo(expected);
        }
    }

    @Nested
    class SharedParserResourceGuards {
        @Test
        void rejectsOversizedRuntimeSource() {
            String oversized = "true" + " || true".repeat(2_000);

            assertThatThrownBy(() -> compileRuntimeOnly(oversized))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reparsed at Runtime");
        }

        @Test
        void rejectsDeepRuntimeSource() {
            String tooDeep = "(".repeat(40) + "true" + ")".repeat(40);

            assertThatThrownBy(() -> compileRuntimeOnly(tooDeep))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reparsed at Runtime");
        }
    }
}
