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

package org.apache.flink.agents.runtime.tracelog;

import org.apache.flink.agents.api.logger.TraceLogDetail;
import org.apache.flink.agents.api.logger.TraceLogScope;
import org.apache.flink.agents.api.trace.TraceContext;
import org.apache.flink.agents.api.trace.TraceRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceLogTargetResolverTest {
    @Test
    void defaultsIncludeOnlyEventsAtStandard() {
        TraceLogTargetResolver resolver = new TraceLogTargetResolver(Collections.emptyMap());
        assertThat(resolver.resolve("event", "some.Event")).isEqualTo(TraceLogDetail.STANDARD);
        assertThat(resolver.resolve("action", "process")).isNull();
        assertThat(resolver.resolve("tool", "lookup")).isNull();
        assertThat(new TraceLogTargetResolver(null).resolve("event", "some.Event"))
                .isEqualTo(TraceLogDetail.STANDARD);
    }

    @Test
    void allOffAndEmptyTargetsSelectNothingInsteadOfFallingBackToDefaults() {
        for (TraceLogTargetResolver resolver :
                List.of(resolver(), resolver(target("ALL", "OFF")))) {
            assertThat(resolver.resolve("event", "some.Event")).isNull();
            assertThat(resolver.resolve("action", "process")).isNull();
        }
    }

    @Test
    void allSelectsEveryEntity() {
        TraceLogTargetResolver resolver = resolver(target("ALL", "VERBOSE"));
        assertThat(resolver.resolve("event", "some.Event")).isEqualTo(TraceLogDetail.VERBOSE);
        assertThat(resolver.resolve("action", "process")).isEqualTo(TraceLogDetail.VERBOSE);
        assertThat(resolver.resolve("custom", "work")).isEqualTo(TraceLogDetail.VERBOSE);
    }

    @Test
    void allOffAllowsExplicitlySelectingOnlyTools() {
        TraceLogTargetResolver resolver =
                resolver(target("ALL", "OFF"), entity("tool", null, "VERBOSE"));
        assertThat(resolver.resolve("tool", "lookup")).isEqualTo(TraceLogDetail.VERBOSE);
        assertThat(resolver.resolve("event", "some.Event")).isNull();
        assertThat(resolver.resolve("action", "process")).isNull();
    }

    @Test
    void exactNameDoesNotImplicitlySelectDescendants() {
        TraceLogTargetResolver resolver = resolver(entity("event", "com.foo", "VERBOSE"));
        assertThat(resolver.resolve("event", "com.foo")).isEqualTo(TraceLogDetail.VERBOSE);
        assertThat(resolver.resolve("event", "com.foo.Event")).isNull();
        assertThat(resolver.resolve("event", "com.foobar.Event")).isNull();
    }

    @Test
    void explicitPrefixIncludesRootAndDescendantsButNotSimilarNames() {
        TraceLogTargetResolver resolver = resolver(entity("event", "com.foo.*", null));
        assertThat(resolver.resolve("event", "com.foo")).isEqualTo(TraceLogDetail.STANDARD);
        assertThat(resolver.resolve("event", "com.foo.Event")).isEqualTo(TraceLogDetail.STANDARD);
        assertThat(resolver.resolve("event", "com.foo.nested.Event"))
                .isEqualTo(TraceLogDetail.STANDARD);
        assertThat(resolver.resolve("event", "com.foobar.Event")).isNull();
    }

    @Test
    void exactThenLongestPrefixThenTypeTakePrecedenceIndependentOfOrder() {
        List<Map<String, Object>> targets =
                List.of(
                        entity("event", "com.foo.Special", "VERBOSE"),
                        entity("event", null, "STANDARD"),
                        entity("event", "com.*", "VERBOSE"),
                        entity("event", "com.foo.*", "STANDARD"));
        List<Map<String, Object>> reversed = new java.util.ArrayList<>(targets);
        Collections.reverse(reversed);
        for (List<Map<String, Object>> ordering : List.of(targets, reversed)) {
            TraceLogTargetResolver resolver =
                    new TraceLogTargetResolver(Map.of("trace-log.targets", ordering));
            assertThat(resolver.resolve("event", "com.foo.Special"))
                    .isEqualTo(TraceLogDetail.VERBOSE);
            assertThat(resolver.resolve("event", "com.foo.Other"))
                    .isEqualTo(TraceLogDetail.STANDARD);
            assertThat(resolver.resolve("event", "com.bar.Other"))
                    .isEqualTo(TraceLogDetail.VERBOSE);
            assertThat(resolver.resolve("event", "org.example.Other"))
                    .isEqualTo(TraceLogDetail.STANDARD);
        }
    }

    @Test
    void entityTypeThenEventPresetThenAllDetermineDetail() {
        List<Map<String, Object>> targets =
                List.of(
                        target("ALL", "VERBOSE"),
                        target("EVENT_ONLY", "STANDARD"),
                        entity("event", "special", "VERBOSE"));
        List<Map<String, Object>> reversed = new java.util.ArrayList<>(targets);
        Collections.reverse(reversed);
        for (List<Map<String, Object>> ordering : List.of(targets, reversed)) {
            TraceLogTargetResolver resolver =
                    new TraceLogTargetResolver(Map.of("trace-log.targets", ordering));
            assertThat(resolver.resolve("event", "some.Event")).isEqualTo(TraceLogDetail.STANDARD);
            assertThat(resolver.resolve("event", "special")).isEqualTo(TraceLogDetail.VERBOSE);
            assertThat(resolver.resolve("action", "process")).isEqualTo(TraceLogDetail.VERBOSE);
        }
        TraceLogTargetResolver type =
                resolver(target("EVENT_ONLY", "STANDARD"), entity("event", null, "VERBOSE"));
        assertThat(type.resolve("event", "some.Event")).isEqualTo(TraceLogDetail.VERBOSE);
    }

    @Test
    void unnamedEntitiesUseTypeOrPresetDetail() {
        TraceLogTargetResolver resolver =
                resolver(
                        entity("tool", "search", "STANDARD"),
                        entity("tool", null, "VERBOSE"),
                        entity("event", "com.foo.*", "VERBOSE"),
                        target("EVENT_ONLY", "STANDARD"),
                        target("ALL", "VERBOSE"));
        assertThat(resolver.resolve("tool", null)).isEqualTo(TraceLogDetail.VERBOSE);
        assertThat(resolver.resolve("event", null)).isEqualTo(TraceLogDetail.STANDARD);
        assertThat(resolver.resolve(null, null)).isEqualTo(TraceLogDetail.VERBOSE);
    }

    @Test
    void selectorsAreIndependentAcrossTypesAndPresetNamesDoNotCollideWithEntityTypes() {
        TraceLogTargetResolver resolver =
                resolver(
                        entity("event", "same", "STANDARD"),
                        entity("action", "same", "VERBOSE"),
                        entity("ALL", null, "VERBOSE"));
        assertThat(resolver.resolve("event", "same")).isEqualTo(TraceLogDetail.STANDARD);
        assertThat(resolver.resolve("action", "same")).isEqualTo(TraceLogDetail.VERBOSE);
        assertThat(resolver.resolve("tool", "same")).isNull();
        assertThat(resolver.resolve("ALL", "same")).isEqualTo(TraceLogDetail.VERBOSE);
    }

    @ParameterizedTest
    @MethodSource("omittedDetails")
    void omittedDetailInheritsPresetInsteadOfBroaderEntityTargets(
            List<Map<String, Object>> targets,
            String entityType,
            String entityName,
            TraceLogDetail expectedDetail) {
        List<Map<String, Object>> reversed = new java.util.ArrayList<>(targets);
        Collections.reverse(reversed);
        for (List<Map<String, Object>> ordering : List.of(targets, reversed)) {
            TraceLogTargetResolver resolver =
                    new TraceLogTargetResolver(Map.of("trace-log.targets", ordering));
            assertThat(resolver.resolve(entityType, entityName)).isEqualTo(expectedDetail);
        }
    }

    private static Stream<Arguments> omittedDetails() {
        return Stream.of(
                Arguments.of(
                        List.of(target("ALL", "VERBOSE"), entity("tool", "search", null)),
                        "tool",
                        "search",
                        TraceLogDetail.VERBOSE),
                Arguments.of(
                        List.of(target("ALL", "VERBOSE"), target("EVENT_ONLY", null)),
                        "event",
                        "some.Event",
                        TraceLogDetail.VERBOSE),
                Arguments.of(
                        List.of(target("ALL", null)), "tool", "search", TraceLogDetail.STANDARD),
                Arguments.of(
                        List.of(
                                target("ALL", "VERBOSE"),
                                target("EVENT_ONLY", "STANDARD"),
                                entity("event", "some.Event", null)),
                        "event",
                        "some.Event",
                        TraceLogDetail.STANDARD),
                Arguments.of(
                        List.of(
                                target("ALL", "VERBOSE"),
                                target("EVENT_ONLY", "STANDARD"),
                                entity("tool", "search", null)),
                        "tool",
                        "search",
                        TraceLogDetail.VERBOSE),
                Arguments.of(
                        List.of(target("EVENT_ONLY", "VERBOSE"), entity("tool", null, null)),
                        "tool",
                        "search",
                        TraceLogDetail.VERBOSE),
                Arguments.of(
                        List.of(target("EVENT_ONLY", null), entity("tool", null, null)),
                        "tool",
                        "search",
                        TraceLogDetail.STANDARD),
                Arguments.of(
                        List.of(entity("tool", "search", null)),
                        "tool",
                        "search",
                        TraceLogDetail.STANDARD),
                Arguments.of(
                        List.of(target("ALL", "OFF"), entity("tool", "search", null)),
                        "tool",
                        "search",
                        null),
                Arguments.of(
                        List.of(
                                target("ALL", "VERBOSE"),
                                target("EVENT_ONLY", "OFF"),
                                entity("event", "some.Event", null)),
                        "event",
                        "some.Event",
                        null),
                Arguments.of(
                        List.of(
                                entity("tool", null, "VERBOSE"),
                                entity("tool", "com.foo.*", "OFF"),
                                entity("tool", "com.foo.search", null)),
                        "tool",
                        "com.foo.search",
                        TraceLogDetail.STANDARD),
                Arguments.of(
                        List.of(
                                target("ALL", "OFF"),
                                entity("tool", null, "STANDARD"),
                                entity("tool", "com.foo.*", "VERBOSE"),
                                entity("tool", "com.foo.search", null)),
                        "tool",
                        "com.foo.search",
                        null));
    }

    @Test
    void localOffOverridesBroaderTargetsAndMoreSpecificTargetsCanEnableRecords() {
        List<Map<String, Object>> targets =
                List.of(
                        target("ALL", "VERBOSE"),
                        entity("tool", null, "OFF"),
                        entity("tool", "com.*", "STANDARD"),
                        entity("tool", "com.foo.*", "OFF"),
                        entity("tool", "com.foo.search", "VERBOSE"),
                        entity("tool", "com.bar.hidden", "OFF"));
        List<Map<String, Object>> reversed = new java.util.ArrayList<>(targets);
        Collections.reverse(reversed);
        for (List<Map<String, Object>> ordering : List.of(targets, reversed)) {
            TraceLogTargetResolver resolver =
                    new TraceLogTargetResolver(Map.of("trace-log.targets", ordering));
            assertThat(resolver.resolve("event", "some.Event")).isEqualTo(TraceLogDetail.VERBOSE);
            assertThat(resolver.resolve("tool", null)).isNull();
            assertThat(resolver.resolve("tool", "org.search")).isNull();
            assertThat(resolver.resolve("tool", "com.bar.search"))
                    .isEqualTo(TraceLogDetail.STANDARD);
            assertThat(resolver.resolve("tool", "com.foo.other")).isNull();
            assertThat(resolver.resolve("tool", "com.foo.search"))
                    .isEqualTo(TraceLogDetail.VERBOSE);
            assertThat(resolver.resolve("tool", "com.bar.hidden")).isNull();
            assertThat(resolver.resolve("tool", "com.foo.other"))
                    .as("Cached OFF detail must not fall back to a broader target")
                    .isNull();
        }
    }

    @Test
    void presetsAndDetailsAcceptEnumsAndCaseInsensitiveStrings() {
        TraceLogTargetResolver resolver =
                resolver(
                        target("event_only", "verbose"),
                        target(TraceLogScope.ALL, TraceLogDetail.OFF));
        assertThat(resolver.resolve("event", "some.Event")).isEqualTo(TraceLogDetail.VERBOSE);
        assertThat(resolver.resolve("tool", "lookup")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"OFF", "STANDARD", "VERBOSE"})
    void duplicateScopesWithSameEffectiveDetailAreAllowed(String detail) {
        List<Map<String, Object>> targets =
                List.of(
                        target("ALL", detail),
                        target("all", detail.toLowerCase(java.util.Locale.ROOT)),
                        target("EVENT_ONLY", null),
                        target("event_only", detail),
                        entity("tool", "com.foo.*", null),
                        entity("tool", "com.foo.*", TraceLogDetail.fromString(detail)));
        List<Map<String, Object>> reversed = new java.util.ArrayList<>(targets);
        Collections.reverse(reversed);
        TraceLogDetail expected = "OFF".equals(detail) ? null : TraceLogDetail.fromString(detail);
        for (List<Map<String, Object>> ordering : List.of(targets, reversed)) {
            TraceLogTargetResolver resolver =
                    new TraceLogTargetResolver(Map.of("trace-log.targets", ordering));
            assertThat(resolver.resolve("tool", "com.foo.search")).isEqualTo(expected);
            assertThat(resolver.resolve("event", "some.Event")).isEqualTo(expected);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"exact", "prefix", "type", "preset", "event-preset"})
    void conflictingDuplicateScopesFailAtStartup(String selector) {
        Map<String, Object> first;
        Map<String, Object> second;
        if ("preset".equals(selector)) {
            first = target("ALL", "VERBOSE");
            second = target("all", null);
        } else if ("event-preset".equals(selector)) {
            first = target("EVENT_ONLY", "STANDARD");
            second = target("EVENT_ONLY", null);
        } else {
            String name =
                    "type".equals(selector)
                            ? null
                            : "prefix".equals(selector) ? "com.foo.*" : "name";
            first = entity("tool", name, "STANDARD");
            second = entity("tool", name, null);
        }
        assertThatThrownBy(() -> resolver(first, second, target("ALL", "VERBOSE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trace-log.targets")
                .hasMessageContaining("conflicting");
    }

    @Test
    void selectionIgnoresStatusAndDoesNotEnableChildRecords() {
        TraceLogTargetResolver resolver = resolver(entity("action", "process", "VERBOSE"));
        TraceContext action =
                TraceContext.forAction(
                        TraceContext.forInputRun("key", "agent"), "process", "event-id");
        assertThat(
                        resolver.resolve(
                                TraceRecord.create(
                                        action, TraceRecord.Statuses.FAILED, null, null)))
                .isEqualTo(TraceLogDetail.VERBOSE);
        assertThat(resolver.resolve(TraceRecord.create(action, null, null, null)))
                .isEqualTo(TraceLogDetail.VERBOSE);
        assertThat(
                        resolver.resolve(
                                TraceRecord.create(
                                        action.childExecution("tool", "lookup", null),
                                        TraceRecord.Statuses.STARTED,
                                        null,
                                        null)))
                .isNull();
    }

    @ParameterizedTest
    @MethodSource("malformedConfigs")
    void malformedConfigFailsAtStartup(Map<String, Object> config) {
        assertThatThrownBy(() -> new TraceLogTargetResolver(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trace-log.targets");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {42})
    void nonStringScopeFieldsAreRejectedWithConfigurationError(Object fieldName) {
        Map<Object, Object> scope = new HashMap<>();
        scope.put("entityType", "tool");
        scope.put(fieldName, "unexpected");

        assertThatThrownBy(() -> resolver(target(scope, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trace-log.targets")
                .hasMessageContaining("unknown field '" + fieldName + "'");
    }

    private static Stream<Map<String, Object>> malformedConfigs() {
        return Stream.of(
                Map.of("trace-log.targets", "not-a-list"),
                Map.of("trace-log.targets.scope", "ALL"),
                Map.of("trace-log.targets", List.of("not-an-object")),
                config(Map.of("detail", "STANDARD")),
                config(target("INVALID_SCOPE", null)),
                config(target(true, null)),
                config(target("OFF", null)),
                config(target("ALL", 42)),
                config(Map.of("scope", "ALL", "level", "VERBOSE")),
                config(target(Map.of("entityName", "name"), null)),
                config(entity("", null, null)),
                config(entity("   ", null, null)),
                config(target(Map.of("entityType", 42), null)),
                config(entity("tool", "", null)),
                config(target(Map.of("entityType", "tool", "entityName", true), null)),
                config(entity("tool", "com.*.foo", null)),
                config(entity("tool", "com.foo*", null)),
                config(entity("tool", ".*", null)),
                config(target(Map.of("entityType", "tool", "eventType", "name"), null)),
                Collections.singletonMap("trace-log.targets", null));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "event-log.level",
                "event-log.trace.enabled",
                "event-log.type.my.Event.level",
                "event-log.standard.max-string-length",
                "eventLoggerType",
                "baseLogDir",
                "prettyPrint"
            })
    void legacyKeysCannotBeMixedWithNewConfig(String legacyKey) {
        Map<String, Object> config = new HashMap<>(config(target("ALL", "STANDARD")));
        config.put(legacyKey, "legacy-value");
        assertThatThrownBy(() -> new TraceLogTargetResolver(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(legacyKey)
                .hasMessageContaining("trace-log.*");
    }

    private static Map<String, Object> config(Map<String, Object> target) {
        return Map.of("trace-log.targets", List.of(target));
    }

    @SafeVarargs
    private static TraceLogTargetResolver resolver(Map<String, Object>... targets) {
        return new TraceLogTargetResolver(Map.of("trace-log.targets", List.of(targets)));
    }

    private static Map<String, Object> target(Object scope, Object detail) {
        Map<String, Object> target = new HashMap<>();
        target.put("scope", scope);
        if (detail != null) target.put("detail", detail);
        return target;
    }

    private static Map<String, Object> entity(String type, String name, Object detail) {
        Map<String, Object> scope = new HashMap<>();
        scope.put("entityType", type);
        if (name != null) scope.put("entityName", name);
        return target(scope, detail);
    }
}
