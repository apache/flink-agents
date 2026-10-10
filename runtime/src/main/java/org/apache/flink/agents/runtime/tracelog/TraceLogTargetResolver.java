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
import org.apache.flink.agents.api.logger.TraceLogDetail;
import org.apache.flink.agents.api.logger.TraceLogScope;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Selects records and resolves their recording detail from trace log targets. */
final class TraceLogTargetResolver {
    private static final Set<String> TARGET_FIELDS = Set.of("scope", "detail");
    private static final Set<String> SCOPE_FIELDS = Set.of("entityType", "entityName");
    private static final Set<String> LEGACY_OUTPUT_KEYS =
            Set.of("eventLoggerType", "baseLogDir", "prettyPrint");

    @Nullable private final TraceLogDetail allDetail;
    @Nullable private final TraceLogDetail eventDetail;
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
        if (!(configuredTargets instanceof List)) {
            throw invalidTargets("must be a list of targets");
        }
        List<Target> configured = new ArrayList<>();
        int index = 0;
        for (Object configuredTarget : (List<?>) configuredTargets) {
            configured.add(parseTarget(configuredTarget, index++));
        }
        allDetail = resolvePresetDetail(configured, TraceLogScope.ALL, TraceLogDetail.STANDARD);
        TraceLogDetail eventOnlyDetail =
                resolvePresetDetail(
                        configured,
                        TraceLogScope.EVENT_ONLY,
                        allDetail == null ? TraceLogDetail.STANDARD : allDetail);
        eventDetail = eventOnlyDetail == null ? allDetail : eventOnlyDetail;
        TraceLogDetail inheritedDetail =
                allDetail != null
                        ? allDetail
                        : eventOnlyDetail != null ? eventOnlyDetail : TraceLogDetail.STANDARD;

