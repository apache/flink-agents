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

package org.apache.flink.agents.api.logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link TraceLogTarget}. */
class TraceLogTargetTest {

    @ParameterizedTest
    @MethodSource("categories")
    void factoriesAndCategorySelectorsUseTheSameSemantics(
            String type,
            Supplier<TraceLogTarget> all,
            Function<TraceLogLevel, TraceLogTarget> allWithLevel,
            Function<String, TraceLogTarget> named,
            BiFunction<String, TraceLogLevel, TraceLogTarget> namedWithLevel) {
        assertThat(all.get())
                .isEqualTo(TraceLogTarget.entity(type))
                .isEqualTo(TraceLogTarget.fromConfig(Map.of("scope", Map.of(type, "*"))));
        assertThat(allWithLevel.apply(null)).isEqualTo(all.get());
        assertThat(allWithLevel.apply(TraceLogLevel.OFF))
                .isEqualTo(TraceLogTarget.entity(type, null, TraceLogLevel.OFF));
        assertThat(named.apply("search"))
                .isEqualTo(TraceLogTarget.entity(type, "search"))
                .isEqualTo(TraceLogTarget.fromConfig(Map.of("scope", Map.of(type, "search"))));
        assertThat(namedWithLevel.apply("search", null)).isEqualTo(named.apply("search"));
        assertThat(namedWithLevel.apply("com.example.*", TraceLogLevel.VERBOSE))
                .isEqualTo(
                        TraceLogTarget.fromConfig(
                                Map.of(
                                        "scope",
                                        Map.of(type, "com.example.*"),
                                        "level",
                                        "verbose")));
    }

    private static Stream<Arguments> categories() {
        return Stream.of(
                category(
                        "event",
                        TraceLogTarget::events,
                        TraceLogTarget::events,
                        TraceLogTarget::event,
                        TraceLogTarget::event),
                category(
                        "action",
                        TraceLogTarget::actions,
                        TraceLogTarget::actions,
                        TraceLogTarget::action,
                        TraceLogTarget::action),
                category(
                        "tool",
                        TraceLogTarget::tools,
                        TraceLogTarget::tools,
                        TraceLogTarget::tool,
                        TraceLogTarget::tool),
                category(
                        "llm",
                        TraceLogTarget::llms,
                        TraceLogTarget::llms,
                        TraceLogTarget::llm,
                        TraceLogTarget::llm),
                category(
                        "parser",
                        TraceLogTarget::parsers,
                        TraceLogTarget::parsers,
                        TraceLogTarget::parser,
                        TraceLogTarget::parser),
                category(
                        "subagent",
                        TraceLogTarget::subagents,
                        TraceLogTarget::subagents,
                        TraceLogTarget::subagent,
                        TraceLogTarget::subagent));
    }

    private static Arguments category(
            String type,
            Supplier<TraceLogTarget> all,
            Function<TraceLogLevel, TraceLogTarget> allWithLevel,
            Function<String, TraceLogTarget> named,
            BiFunction<String, TraceLogLevel, TraceLogTarget> namedWithLevel) {
        return Arguments.of(type, all, allWithLevel, named, namedWithLevel);
    }

    @Test
    void omittedAndNullLevelsStayDistinctFromExplicitStandard() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("scope", "all");
        config.put("level", null);

