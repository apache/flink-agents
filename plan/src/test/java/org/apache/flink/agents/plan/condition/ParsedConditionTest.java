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

package org.apache.flink.agents.plan.condition;

import org.apache.flink.agents.plan.condition.ParsedCondition.CelExpression;
import org.apache.flink.agents.plan.condition.ParsedCondition.TypeMatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ParsedCondition}: value semantics of TypeMatch/CelExpression, and {@link
 * ParsedCondition#classify} (corpus equivalence, regression guards, and routing).
 */
class ParsedConditionTest {

    // ==================================================================
    // Value semantics
    // ==================================================================

    @Test
    void typeMatch_storesSource() {
        TypeMatch tm = new TypeMatch("_input_event");
        assertEquals("_input_event", tm.source());
        assertTrue(((ParsedCondition) tm) instanceof TypeMatch);
        assertFalse(((ParsedCondition) tm) instanceof CelExpression);
    }

    @Test
    void typeMatch_equalityAndHash() {
        TypeMatch a = new TypeMatch("_input_event");
        TypeMatch b = new TypeMatch("_input_event");
        TypeMatch c = new TypeMatch("_output_event");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    void celExpression_storesSource() {
        CelExpression ce = new CelExpression("type == EventType.InputEvent && id > 0");
        assertEquals("type == EventType.InputEvent && id > 0", ce.source());
        assertFalse(((ParsedCondition) ce) instanceof TypeMatch);
        assertTrue(((ParsedCondition) ce) instanceof CelExpression);
    }

    @Test
    void celExpression_equalityAndHash() {
        CelExpression a = new CelExpression("type == 'x'");
        CelExpression b = new CelExpression("type == 'x'");
        CelExpression c = new CelExpression("type == 'y'");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    void celExpression_isNotEqualToTypeMatchWithSameSource() {
        CelExpression ce = new CelExpression("InputEvent");
        TypeMatch tm = new TypeMatch("InputEvent");
        assertNotEquals(ce, tm);
        assertNotEquals(tm, ce);
    }

    // ==================================================================
    // classify(): corpus. Format: input, expected_kind, expected_event_type (null for CEL).
    // ==================================================================

    static Stream<Arguments> classifierCorpus() {
        return Stream.of(
                // ----- bare identifiers are TypeMatch (no short-name translation) -----
                Arguments.of("InputEvent", "TypeMatch", "InputEvent"),
                Arguments.of("OutputEvent", "TypeMatch", "OutputEvent"),
                Arguments.of("ChatRequestEvent", "TypeMatch", "ChatRequestEvent"),
                Arguments.of("ChatResponseEvent", "TypeMatch", "ChatResponseEvent"),
                Arguments.of("ToolRequestEvent", "TypeMatch", "ToolRequestEvent"),
                Arguments.of("ToolResponseEvent", "TypeMatch", "ToolResponseEvent"),
                Arguments.of(
                        "ContextRetrievalRequestEvent",
                        "TypeMatch",
                        "ContextRetrievalRequestEvent"),
                Arguments.of(
                        "ContextRetrievalResponseEvent",
                        "TypeMatch",
                        "ContextRetrievalResponseEvent"),

                // ----- raw event-type strings & passthrough -----
                Arguments.of("_input_event", "TypeMatch", "_input_event"),
                Arguments.of("_my_custom_event", "TypeMatch", "_my_custom_event"),
                Arguments.of("CustomEvent", "TypeMatch", "CustomEvent"),
                Arguments.of("in_progress_event", "TypeMatch", "in_progress_event"),

                // ----- equality / inequality -----
                Arguments.of("type == 'x'", "CelExpression", null),
                Arguments.of("type != 'x'", "CelExpression", null),

                // ----- numeric comparisons -----
                Arguments.of("id > 0", "CelExpression", null),
                Arguments.of("id < 0", "CelExpression", null),
                Arguments.of("id >= 0", "CelExpression", null),
                Arguments.of("id <= 0", "CelExpression", null),

                // ----- logical combinators -----
                Arguments.of("a == 1 && b == 2", "CelExpression", null),
                Arguments.of("a == 1 || b == 2", "CelExpression", null),

                // ----- has() macro & in keyword -----
                Arguments.of("has(attributes.user_id)", "CelExpression", null),
                Arguments.of("type in ['a', 'b']", "CelExpression", null),

                // ----- attribute access shapes -----
                Arguments.of("attributes.score >= 3", "CelExpression", null),
                Arguments.of("attributes.score < 0", "CelExpression", null),
                Arguments.of("attributes['k'] == 'v'", "CelExpression", null),
                Arguments.of("type in ['_input_event']", "CelExpression", null),

                // ----- size / null / has compounds -----
                Arguments.of("size(attributes) > 0", "CelExpression", null),
                Arguments.of("category == 'premium' && score > 5", "CelExpression", null),
                Arguments.of("id != null", "CelExpression", null),
                Arguments.of("has(attributes.k)", "CelExpression", null),

                // ----- EventType-qualified comparisons -----
                Arguments.of("type == EventType.InputEvent", "CelExpression", null),
                Arguments.of("type == EventType.InputEvent && id > 0", "CelExpression", null));
    }

    @ParameterizedTest(name = "[{index}] classify(\"{0}\") -> {1}")
    @MethodSource("classifierCorpus")
    void astClassifierMatchesCorpus(String input, String expectedKind, String expectedEventType) {
        ParsedCondition pc = ParsedCondition.classify(input);

        switch (expectedKind) {
            case "TypeMatch":
                assertThat(pc)
                        .as("expected TypeMatch for input %s", input)
                        .isInstanceOf(TypeMatch.class);
                assertThat(pc.source())
                        .as("source must round-trip unchanged for input %s", input)
                        .isEqualTo(input);
                assertThat(((TypeMatch) pc).source())
                        .as("eventType disagreement on input %s", input)
                        .isEqualTo(expectedEventType);
                break;
            case "CelExpression":
                assertThat(pc)
                        .as("expected CelExpression for input %s", input)
                        .isInstanceOf(CelExpression.class);
                assertThat(pc.source())
                        .as("source must round-trip unchanged for input %s", input)
                        .isEqualTo(input);
                assertThat(expectedEventType)
                        .as(
                                "CelExpression cases must declare null expected_event_type (input %s)",
                                input)
                        .isNull();
                break;
            default:
                throw new IllegalStateException(
                        "Unknown expected_kind '" + expectedKind + "' for input " + input);
        }
    }

    // Regression: inputs the old regex classifier silently mis-routed as TypeMatch.

    static Stream<String> formerlySilentRegressions() {
        return Stream.of(
                "event.someFlag", // SELECT root
                "!event.flag", // CALL root, op `!_`
                "myBoolFunc()", // CALL root, function call
                "'OrderEvent'", // CONSTANT root, string literal
                "true", // CONSTANT root, boolean literal
                "event.size + 1 > 0" // CALL root, comparison
                );
    }

    @ParameterizedTest(name = "[{index}] {0} -> CelExpression (was silent TypeMatch)")
    @MethodSource("formerlySilentRegressions")
    void fixesSilentRegexMisclassifications(String input) {
        ParsedCondition pc = ParsedCondition.classify(input);
        assertThat(pc)
                .as(
                        "input %s must classify as CelExpression — was silently TypeMatch under the"
                                + " regex classifier",
                        input)
                .isInstanceOf(CelExpression.class);
        assertThat(pc.source()).isEqualTo(input);
    }

    // -- Failure mode: silent → loud --

    @Test
    void invalidCelThrows_atClassifyTime() {
        assertThatThrownBy(() -> ParsedCondition.classify("type =="))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid CEL expression");
        assertThatThrownBy(() -> ParsedCondition.classify("event.size +"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid CEL expression");
    }

    @Test
    void rejectsNullOrEmptyInput() {
        assertThatThrownBy(() -> ParsedCondition.classify(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-null");
        assertThatThrownBy(() -> ParsedCondition.classify(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-null");
    }

    // -- Reserved-keyword rejection --

    @Test
    void rejectsReservedKeywordAsEventTypeName() {
        // "type" is a framework-owned activation variable; using it as a bare event type must
        // fail loudly.
        assertThatThrownBy(() -> ParsedCondition.classify("type"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved keyword")
                .hasMessageContaining("@action(\"type == ");
    }

    @Test
    void idIsNotReservedAndClassifiesAsTypeMatch() {
        // "id" is user-overridable, not framework-reserved — a bare "id" entry is a legal event
        // type alias.
        ParsedCondition pc = ParsedCondition.classify("id");
        assertThat(pc).isInstanceOf(TypeMatch.class);
        assertThat(pc.source()).isEqualTo("id");
    }

    @Test
    void hasOnBareAttributeNameClassifiesAsCelExpression() {
        // Custom has() macro accepts bare attribute names at parse time.
        ParsedCondition pc = ParsedCondition.classify("has(score)");
        assertThat(pc).isInstanceOf(CelExpression.class);
        assertThat(pc.source()).isEqualTo("has(score)");
    }
}
