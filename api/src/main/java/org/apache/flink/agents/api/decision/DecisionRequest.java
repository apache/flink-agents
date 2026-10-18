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
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import javax.annotation.Nullable;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Shared JSON evidence and named independent questions. Strings remain text, including strings
 * containing JSON. Providers validate supported content and convert questions before making calls.
 */
public final class DecisionRequest implements Serializable {
    @Nullable private final Object input;
    private final Map<String, Question> questions;

    @JsonCreator
    public DecisionRequest(
            @JsonProperty(value = "input", required = true) @Nullable Object input,
            @JsonProperty(value = "questions", required = true) Map<String, Question> questions) {
        this.input = DecisionJson.copy(input, "input");
        Objects.requireNonNull(questions, "questions");
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("request requires at least one question");
        }
        Map<String, Question> validated = new LinkedHashMap<>();
        questions.forEach(
                (name, question) ->
                        validated.put(
                                Objects.requireNonNull(name, "question name"),
                                Objects.requireNonNull(question, "question")));
        this.questions = Collections.unmodifiableMap(validated);
    }

    @Nullable
    public Object getInput() {
        return input;
    }

    public Map<String, Question> getQuestions() {
        return questions;
    }

    /** A typed predicate, choice or score question with optional JSON instructions. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = PredicateQuestion.class, name = "predicate"),
        @JsonSubTypes.Type(value = ChoiceQuestion.class, name = "choice"),
        @JsonSubTypes.Type(value = ScoreQuestion.class, name = "score")
    })
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public abstract static class Question implements Serializable {
        @Nullable private final Object instructions;

        private Question(@Nullable Object instructions) {
            this.instructions = DecisionJson.copy(instructions, "instructions");
        }

        /** Estimate the probability that the supplied condition is true. */
        public static PredicateQuestion predicate(@Nullable Object instructions) {
            return predicate(instructions, null);
        }

        /** Define a condition using instructions, true/false descriptions, or both. */
        public static PredicateQuestion predicate(
                @Nullable Object instructions, @Nullable PredicateCriteria criteria) {
            return new PredicateQuestion(instructions, criteria);
        }

        /** Choose among string alternatives mapped to their JSON descriptions. */
        public static ChoiceQuestion choice(
                Map<String, ?> criteria, @Nullable Object instructions) {
            return new ChoiceQuestion(instructions, criteria);
        }

        /** Score JSON descriptions ordered from lowest to highest. */
        public static ScoreQuestion score(List<?> criteria, @Nullable Object instructions) {
            return new ScoreQuestion(instructions, criteria);
        }

        @Nullable
        public Object getInstructions() {
            return instructions;
        }
    }

    /** A condition defined by instructions or at least one non-null outcome description. */
    public static final class PredicateQuestion extends Question {
        @Nullable private final PredicateCriteria criteria;

        @JsonCreator
        public PredicateQuestion(
                @JsonProperty("instructions") @Nullable Object instructions,
                @JsonProperty("criteria") @Nullable PredicateCriteria criteria) {
            super(instructions);
            if (instructions == null
                    && (criteria == null
                            || (criteria.whenTrue == null && criteria.whenFalse == null))) {
                throw new IllegalArgumentException(
                        "predicate requires instructions or a true/false criterion");
            }
            this.criteria = criteria;
        }

        @Nullable
        public PredicateCriteria getCriteria() {
            return criteria;
        }
    }

    /** JSON descriptions of the circumstances in which a predicate is true or false. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class PredicateCriteria implements Serializable {
        @Nullable private final Object whenTrue;
        @Nullable private final Object whenFalse;

        @JsonCreator
        public PredicateCriteria(
                @JsonProperty("true") @Nullable Object whenTrue,
                @JsonProperty("false") @Nullable Object whenFalse) {
            this.whenTrue = DecisionJson.copy(whenTrue, "criteria.true");
            this.whenFalse = DecisionJson.copy(whenFalse, "criteria.false");
        }

        @Nullable
        @JsonProperty("true")
        public Object getTrue() {
            return whenTrue;
        }

        @Nullable
        @JsonProperty("false")
        public Object getFalse() {
            return whenFalse;
        }
    }

    /** An unordered map of string choice values to optional JSON descriptions. */
    public static final class ChoiceQuestion extends Question {
        private final Map<String, Object> criteria;

        @JsonCreator
        public ChoiceQuestion(
                @JsonProperty("instructions") @Nullable Object instructions,
                @JsonProperty(value = "criteria", required = true) Map<String, ?> criteria) {
            super(instructions);
            Objects.requireNonNull(criteria, "criteria");
            if (criteria.isEmpty()) {
                throw new IllegalArgumentException("choice requires at least one option");
            }
            Map<String, Object> descriptions = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : criteria.entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IllegalArgumentException("choice criteria require string keys");
                }
                String value = (String) entry.getKey();
                descriptions.put(value, DecisionJson.copy(entry.getValue(), "criteria." + value));
            }
            this.criteria = Collections.unmodifiableMap(descriptions);
        }

        public Map<String, Object> getCriteria() {
            return criteria;
        }
    }

    /** Ordered JSON descriptions identified by position; each item is a complete description. */
    public static final class ScoreQuestion extends Question {
        private final List<?> criteria;

        @JsonCreator
        public ScoreQuestion(
                @JsonProperty("instructions") @Nullable Object instructions,
                @JsonProperty(value = "criteria", required = true) List<?> criteria) {
            super(instructions);
            Objects.requireNonNull(criteria, "criteria");
            if (criteria.isEmpty()) {
                throw new IllegalArgumentException("score requires at least one level");
            }
            this.criteria = (List<?>) DecisionJson.copy(criteria, "criteria");
        }

        public List<?> getCriteria() {
            return criteria;
        }
    }
}
