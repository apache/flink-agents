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

package org.apache.flink.agents.api.agents;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import javax.annotation.Nullable;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Independent limits for a single chat request and its tool rounds. */
public final class ReActLoopBudget {
    public enum BudgetMode {
        SOFT,
        HARD
    }

    @Nullable private final Integer maxTurns;
    @Nullable private final Long maxTokens;
    @Nullable private final Duration maxTime;
    private final BudgetMode mode;

    private ReActLoopBudget(
            @Nullable Integer maxTurns,
            @Nullable Duration maxTime,
            @Nullable Long maxTokens,
            BudgetMode mode) {
        if (maxTurns != null && maxTurns <= 0 || maxTokens != null && maxTokens <= 0)
            throw new IllegalArgumentException("ReAct loop limits must be positive");
        if (maxTime != null
                && (maxTime.isNegative() || maxTime.isZero() || maxTime.getNano() % 1_000_000 != 0))
            throw new IllegalArgumentException(
                    "max_time must be positive with millisecond precision");
        if (maxTime != null) maxTime.toMillis();
        this.maxTurns = maxTurns;
        this.maxTokens = maxTokens;
        this.maxTime = maxTime;
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    /** Creates a builder with unlimited budgets and soft termination. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for an immutable request-scoped budget. */
    public static final class Builder {
        @Nullable private Integer maxTurns;
        @Nullable private Duration maxTime;
        @Nullable private Long maxTokens;
        private BudgetMode mode = BudgetMode.SOFT;

        private Builder() {}

        /** Sets the maximum ordinary inference rounds; null means unlimited. */
        public Builder maxTurns(@Nullable Integer maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        /** Sets the elapsed-time limit; null means unlimited. */
        public Builder maxTime(@Nullable Duration maxTime) {
            this.maxTime = maxTime;
            return this;
        }

        /** Sets the cumulative input/output token limit; null means unlimited. */
        public Builder maxTokens(@Nullable Long maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /** Sets the termination mode; null selects the default SOFT mode. */
        public Builder mode(@Nullable BudgetMode mode) {
            this.mode = mode == null ? BudgetMode.SOFT : mode;
            return this;
        }

        /**
         * Builds a budget after validating its limits.
         *
         * @throws IllegalArgumentException if a limit is non-positive or time has sub-millisecond
         *     precision
         * @throws ArithmeticException if the time limit cannot be represented in milliseconds as a
         *     long
         */
        public ReActLoopBudget build() {
            return new ReActLoopBudget(maxTurns, maxTime, maxTokens, mode);
        }
    }

    @Nullable
    public Integer getMaxTurns() {
        return maxTurns;
    }

    @Nullable
    public Long getMaxTokens() {
        return maxTokens;
    }

    @Nullable
    public Duration getMaxTime() {
        return maxTime;
    }

    public BudgetMode getMode() {
        return mode;
    }

    @JsonValue
    public Map<String, Object> toMap() {
        Map<String, Object> values = new HashMap<>();
        if (maxTurns != null) values.put("max_turns", maxTurns);
        if (maxTokens != null) values.put("max_tokens", maxTokens);
        if (maxTime != null) values.put("max_time_ms", maxTime.toMillis());
        values.put("mode", mode.name());
        return values;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ReActLoopBudget fromMap(Map<?, ?> values) {
        if (!Set.of("max_turns", "max_tokens", "max_time_ms", "mode").containsAll(values.keySet()))
            throw new IllegalArgumentException("Unknown ReAct loop budget field");
        Long time = count(values.get("max_time_ms"));
        Object mode = values.get("mode");
        Long turns = count(values.get("max_turns"));
        return builder()
                .maxTurns(turns == null ? null : Math.toIntExact(turns))
                .maxTime(time == null ? null : Duration.ofMillis(time))
                .maxTokens(count(values.get("max_tokens")))
                .mode(
                        mode == null
                                ? null
                                : BudgetMode.valueOf(
                                        mode.toString().toUpperCase(java.util.Locale.ROOT)))
                .build();
    }

    @Nullable
    private static Long count(@Nullable Object value) {
        if (value == null) return null;
        if (!(value instanceof Integer
                || value instanceof Long
                || value instanceof Short
                || value instanceof Byte))
            throw new IllegalArgumentException("Expected integer count: " + value);
        return ((Number) value).longValue();
    }
}
