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
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import org.apache.flink.agents.plan.condition.CelMacroPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link CelExpressionFacade}: parse validation and toProgram compilation + caching. */
class CelExpressionFacadeTest {

    @BeforeEach
    void clearCache() {
        // Per-process cache keyed by source string — clear between tests so cache assertions are
        // deterministic.
        CelExpressionFacade.clearProgramCacheForTests();
    }

    @ParameterizedTest(name = "rejects disallowed macro {0}")
    @ValueSource(
            strings = {
                "[1, 2, 3].all(x, x > 0)",
                "[1, 2, 3].exists(x, x > 0)",
                "attributes.tags.exists(x, x == 'a')",
                "has(attributes.x) && attributes.list.all(t, t > 0)"
            })
    void toProgram_rejectsDisallowedMacroCalls(String source) {
        assertThatThrownBy(() -> CelExpressionFacade.toProgram(source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disallowed macro");
    }

    @Test
    void disallowedMacroMessage_isByteIdenticalToPython() {
        // Must match Python ``cel_facade._format_disallowed_message`` byte-for-byte. CI
        // ``cel-conformance.yml`` validates this alignment via diff.
        String expected =
                "CEL expression uses disallowed macro 'all': \"test_source\". "
                        + "Only allows: [has].";
        assertThat(CelMacroPolicy.formatDisallowedMessage("all", "test_source"))
                .isEqualTo(expected);
    }

    @Test
    void toProgram_acceptsMacroNameInStringLiteral() {
        // "exists" inside a string literal is not a macro call — should compile fine.
        CelRuntime.Program program = CelExpressionFacade.toProgram("type == 'exists_check_event'");
        assertThat(program).isNotNull();
    }

    @Test
    void toProgram_acceptsMacroNameAsFieldSubstring() {
        // "existing" contains "exist" but is not a macro call.
        CelRuntime.Program program = CelExpressionFacade.toProgram("existing == true");
        assertThat(program).isNotNull();
    }

    @Test
    void toProgram_doesNotMisclassifyFieldNamedAllAsDisallowedMacro() {
        // `attributes.all` is a field access, not a macro call. AST-based check (vs. old
        // error-message string match) won't false-positive on the substring "all".
        CelRuntime.Program program = CelExpressionFacade.toProgram("attributes.all == 'value'");
        assertThat(program).isNotNull();
    }

    @Test
    void toProgram_findsDisallowedMacroInDeeplyNestedExpression() {
        // Macro buried inside arg of another macro arg — AST walker must find it.
        assertThatThrownBy(
                        () ->
                                CelExpressionFacade.toProgram(
                                        "has(attributes.x) && (attributes.y > 0 || attributes.tags.exists(t, t == 'a'))"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'exists'");
    }

    @Test
    void parse_rejectsHasOnNonFieldArgument() {
        // has(1 + 1) is neither a field selection nor an attribute name.
        assertThatThrownBy(() -> CelExpressionFacade.toProgram("has(1 + 1)"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid argument to has() macro");
    }

    // -- EventType map access --

    @Test
    void toProgram_unknownEventTypeConstantFailsAtPlanLoad() {
        assertThatThrownBy(() -> CelExpressionFacade.toProgram("type == EventType.NotARealEvent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown EventType constant: EventType.NotARealEvent");
    }

    // -- Dynamic identifier declaration --

    @Test
    void toProgram_userSuppliedIdSupportsNonStringComparison() throws CelEvaluationException {
        // id is declared DYN: users may supply numeric ids via attributes.
        CelRuntime.Program program = CelExpressionFacade.toProgram("id > 0");
        Map<String, Object> activation = new HashMap<>();
        activation.put("id", 42L);
        assertThat(program.eval(activation)).isEqualTo(true);
    }

    // -- toProgram(String) --

    @Test
    void toProgram_fromString_returnsRunnableProgram() throws CelEvaluationException {
        CelRuntime.Program program = CelExpressionFacade.toProgram("type == 'a'");
        Map<String, Object> activation = new HashMap<>();
        activation.put("id", "42");
        activation.put("type", "a");
        activation.put("attributes", new HashMap<String, Object>());
        Object result = program.eval(activation);
        assertThat(result).isEqualTo(true);
    }

    @Test
    void toProgram_fromString_throwsOnInvalidSource() {
        assertThatThrownBy(() -> CelExpressionFacade.toProgram("type =="))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid CEL condition expression");
    }

    @Test
    void toProgram_fromString_throwsOnNullOrEmpty() {
        assertThatThrownBy(() -> CelExpressionFacade.toProgram((String) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CelExpressionFacade.toProgram(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toProgram_fromString_isCachedBySource() {
        CelRuntime.Program first = CelExpressionFacade.toProgram("type == 'x'");
        CelRuntime.Program second = CelExpressionFacade.toProgram("type == 'x'");
        assertThat(second).isSameAs(first); // cache hit
    }

    // -- String functions: contains / startsWith / endsWith / matches --

    static Stream<Arguments> stringFunctionCases() {
        return Stream.of(
                Arguments.of("name.contains(\"foo\")", "name", "hello_foo_bar", true),
                Arguments.of("name.contains(\"xyz\")", "name", "hello_foo_bar", false),
                Arguments.of("name.contains(\"\")", "name", "anything", true),
                Arguments.of("name.startsWith(\"pre_\")", "name", "pre_order_123", true),
                Arguments.of("name.startsWith(\"pre_\")", "name", "order_pre_123", false),
                Arguments.of("name.startsWith(\"\")", "name", "anything", true),
                Arguments.of("name.endsWith(\".json\")", "name", "config.json", true),
                Arguments.of("name.endsWith(\".json\")", "name", "config.yaml", false),
                Arguments.of("name.matches(\"^order_[0-9]+$\")", "name", "order_42", true),
                Arguments.of("name.matches(\"^order_[0-9]+$\")", "name", "order_abc", false),
                Arguments.of("name.matches(\"[\")", "name", "test", CelEvaluationException.class));
    }

    @ParameterizedTest(name = "{0} on {1}={2} → {3}")
    @MethodSource("stringFunctionCases")
    void stringFunctions_evaluateOrThrow(
            String source, String attrName, String attrValue, Object expected)
            throws CelEvaluationException {
        CelRuntime.Program program = CelExpressionFacade.toProgram(source);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(attrName, attrValue);
        Map<String, Object> activation = new HashMap<>();
        activation.put("id", "1");
        activation.put("type", "t");
        activation.put("attributes", attrs);
        // Flattened contract: bare identifiers read from the activation top level.
        activation.put(attrName, attrValue);

        if (expected instanceof Class) {
            @SuppressWarnings("unchecked")
            Class<? extends Throwable> exceptionClass = (Class<? extends Throwable>) expected;
            assertThatThrownBy(() -> program.eval(activation)).isInstanceOf(exceptionClass);
        } else {
            assertThat(program.eval(activation)).isEqualTo(expected);
        }
    }

    // -- Fixture-driven tests from disallowed_macros.yaml --

    private static final String FIXTURE_RESOURCE = "/disallowed_macros.yaml";

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> loadMacroFixture() throws IOException {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        InputStream is = CelExpressionFacadeTest.class.getResourceAsStream(FIXTURE_RESOURCE);
        assertThat(is)
                .as("Shared fixture resource must exist on classpath: %s", FIXTURE_RESOURCE)
                .isNotNull();
        return yamlMapper.readValue(is, Map.class);
    }

    static Stream<Arguments> rejectFixtureCases() throws IOException {
        return loadMacroFixture().get("reject").stream().map(Arguments::of);
    }

    static Stream<Arguments> acceptFixtureCases() throws IOException {
        return loadMacroFixture().get("accept").stream().map(Arguments::of);
    }

    @ParameterizedTest(name = "REJECT: {0}")
    @MethodSource("rejectFixtureCases")
    void fixture_rejectDisallowedMacro(String expression) {
        assertThatThrownBy(() -> CelExpressionFacade.toProgram(expression))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disallowed macro");
    }

    @ParameterizedTest(name = "ACCEPT: {0}")
    @MethodSource("acceptFixtureCases")
    void fixture_acceptAllowedExpression(String expression) {
        CelRuntime.Program program = CelExpressionFacade.toProgram(expression);
        assertThat(program).isNotNull();
    }

    // -- Resource guards --

    @Nested
    class ResourceGuards {
        @Test
        void parse_oversizedSource_rejected() {
            String huge = "true" + " || true".repeat(2000); // ~16K chars
            assertThatThrownBy(() -> CelExpressionFacade.parse(huge))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid CEL expression");
        }

        @Test
        void parse_overlyNestedExpression_rejected() {
            // Parenthesis nesting reliably hits maxParseRecursionDepth (member-access chains
            // are left-recursive in ANTLR4 and parsed iteratively, so they don't).
            String deep = "(".repeat(40) + "true" + ")".repeat(40);
            assertThatThrownBy(() -> CelExpressionFacade.parse(deep))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
