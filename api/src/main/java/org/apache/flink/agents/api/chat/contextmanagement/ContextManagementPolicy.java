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
package org.apache.flink.agents.api.chat.contextmanagement;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.configuration.ConfigOption;
import org.apache.flink.agents.api.configuration.ReadableConfiguration;

import javax.annotation.Nullable;

import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * How the built-in chat action bounds the model input of one chat request.
 *
 * <p>A chat request and its tool-call loop send the request's whole conversation to the model on
 * every round, including every earlier tool result. A policy bounds that input in three ways, each
 * off unless it is set:
 *
 * <ul>
 *   <li><b>Tool result truncation</b> ({@link #getToolResultMaxChars()}): a tool result longer than
 *       the limit is cut in the middle before it is sent.
 *   <li><b>Tool result masking</b> ({@link #getToolResultKeepLastRounds()}): the results of the
 *       last rounds are sent as they are, and the results of older rounds are replaced by a one
 *       line placeholder. The model cannot read a masked result again, so this fits requests whose
 *       later rounds do not depend on the content of earlier results. {@link
 *       #getToolResultMaskStepRounds()} makes the boundary move in steps of several rounds, so the
 *       messages before it stay unchanged between two steps.
 *   <li><b>Summarization</b> ({@link #getMaxInputTokens()}): when the estimated input exceeds
 *       {@link #getSummarizationTriggerRatio()} of the budget, the older rounds are replaced by a
 *       summary written by a chat model, and the last {@link #getSummarizationKeepLastRounds()}
 *       rounds are kept as they are. A round whose results are already masked is summarized only
 *       when what is left of it is still longer than a line of the summary: with masking set as
 *       well, most rounds are summarized only while they still have their results.
 * </ul>
 *
 * <p>A round is one model reply that requests tool calls together with the results of those calls.
 * Only messages of rounds are ever truncated, masked or summarized; system, user and plain
 * assistant messages are always sent as they are. So is what the built-in {@code load_skill} tool
 * returns, the instructions of a skill: its result is neither truncated nor masked, and its round
 * is not summarized. Only the messages sent to the model change: the conversation the framework
 * keeps for the request stays complete.
 *
 * <p>The default policy of an agent comes from the {@code context-management.*} execution options
 * ({@link #fromConfiguration}). A {@link org.apache.flink.agents.api.event.ChatRequestEvent} can
 * carry its own policy, which replaces the default as a whole for that request. A policy with
 * nothing set changes nothing.
 */
public final class ContextManagementPolicy {

    /** Map form key of {@link #getToolResultMaxChars()}. */
    public static final String TOOL_RESULT_MAX_CHARS = "tool_result_max_chars";

    /** Map form key of {@link #getToolResultKeepLastRounds()}. */
    public static final String TOOL_RESULT_KEEP_LAST_ROUNDS = "tool_result_keep_last_rounds";

    /** Map form key of {@link #getToolResultMaskStepRounds()}. */
    public static final String TOOL_RESULT_MASK_STEP_ROUNDS = "tool_result_mask_step_rounds";

    /** Map form key of {@link #getMaxInputTokens()}. */
    public static final String MAX_INPUT_TOKENS = "max_input_tokens";

    /** Map form key of {@link #getSummarizationTriggerRatio()}. */
    public static final String SUMMARIZATION_TRIGGER_RATIO = "summarization_trigger_ratio";

    /** Map form key of {@link #getSummarizationKeepLastRounds()}. */
    public static final String SUMMARIZATION_KEEP_LAST_ROUNDS = "summarization_keep_last_rounds";

    /** Map form key of {@link #getSummarizer()}. */
    public static final String SUMMARIZER = "summarizer";

    /** The smallest accepted {@link #getToolResultMaxChars()}. */
    public static final int MIN_TOOL_RESULT_MAX_CHARS = 100;

    public static final int DEFAULT_TOOL_RESULT_MASK_STEP_ROUNDS = 1;
    public static final double DEFAULT_SUMMARIZATION_TRIGGER_RATIO = 0.8;
    public static final int DEFAULT_SUMMARIZATION_KEEP_LAST_ROUNDS = 3;

    /** The execution option that sets each field, for errors about configured values. */
    private static final Map<String, String> CONFIG_KEYS;

    private static final ContextManagementPolicy DISABLED;

    /**
     * A name without a character that is not white space, by the Unicode property White_Space. The
     * characters are listed, so that Java and Python read the same names as blank.
     */
    private static final Pattern BLANK =
            Pattern.compile(
                    "[\\t-\\r \\x85\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]*");

    /** How the value of an integer option is written: a sign and digits. */
    private static final Pattern INTEGER = Pattern.compile("[+-]?[0-9]+");

    /** How the value of a number option is written: a decimal number that can have an exponent. */
    private static final Pattern NUMBER =
            Pattern.compile("[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

    static {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put(
                TOOL_RESULT_MAX_CHARS,
                AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS.getKey());
        keys.put(
                TOOL_RESULT_KEEP_LAST_ROUNDS,
                AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS.getKey());
        keys.put(
                TOOL_RESULT_MASK_STEP_ROUNDS,
                AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MASK_STEP_ROUNDS.getKey());
        keys.put(
                MAX_INPUT_TOKENS,
                AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS.getKey());
        keys.put(
                SUMMARIZATION_TRIGGER_RATIO,
                AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO.getKey());
        keys.put(
                SUMMARIZATION_KEEP_LAST_ROUNDS,
                AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS.getKey());
        keys.put(SUMMARIZER, AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL.getKey());
        CONFIG_KEYS = Collections.unmodifiableMap(keys);
        DISABLED = new Builder().build();
    }

    @Nullable private final Integer toolResultMaxChars;
    @Nullable private final Integer toolResultKeepLastRounds;
    @Nullable private final Integer toolResultMaskStepRounds;
    @Nullable private final Integer maxInputTokens;
    @Nullable private final Double summarizationTriggerRatio;
    @Nullable private final Integer summarizationKeepLastRounds;
    @Nullable private final String summarizer;

    private ContextManagementPolicy(Builder builder, UnaryOperator<String> name) {
        this.toolResultMaxChars = builder.toolResultMaxChars;
        this.toolResultKeepLastRounds = builder.toolResultKeepLastRounds;
        this.toolResultMaskStepRounds = builder.toolResultMaskStepRounds;
        this.maxInputTokens = builder.maxInputTokens;
        this.summarizationTriggerRatio = builder.summarizationTriggerRatio;
        this.summarizationKeepLastRounds = builder.summarizationKeepLastRounds;
        this.summarizer = builder.summarizer;
        validate(name);
    }

    private void validate(UnaryOperator<String> name) {
        requireAtLeast(
                name.apply(TOOL_RESULT_MAX_CHARS), toolResultMaxChars, MIN_TOOL_RESULT_MAX_CHARS);
        requireAtLeast(name.apply(TOOL_RESULT_KEEP_LAST_ROUNDS), toolResultKeepLastRounds, 1);
        requireAtLeast(name.apply(TOOL_RESULT_MASK_STEP_ROUNDS), toolResultMaskStepRounds, 1);
        requireAtLeast(name.apply(MAX_INPUT_TOKENS), maxInputTokens, 1);
        requireAtLeast(name.apply(SUMMARIZATION_KEEP_LAST_ROUNDS), summarizationKeepLastRounds, 1);
        if (summarizationTriggerRatio != null
                && !(summarizationTriggerRatio > 0.0 && summarizationTriggerRatio <= 1.0)) {
            throw new IllegalArgumentException(
                    name.apply(SUMMARIZATION_TRIGGER_RATIO)
                            + " must be greater than 0 and at most 1, got "
                            + summarizationTriggerRatio);
        }
        if (summarizer != null && BLANK.matcher(summarizer).matches()) {
            throw new IllegalArgumentException(
                    name.apply(SUMMARIZER) + " must name a chat model, got a blank value");
        }
        requireWith(
                name,
                TOOL_RESULT_MASK_STEP_ROUNDS,
                toolResultMaskStepRounds,
                TOOL_RESULT_KEEP_LAST_ROUNDS,
                toolResultKeepLastRounds);
        requireWith(
                name,
                SUMMARIZATION_TRIGGER_RATIO,
                summarizationTriggerRatio,
                MAX_INPUT_TOKENS,
                maxInputTokens);
        requireWith(
                name,
                SUMMARIZATION_KEEP_LAST_ROUNDS,
                summarizationKeepLastRounds,
                MAX_INPUT_TOKENS,
                maxInputTokens);
        requireWith(name, SUMMARIZER, summarizer, MAX_INPUT_TOKENS, maxInputTokens);
    }

    private static void requireAtLeast(String name, @Nullable Integer value, int minimum) {
        if (value != null && value < minimum) {
            throw new IllegalArgumentException(
                    name + " must be at least " + minimum + ", got " + value);
        }
    }

    /** A value that only tunes another setting is a mistake when that setting is absent. */
    private static void requireWith(
            UnaryOperator<String> name,
            String field,
            @Nullable Object value,
            String requiredField,
            @Nullable Object requiredValue) {
        if (value != null && requiredValue == null) {
            throw new IllegalArgumentException(
                    name.apply(field)
                            + " has no effect without "
                            + name.apply(requiredField)
                            + "; set "
                            + name.apply(requiredField)
                            + " or remove "
                            + name.apply(field));
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * A policy with nothing set. On a chat request it switches context management off for that
     * request, whatever the agent's default policy is.
     */
    public static ContextManagementPolicy disabled() {
        return DISABLED;
    }

    /**
     * The policy declared by the {@code context-management.*} execution options, disabled when none
     * of them is set.
     *
     * @throws IllegalArgumentException if a configured value is out of range, named by its
     *     configuration key
     */
    public static ContextManagementPolicy fromConfiguration(ReadableConfiguration config) {
        Builder builder = new Builder();
        builder.toolResultMaxChars =
                read(config, AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS);
        builder.toolResultKeepLastRounds =
                read(
                        config,
                        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS);
        builder.toolResultMaskStepRounds =
                read(
                        config,
                        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MASK_STEP_ROUNDS);
        builder.maxInputTokens =
                read(config, AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS);
        builder.summarizationTriggerRatio =
                read(config, AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO);
        builder.summarizationKeepLastRounds =
                read(
                        config,
                        AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS);
        builder.summarizer =
                read(config, AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL);
        return new ContextManagementPolicy(builder, CONFIG_KEYS::get);
    }

    /**
     * Reads one option, naming its key when the configured value is not of the option's type.
     *
     * <p>A configuration converts a number with the parser of its language, and the parsers of Java
     * and Python take different texts: {@code 0.8d} is a number in Java only and {@code 0.8_0} in
     * Python only. So the value of a number is also read as it was written and judged by one
     * syntax, the same as on the Python side.
     */
    private static <T> T read(ReadableConfiguration config, ConfigOption<T> option) {
        try {
            T value = config.get(option);
            if (value instanceof Number) {
                String written = config.getStr(option.getKey(), null);
                Pattern syntax = Integer.class.equals(option.getType()) ? INTEGER : NUMBER;
                if (written != null && !syntax.matcher(written).matches()) {
                    throw new IllegalArgumentException("got '" + written + "'");
                }
            }
            return value;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    option.getKey()
                            + " must be "
                            + (Integer.class.equals(option.getType())
                                    ? "an integer"
                                    : Double.class.equals(option.getType())
                                            ? "a number"
                                            : "a string")
                            + ": "
                            + e.getMessage(),
                    e);
        }
    }

    /**
     * Reads the map form written by {@link #toMap()}, which is also how a policy crosses the JSON
     * boundary. Entries with a null value are ignored.
     *
     * @throws IllegalArgumentException if the map has a key that is not a policy field, a value of
     *     the wrong type, or a value out of range
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ContextManagementPolicy fromMap(Map<String, ?> map) {
        Objects.requireNonNull(map, "map");
        Builder builder = new Builder();
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (!CONFIG_KEYS.containsKey(key)) {
                throw new IllegalArgumentException(
                        "Unknown context management policy field '"
                                + key
                                + "'; the fields are "
                                + CONFIG_KEYS.keySet());
            }
            if (value == null) {
                continue;
            }
            switch (key) {
                case TOOL_RESULT_MAX_CHARS:
                    builder.toolResultMaxChars = integer(key, value);
                    break;
                case TOOL_RESULT_KEEP_LAST_ROUNDS:
                    builder.toolResultKeepLastRounds = integer(key, value);
                    break;
                case TOOL_RESULT_MASK_STEP_ROUNDS:
                    builder.toolResultMaskStepRounds = integer(key, value);
                    break;
                case MAX_INPUT_TOKENS:
                    builder.maxInputTokens = integer(key, value);
                    break;
                case SUMMARIZATION_KEEP_LAST_ROUNDS:
                    builder.summarizationKeepLastRounds = integer(key, value);
                    break;
                case SUMMARIZATION_TRIGGER_RATIO:
                    if (!(value instanceof Number)) {
                        throw new IllegalArgumentException(
                                key + " must be a number, got " + describe(value));
                    }
                    builder.summarizationTriggerRatio = ((Number) value).doubleValue();
                    break;
                default:
                    if (!(value instanceof String)) {
                        throw new IllegalArgumentException(
                                key + " must be a string, got " + describe(value));
                    }
                    builder.summarizer = (String) value;
                    break;
            }
        }
        return builder.build();
    }

    private static Integer integer(String key, Object value) {
        boolean integral =
                value instanceof Integer
                        || value instanceof Long
                        || value instanceof Short
                        || value instanceof Byte
                        || value instanceof BigInteger;
        if (!integral) {
            throw new IllegalArgumentException(key + " must be an integer, got " + describe(value));
        }
        long number =
                value instanceof BigInteger
                        ? ((BigInteger) value)
                                .max(BigInteger.valueOf(Long.MIN_VALUE))
                                .min(BigInteger.valueOf(Long.MAX_VALUE))
                                .longValue()
                        : ((Number) value).longValue();
        if (number > Integer.MAX_VALUE || number < Integer.MIN_VALUE) {
            throw new IllegalArgumentException(key + " is out of range, got " + value);
        }
        return (int) number;
    }

    private static String describe(Object value) {
        return value + " (" + value.getClass().getSimpleName() + ")";
    }

    /**
     * The fields that were set, by their map form keys, in declaration order. Defaults of unset
     * tuning values are not included.
     */
    @JsonValue
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        putIfSet(map, TOOL_RESULT_MAX_CHARS, toolResultMaxChars);
        putIfSet(map, TOOL_RESULT_KEEP_LAST_ROUNDS, toolResultKeepLastRounds);
        putIfSet(map, TOOL_RESULT_MASK_STEP_ROUNDS, toolResultMaskStepRounds);
        putIfSet(map, MAX_INPUT_TOKENS, maxInputTokens);
        putIfSet(map, SUMMARIZATION_TRIGGER_RATIO, summarizationTriggerRatio);
        putIfSet(map, SUMMARIZATION_KEEP_LAST_ROUNDS, summarizationKeepLastRounds);
        putIfSet(map, SUMMARIZER, summarizer);
        return map;
    }

    private static void putIfSet(Map<String, Object> map, String key, @Nullable Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /** Whether the policy truncates, masks or summarizes anything. */
    public boolean isEnabled() {
        return toolResultMaxChars != null
                || toolResultKeepLastRounds != null
                || maxInputTokens != null;
    }

    /**
     * The most characters of one tool result that are sent to the model, or null when tool results
     * are not truncated. A longer result is cut in the middle, keeping its beginning and its end.
     */
    @Nullable
    public Integer getToolResultMaxChars() {
        return toolResultMaxChars;
    }

    /**
     * How many of the most recent rounds keep their tool results, or null when tool results are not
     * masked. The latest round is always kept.
     */
    @Nullable
    public Integer getToolResultKeepLastRounds() {
        return toolResultKeepLastRounds;
    }

    /**
     * How many rounds are masked in one step. With a step of {@code s}, masking waits until {@code
     * s} rounds beyond {@link #getToolResultKeepLastRounds()} are eligible and then masks them
     * together.
     */
    public int getToolResultMaskStepRounds() {
        return toolResultMaskStepRounds == null
                ? DEFAULT_TOOL_RESULT_MASK_STEP_ROUNDS
                : toolResultMaskStepRounds;
    }

    /**
     * The input budget of one model call in tokens, or null when the conversation is not
     * summarized. The budget triggers summarization; it is not a hard limit on the request.
     */
    @Nullable
    public Integer getMaxInputTokens() {
        return maxInputTokens;
    }

    /** The share of {@link #getMaxInputTokens()} above which older rounds are summarized. */
    public double getSummarizationTriggerRatio() {
        return summarizationTriggerRatio == null
                ? DEFAULT_SUMMARIZATION_TRIGGER_RATIO
                : summarizationTriggerRatio;
    }

    /** How many of the most recent rounds a summarization leaves as they are. */
    public int getSummarizationKeepLastRounds() {
        return summarizationKeepLastRounds == null
                ? DEFAULT_SUMMARIZATION_KEEP_LAST_ROUNDS
                : summarizationKeepLastRounds;
    }

    /**
     * The chat model resource that writes summaries, or null to use the chat model that answers the
     * request.
     */
    @Nullable
    public String getSummarizer() {
        return summarizer;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ContextManagementPolicy)) {
            return false;
        }
        ContextManagementPolicy that = (ContextManagementPolicy) o;
        return Objects.equals(toolResultMaxChars, that.toolResultMaxChars)
                && Objects.equals(toolResultKeepLastRounds, that.toolResultKeepLastRounds)
                && Objects.equals(toolResultMaskStepRounds, that.toolResultMaskStepRounds)
                && Objects.equals(maxInputTokens, that.maxInputTokens)
                && Objects.equals(summarizationTriggerRatio, that.summarizationTriggerRatio)
                && Objects.equals(summarizationKeepLastRounds, that.summarizationKeepLastRounds)
                && Objects.equals(summarizer, that.summarizer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                toolResultMaxChars,
                toolResultKeepLastRounds,
                toolResultMaskStepRounds,
                maxInputTokens,
                summarizationTriggerRatio,
                summarizationKeepLastRounds,
                summarizer);
    }

    @Override
    public String toString() {
        return "ContextManagementPolicy" + toMap();
    }

    /** Builds a {@link ContextManagementPolicy}; a value that is not set stays off. */
    public static final class Builder {
        @Nullable private Integer toolResultMaxChars;
        @Nullable private Integer toolResultKeepLastRounds;
        @Nullable private Integer toolResultMaskStepRounds;
        @Nullable private Integer maxInputTokens;
        @Nullable private Double summarizationTriggerRatio;
        @Nullable private Integer summarizationKeepLastRounds;
        @Nullable private String summarizer;

        private Builder() {}

        /** See {@link ContextManagementPolicy#getToolResultMaxChars()}; at least 100. */
        public Builder toolResultMaxChars(int maxChars) {
            this.toolResultMaxChars = maxChars;
            return this;
        }

        /** See {@link ContextManagementPolicy#getToolResultKeepLastRounds()}; at least 1. */
        public Builder toolResultKeepLastRounds(int rounds) {
            this.toolResultKeepLastRounds = rounds;
            return this;
        }

        /**
         * See {@link ContextManagementPolicy#getToolResultMaskStepRounds()}; at least 1, and only
         * with {@link #toolResultKeepLastRounds}.
         */
        public Builder toolResultMaskStepRounds(int rounds) {
            this.toolResultMaskStepRounds = rounds;
            return this;
        }

        /** See {@link ContextManagementPolicy#getMaxInputTokens()}; at least 1. */
        public Builder maxInputTokens(int tokens) {
            this.maxInputTokens = tokens;
            return this;
        }

        /**
         * See {@link ContextManagementPolicy#getSummarizationTriggerRatio()}; greater than 0 and at
         * most 1, and only with {@link #maxInputTokens}.
         */
        public Builder summarizationTriggerRatio(double ratio) {
            this.summarizationTriggerRatio = ratio;
            return this;
        }

        /**
         * See {@link ContextManagementPolicy#getSummarizationKeepLastRounds()}; at least 1, and
         * only with {@link #maxInputTokens}.
         */
        public Builder summarizationKeepLastRounds(int rounds) {
            this.summarizationKeepLastRounds = rounds;
            return this;
        }

        /**
         * See {@link ContextManagementPolicy#getSummarizer()}; only with {@link #maxInputTokens}.
         */
        public Builder summarizer(String chatModel) {
            this.summarizer = chatModel;
            return this;
        }

        /**
         * @throws IllegalArgumentException if a value is out of range, or tunes a setting that is
         *     not set
         */
        public ContextManagementPolicy build() {
            return new ContextManagementPolicy(this, UnaryOperator.identity());
        }
    }
}
