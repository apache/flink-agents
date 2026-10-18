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

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.decision.DecisionRequest.ChoiceQuestion;
import org.apache.flink.agents.api.decision.DecisionRequest.PredicateCriteria;
import org.apache.flink.agents.api.decision.DecisionRequest.PredicateQuestion;
import org.apache.flink.agents.api.decision.DecisionRequest.Question;
import org.apache.flink.agents.api.decision.DecisionRequest.ScoreQuestion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionDataTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void questionsRoundTripWithTypedKindsAndJsonDescriptions() throws Exception {
        Map<String, Question> questions = new LinkedHashMap<>();
        questions.put(
                "predicate",
                new PredicateQuestion(null, new PredicateCriteria(null, Map.of("receipt", false))));
        questions.put(
                "choice",
                new ChoiceQuestion(
                        "Choose a route.",
                        Map.of(
                                "Approve",
                                "Eligible",
                                "approve",
                                Map.of("review", "Manually approve"))));
        questions.put(
                "score",
                new ScoreQuestion("Rate severity.", List.of("low", Map.of("label", "high"))));
        DecisionRequest request = new DecisionRequest(Map.of("receipt", true), questions);
        DecisionRequest restored =
                MAPPER.readValue(MAPPER.writeValueAsString(request), DecisionRequest.class);

        PredicateQuestion predicate = (PredicateQuestion) restored.getQuestions().get("predicate");
        assertThat(predicate.getInstructions()).isNull();
        assertThat(predicate.getCriteria().getFalse()).isEqualTo(Map.of("receipt", false));
        assertThat(((ChoiceQuestion) restored.getQuestions().get("choice")).getCriteria())
                .containsOnlyKeys("Approve", "approve");
        assertThat(((ScoreQuestion) restored.getQuestions().get("score")).getCriteria())
                .isEqualTo(List.of("low", Map.of("label", "high")));
        assertThat(MAPPER.<JsonNode>valueToTree(restored)).isEqualTo(MAPPER.valueToTree(request));
    }

    @Test
    void predicateRequiresContentWithoutTreatingEmptyJsonAsMissing() {
        assertThatThrownBy(() -> Question.predicate(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Question.predicate(null, new PredicateCriteria(null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Question.predicate("").getInstructions()).isEqualTo("");
        assertThat(
                        Question.predicate(null, new PredicateCriteria(Map.of(), null))
                                .getCriteria()
                                .getTrue())
                .isEqualTo(Map.of());
    }

    @Test
    void choiceCriteriaPreserveCaseAndCompleteJsonDescriptions() throws Exception {
        Map<String, Object> descriptions = new LinkedHashMap<>();
        descriptions.put("Approve", Map.of("value", true, "description", "literal JSON"));
        descriptions.put("approve", null);
        ChoiceQuestion question = Question.choice(descriptions, "Choose a route.");

        assertThat(question.getCriteria()).containsOnlyKeys("Approve", "approve");
        assertThat(question.getCriteria().get("Approve"))
                .isEqualTo(Map.of("value", true, "description", "literal JSON"));
        assertThat(question.getCriteria()).containsEntry("approve", null);
        JsonNode json = MAPPER.valueToTree(question);
        assertThat(json.get("criteria").isObject()).isTrue();
        assertThat(MAPPER.<JsonNode>valueToTree(MAPPER.treeToValue(json, ChoiceQuestion.class)))
                .isEqualTo(json);
        assertThatThrownBy(() -> Question.choice(Map.of(), "Choose."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scoreCriteriaPreserveEveryJsonDescriptionWithoutInterpretingKeys() throws Exception {
        List<?> descriptions =
                Arrays.asList(
                        "{\"label\":\"literal text\"}",
                        Map.of("label", 42, "description", List.of("business data")),
                        List.of("high"),
                        null,
                        true,
                        42);
        ScoreQuestion question = Question.score(descriptions, "Rate severity.");
        assertThat(question.getCriteria()).isEqualTo(descriptions);
        JsonNode json = MAPPER.valueToTree(question);
        assertThat(MAPPER.treeToValue(json, ScoreQuestion.class).getCriteria())
                .isEqualTo(descriptions);
        assertThat(Question.score(List.of("same", "same"), "Rate severity.").getCriteria())
                .hasSize(2);
        assertThatThrownBy(() -> Question.score(List.of(), "Rate severity."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void contentMustBeFiniteRecursiveJson() {
        Map<String, Question> questions = Map.of("p", Question.predicate("P?"));
        assertThat(new DecisionRequest(null, questions).getInput()).isNull();
        assertThat(new DecisionRequest(false, questions).getInput()).isEqualTo(false);
        assertThatThrownBy(() -> new DecisionRequest("evidence", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one question");
        assertThatThrownBy(
                        () -> new DecisionRequest(Map.of("nested", List.of(Double.NaN)), questions))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("input.nested[0]");
        assertThatThrownBy(() -> new PredicateCriteria(Double.POSITIVE_INFINITY, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Question.choice(Map.of("a", Double.NaN), "Choose."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Question.score(List.of(Map.of(1, "value")), "Rate severity."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("string object keys");
        assertThatThrownBy(() -> Question.score(List.of(new Object()), "Rate severity."))
                .isInstanceOf(IllegalArgumentException.class);
        List<Object> cycle = new ArrayList<>();
        cycle.add(cycle);
        assertThatThrownBy(() -> new DecisionRequest(cycle, questions))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void criteriaAreIndependentSnapshotsOfJsonContent() {
        List<Object> description = new ArrayList<>(List.of("before"));
        ChoiceQuestion choice = Question.choice(Map.of("a", description), "Choose.");
        ScoreQuestion score = Question.score(List.of(description), "Rate severity.");
        description.add("after");
        assertThat(choice.getCriteria().get("a")).isEqualTo(List.of("before"));
        assertThat(score.getCriteria()).isEqualTo(List.of(List.of("before")));
    }

    @Test
    void choiceAnswersPreserveStringCaseAndProviderOrder() throws Exception {
        Map<String, Double> probabilities = new LinkedHashMap<>();
        probabilities.put("Approve", 0.8);
        probabilities.put("approve", 0.2);
        DecisionResult.ChoiceAnswer original =
                new DecisionResult.ChoiceAnswer("Approve", probabilities, 0.7);
        probabilities.put("Approve", 0.5);
        probabilities.put("other", 0.5);
        DecisionResult result = new DecisionResult(Map.of("route", original), null, null, Map.of());
        DecisionResult restored =
                MAPPER.readValue(MAPPER.writeValueAsString(result), DecisionResult.class);
        DecisionResult.ChoiceAnswer answer =
                (DecisionResult.ChoiceAnswer) restored.getAnswers().get("route");
        assertThat(answer.getChoice()).isEqualTo("Approve");
        assertThat(answer.getProbabilities().keySet()).containsExactly("Approve", "approve");
        assertThat(answer.getProbabilities().values()).containsExactly(0.8, 0.2);
        assertThat(answer.getProviderConfidence()).isEqualTo(0.7);
        assertThat(MAPPER.valueToTree(answer).get("probabilities").isObject()).isTrue();
        assertThatThrownBy(() -> original.getProbabilities().put("other", 0.1))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> answer.getProbabilities().put("other", 0.1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void choiceProbabilitiesRequireNonemptyNamedFiniteValues() {
        assertThatThrownBy(() -> new DecisionResult.ChoiceAnswer("a", Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires probabilities");
        for (double probability : new double[] {-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(
                            () ->
                                    new DecisionResult.ChoiceAnswer(
                                            "a", Map.of("a", probability), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("probability must be finite and between 0 and 1");
        }
        Map<String, Double> probabilities = new LinkedHashMap<>();
        probabilities.put("a", null);
        assertThatThrownBy(() -> new DecisionResult.ChoiceAnswer("a", probabilities, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("probability must be a number");
        probabilities.clear();
        probabilities.put(null, 1.0);
        assertThatThrownBy(() -> new DecisionResult.ChoiceAnswer("a", probabilities, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("choice value must be a string");
    }

    @Test
    void choiceJsonRequiresNonemptyProbabilityMap() throws Exception {
        for (String json :
                List.of(
                        "{\"type\":\"choice\",\"choice\":\"a\"}",
                        "{\"type\":\"choice\",\"choice\":\"a\",\"probabilities\":null}",
                        "{\"type\":\"choice\",\"choice\":\"a\",\"probabilities\":{}}")) {
            assertThatThrownBy(() -> MAPPER.readValue(json, DecisionResult.ChoiceAnswer.class))
                    .isInstanceOf(JsonMappingException.class)
                    .hasMessageContaining("probabilities");
        }
        assertThat(
                        MAPPER.readValue(
                                        "{\"type\":\"choice\",\"choice\":\"a\",\"probabilities\":{\"a\":1,\"b\":0}}",
                                        DecisionResult.ChoiceAnswer.class)
                                .getProbabilities())
                .containsExactlyInAnyOrderEntriesOf(Map.of("a", 1.0, "b", 0.0));
    }

    @Test
    void scoreAnswersRoundTripProbabilityAndOriginalDescriptionMaps() throws Exception {
        Map<Integer, Object> legend = new LinkedHashMap<>();
        legend.put(0, "low");
        legend.put(1, Map.of("label", "medium"));
        legend.put(2, List.of("high"));
        DecisionResult result =
                new DecisionResult(
                        Map.of(
                                "score",
                                new DecisionResult.ScoreAnswer(
                                        1.25, Map.of(0, 0.0, 1, 0.75, 2, 0.25), legend, null)),
                        null,
                        null,
                        Map.of());
        DecisionResult restored =
                MAPPER.readValue(MAPPER.writeValueAsString(result), DecisionResult.class);
        DecisionResult.ScoreAnswer answer =
                (DecisionResult.ScoreAnswer) restored.getAnswers().get("score");
        assertThat(answer.getProbabilities())
                .containsExactlyInAnyOrderEntriesOf(Map.of(0, 0.0, 1, 0.75, 2, 0.25));
        assertThat(answer.getLegend()).containsExactlyInAnyOrderEntriesOf(legend);
        assertThat(MAPPER.<JsonNode>valueToTree(restored)).isEqualTo(MAPPER.valueToTree(result));
        Map<Integer, Object> nullableLegend = new LinkedHashMap<>();
        nullableLegend.put(0, null);
        assertThat(
                        new DecisionResult.ScoreAnswer(0, Map.of(0, 1.0), nullableLegend, null)
                                .getLegend())
                .containsEntry(0, null);
    }

    @Test
    void scoreLegendMayBeOmittedButMustBeAnObjectWhenProvided() throws Exception {
        String json = "{\"type\":\"score\",\"score\":0,\"probabilities\":{\"0\":1}}";
        assertThat(MAPPER.readValue(json, DecisionResult.ScoreAnswer.class).getLegend()).isEmpty();
        assertThatThrownBy(
                        () ->
                                MAPPER.readValue(
                                        "{\"type\":\"score\",\"score\":0,\"probabilities\":{\"0\":1},\"legend\":null}",
                                        DecisionResult.ScoreAnswer.class))
                .isInstanceOf(JsonMappingException.class)
                .hasMessageContaining("legend must be an object");
    }

    @Test
    void scoreJsonMapKeysMustBeCanonicalNonnegativeIntegerIndices() {
        for (String key : List.of("-1", "01", "+1", "1.0", "-0", "1e0", "2147483648", "other")) {
            String probabilities =
                    "{\"type\":\"score\",\"score\":0,\"probabilities\":{\""
                            + key
                            + "\":1},\"legend\":{\"0\":\"low\"}}";
            String legend =
                    "{\"type\":\"score\",\"score\":0,\"probabilities\":{\"0\":1},\"legend\":{\""
                            + key
                            + "\":\"low\"}}";
            assertThatThrownBy(
                            () -> MAPPER.readValue(probabilities, DecisionResult.ScoreAnswer.class))
                    .isInstanceOf(JsonMappingException.class)
                    .hasMessageContaining("level index key");
            assertThatThrownBy(() -> MAPPER.readValue(legend, DecisionResult.ScoreAnswer.class))
                    .isInstanceOf(JsonMappingException.class)
                    .hasMessageContaining("level index key");
        }
    }

    @Test
    void answersRequireFiniteProbabilitiesAndNonnegativeScoresAndIndices() {
        assertThatThrownBy(() -> new DecisionResult.PredicateAnswer(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DecisionResult.PredicateAnswer(1.01))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DecisionResult.ChoiceAnswer("a", Map.of("a", 1.0), -1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new DecisionResult.ScoreAnswer(
                                        Double.POSITIVE_INFINITY,
                                        Map.of(0, 1.0),
                                        Map.of(0, "low"),
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DecisionResult.ScoreAnswer(0, Map.of(-1, 1.0), Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DecisionResult.ScoreAnswer(0, Map.of(), Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new DecisionResult.ScoreAnswer(
                                        0, Map.of(0, Double.NaN), Map.of(0, "low"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new DecisionResult.ScoreAnswer(
                                        0, Map.of(0, 1.0), Map.of(0, Double.NaN), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jsonFieldsRejectMissingNullAndCoercedNumbersAndChoiceStrings() {
        for (String json :
                List.of(
                        "{\"type\":\"predicate\"}",
                        "{\"type\":\"predicate\",\"probability_true\":null}",
                        "{\"type\":\"predicate\",\"probability_true\":\"0.5\"}",
                        "{\"type\":\"predicate\",\"probability_true\":true}")) {
            assertThatThrownBy(() -> MAPPER.readValue(json, DecisionResult.PredicateAnswer.class))
                    .isInstanceOf(JsonMappingException.class)
                    .hasMessageContaining("probability_true");
        }
        for (String value : List.of("null", "42", "true")) {
            assertThatThrownBy(
                            () ->
                                    MAPPER.readValue(
                                            "{\"type\":\"choice\",\"choice\":"
                                                    + value
                                                    + ",\"probabilities\":{\"a\":1}}",
                                            DecisionResult.ChoiceAnswer.class))
                    .isInstanceOf(JsonMappingException.class)
                    .hasMessageContaining("choice must be a string");
        }
        for (String probability : List.of("null", "\"1\"", "true")) {
            String choiceJson =
                    "{\"type\":\"choice\",\"choice\":\"a\",\"probabilities\":{\"a\":"
                            + probability
                            + "}}";
            assertThatThrownBy(
                            () -> MAPPER.readValue(choiceJson, DecisionResult.ChoiceAnswer.class))
                    .isInstanceOf(JsonMappingException.class)
                    .hasMessageContaining("probability must be a number");
            String json =
                    "{\"type\":\"score\",\"score\":0,\"probabilities\":{\"0\":"
                            + probability
                            + "},\"legend\":{\"0\":\"low\"}}";
            assertThatThrownBy(() -> MAPPER.readValue(json, DecisionResult.ScoreAnswer.class))
                    .isInstanceOf(JsonMappingException.class)
                    .hasMessageContaining("probability must be a number");
        }
        assertThatThrownBy(
                        () ->
                                MAPPER.readValue(
                                        "{\"type\":\"score\",\"score\":null,\"probabilities\":{\"0\":1},\"legend\":{\"0\":\"low\"}}",
                                        DecisionResult.ScoreAnswer.class))
                .isInstanceOf(JsonMappingException.class)
                .hasMessageContaining("score must be a number");
        assertThatThrownBy(() -> MAPPER.readValue("{\"questions\":{}}", DecisionRequest.class))
                .isInstanceOf(JsonMappingException.class)
                .hasMessageContaining("input");
    }

    @Test
    void resultsRequireNamedNonNullAnswers() {
        Map<String, DecisionResult.Answer> answers = new LinkedHashMap<>();
        answers.put("a", null);
        assertThatThrownBy(() -> new DecisionResult(answers, null, null, Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("answer");
        answers.clear();
        answers.put(null, new DecisionResult.RefusalAnswer(null));
        assertThatThrownBy(() -> new DecisionResult(answers, null, null, Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("answer name");

        for (String json :
                List.of(
                        "{}",
                        "{\"answers\":null}",
                        "{\"answers\":{}}",
                        "{\"answers\":{\"a\":null}}")) {
            assertThatThrownBy(() -> MAPPER.readValue(json, DecisionResult.class))
                    .isInstanceOf(JsonMappingException.class)
                    .hasMessageContaining("answer");
        }
    }

    @Test
    void optionalProviderStringsRejectCoercion() {
        for (String value : List.of("42", "true", "[]", "{}")) {
            assertThatThrownBy(
                            () ->
                                    MAPPER.readValue(
                                            "{\"answers\":{\"a\":{\"type\":\"refusal\"}},\"model\":"
                                                    + value
                                                    + "}",
                                            DecisionResult.class))
                    .isInstanceOf(JsonMappingException.class)
                    .hasMessageContaining("model must be a string");
            assertThatThrownBy(
                            () ->
                                    MAPPER.readValue(
                                            "{\"type\":\"refusal\",\"reason\":" + value + "}",
                                            DecisionResult.RefusalAnswer.class))
                    .isInstanceOf(JsonMappingException.class)
                    .hasMessageContaining("reason must be a string");
        }
    }

    @Test
    void optionalProviderStringsAndUsagePreserveNullsAndReportedValues() throws Exception {
        DecisionResult result =
                new DecisionResult(
                        Map.of("a", new DecisionResult.RefusalAnswer("Cannot answer")),
                        "reported-model",
                        new DecisionResult.TokenUsage(0L, Long.MAX_VALUE, null),
                        Map.of());
        DecisionResult restored =
                MAPPER.readValue(MAPPER.writeValueAsString(result), DecisionResult.class);
        assertThat(restored.getModel()).isEqualTo("reported-model");
        assertThat(((DecisionResult.RefusalAnswer) restored.getAnswers().get("a")).getReason())
                .isEqualTo("Cannot answer");
        assertThat(restored.getUsage().getInputTokens()).isZero();
        assertThat(restored.getUsage().getOutputTokens()).isEqualTo(Long.MAX_VALUE);
        assertThat(restored.getUsage().getTotalTokens()).isNull();

        for (String json :
                List.of(
                        "{\"answers\":{\"a\":{\"type\":\"refusal\"}}}",
                        "{\"answers\":{\"a\":{\"type\":\"refusal\",\"reason\":null}},\"model\":null,\"usage\":null}")) {
            DecisionResult absent = MAPPER.readValue(json, DecisionResult.class);
            assertThat(absent.getModel()).isNull();
            assertThat(absent.getUsage()).isNull();
            assertThat(((DecisionResult.RefusalAnswer) absent.getAnswers().get("a")).getReason())
                    .isNull();
        }
        for (String json :
                List.of(
                        "{}",
                        "{\"input_tokens\":null,\"output_tokens\":null,\"total_tokens\":null}")) {
            DecisionResult.TokenUsage usage =
                    MAPPER.readValue(json, DecisionResult.TokenUsage.class);
            assertThat(usage.getInputTokens()).isNull();
            assertThat(usage.getOutputTokens()).isNull();
            assertThat(usage.getTotalTokens()).isNull();
        }
    }

    @Test
    void tokenCountsRequireNonnegativeIntegersInLongRange() {
        for (String field : List.of("input_tokens", "output_tokens", "total_tokens")) {
            for (String value :
                    List.of(
                            "-1",
                            "1.0",
                            "1.5",
                            "\"3\"",
                            "true",
                            "9223372036854775808",
                            "-9223372036854775809")) {
                assertThatThrownBy(
                                () ->
                                        MAPPER.readValue(
                                                "{\"" + field + "\":" + value + "}",
                                                DecisionResult.TokenUsage.class))
                        .isInstanceOf(JsonMappingException.class);
            }
        }
        assertThatThrownBy(() -> new DecisionResult.TokenUsage(-1L, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonnegative");
    }

    @Test
    void resultMetadataDefaultsToEmptyButRejectsExplicitNonObjects() throws Exception {
        String answers = "\"answers\":{\"a\":{\"type\":\"refusal\"}}";
        assertThat(MAPPER.readValue("{" + answers + "}", DecisionResult.class).getMetadata())
                .isEmpty();
        assertThat(
                        MAPPER.readValue(
                                        "{" + answers + ",\"metadata\":{\"trace_id\":\"1\"}}",
                                        DecisionResult.class)
                                .getMetadata())
                .containsEntry("trace_id", "1");
        for (String value : List.of("null", "42", "true", "[]")) {
            assertThatThrownBy(
                            () ->
                                    MAPPER.readValue(
                                            "{" + answers + ",\"metadata\":" + value + "}",
                                            DecisionResult.class))
                    .isInstanceOf(JsonMappingException.class)
                    .hasMessageContaining("metadata must be an object");
        }
        assertThatThrownBy(
                        () ->
                                new DecisionResult(
                                        Map.of("a", new DecisionResult.RefusalAnswer(null)),
                                        null,
                                        null,
                                        null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("metadata");
    }
}
