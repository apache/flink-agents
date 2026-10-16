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
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import javax.annotation.Nullable;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers keyed by the original question identifiers. Model, usage and confidence contain only
 * provider-reported values; absent values remain null. Authentication, transport and malformed
 * response errors raise exceptions rather than being represented as answers.
 */
public final class DecisionResult implements Serializable {
    private final Map<String, Answer> answers;
    @Nullable private final String model;
    @Nullable private final TokenUsage usage;
    private final Map<String, Object> metadata;

    @JsonCreator
    public DecisionResult(
            @JsonProperty("answers") Map<String, Answer> answers,
            @JsonProperty("model") @Nullable String model,
            @JsonProperty("usage") @Nullable TokenUsage usage,
            @JsonProperty("metadata") Map<String, Object> metadata) {
        this.answers = Collections.unmodifiableMap(new LinkedHashMap<>(answers));
        this.model = model;
        this.usage = usage;
        this.metadata =
                metadata == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public Map<String, Answer> getAnswers() {
        return answers;
    }

    @Nullable
    public String getModel() {
        return model;
    }

    @Nullable
    public TokenUsage getUsage() {
        return usage;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /** The discriminator preserves per-question refusals separately from model answers. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = PredicateAnswer.class, name = "predicate"),
        @JsonSubTypes.Type(value = ChoiceAnswer.class, name = "choice"),
        @JsonSubTypes.Type(value = ScoreAnswer.class, name = "score"),
        @JsonSubTypes.Type(value = RefusalAnswer.class, name = "refusal")
    })
    public abstract static class Answer implements Serializable {}

    /** Probability that the predicate is true; callers choose thresholds. */
    public static final class PredicateAnswer extends Answer {
        private final double probabilityTrue;

        @JsonCreator
        public PredicateAnswer(@JsonProperty("probability_true") double probabilityTrue) {
            this.probabilityTrue = probabilityTrue;
        }

        @JsonProperty("probability_true")
        public double getProbabilityTrue() {
            return probabilityTrue;
        }
    }

    /** String or boolean choice, plus a distribution in provider order. */
    public static final class ChoiceAnswer extends Answer {
        private final Object choice;
        private final List<ChoiceProbability> probabilities;
        @Nullable private final Double providerConfidence;

        @JsonCreator
        public ChoiceAnswer(
                @JsonProperty("choice") Object choice,
                @JsonProperty("probabilities") List<ChoiceProbability> probabilities,
                @JsonProperty("provider_confidence") @Nullable Double providerConfidence) {
            this.choice = choice;
            this.probabilities = List.copyOf(probabilities);
            this.providerConfidence = providerConfidence;
        }
        /** Returns a String or Boolean, preserving the provider value's identity. */
        public Object getChoice() {
            return choice;
        }

        public List<ChoiceProbability> getProbabilities() {
            return probabilities;
        }

        @Nullable
        @JsonProperty("provider_confidence")
        public Double getProviderConfidence() {
            return providerConfidence;
        }
    }

    /** A string or boolean value and its probability; true and "true" remain distinct. */
    public static final class ChoiceProbability implements Serializable {
        private final Object value;
        private final double probability;

        @JsonCreator
        public ChoiceProbability(
                @JsonProperty("value") Object value,
                @JsonProperty("probability") double probability) {
            this.value = value;
            this.probability = probability;
        }

        public Object getValue() {
            return value;
        }

        public double getProbability() {
            return probability;
        }
    }

    /** Expected zero-based level index, which may lie between levels. */
    public static final class ScoreAnswer extends Answer {
        private final double score;
        private final List<ScoreProbability> probabilities;
        @Nullable private final Double providerConfidence;

        @JsonCreator
        public ScoreAnswer(
                @JsonProperty("score") double score,
                @JsonProperty("probabilities") List<ScoreProbability> probabilities,
                @JsonProperty("provider_confidence") @Nullable Double providerConfidence) {
            this.score = score;
            this.probabilities = List.copyOf(probabilities);
            this.providerConfidence = providerConfidence;
        }

        public double getScore() {
            return score;
        }

        public List<ScoreProbability> getProbabilities() {
            return probabilities;
        }

        @Nullable
        @JsonProperty("provider_confidence")
        public Double getProviderConfidence() {
            return providerConfidence;
        }
    }

    /** A level index, its text or structured JSON label, and its probability. */
    public static final class ScoreProbability implements Serializable {
        private final int value;
        private final Object label;
        private final double probability;

        @JsonCreator
        public ScoreProbability(
                @JsonProperty("value") int value,
                @JsonProperty("label") Object label,
                @JsonProperty("probability") double probability) {
            this.value = value;
            this.label = label;
            this.probability = probability;
        }

        public int getValue() {
            return value;
        }

        public Object getLabel() {
            return label;
        }

        public double getProbability() {
            return probability;
        }
    }

    /** An explicit refusal; the reason is null unless reported by the provider. */
    public static final class RefusalAnswer extends Answer {
        @Nullable private final String reason;

        @JsonCreator
        public RefusalAnswer(@JsonProperty("reason") @Nullable String reason) {
            this.reason = reason;
        }

        @Nullable
        public String getReason() {
            return reason;
        }
    }

    /**
     * Per-call counts. Null means unreported; counts are neither inferred nor replaced with zero.
     */
    public static final class TokenUsage implements Serializable {
        @Nullable private final Long inputTokens;
        @Nullable private final Long outputTokens;
        @Nullable private final Long totalTokens;

        @JsonCreator
        public TokenUsage(
                @JsonProperty("input_tokens") @Nullable Long inputTokens,
                @JsonProperty("output_tokens") @Nullable Long outputTokens,
                @JsonProperty("total_tokens") @Nullable Long totalTokens) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.totalTokens = totalTokens;
        }

        @Nullable
        @JsonProperty("input_tokens")
        public Long getInputTokens() {
            return inputTokens;
        }

        @Nullable
        @JsonProperty("output_tokens")
        public Long getOutputTokens() {
            return outputTokens;
        }

        @Nullable
        @JsonProperty("total_tokens")
        public Long getTotalTokens() {
            return totalTokens;
        }
    }
}