        Map<Object, TraceLogDetail> selectors = new LinkedHashMap<>();
        for (Target target : configured) {
            TraceLogDetail presetDefault = presetDetail(target.entityType);
            TraceLogDetail detail =
                    target.detailOr(presetDefault == null ? inheritedDetail : presetDefault);
            Object selector =
                    target.preset != null
                            ? target.preset
                            : new AbstractMap.SimpleImmutableEntry<>(
                                    target.entityType, target.entityName);
            TraceLogDetail previous = selectors.putIfAbsent(selector, detail);
            if (previous != null) {
                if (previous != detail) {
                    throw invalidTargets("conflicting details for scope " + selector);
                }
                continue;
            }
            targets.add(new Target(target.preset, target.entityType, target.entityName, detail));
            if (target.preset == null) {
                typeTargets
                        .computeIfAbsent(
                                target.entityType, type -> new TypeTargets(presetDetail(type)))
                        .add(target, detail);
            }
        }
        typeTargets
                .values()
                .forEach(
                        values ->
                                values.prefixDetails.sort(
                                        Comparator.comparingInt(
                                                        (PrefixTarget target) ->
                                                                target.prefix.length())
                                                .reversed()));
    }

    @Nullable
    private static TraceLogDetail resolvePresetDetail(
            List<Target> configured, TraceLogScope preset, TraceLogDetail inheritedDetail) {
        TraceLogDetail resolved = null;
        for (Target target : configured) {
            if (target.preset == preset) {
                TraceLogDetail detail = target.detailOr(inheritedDetail);
                if (resolved != null && resolved != detail) {
                    throw invalidTargets("conflicting details for scope " + preset);
                }
                resolved = detail;
            }
        }
        return resolved;
    }

    /**
     * Returns null for unmatched or OFF records; selection is independent of parents and status.
     */
    @Nullable
    TraceLogDetail resolve(TraceRecord record) {
        return resolve(record.getContext().getEntityType(), record.getContext().getEntityName());
    }

    /** Exact name, longest prefix, type, EVENT_ONLY, then ALL determine the detail. */
    @Nullable
    TraceLogDetail resolve(@Nullable String entityType, @Nullable String entityName) {
        TraceLogDetail defaultDetail = presetDetail(entityType);
        TypeTargets overrides = typeTargets.get(entityType);
        TraceLogDetail detail = overrides == null ? defaultDetail : overrides.resolve(entityName);
        return detail == TraceLogDetail.OFF ? null : detail;
    }

    @Nullable
    private TraceLogDetail presetDetail(@Nullable String entityType) {
        return TraceContext.EVENT_ENTITY_TYPE.equals(entityType) ? eventDetail : allDetail;
    }

    private static Target parseTarget(Object configuredTarget, int index) {
        if (!(configuredTarget instanceof Map)) {
            throw invalidTargets("target " + index + " must be an object");
        }
        Map<?, ?> data = (Map<?, ?>) configuredTarget;
        checkFields(data, TARGET_FIELDS, index);
        TraceLogDetail detail;
        try {
            Object rawDetail = data.get("detail");
            detail = rawDetail == null ? null : TraceLogDetail.fromString(rawDetail.toString());
        } catch (IllegalArgumentException error) {
            throw invalidTargets("target " + index + ": " + error.getMessage());
        }
        Object scope = data.get("scope");
        if (scope instanceof String || scope instanceof TraceLogScope) {
            try {
                return new Target(TraceLogScope.fromString(scope.toString()), null, null, detail);
            } catch (IllegalArgumentException error) {
                throw invalidTargets("target " + index + ": " + error.getMessage());
            }
        }
        if (!(scope instanceof Map)) {
            throw invalidTargets(
                    "target " + index + " scope must be EVENT_ONLY, ALL, or an object");
        }
        Map<?, ?> scopeData = (Map<?, ?>) scope;
        checkFields(scopeData, SCOPE_FIELDS, index);
        String entityType = requiredString(scopeData.get("entityType"), index, "entityType");
        String entityName =
                scopeData.get("entityName") == null
                        ? null
                        : requiredString(scopeData.get("entityName"), index, "entityName");
        boolean prefix = entityName != null && entityName.endsWith(".*");
        if (entityName != null
                && entityName.contains("*")
                && (!prefix
                        || entityName.length() == 2
                        || entityName.substring(0, entityName.length() - 2).contains("*"))) {
            throw invalidTargets(
                    "target "
                            + index
                            + " entityName must be an exact name or a namespace ending in .*; omit entityName to select the whole type");
        }
        return new Target(null, entityType, entityName, detail);
    }

    private static void checkFields(Map<?, ?> data, Set<String> allowed, int index) {
        for (Object key : data.keySet()) {
            if (!(key instanceof String) || !allowed.contains((String) key)) {
                throw invalidTargets("target " + index + " has unknown field '" + key + "'");
            }
        }
    }

    private static String requiredString(Object value, int index, String field) {
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw invalidTargets("target " + index + " " + field + " must be a non-empty string");
        }
        return (String) value;
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

    /** Targets for one entity type, with the type and preset default resolved at startup. */
    private static final class TypeTargets {
        @Nullable private TraceLogDetail defaultDetail;
        private final Map<String, TraceLogDetail> exactDetails = new LinkedHashMap<>();
        private final List<PrefixTarget> prefixDetails = new ArrayList<>();
        private final ConcurrentHashMap<String, Optional<TraceLogDetail>> cache =
                new ConcurrentHashMap<>();

        private TypeTargets(@Nullable TraceLogDetail defaultDetail) {
            this.defaultDetail = defaultDetail;
        }

        private void add(Target target, TraceLogDetail detail) {
            String name = target.entityName;
            if (name == null) {
                defaultDetail = detail;
            } else if (name.endsWith(".*")) {
                prefixDetails.add(new PrefixTarget(name.substring(0, name.length() - 2), detail));
            } else {
                exactDetails.put(name, detail);
            }
        }

        @Nullable
        private TraceLogDetail resolve(@Nullable String entityName) {
            if (entityName == null || (exactDetails.isEmpty() && prefixDetails.isEmpty())) {
                return defaultDetail;
            }
            return cache.computeIfAbsent(entityName, name -> Optional.ofNullable(resolveName(name)))
                    .orElse(null);
        }

        @Nullable
        private TraceLogDetail resolveName(String entityName) {
            TraceLogDetail exact = exactDetails.get(entityName);
            if (exact != null) {
                return exact;
            }
            for (PrefixTarget target : prefixDetails) {
                String prefix = target.prefix;
                if (entityName.equals(prefix) || entityName.startsWith(prefix + ".")) {
                    return target.detail;
                }
            }
            return defaultDetail;
        }
    }

    private static final class PrefixTarget {
        private final String prefix;
        private final TraceLogDetail detail;

        private PrefixTarget(String prefix, TraceLogDetail detail) {
            this.prefix = prefix;
            this.detail = detail;
        }
    }

    private static final class Target {
        @Nullable private final TraceLogScope preset;
        @Nullable private final String entityType;
        @Nullable private final String entityName;
        @Nullable private final TraceLogDetail detail;

        private Target(
                @Nullable TraceLogScope preset,
                @Nullable String entityType,
                @Nullable String entityName,
                @Nullable TraceLogDetail detail) {
            this.preset = preset;
            this.entityType = entityType;
            this.entityName = entityName;
            this.detail = detail;
        }

        private TraceLogDetail detailOr(TraceLogDetail inheritedDetail) {
            return detail == null ? inheritedDetail : detail;
        }

        @Override
        public String toString() {
            Object scope =
                    preset != null
                            ? preset
                            : new AbstractMap.SimpleImmutableEntry<>(entityType, entityName);
            return "{scope=" + scope + ", detail=" + detail + "}";
        }
    }
}
