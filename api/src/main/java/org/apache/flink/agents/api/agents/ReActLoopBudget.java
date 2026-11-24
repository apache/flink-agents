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
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.annotation.Nullable;

import java.time.Duration;

/** Independent limits for a single chat request and its tool rounds. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ReActLoopBudget {
    public enum BudgetMode {
        SOFT,
        HARD;

        @JsonCreator
        public static BudgetMode fromString(String value) {
            return value == null ? SOFT : valueOf(value.toUpperCase(java.util.Locale.ROOT));
        }
    }

    @Nullable private final Integer maxTurns;
    @Nullable private final Long maxTokens;
    @Nullable private final Duration maxTime;
    private final BudgetMode mode;

    @JsonCreator
    private ReActLoopBudget(
            @JsonProperty("max_turns") @Nullable Integer maxTurns,
            @JsonProperty("max_time") @Nullable Duration maxTime,
            @JsonProperty("max_tokens") @Nullable Long maxTokens,
            @JsonProperty("mode") @Nullable BudgetMode mode) {
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
        this.mode = mode == null ? BudgetMode.SOFT : mode;
    }

    /** Creates a builder with unlimited budgets and soft termination. */
    public static Builder builder() {
        return new Builder();
    }

    @Nullable
    @JsonProperty("max_turns")
    public Integer getMaxTurns() {
        return maxTurns;
    }

    @Nullable
    @JsonProperty("max_tokens")
    public Long getMaxTokens() {
        return maxTokens;
    }

    @Nullable
    @JsonProperty("max_time")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    public Duration getMaxTime() {
        return maxTime;
    }

    public BudgetMode getMode() {
        return mode;
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
}
