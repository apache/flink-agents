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

package org.apache.flink.agents.api.decision;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.annotation.Nullable;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Shared evidence and named independent questions. Input and instructions may be text, a JSON
 * object or a JSON array. Strings containing JSON remain strings. Criteria retain the selected
 * provider's native format; they are not translated between providers.
 */
public final class DecisionRequest implements Serializable {
    private final Object input;
    private final Map<String, Question> questions;

    @JsonCreator
    public DecisionRequest(
            @JsonProperty("input") Object input,
            @JsonProperty("questions") Map<String, Question> questions) {
        this.input = Objects.requireNonNull(input, "input");
        this.questions = Collections.unmodifiableMap(new LinkedHashMap<>(questions));
    }

    public Object getInput() {
        return input;
    }

    public Map<String, Question> getQuestions() {
        return questions;
    }

    /** A predicate, choice or score question, validated by the Python DecisionModel API on call. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Question implements Serializable {
        private final String type;
        @Nullable private final Object instructions;
        @Nullable private final Object criteria;

        @JsonCreator
        private Question(
                @JsonProperty("type") String type,
                @JsonProperty("instructions") @Nullable Object instructions,
                @JsonProperty("criteria") @Nullable Object criteria) {
            this.type = type;
            this.instructions = instructions;
            this.criteria = criteria;
        }

        /** Estimate the probability that the supplied condition is true. */
        public static Question predicate(Object instructions) {
            return predicate(instructions, null);
        }

        /** True/false criteria supplement the condition for providers that support them. */
        public static Question predicate(Object instructions, @Nullable Map<String, ?> criteria) {
            return new Question("predicate", Objects.requireNonNull(instructions), criteria);
        }

        /** Choose using the provider's native criteria map or list of option objects. */
        public static Question choice(Object criteria, @Nullable Object instructions) {
            return new Question("choice", instructions, Objects.requireNonNull(criteria));
        }

        /** Score the ordered levels by their expected zero-based index. */
        public static Question score(List<?> criteria, @Nullable Object instructions) {
            return new Question("score", instructions, Objects.requireNonNull(criteria));
        }

        public String getType() {
            return type;
        }

        @Nullable
        public Object getInstructions() {
            return instructions;
        }

        @Nullable
        public Object getCriteria() {
            return criteria;
        }
    }
}