        assertThat(TraceLogTarget.all())
                .isEqualTo(TraceLogTarget.all(null))
                .isEqualTo(TraceLogTarget.fromConfig(config))
                .isNotEqualTo(TraceLogTarget.all(TraceLogLevel.STANDARD));
        assertThat(TraceLogTarget.all().toConfig()).isEqualTo(Map.of("scope", "ALL"));
        assertThat(TraceLogTarget.all(TraceLogLevel.STANDARD).toConfig())
                .isEqualTo(Map.of("scope", "ALL", "level", "STANDARD"));
    }

    @Test
    void allEventRepresentationsHaveOneCanonicalIdentity() {
        TraceLogTarget target = TraceLogTarget.events(TraceLogLevel.VERBOSE);
        TraceLogTarget generic =
                TraceLogTarget.fromConfig(
                        Map.of(
                                "scope",
                                Map.of("entityType", "event"),
                                "level",
                                TraceLogLevel.VERBOSE));

        assertThat(target)
                .isEqualTo(generic)
                .isEqualTo(
                        TraceLogTarget.fromConfig(
                                Map.of("scope", TraceLogScope.EVENT_ONLY, "level", "VERBOSE")))
                .isEqualTo(
                        TraceLogTarget.fromConfig(
                                Map.of("scope", "event_only", "level", "verbose")));
        assertThat(target.hashCode()).isEqualTo(generic.hashCode());
        assertThat(target.toConfig()).isEqualTo(Map.of("scope", "EVENT_ONLY", "level", "VERBOSE"));
    }

    @Test
    void customEntityTypesRemainCaseSensitiveAndSupportNamespaceSelection() {
        TraceLogTarget target =
                TraceLogTarget.entity("Retriever", "com.example.*", TraceLogLevel.OFF);

        assertThat(target.toConfig())
                .isEqualTo(
                        Map.of(
                                "scope",
                                Map.of("entityType", "Retriever", "entityName", "com.example.*"),
                                "level",
                                "OFF"));
        assertThat(target)
                .isNotEqualTo(
                        TraceLogTarget.entity("retriever", "com.example.*", TraceLogLevel.OFF));
        assertThat(TraceLogTarget.entity("Retriever", null).toConfig())
                .isEqualTo(Map.of("scope", Map.of("entityType", "Retriever")));
    }

    @Test
    void decodedTargetsAreIndependentOfMutableInputAndExposeNoMutableMaps() {
        Map<String, Object> scope =
                new LinkedHashMap<>(Map.of("entityType", "tool", "entityName", "search"));
        Map<String, Object> config =
                new LinkedHashMap<>(Map.of("scope", scope, "level", "verbose"));
        List<Object> input = new ArrayList<>(List.of(config));
        List<TraceLogTarget> targets = TraceLogTarget.parseTargets(input);
        TraceLogTarget target = targets.get(0);

        scope.put("entityName", "changed");
        config.put("level", "OFF");
        input.clear();

        assertThat(target).isEqualTo(TraceLogTarget.tool("search", TraceLogLevel.VERBOSE));
        assertThat(TraceLogTarget.fromConfig(target)).isEqualTo(target);
        assertThatThrownBy(targets::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> target.toConfig().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((Map<?, ?>) target.toConfig().get("scope")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidScopes")
    void rejectsInvalidOrAmbiguousSelectors(Object scope, String message) {
        assertThatThrownBy(() -> TraceLogTarget.fromConfig(Map.of("scope", scope)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    private static Stream<Arguments> invalidScopes() {
        return Stream.of(
                Arguments.of(Map.of(), "exactly one category selector"),
                Arguments.of(
                        Map.of("event", "Order", "tool", "search"),
                        "exactly one category selector"),
                Arguments.of(
                        Map.of("entityType", "event", "event", "Order"), "unknown field 'event'"),
                Arguments.of(Map.of("custom", "search"), "unknown field 'custom'"),
                Arguments.of(Map.of("tool", " "), "tool must be a non-empty string"),
                Arguments.of(Map.of("event", List.of("Order")), "event must be a non-empty string"),
                Arguments.of(Map.of("entityType", " "), "entityType must be a non-empty string"),
                Arguments.of(
                        Map.of("entityName", "search"), "entityType must be a non-empty string"),
                Arguments.of(
                        Map.of("entityType", "tool", "entityName", " "),
                        "entityName must be a non-empty string"),
                Arguments.of(
                        Map.of("entityType", "tool", "entityName", "*"),
                        "entityName must be an exact name"),
                Arguments.of(Map.of("tool", ".*"), "entityName must be an exact name"),
                Arguments.of(Map.of("tool", "com.*.*"), "entityName must be an exact name"),
                Arguments.of(Map.of("tool", "search*"), "entityName must be an exact name"),
                Arguments.of(Map.of(1, "search"), "unknown field '1'"),
                Arguments.of(42, "scope must be EVENT_ONLY, ALL, or an object"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {42})
    void rejectsNonStringScopeFields(Object fieldName) {
        Map<Object, Object> scope = new LinkedHashMap<>();
        scope.put("entityType", "tool");
        scope.put(fieldName, "unexpected");

        assertThatThrownBy(() -> TraceLogTarget.fromConfig(Map.of("scope", scope)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown field '" + fieldName + "'");
    }

    @Test
    void rejectsInvalidFactoryNamesAndTypes() {
        assertThatThrownBy(() -> TraceLogTarget.event(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityName must be a non-empty string");
        assertThatThrownBy(() -> TraceLogTarget.entity(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityType must be a non-empty string");
        assertThatThrownBy(() -> TraceLogTarget.tool("*"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityName must be an exact name");
    }

    @Test
    void rejectsUnknownTargetFieldsAndNonStringLevels() {
        assertThatThrownBy(() -> TraceLogTarget.fromConfig(Map.of("scope", "ALL", "unknown", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown field 'unknown'");
        assertThatThrownBy(() -> TraceLogTarget.fromConfig(Map.of("scope", "ALL", "level", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("level must be OFF, STANDARD, or VERBOSE");
        assertThatThrownBy(
                        () -> TraceLogTarget.fromConfig(Map.of("scope", "ALL", "level", "unknown")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid TraceLogLevel");
    }

    @Test
    void listValidationReportsTargetIndexAndPreservesTheCause() {
        assertThat(
                        TraceLogTarget.parseTargets(
                                List.of(TraceLogTarget.events(), Map.of("scope", "all"))))
                .containsExactly(TraceLogTarget.events(), TraceLogTarget.all());
        assertThatThrownBy(() -> TraceLogTarget.parseTargets(Map.of("scope", "ALL")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid trace-log.targets: must be a list of targets");
        assertThatThrownBy(
                        () -> TraceLogTarget.parseTargets(List.of(TraceLogTarget.events(), "ALL")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid trace-log.targets: target 1: must be an object")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }
}
