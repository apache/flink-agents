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

import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.TraceContext;
import org.apache.flink.annotation.Internal;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An immutable selection of trace log records and their optional recording level.
 *
 * <p>Names select an exact name or a namespace ending in {@code .*}. A target with no level, or
 * with a null level, inherits the level of the nearest configured broader scope, falling back to
 * {@link TraceLogLevel#STANDARD} when none exists. An explicit level overrides that inheritance.
 *
 * <p>Use these targets with {@link
 * org.apache.flink.agents.api.configuration.AgentConfigOptions#TRACE_LOG_TARGETS}.
 */
public final class TraceLogTarget {
    private static final Set<String> TARGET_FIELDS = Set.of("scope", "level");
    private static final Set<String> SCOPE_FIELDS = Set.of("entityType", "entityName");
    private static final Set<String> CATEGORY_SELECTORS =
            Set.of(
                    TraceContext.EVENT_ENTITY_TYPE,
                    ExecutionReporter.EntityTypes.ACTION,
                    ExecutionReporter.EntityTypes.TOOL,
                    ExecutionReporter.EntityTypes.LLM,
                    ExecutionReporter.EntityTypes.PARSER,
                    ExecutionReporter.EntityTypes.SUBAGENT);

    private final Map<String, Object> config;

    private TraceLogTarget(Map<String, Object> config) {
        this.config = Collections.unmodifiableMap(config);
    }

    /** Selects all entities with an inherited level. */
    public static TraceLogTarget all() {
        return all(null);
    }

    /** Selects all entities with the supplied level, or an inherited level if null. */
    public static TraceLogTarget all(@Nullable TraceLogLevel level) {
        return create(TraceLogScope.ALL, level);
    }

    /** Selects all Events with an inherited level. */
    public static TraceLogTarget events() {
        return events(null);
    }

    /** Selects all Events with the supplied level, or an inherited level if null. */
    public static TraceLogTarget events(@Nullable TraceLogLevel level) {
        return entity(TraceContext.EVENT_ENTITY_TYPE, null, level);
    }

    /** Selects matching Events with an inherited level. */
    public static TraceLogTarget event(String name) {
        return event(name, null);
    }

    /** Selects matching Events with the supplied level, or an inherited level if null. */
    public static TraceLogTarget event(String name, @Nullable TraceLogLevel level) {
        return namedEntity(TraceContext.EVENT_ENTITY_TYPE, name, level);
    }

    /** Selects all Actions with an inherited level. */
    public static TraceLogTarget actions() {
        return actions(null);
    }

    /** Selects all Actions with the supplied level, or an inherited level if null. */
    public static TraceLogTarget actions(@Nullable TraceLogLevel level) {
        return entity(ExecutionReporter.EntityTypes.ACTION, null, level);
    }

    /** Selects matching Actions with an inherited level. */
    public static TraceLogTarget action(String name) {
        return action(name, null);
    }

    /** Selects matching Actions with the supplied level, or an inherited level if null. */
    public static TraceLogTarget action(String name, @Nullable TraceLogLevel level) {
        return namedEntity(ExecutionReporter.EntityTypes.ACTION, name, level);
    }

    /** Selects all tools with an inherited level. */
    public static TraceLogTarget tools() {
        return tools(null);
    }

    /** Selects all tools with the supplied level, or an inherited level if null. */
    public static TraceLogTarget tools(@Nullable TraceLogLevel level) {
        return entity(ExecutionReporter.EntityTypes.TOOL, null, level);
    }

    /** Selects matching tools with an inherited level. */
    public static TraceLogTarget tool(String name) {
        return tool(name, null);
    }

    /** Selects matching tools with the supplied level, or an inherited level if null. */
    public static TraceLogTarget tool(String name, @Nullable TraceLogLevel level) {
        return namedEntity(ExecutionReporter.EntityTypes.TOOL, name, level);
    }

    /** Selects all LLMs with an inherited level. */
    public static TraceLogTarget llms() {
        return llms(null);
    }

    /** Selects all LLMs with the supplied level, or an inherited level if null. */
    public static TraceLogTarget llms(@Nullable TraceLogLevel level) {
        return entity(ExecutionReporter.EntityTypes.LLM, null, level);
    }

    /** Selects matching LLMs with an inherited level. */
    public static TraceLogTarget llm(String name) {
        return llm(name, null);
    }

    /** Selects matching LLMs with the supplied level, or an inherited level if null. */
    public static TraceLogTarget llm(String name, @Nullable TraceLogLevel level) {
        return namedEntity(ExecutionReporter.EntityTypes.LLM, name, level);
    }

    /** Selects all parsers with an inherited level. */
    public static TraceLogTarget parsers() {
        return parsers(null);
    }

    /** Selects all parsers with the supplied level, or an inherited level if null. */
    public static TraceLogTarget parsers(@Nullable TraceLogLevel level) {
        return entity(ExecutionReporter.EntityTypes.PARSER, null, level);
    }

    /** Selects matching parsers with an inherited level. */
    public static TraceLogTarget parser(String name) {
        return parser(name, null);
    }

    /** Selects matching parsers with the supplied level, or an inherited level if null. */
    public static TraceLogTarget parser(String name, @Nullable TraceLogLevel level) {
        return namedEntity(ExecutionReporter.EntityTypes.PARSER, name, level);
    }

    /** Selects all subagents with an inherited level. */
    public static TraceLogTarget subagents() {
        return subagents(null);
    }

    /** Selects all subagents with the supplied level, or an inherited level if null. */
    public static TraceLogTarget subagents(@Nullable TraceLogLevel level) {
        return entity(ExecutionReporter.EntityTypes.SUBAGENT, null, level);
    }

    /** Selects matching subagents with an inherited level. */
    public static TraceLogTarget subagent(String name) {
        return subagent(name, null);
    }

    /** Selects matching subagents with the supplied level, or an inherited level if null. */
    public static TraceLogTarget subagent(String name, @Nullable TraceLogLevel level) {
        return namedEntity(ExecutionReporter.EntityTypes.SUBAGENT, name, level);
    }

    /** Selects all entities of a built-in or custom type with an inherited level. */
    public static TraceLogTarget entity(String type) {
        return entity(type, null, null);
    }

    /** Selects matching entities of a built-in or custom type with an inherited level. */
    public static TraceLogTarget entity(String type, @Nullable String name) {
        return entity(type, name, null);
    }

    /**
     * Selects entities of a built-in or custom type.
     *
     * @param type the nonblank, case-sensitive entity type
     * @param name an exact name or namespace ending in {@code .*}, or null for the whole type
     * @param level the recording level, or null to inherit from a broader scope
     */
    public static TraceLogTarget entity(
            String type, @Nullable String name, @Nullable TraceLogLevel level) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("entityType", type);
        if (name != null) {
            scope.put("entityName", name);
        }
        return create(scope, level);
    }

    /** Decodes the target list shared by configuration and runtime validation. */
    @Internal
    public static List<TraceLogTarget> parseTargets(Object configuredTargets) {
        if (!(configuredTargets instanceof List)) {
            throw new IllegalArgumentException(
                    "Invalid trace-log.targets: must be a list of targets");
        }
        List<TraceLogTarget> targets = new ArrayList<>();
        int index = 0;
        for (Object target : (List<?>) configuredTargets) {
            try {
                targets.add(fromConfig(target));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException(
                        "Invalid trace-log.targets: target " + index + ": " + error.getMessage(),
                        error);
            }
            index++;
        }
        return Collections.unmodifiableList(targets);
    }

    /** Decodes a typed target or a map with the same semantics as YAML configuration. */
    @Internal
    public static TraceLogTarget fromConfig(Object configuredTarget) {
        if (configuredTarget instanceof TraceLogTarget) {
            return (TraceLogTarget) configuredTarget;
        }
        if (!(configuredTarget instanceof Map)) {
            throw new IllegalArgumentException("must be an object");
        }
        Map<?, ?> data = (Map<?, ?>) configuredTarget;
        checkFields(data, TARGET_FIELDS);
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("scope", parseScope(data.get("scope")));
        Object level = data.get("level");
        if (level != null) {
            if (!(level instanceof String) && !(level instanceof TraceLogLevel)) {
                throw new IllegalArgumentException("level must be OFF, STANDARD, or VERBOSE");
            }
            canonical.put("level", TraceLogLevel.fromString(level.toString()).name());
        }
        return new TraceLogTarget(canonical);
    }

    /** Returns an immutable, JSON-native representation for configuration serialization. */
    @Internal
    public Map<String, Object> toConfig() {
        return config;
    }

    private static TraceLogTarget create(Object scope, @Nullable TraceLogLevel level) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("scope", scope);
        if (level != null) {
            config.put("level", level);
        }
        return fromConfig(config);
    }

    private static TraceLogTarget namedEntity(
            String type, String name, @Nullable TraceLogLevel level) {
        return entity(type, requiredString(name, "entityName"), level);
    }

    private static Object parseScope(Object scope) {
        if (scope instanceof String || scope instanceof TraceLogScope) {
            return TraceLogScope.fromString(scope.toString()).name();
        }
        if (!(scope instanceof Map)) {
            throw new IllegalArgumentException("scope must be EVENT_ONLY, ALL, or an object");
        }
        Map<?, ?> scopeData = (Map<?, ?>) scope;
        String type;
        String name;
        if (scopeData.containsKey("entityType") || scopeData.containsKey("entityName")) {
            checkFields(scopeData, SCOPE_FIELDS);
            type = requiredString(scopeData.get("entityType"), "entityType");
            name =
                    scopeData.get("entityName") == null
                            ? null
                            : requiredString(scopeData.get("entityName"), "entityName");
        } else {
            if (scopeData.size() != 1) {
                throw new IllegalArgumentException(
                        "scope must contain exactly one category selector");
            }
            checkFields(scopeData, CATEGORY_SELECTORS);
            Map.Entry<?, ?> selector = scopeData.entrySet().iterator().next();
            type = (String) selector.getKey();
            name = requiredString(selector.getValue(), type);
            if (name.equals("*")) {
                name = null;
            }
        }
        boolean prefix = name != null && name.endsWith(".*");
        if (name != null
                && name.contains("*")
                && (!prefix
                        || name.length() == 2
                        || name.substring(0, name.length() - 2).contains("*"))) {
            throw new IllegalArgumentException(
                    "entityName must be an exact name or a namespace ending in .*; omit entityName to select the whole type");
        }
        if (TraceContext.EVENT_ENTITY_TYPE.equals(type) && name == null) {
            return TraceLogScope.EVENT_ONLY.name();
        }
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("entityType", type);
        if (name != null) {
            canonical.put("entityName", name);
        }
        return Collections.unmodifiableMap(canonical);
    }

    private static void checkFields(Map<?, ?> data, Set<String> allowed) {
        for (Object key : data.keySet()) {
            if (!(key instanceof String) || !allowed.contains(key)) {
                throw new IllegalArgumentException("has unknown field '" + key + "'");
            }
        }
    }

    private static String requiredString(Object value, String field) {
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
        return (String) value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof TraceLogTarget
                        && config.equals(((TraceLogTarget) other).config));
    }

    @Override
    public int hashCode() {
        return config.hashCode();
    }

    @Override
    public String toString() {
        return config.toString();
    }
}
