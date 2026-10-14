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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.api.logger.TraceLogLevel;
import org.apache.flink.agents.api.logger.TraceLogTarget;
import org.apache.flink.agents.api.trace.TraceContext;
import org.apache.flink.agents.api.trace.TraceRecord;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceLogTargetResolverTest {
    @Test
    void defaultsIncludeOnlyEventsAtStandard() {
        TraceLogTargetResolver resolver = new TraceLogTargetResolver(Collections.emptyMap());
        assertThat(resolver.resolve("event", "some.Event")).isEqualTo(TraceLogLevel.STANDARD);
        assertThat(resolver.resolve("action", "process")).isNull();
        assertThat(resolver.resolve("tool", "lookup")).isNull();
        assertThat(new TraceLogTargetResolver(null).resolve("event", "some.Event"))
                .isEqualTo(TraceLogLevel.STANDARD);
    }

    @Test
    void allOffAndEmptyTargetsSelectNothingInsteadOfFallingBackToDefaults() {
        for (TraceLogTargetResolver resolver :
                List.of(
                        resolver(),
                        resolver(target("ALL", "OFF")),
                        new TraceLogTargetResolver(
                                new AgentConfiguration(
                                                Map.of("trace-log", Map.of("targets", List.of())))
                                        .getConfData()))) {
            assertThat(resolver.resolve("event", "some.Event")).isNull();
            assertThat(resolver.resolve("action", "process")).isNull();
        }
    }

    @Test
    void allSelectsEveryEntity() {
        TraceLogTargetResolver resolver = resolver(target("ALL", "VERBOSE"));
        assertThat(resolver.resolve("event", "some.Event")).isEqualTo(TraceLogLevel.VERBOSE);
        assertThat(resolver.resolve("action", "process")).isEqualTo(TraceLogLevel.VERBOSE);
        assertThat(resolver.resolve("custom", "work")).isEqualTo(TraceLogLevel.VERBOSE);
    }

    @Test
    void allOffAllowsExplicitlySelectingOnlyTools() {
        TraceLogTargetResolver resolver =
                resolver(target("ALL", "OFF"), entity("tool", null, "VERBOSE"));
        assertThat(resolver.resolve("tool", "lookup")).isEqualTo(TraceLogLevel.VERBOSE);
        assertThat(resolver.resolve("event", "some.Event")).isNull();
        assertThat(resolver.resolve("action", "process")).isNull();
    }

    @Test
    void exactNameDoesNotImplicitlySelectDescendants() {
        TraceLogTargetResolver resolver = resolver(entity("event", "com.foo", "VERBOSE"));
        assertThat(resolver.resolve("event", "com.foo")).isEqualTo(TraceLogLevel.VERBOSE);
        assertThat(resolver.resolve("event", "com.foo.Event")).isNull();
        assertThat(resolver.resolve("event", "com.foobar.Event")).isNull();
    }

    @Test
    void explicitPrefixIncludesRootAndDescendantsButNotSimilarNames() {
        TraceLogTargetResolver resolver = resolver(entity("event", "com.foo.*", null));
        assertThat(resolver.resolve("event", "com.foo")).isEqualTo(TraceLogLevel.STANDARD);
        assertThat(resolver.resolve("event", "com.foo.Event")).isEqualTo(TraceLogLevel.STANDARD);
        assertThat(resolver.resolve("event", "com.foo.nested.Event"))
                .isEqualTo(TraceLogLevel.STANDARD);
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
                    .isEqualTo(TraceLogLevel.VERBOSE);
            assertThat(resolver.resolve("event", "com.foo.Other"))
                    .isEqualTo(TraceLogLevel.STANDARD);
            assertThat(resolver.resolve("event", "com.bar.Other")).isEqualTo(TraceLogLevel.VERBOSE);
            assertThat(resolver.resolve("event", "org.example.Other"))
                    .isEqualTo(TraceLogLevel.STANDARD);
        }
    }

    @Test
    void eventTypeAliasOverridesAllAndAllowsMoreSpecificLevels() {
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
            assertThat(resolver.resolve("event", "some.Event")).isEqualTo(TraceLogLevel.STANDARD);
            assertThat(resolver.resolve("event", "special")).isEqualTo(TraceLogLevel.VERBOSE);
            assertThat(resolver.resolve("action", "process")).isEqualTo(TraceLogLevel.VERBOSE);
        }
    }

    @Test
    void unnamedEntitiesUseTypeOrAllLevel() {
        TraceLogTargetResolver resolver =
                resolver(
                        entity("tool", "search", "STANDARD"),
                        entity("tool", null, "VERBOSE"),
                        entity("event", "com.foo.*", "VERBOSE"),
                        target("EVENT_ONLY", "STANDARD"),
                        target("ALL", "VERBOSE"));
        assertThat(resolver.resolve("tool", null)).isEqualTo(TraceLogLevel.VERBOSE);
        assertThat(resolver.resolve("event", null)).isEqualTo(TraceLogLevel.STANDARD);
        assertThat(resolver.resolve(null, null)).isEqualTo(TraceLogLevel.VERBOSE);
    }

    @Test
    void selectorsAreIndependentAcrossTypesAndPresetNamesDoNotCollideWithEntityTypes() {
        TraceLogTargetResolver resolver =
                resolver(
                        entity("event", "same", "STANDARD"),
                        entity("action", "same", "VERBOSE"),
                        entity("ALL", null, "VERBOSE"));
        assertThat(resolver.resolve("event", "same")).isEqualTo(TraceLogLevel.STANDARD);
        assertThat(resolver.resolve("action", "same")).isEqualTo(TraceLogLevel.VERBOSE);
        assertThat(resolver.resolve("tool", "same")).isNull();
        assertThat(resolver.resolve("ALL", "same")).isEqualTo(TraceLogLevel.VERBOSE);
    }

    @ParameterizedTest
    @MethodSource("omittedLevels")
    void omittedLevelInheritsFromTheMatchingChain(
            List<Map<String, Object>> targets,
            String entityType,
            String entityName,
            TraceLogLevel expectedLevel) {
        List<Map<String, Object>> reversed = new java.util.ArrayList<>(targets);
        Collections.reverse(reversed);
        for (List<Map<String, Object>> ordering : List.of(targets, reversed)) {
            TraceLogTargetResolver resolver =
                    new TraceLogTargetResolver(Map.of("trace-log.targets", ordering));
            assertThat(resolver.resolve(entityType, entityName)).isEqualTo(expectedLevel);
        }
    }

    private static Stream<Arguments> omittedLevels() {
        return Stream.of(
                Arguments.of(
                        List.of(target("ALL", "VERBOSE"), target("EVENT_ONLY", null)),
                        "event",
                        "some.Event",
                        TraceLogLevel.VERBOSE),
                Arguments.of(
                        List.of(target("ALL", null)), "tool", "search", TraceLogLevel.STANDARD),
                Arguments.of(
                        List.of(
                                target("ALL", "VERBOSE"),
                                target("EVENT_ONLY", "STANDARD"),
                                entity("event", "some.Event", null)),
                        "event",
                        "some.Event",
                        TraceLogLevel.STANDARD),
                Arguments.of(
                        List.of(
                                target("ALL", "VERBOSE"),
                                target("EVENT_ONLY", "STANDARD"),
                                entity("tool", "search", null)),
                        "tool",
                        "search",
                        TraceLogLevel.VERBOSE),
                Arguments.of(
                        List.of(target("EVENT_ONLY", "VERBOSE"), entity("tool", null, null)),
                        "tool",
                        "search",
                        TraceLogLevel.STANDARD),
                Arguments.of(
                        List.of(entity("tool", "search", null)),
                        "tool",
                        "search",
                        TraceLogLevel.STANDARD),
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
                        null),
                Arguments.of(
                        List.of(
                                target("ALL", "OFF"),
                                entity("tool", null, "STANDARD"),
                                entity("tool", "com.foo.*", "VERBOSE"),
                                entity("tool", "com.foo.search", null)),
                        "tool",
                        "com.foo.search",
                        TraceLogLevel.VERBOSE),
                Arguments.of(
                        List.of(
                                target("ALL", "OFF"),
                                entity("tool", null, "VERBOSE"),
                                entity("tool", "com.*", null),
                                entity("tool", "com.foo.*", null),
                                entity("tool", "com.foo.search", null)),
                        "tool",
                        "com.foo.search",
                        TraceLogLevel.VERBOSE),
                Arguments.of(
                        List.of(
                                entity("tool", "com.*", "VERBOSE"),
                                entity("tool", "com.foo.*", null),
                                entity("tool", "com.foo.search", null)),
                        "tool",
                        "com.foo.search",
                        TraceLogLevel.VERBOSE),
                Arguments.of(
                        List.of(
                                entity("tool", "com.*", "OFF"),
                                entity("tool", "com.foo.*", null),
                                entity("tool", "com.foo.search", null)),
                        "tool",
                        "com.foo.search",
                        null),
                Arguments.of(
                        List.of(
                                entity("tool", null, null),
                                entity("tool", "com.*", null),
                                entity("tool", "com.foo.*", null),
                                entity("tool", "com.foo.search", null)),
                        "tool",
                        "com.foo.search",
                        TraceLogLevel.STANDARD),
                Arguments.of(
                        List.of(
                                entity("tool", "com.*", "VERBOSE"),
                                entity("tool", "com.foo", "OFF"),
                                entity("tool", "com.foo.*", null)),
                        "tool",
                        "com.foo.child",
                        TraceLogLevel.VERBOSE),
                Arguments.of(
                        List.of(
                                entity("tool", "com.foo.*", "OFF"),
                                entity("tool", "com.foobar.search", null)),
                        "tool",
                        "com.foobar.search",
                        TraceLogLevel.STANDARD),
                Arguments.of(
                        List.of(
                                entity("tool", "com.foo.*", null),
                                entity("tool", "com.foo.search", null)),
                        "tool",
                        "org.search",
                        null),
                Arguments.of(List.of(entity("tool", "com.foo.*", null)), "tool", null, null),
                Arguments.of(
                        List.of(
                                target("ALL", "VERBOSE"),
                                entity("tool", "com.foo.*", "OFF"),
                                entity("event", "com.foo.search", null)),
                        "event",
                        "com.foo.search",
                        TraceLogLevel.VERBOSE));
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
            assertThat(resolver.resolve("event", "some.Event")).isEqualTo(TraceLogLevel.VERBOSE);
            assertThat(resolver.resolve("tool", null)).isNull();
            assertThat(resolver.resolve("tool", "org.search")).isNull();
            assertThat(resolver.resolve("tool", "com.bar.search"))
                    .isEqualTo(TraceLogLevel.STANDARD);
            assertThat(resolver.resolve("tool", "com.foo.other")).isNull();
            assertThat(resolver.resolve("tool", "com.foo.search")).isEqualTo(TraceLogLevel.VERBOSE);
            assertThat(resolver.resolve("tool", "com.bar.hidden")).isNull();
        }
    }

    @ParameterizedTest
    @MethodSource("duplicateScopes")
    void duplicateScopesUseExplicitLevelRegardlessOfOrder(Object firstScope, Object secondScope) {
        for (TraceLogLevel level : List.of(TraceLogLevel.OFF, TraceLogLevel.VERBOSE)) {
            Map<String, Object> explicit = target(firstScope, level);
            Map<String, Object> omitted = target(secondScope, null);
            TraceLogLevel expected = level == TraceLogLevel.OFF ? null : level;
            for (TraceLogTargetResolver resolver :
                    List.of(
                            resolver(explicit, omitted),
                            resolver(omitted, explicit),
                            resolver(
                                    explicit,
                                    target(
                                            secondScope,
                                            level.name().toLowerCase(java.util.Locale.ROOT))))) {
                assertThat(resolver.resolve("event", "com.foo.Event")).isEqualTo(expected);
            }
        }
        assertThat(
                        resolver(target(firstScope, null), target(secondScope, null))
                                .resolve("event", "com.foo.Event"))
                .isEqualTo(TraceLogLevel.STANDARD);
    }

    @ParameterizedTest
    @MethodSource("duplicateScopes")
    void conflictingExplicitDuplicateScopesFailAtStartup(Object firstScope, Object secondScope) {
        for (TraceLogLevel first : TraceLogLevel.values()) {
            for (TraceLogLevel second : TraceLogLevel.values()) {
                if (first == second) {
                    continue;
                }
                assertThatThrownBy(
                                () ->
                                        resolver(
                                                target(firstScope, first),
                                                target(secondScope, second)))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("trace-log.targets")
                        .hasMessageContaining("conflicting");
            }
        }
    }

    private static Stream<Arguments> duplicateScopes() {
        Map<String, String> eventType = Map.of("entityType", "event");
        Map<String, String> prefix = Map.of("entityType", "event", "entityName", "com.foo.*");
        Map<String, String> exact = Map.of("entityType", "event", "entityName", "com.foo.Event");
        return Stream.of(
                Arguments.of("ALL", "ALL"),
                Arguments.of("EVENT_ONLY", eventType),
                Arguments.of(prefix, prefix),
                Arguments.of(exact, exact));
    }

    @Test
    void mergedExplicitLevelIsInheritedByMoreSpecificScopes() {
        List<Map<String, Object>> targets =
                List.of(
                        target("ALL", "OFF"),
                        target("EVENT_ONLY", null),
                        entity("event", null, "VERBOSE"),
                        entity("event", "com.*", null),
                        entity("event", "com.foo.*", null),
                        entity("event", "com.foo.Event", null));
        List<Map<String, Object>> reversed = new java.util.ArrayList<>(targets);
        Collections.reverse(reversed);
        for (List<Map<String, Object>> ordering : List.of(targets, reversed)) {
            TraceLogTargetResolver resolver =
                    new TraceLogTargetResolver(Map.of("trace-log.targets", ordering));
            assertThat(resolver.resolve("event", "com.foo.Event")).isEqualTo(TraceLogLevel.VERBOSE);
            assertThat(resolver.resolve("event", "com.foo.Other")).isEqualTo(TraceLogLevel.VERBOSE);
            assertThat(resolver.resolve("tool", "com.foo.Event")).isNull();
        }
    }

    @Test
    void typedTargetsAndYamlKeepTheSameInheritanceThroughPlanJson() throws Exception {
        AgentConfiguration typed = new AgentConfiguration();
        typed.set(
                AgentConfigOptions.TRACE_LOG_TARGETS,
                List.of(
                        TraceLogTarget.events(TraceLogLevel.OFF),
                        TraceLogTarget.tools(TraceLogLevel.VERBOSE),
                        TraceLogTarget.tool("com.foo.*", TraceLogLevel.OFF),
                        TraceLogTarget.tool("com.foo.search"),
                        TraceLogTarget.tool("com.foo.visible", TraceLogLevel.STANDARD),
                        TraceLogTarget.entity("custom", null, TraceLogLevel.VERBOSE),
                        TraceLogTarget.entity("custom", "step")));
        String yaml =
                "trace-log:\n"
                        + "  targets:\n"
                        + "    - scope: EVENT_ONLY\n      level: \"OFF\"\n"
                        + "    - scope: {tool: \"*\"}\n      level: VERBOSE\n"
                        + "    - scope: {tool: com.foo.*}\n      level: \"OFF\"\n"
                        + "    - scope: {tool: com.foo.search}\n"
                        + "    - scope: {tool: com.foo.visible}\n      level: STANDARD\n"
                        + "    - scope: {entityType: custom}\n      level: VERBOSE\n"
                        + "    - scope: {entityType: custom, entityName: step}\n";
        AgentConfiguration fromYaml =
                new AgentConfiguration(
                        new ObjectMapper(new YAMLFactory())
                                .readValue(yaml, new TypeReference<Map<String, Object>>() {}));
        assertThat(fromYaml.get(AgentConfigOptions.TRACE_LOG_TARGETS))
                .isEqualTo(typed.get(AgentConfigOptions.TRACE_LOG_TARGETS));
        ObjectMapper mapper = new ObjectMapper();
        for (AgentConfiguration config : List.of(typed, fromYaml)) {
            AgentPlan restored =
                    mapper.readValue(
                            mapper.writeValueAsString(new AgentPlan(Map.of(), Map.of(), config)),
                            AgentPlan.class);
            for (Map<String, Object> data :
                    List.of(
                            config.getConfData(),
                            restored.getConfigData(),
                            Map.<String, Object>of(
                                    "trace-log.targets",
                                    config.get(AgentConfigOptions.TRACE_LOG_TARGETS)))) {
                TraceLogTargetResolver resolver = new TraceLogTargetResolver(data);
                assertThat(resolver.resolve("event", "OrderCreated")).isNull();
                assertThat(resolver.resolve("tool", "search")).isEqualTo(TraceLogLevel.VERBOSE);
                assertThat(resolver.resolve("tool", "com.foo.search")).isNull();
                assertThat(resolver.resolve("tool", "com.foo.visible"))
                        .isEqualTo(TraceLogLevel.STANDARD);
                assertThat(resolver.resolve("custom", "step")).isEqualTo(TraceLogLevel.VERBOSE);
                assertThat(resolver.resolve("action", "process")).isNull();
            }
        }
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
                .isEqualTo(TraceLogLevel.VERBOSE);
        assertThat(resolver.resolve(TraceRecord.create(action, null, null, null)))
                .isEqualTo(TraceLogLevel.VERBOSE);
        assertThat(
                        resolver.resolve(
                                TraceRecord.create(
                                        action.childExecution(
                                                UUID.randomUUID().toString(),
                                                "tool",
                                                "lookup",
                                                null),
                                        TraceRecord.Statuses.STARTED,
                                        null,
                                        null)))
                .isNull();
    }

    @ParameterizedTest
    @MethodSource("malformedConfigs")
    void malformedConfigFailsAtStartup(Map<String, Object> config) {
        assertThatThrownBy(
                        () ->
                                new TraceLogTargetResolver(
                                        new AgentConfiguration(config).getConfData()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trace-log.targets");
    }

    private static Stream<Map<String, Object>> malformedConfigs() {
        return Stream.of(
                Map.of("trace-log.targets", "not-a-list"),
                Map.of("trace-log.targets", Map.of()),
                Map.of("trace-log", Map.of("targets", Map.of())),
                Map.of("trace-log.targets.scope", "ALL"),
                Map.of("trace-log.targets", List.of("not-an-object")),
                config(target("ALL", 42)),
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

    private static Map<String, Object> target(Object scope, Object level) {
        Map<String, Object> target = new HashMap<>();
        target.put("scope", scope);
        if (level != null) target.put("level", level);
        return target;
    }

    private static Map<String, Object> entity(String type, String name, Object level) {
        Map<String, Object> scope = new HashMap<>();
        scope.put("entityType", type);
        if (name != null) scope.put("entityName", name);
        return target(scope, level);
    }
}
