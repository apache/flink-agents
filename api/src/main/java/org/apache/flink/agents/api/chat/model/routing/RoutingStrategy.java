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

package org.apache.flink.agents.api.chat.model.routing;

import javax.annotation.Nullable;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The immutable, serializable <b>declaration</b> of a routing strategy: which kind of routing
 * behavior is configured ({@link RoutingStrategyType}) and its arguments. Declarations carry no
 * executable logic — execution lives in the plan layer, which resolves an executor for the declared
 * type (built-ins) or instantiates the referenced {@link CustomRoutingExecutor} ({@code CUSTOM}).
 *
 * <p>Built-ins are produced by the {@link Strategies} factories; the declaration serializes into
 * the agent plan as a language-neutral type tag plus arguments, so a non-Java runtime can execute
 * the same plan with its own executors.
 */
public final class RoutingStrategy implements Serializable {

    private static final long serialVersionUID = 2L;

    /** Argument key: the judge chat-model name ({@code LLM_JUDGE}). */
    public static final String ARG_JUDGE_MODEL = "judge_model";

    /** Argument key: optional judge system-prompt template ({@code LLM_JUDGE}). */
    public static final String ARG_PROMPT_TEMPLATE = "prompt_template";

    /**
     * Argument key: optional cap (in characters) on the conversation context included in the judge
     * input ({@code LLM_JUDGE}). Unset means the judge sees the complete message list.
     */
    public static final String ARG_MAX_CONTEXT_CHARS = "max_context_chars";

    /** Argument key: the candidate-to-regex rule map ({@code RULE_BASED}). */
    public static final String ARG_RULES = "rules";

    private final RoutingStrategyType type;
    private final Map<String, Object> arguments;

    /** Only present for {@link RoutingStrategyType#CUSTOM}. */
    @Nullable private final String executorClass;

    public RoutingStrategy(
            RoutingStrategyType type,
            Map<String, Object> arguments,
            @Nullable String executorClass) {
        if (type == null) {
            throw new IllegalArgumentException("Routing strategy type must be non-null.");
        }
        this.type = type;
        this.arguments =
                arguments == null
                        ? Collections.emptyMap()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        this.executorClass = executorClass;
        validate();
    }

    /** The declaration's argument rules — the single source of truth for per-type validation. */
    private void validate() {
        switch (type) {
            case LLM_JUDGE:
                Object judge = arguments.get(ARG_JUDGE_MODEL);
                if (!(judge instanceof String) || ((String) judge).isEmpty()) {
                    throw new IllegalArgumentException(
                            "Strategies.llm requires a non-empty '" + ARG_JUDGE_MODEL + "'.");
                }
                Object template = arguments.get(ARG_PROMPT_TEMPLATE);
                if (template != null
                        && (!(template instanceof String) || ((String) template).isEmpty())) {
                    throw new IllegalArgumentException(
                            "'"
                                    + ARG_PROMPT_TEMPLATE
                                    + "' must be a non-empty String when provided.");
                }
                Object cap = arguments.get(ARG_MAX_CONTEXT_CHARS);
                if (cap != null && (!(cap instanceof Number) || ((Number) cap).intValue() <= 0)) {
                    throw new IllegalArgumentException(
                            "'"
                                    + ARG_MAX_CONTEXT_CHARS
                                    + "' must be a positive integer when provided.");
                }
                break;
            case CUSTOM:
                if (executorClass == null || executorClass.isEmpty()) {
                    throw new IllegalArgumentException(
                            "A CUSTOM routing strategy requires an executor class name.");
                }
                break;
            case RULE_BASED:
                // Declaration-level rule validation (shape, key/value types, regex syntax) lives
                // here so every path that constructs the declaration — the fluent builder, the
                // router constructor, plan-time validation — fails identically. Key-vs-candidate
                // checks happen where the candidates are in hand: ModelRouter.Builder#build() for
                // the fluent path, AgentPlan#validateRuleKeys for descriptor-built plans.
                validateRules(arguments.get(ARG_RULES));
                break;
            default:
                // A new type must opt into validation explicitly (mirrors the executor
                // dispatch, which also throws on unhandled types).
                throw new IllegalStateException("Unvalidated routing strategy type: " + type);
        }
        if (type != RoutingStrategyType.CUSTOM && executorClass != null) {
            throw new IllegalArgumentException(
                    "Only CUSTOM strategies carry an executor class (got type " + type + ").");
        }
    }

    /**
     * Returns a copy of this {@code LLM_JUDGE} declaration with a cap (in characters) on the
     * conversation context included in the judge input. Purely opt-in: without it the judge
     * receives the complete message list. When set, the rendered request and the SYSTEM message are
     * always kept, remaining messages fill newest-first within the budget, and any truncation is
     * flagged in the decision metadata ({@code judge_context_truncated}).
     */
    public RoutingStrategy withMaxContextChars(int maxContextChars) {
        if (type != RoutingStrategyType.LLM_JUDGE) {
            throw new IllegalStateException(
                    "withMaxContextChars applies to Strategies.llm(...) declarations only.");
        }
        Map<String, Object> withCap = new LinkedHashMap<>(arguments);
        withCap.put(ARG_MAX_CONTEXT_CHARS, maxContextChars);
        return new RoutingStrategy(type, withCap, null);
    }

    /**
     * A mis-shaped 'rules' value must fail loudly at declaration time: silently compiling zero
     * rules would disable routing (every request abstains to the default) with no diagnostic. A
     * {@code null} value is a rule-less declaration (always-abstain), which is legal.
     */
    private static void validateRules(@Nullable Object raw) {
        if (raw == null) {
            return;
        }
        if (!(raw instanceof Map)) {
            throw new IllegalArgumentException(
                    String.format(
                            "Routing rules must be a map of candidate name to regex, got %s.",
                            raw.getClass().getSimpleName()));
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
            Object key = entry.getKey();
            Object value = entry.getValue();
            if (!(key instanceof String) || ((String) key).isEmpty()) {
                throw new IllegalArgumentException(
                        "Routing rule has a null or empty candidate key.");
            }
            // String.valueOf(null) would silently become the literal pattern "null" (and
            // non-String values would coerce); reject both instead.
            if (!(value instanceof String)) {
                throw new IllegalArgumentException(
                        String.format(
                                "Routing rule for candidate '%s' must be a regex String, got %s.",
                                key, value == null ? "null" : value.getClass().getSimpleName()));
            }
            try {
                Pattern.compile((String) value);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException(
                        String.format(
                                "Routing rule pattern '%s' for candidate '%s' is not a valid"
                                        + " regex.",
                                value, key),
                        e);
            }
        }
    }

    public RoutingStrategyType getType() {
        return type;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    @Nullable
    public String getExecutorClass() {
        return executorClass;
    }
}
