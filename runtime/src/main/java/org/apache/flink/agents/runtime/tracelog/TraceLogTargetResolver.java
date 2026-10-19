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

import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.api.logger.TraceLogLevel;
import org.apache.flink.agents.api.logger.TraceLogScope;
import org.apache.flink.agents.api.logger.TraceLogTarget;
import org.apache.flink.agents.api.trace.TraceContext;
import org.apache.flink.agents.api.trace.TraceRecord;

import javax.annotation.Nullable;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Selects records and resolves their recording level from trace log targets. */
final class TraceLogTargetResolver {
    private static final Set<String> LEGACY_OUTPUT_KEYS =
            Set.of("eventLoggerType", "baseLogDir", "prettyPrint");

    @Nullable private final TraceLogLevel allLevel;
    private final Map<String, TypeTargets> typeTargets = new LinkedHashMap<>();
    private final List<Target> targets = new ArrayList<>();

    /** Reads and validates targets before logging starts. */
    TraceLogTargetResolver(@Nullable Map<String, Object> confData) {
        Map<String, Object> data = confData == null ? Collections.emptyMap() : confData;
        rejectLegacySettings(data);
        String targetsKey = AgentConfigOptions.TRACE_LOG_TARGETS.getKey();
        if (data.keySet().stream().anyMatch(key -> key.startsWith(targetsKey + "."))) {
            throw invalidTargets("must be a list of targets, not a nested object");
        }
        Object configuredTargets =
                data.getOrDefault(
                        targetsKey, AgentConfigOptions.TRACE_LOG_TARGETS.getDefaultValue());
        Map<Object, Target> selectors = new LinkedHashMap<>();
        for (TraceLogTarget configuredTarget : TraceLogTarget.parseTargets(configuredTargets)) {
            Target target = fromConfiguredTarget(configuredTarget);
            Object selector = target.selector();
            Target previous = selectors.putIfAbsent(selector, target);
            if (previous != null && target.level != null) {
                if (previous.level != null && previous.level != target.level) {
                    throw invalidTargets("conflicting levels for scope " + selector);
                }
                selectors.put(selector, target);
            }
        }
        Target all = selectors.get(TraceLogScope.ALL);
        allLevel = all == null ? null : all.levelOr(TraceLogLevel.STANDARD);

        // Compile broader scopes first so runtime lookups need no inheritance traversal.
        List<Target> configured = new ArrayList<>(selectors.values());
        configured.sort(
                Comparator.comparingInt(
                                (Target target) ->
                                        target.entityName == null
                                                ? 0
                                                : target.entityName.endsWith(".*") ? 1 : 2)
                        .thenComparingInt(
                                target ->
                                        target.entityName == null
                                                ? 0
                                                : target.entityName.length()));
        for (Target target : configured) {
            TraceLogLevel level;
            if (target.preset == TraceLogScope.ALL) {
                level = allLevel;
            } else {
                TypeTargets overrides =
                        typeTargets.computeIfAbsent(
                                target.entityType, type -> new TypeTargets(allLevel));
                TraceLogLevel inherited = overrides.resolve(target.entityName);
                level = target.levelOr(inherited == null ? TraceLogLevel.STANDARD : inherited);
                overrides.add(target, level);
            }
            targets.add(new Target(target.preset, target.entityType, target.entityName, level));
        }
    }

    /**
     * Returns null for unmatched or OFF records; selection is independent of parents and status.
     */
    @Nullable
    TraceLogLevel resolve(TraceRecord record) {
        return resolve(record.getContext().getEntityType(), record.getContext().getEntityName());
    }

    /** Exact name, longest prefix, type, then ALL select the precomputed level. */
    @Nullable
    TraceLogLevel resolve(@Nullable String entityType, @Nullable String entityName) {
        TypeTargets overrides = typeTargets.get(entityType);
        TraceLogLevel level = overrides == null ? allLevel : overrides.resolve(entityName);
        return level == TraceLogLevel.OFF ? null : level;
    }

