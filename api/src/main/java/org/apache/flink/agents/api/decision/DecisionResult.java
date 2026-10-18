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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.annotation.Nullable;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Answers keyed by the original question identifiers. Model, usage and confidence contain only
 * provider-reported values; absent values remain null. Authentication, transport and malformed
 * response errors raise exceptions rather than being represented as answers.
 */
public final class DecisionResult implements Serializable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Answer> answers;
    @Nullable private final String model;
    @Nullable private final TokenUsage usage;
    private final Map<String, Object> metadata;

    public DecisionResult(
            Map<String, Answer> answers,
            @Nullable String model,
            @Nullable TokenUsage usage,
            Map<String, Object> metadata) {
        Objects.requireNonNull(answers, "answers");
        if (answers.isEmpty()) {
            throw new IllegalArgumentException("result requires at least one answer");
        }
        Map<String, Answer> validated = new LinkedHashMap<>();
        answers.forEach(
                (name, answer) ->
                        validated.put(
                                Objects.requireNonNull(name, "answer name"),
                                Objects.requireNonNull(answer, "answer")));
        this.answers = Collections.unmodifiableMap(validated);
        this.model = model;
        this.usage = usage;
        this.metadata =
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(Objects.requireNonNull(metadata, "metadata")));
    }

    @JsonCreator
    private static DecisionResult fromJson(
            @JsonProperty(value = "answers", required = true) Map<String, Answer> answers,
            @JsonProperty("model") @Nullable Object model,
            @JsonProperty("usage") @Nullable TokenUsage usage,
            @JsonProperty("metadata") @Nullable JsonNode metadata) {
        if (metadata != null && !metadata.isObject()) {
            throw new IllegalArgumentException("metadata must be an object");
        }
        return new DecisionResult(
                answers,
                DecisionJson.optionalString(model, "model"),
                usage,
                metadata == null
                        ? Map.of()
                        : MAPPER.convertValue(
                                metadata, new TypeReference<Map<String, Object>>() {}));
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

        public PredicateAnswer(double probabilityTrue) {
            DecisionJson.requireProbability(probabilityTrue, "probability_true");
            this.probabilityTrue = probabilityTrue;
        }

        @JsonCreator
        private static PredicateAnswer fromJson(
                @JsonProperty(value = "probability_true", required = true) Object probabilityTrue) {
            return new PredicateAnswer(DecisionJson.number(probabilityTrue, "probability_true"));
        }

        @JsonProperty("probability_true")
        public double getProbabilityTrue() {
            return probabilityTrue;
        }
    }

    /** String choice, plus case-sensitive choice probabilities in provider order. */
    public static final class ChoiceAnswer extends Answer {
        private final String choice;
        private final Map<String, Double> probabilities;
        @Nullable private final Double providerConfidence;

        public ChoiceAnswer(
                String choice,
                Map<String, Double> probabilities,
                @Nullable Double providerConfidence) {
            this.choice = Objects.requireNonNull(choice, "choice");
            Objects.requireNonNull(probabilities, "probabilities");
            if (probabilities.isEmpty()) {
                throw new IllegalArgumentException("choice answer requires probabilities");
            }
            Map<String, Double> distribution = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : probabilities.entrySet()) {
                String value = DecisionJson.string(entry.getKey(), "choice value");
                double probability = DecisionJson.number(entry.getValue(), "probability");
                DecisionJson.requireProbability(probability, "probability");
                distribution.put(value, probability);
            }
            this.probabilities = Collections.unmodifiableMap(distribution);
            if (providerConfidence != null) {
                DecisionJson.requireProbability(providerConfidence, "provider_confidence");
            }
            this.providerConfidence = providerConfidence;
        }

        @JsonCreator
        private static ChoiceAnswer fromJson(
                @JsonProperty(value = "choice", required = true) Object choice,
                @JsonProperty(value = "probabilities", required = true)
                        Map<String, Object> probabilities,
                @JsonProperty("provider_confidence") @Nullable Object providerConfidence) {
            Objects.requireNonNull(probabilities, "probabilities");
            Map<String, Double> distribution = new LinkedHashMap<>();
            probabilities.forEach(
                    (value, probability) ->
                            distribution.put(
                                    value, DecisionJson.number(probability, "probability")));
            return new ChoiceAnswer(
                    DecisionJson.string(choice, "choice"),
                    distribution,
                    DecisionJson.optionalNumber(providerConfidence, "provider_confidence"));
        }

        public String getChoice() {
            return choice;
        }

        public Map<String, Double> getProbabilities() {
            return probabilities;
        }

        @Nullable
        @JsonProperty("provider_confidence")
        public Double getProviderConfidence() {
            return providerConfidence;
        }
    }

    /** Expected zero-based level index and distributions over the original JSON descriptions. */
    public static final class ScoreAnswer extends Answer {
        private final double score;
        private final Map<Integer, Double> probabilities;
        private final Map<Integer, Object> legend;
        @Nullable private final Double providerConfidence;

        public ScoreAnswer(
                double score,
                Map<Integer, Double> probabilities,
                Map<Integer, ?> legend,
                @Nullable Double providerConfidence) {
            if (!Double.isFinite(score) || score < 0) {
                throw new IllegalArgumentException("score must be finite and nonnegative");
            }
            this.score = score;
            Objects.requireNonNull(probabilities, "probabilities");
            if (probabilities.isEmpty()) {
                throw new IllegalArgumentException("score answer requires probabilities");
            }
            Map<Integer, Double> distribution = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : probabilities.entrySet()) {
                int index = DecisionJson.index(entry.getKey());
                double probability = DecisionJson.number(entry.getValue(), "probability");
                DecisionJson.requireProbability(probability, "probability");
                distribution.put(index, probability);
            }
            this.probabilities = Collections.unmodifiableMap(distribution);
            Objects.requireNonNull(legend, "legend");
            Map<Integer, Object> descriptions = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : legend.entrySet()) {
                int index = DecisionJson.index(entry.getKey());
                descriptions.put(
                        index, DecisionJson.copy(entry.getValue(), "legend[" + index + "]"));
            }
            this.legend = Collections.unmodifiableMap(descriptions);
            if (providerConfidence != null) {
                DecisionJson.requireProbability(providerConfidence, "provider_confidence");
            }
            this.providerConfidence = providerConfidence;
        }

        @JsonCreator
        private static ScoreAnswer fromJson(
                @JsonProperty(value = "score", required = true) Object score,
                @JsonProperty(value = "probabilities", required = true)
                        Map<String, Object> probabilities,
                @JsonProperty("legend") @Nullable JsonNode legend,
                @JsonProperty("provider_confidence") @Nullable Object providerConfidence) {
            Objects.requireNonNull(probabilities, "probabilities");
            Map<Integer, Double> distribution = new LinkedHashMap<>();
            probabilities.forEach(
                    (key, value) ->
                            distribution.put(
                                    DecisionJson.indexKey(key),
                                    DecisionJson.number(value, "probability")));
            Map<Integer, Object> descriptions = new LinkedHashMap<>();
            if (legend != null) {
                if (!legend.isObject()) {
                    throw new IllegalArgumentException("legend must be an object");
                }
                legend.fields()
                        .forEachRemaining(
                                entry ->
                                        descriptions.put(
                                                DecisionJson.indexKey(entry.getKey()),
                                                MAPPER.convertValue(
                                                        entry.getValue(), Object.class)));
            }
            return new ScoreAnswer(
                    DecisionJson.number(score, "score"),
                    distribution,
                    descriptions,
                    DecisionJson.optionalNumber(providerConfidence, "provider_confidence"));
        }

        public double getScore() {
            return score;
        }

        public Map<Integer, Double> getProbabilities() {
            return probabilities;
        }

        /** Original request descriptions keyed by zero-based level indices. */
        public Map<Integer, Object> getLegend() {
            return legend;
        }

        @Nullable
        @JsonProperty("provider_confidence")
        public Double getProviderConfidence() {
            return providerConfidence;
        }
    }

    /** An explicit refusal; the reason is null unless reported by the provider. */
    public static final class RefusalAnswer extends Answer {
        @Nullable private final String reason;

        public RefusalAnswer(@Nullable String reason) {
            this.reason = reason;
        }

        @JsonCreator
        private static RefusalAnswer fromJson(@JsonProperty("reason") @Nullable Object reason) {
            return new RefusalAnswer(DecisionJson.optionalString(reason, "reason"));
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

        public TokenUsage(
                @Nullable Long inputTokens,
                @Nullable Long outputTokens,
                @Nullable Long totalTokens) {
            requireTokenCount(inputTokens);
            requireTokenCount(outputTokens);
            requireTokenCount(totalTokens);
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.totalTokens = totalTokens;
        }

        @JsonCreator
        private static TokenUsage fromJson(
                @JsonProperty("input_tokens") @Nullable Object inputTokens,
                @JsonProperty("output_tokens") @Nullable Object outputTokens,
                @JsonProperty("total_tokens") @Nullable Object totalTokens) {
            return new TokenUsage(
                    DecisionJson.optionalTokenCount(inputTokens, "input_tokens"),
                    DecisionJson.optionalTokenCount(outputTokens, "output_tokens"),
                    DecisionJson.optionalTokenCount(totalTokens, "total_tokens"));
        }

        private static void requireTokenCount(@Nullable Long count) {
            if (count != null && count < 0) {
                throw new IllegalArgumentException("token count must be nonnegative");
            }
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