    private static Target fromConfiguredTarget(TraceLogTarget configuredTarget) {
        Map<String, Object> data = configuredTarget.toConfig();
        TraceLogLevel level =
                data.containsKey("level")
                        ? TraceLogLevel.fromString((String) data.get("level"))
                        : null;
        Object scope = data.get("scope");
        if (scope instanceof String) {
            return "EVENT_ONLY".equals(scope)
                    ? new Target(null, TraceContext.EVENT_ENTITY_TYPE, null, level)
                    : new Target(TraceLogScope.ALL, null, null, level);
        }
        Map<?, ?> entityScope = (Map<?, ?>) scope;
        return new Target(
                null,
                (String) entityScope.get("entityType"),
                (String) entityScope.get("entityName"),
                level);
    }

    private static IllegalArgumentException invalidTargets(String detail) {
        return new IllegalArgumentException("Invalid trace-log.targets: " + detail);
    }

    private static void rejectLegacySettings(Map<String, Object> data) {
        String legacyKeys =
                data.keySet().stream()
                        .filter(
                                key ->
                                        key.startsWith("event-log.")
                                                || LEGACY_OUTPUT_KEYS.contains(key))
                        .sorted()
                        .collect(Collectors.joining(", "));
        if (!legacyKeys.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unsupported legacy logging keys: "
                            + legacyKeys
                            + ". Use trace-log.* settings instead.");
        }
    }

    @Override
    public String toString() {
        return "targets=" + targets;
    }

    /** Targets for one entity type, with inherited levels resolved at startup. */
    private static final class TypeTargets {
        @Nullable private TraceLogLevel defaultLevel;
        private final Map<String, TraceLogLevel> exactLevels = new LinkedHashMap<>();
        private final List<PrefixTarget> prefixLevels = new ArrayList<>();

        private TypeTargets(@Nullable TraceLogLevel defaultLevel) {
            this.defaultLevel = defaultLevel;
        }

        private void add(Target target, TraceLogLevel level) {
            String name = target.entityName;
            if (name == null) {
                defaultLevel = level;
            } else if (name.endsWith(".*")) {
                String prefix = name.substring(0, name.length() - 2);
                // Keep longest prefixes first, preserving input order for equal lengths.
                int index = 0;
                while (index < prefixLevels.size()
                        && prefixLevels.get(index).prefix.length() >= prefix.length()) {
                    index++;
                }
                prefixLevels.add(index, new PrefixTarget(prefix, level));
            } else {
                exactLevels.put(name, level);
            }
        }

        @Nullable
        private TraceLogLevel resolve(@Nullable String entityName) {
            if (entityName == null || (exactLevels.isEmpty() && prefixLevels.isEmpty())) {
                return defaultLevel;
            }
            TraceLogLevel exact = exactLevels.get(entityName);
            if (exact != null) {
                return exact;
            }
            for (PrefixTarget target : prefixLevels) {
                String prefix = target.prefix;
                if (entityName.equals(prefix) || entityName.startsWith(prefix + ".")) {
                    return target.level;
                }
            }
            return defaultLevel;
        }
    }

    private static final class PrefixTarget {
        private final String prefix;
        private final TraceLogLevel level;

        private PrefixTarget(String prefix, TraceLogLevel level) {
            this.prefix = prefix;
            this.level = level;
        }
    }

    private static final class Target {
        @Nullable private final TraceLogScope preset;
        @Nullable private final String entityType;
        @Nullable private final String entityName;
        @Nullable private final TraceLogLevel level;

        private Target(
                @Nullable TraceLogScope preset,
                @Nullable String entityType,
                @Nullable String entityName,
                @Nullable TraceLogLevel level) {
            this.preset = preset;
            this.entityType = entityType;
            this.entityName = entityName;
            this.level = level;
        }

        private TraceLogLevel levelOr(TraceLogLevel inheritedLevel) {
            return level == null ? inheritedLevel : level;
        }

        private Object selector() {
            return preset != null
                    ? preset
                    : new AbstractMap.SimpleImmutableEntry<>(entityType, entityName);
        }

        @Override
        public String toString() {
            return "{scope=" + selector() + ", level=" + level + "}";
        }
    }
}
