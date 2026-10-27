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
package org.apache.flink.agents.plan.routing;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.apache.flink.agents.api.chat.model.routing.RoutingCandidate;
import org.apache.flink.agents.api.chat.model.routing.RoutingContext;
import org.apache.flink.agents.api.chat.model.routing.RoutingDecision;
import org.apache.flink.agents.api.chat.model.routing.RoutingStrategy;
import org.apache.flink.agents.api.chat.model.routing.Strategies;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.decision.BaseDecisionModelSetup;
import org.apache.flink.agents.api.decision.DecisionRequest;
import org.apache.flink.agents.api.decision.DecisionResult;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the decision-model executor's pure functions (request building, answer mapping).
 */
class DecisionModelRoutingExecutorTest {

    private static RoutingContext ctx(List<ChatMessage> messages, Map<String, Object> promptArgs) {
        return new RoutingContext(
                UUID.randomUUID(),
                "router",
                messages,
                promptArgs,
                List.of(
                        new RoutingCandidate("small", "cheap chit-chat"),
                        new RoutingCandidate("big", "code and sql")),
                "small");
    }

    /** A runner context whose anchor candidate cannot be resolved: the raw messages are used. */
    private static RunnerContext unresolvableAnchor() {
        RunnerContext ctx = mock(RunnerContext.class);
        try {
            when(ctx.getResource(any(), any()))
                    .thenThrow(new IllegalArgumentException("no resource"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return ctx;
    }

    private static DecisionResult choice(
            String choice, Map<String, Double> probabilities, Double providerConfidence) {
        return new DecisionResult(
                Map.of(
                        DecisionModelRoutingExecutor.QUESTION_NAME,
                        new DecisionResult.ChoiceAnswer(choice, probabilities, providerConfidence)),
                "dm-v1",
                new DecisionResult.TokenUsage(120L, 4L, 124L),
                Map.of());
    }

    private static DecisionRequest.ChoiceQuestion question(DecisionRequest request) {
        return (DecisionRequest.ChoiceQuestion)
                request.getQuestions().get(DecisionModelRoutingExecutor.QUESTION_NAME);
    }

    @Test
    void requestCarriesCandidatesAsChoiceCriteriaAndConversationAsText() throws Exception {
        RoutingContext context =
                ctx(List.of(new ChatMessage(MessageRole.USER, "write some sql")), Map.of());
        Map<String, Object> metadata = new LinkedHashMap<>();
        DecisionRequest request =
                DecisionModelRoutingExecutor.buildRequest(
                        Strategies.decision("dm"), context, unresolvableAnchor(), metadata);
        assertThat(request.getInput()).isEqualTo("USER: write some sql");
        DecisionRequest.ChoiceQuestion question = question(request);
        assertThat(question.getCriteria())
                .containsExactly(
                        Map.entry("small", "cheap chit-chat"), Map.entry("big", "code and sql"));
        assertThat(question.getInstructions())
                .isEqualTo(DecisionModelRoutingExecutor.DEFAULT_INSTRUCTIONS);
        assertThat(metadata)
                .doesNotContainKeys(
                        DecisionModelRoutingExecutor.CONTEXT_TRUNCATED_KEY,
                        DecisionModelRoutingExecutor.MEDIA_DROPPED_KEY);
    }

    @Test
    void customInstructionsReplaceTheDefault() throws Exception {
        RoutingContext context = ctx(List.of(new ChatMessage(MessageRole.USER, "hi")), Map.of());
        DecisionRequest request =
                DecisionModelRoutingExecutor.buildRequest(
                        Strategies.decision("dm", "Which model should answer?"),
                        context,
                        unresolvableAnchor(),
                        new LinkedHashMap<>());
        assertThat(question(request).getInstructions()).isEqualTo("Which model should answer?");
    }

    @Test
    void requestIncludesFullConversationWithRoles() throws Exception {
        RoutingContext context =
                ctx(
                        List.of(
                                new ChatMessage(MessageRole.SYSTEM, "You review concurrency code"),
                                new ChatMessage(MessageRole.USER, "Focus on race conditions"),
                                new ChatMessage(MessageRole.ASSISTANT, "Understood"),
                                new ChatMessage(MessageRole.USER, "synchronized void transfer()")),
                        Map.of());
        String input =
                (String)
                        DecisionModelRoutingExecutor.buildRequest(
                                        Strategies.decision("dm"),
                                        context,
                                        unresolvableAnchor(),
                                        new LinkedHashMap<>())
                                .getInput();
        assertThat(input)
                .contains("SYSTEM: You review concurrency code")
                .contains("USER: Focus on race conditions")
                .contains("ASSISTANT: Understood")
                .contains("USER: synchronized void transfer()");
    }

    @Test
    void contextCapPinsSystemAndNewestAndFlagsTruncation() throws Exception {
        String oldTurn = "y".repeat(500);
        RoutingContext context =
                ctx(
                        List.of(
                                new ChatMessage(MessageRole.SYSTEM, "task framing"),
                                new ChatMessage(MessageRole.USER, oldTurn),
                                new ChatMessage(MessageRole.USER, "current question")),
                        Map.of());
        Map<String, Object> metadata = new LinkedHashMap<>();
        String input =
                (String)
                        DecisionModelRoutingExecutor.buildRequest(
                                        Strategies.decision("dm").withMaxContextChars(80),
                                        context,
                                        unresolvableAnchor(),
                                        metadata)
                                .getInput();
        assertThat(input).contains("SYSTEM: task framing").contains("USER: current question");
        assertThat(input).doesNotContain(oldTurn);
        assertThat(metadata)
                .containsEntry(DecisionModelRoutingExecutor.CONTEXT_TRUNCATED_KEY, true);
    }

    @Test
    void mediaIsLeftOutOfTheTextInputAndCounted() throws Exception {
        ChatMessage message =
                ChatMessage.user(
                        List.of(
                                new TextBlock("before"),
                                ImageBlock.fromBase64("image/png", "aGk="),
                                new TextBlock("after")));
        Map<String, Object> metadata = new LinkedHashMap<>();
        String input =
                (String)
                        DecisionModelRoutingExecutor.buildRequest(
                                        Strategies.decision("dm"),
                                        ctx(List.of(message), Map.of()),
                                        unresolvableAnchor(),
                                        metadata)
                                .getInput();
        assertThat(input).isEqualTo("USER: before\nafter");
        assertThat(metadata).containsEntry(DecisionModelRoutingExecutor.MEDIA_DROPPED_KEY, 1);
    }

    /** Tool rounds reach the decision model as data, framed the way the judge frames them. */
    @Test
    void toolCallsAndResultsAreRenderedAsText() throws Exception {
        RoutingContext context =
                ctx(
                        List.of(
                                ChatMessage.user("check the weather"),
                                ChatMessage.assistant(
                                        List.of(
                                                new ToolCallBlock(
                                                        "call-1",
                                                        "lookup",
                                                        Map.of("q", "weather")))),
                                ChatMessage.tool(
                                        new ToolResultBlock(
                                                "call-1", List.of(new TextBlock("sunny")), false))),
                        Map.of());
        String input =
                (String)
                        DecisionModelRoutingExecutor.buildRequest(
                                        Strategies.decision("dm"),
                                        context,
                                        unresolvableAnchor(),
                                        new LinkedHashMap<>())
                                .getInput();
        // One line per rendered piece, no blank lines: the tool framing's own newlines are not
        // doubled by the block or message separators. JSON key order is the mapper's, so the
        // payloads are checked by content.
        List<String> lines = List.of(input.split("\n"));
        assertThat(lines).hasSize(5);
        assertThat(lines.get(0)).isEqualTo("USER: check the weather");
        assertThat(lines.get(1)).isEqualTo("ASSISTANT: ");
        assertThat(lines.get(2))
                .startsWith("Tool call {")
                .contains("\"name\":\"lookup\"")
                .contains("\"call_id\":\"call-1\"");
        assertThat(lines.get(3))
                .startsWith("TOOL: Tool result {")
                .contains("\"is_error\":false")
                .endsWith("}:");
        assertThat(lines.get(4)).isEqualTo("sunny");
    }

    /** A result that answers other questions but not ours is a missing answer, not a wrong one. */
    @Test
    void missingAnswerForTheQuestionAbstains() {
        DecisionResult other =
                new DecisionResult(
                        Map.of("other", new DecisionResult.PredicateAnswer(0.4)),
                        null,
                        null,
                        Map.of());
        RoutingDecision decision =
                DecisionModelRoutingExecutor.toDecision(
                        Strategies.decision("dm"),
                        ctx(List.of(), Map.of()),
                        other,
                        new LinkedHashMap<>());
        assertThat(decision.isAbstain()).isTrue();
        assertThat(decision.getReason())
                .isEqualTo("decision model returned no answer for question 'model'");
    }

    /** The cap may drop the USER text from the input, but that is not a request without text. */
    @Test
    void contextCapDoesNotTriggerThePromptArgsFallback() throws Exception {
        RoutingContext context =
                ctx(
                        List.of(
                                new ChatMessage(MessageRole.USER, "x".repeat(200)),
                                new ChatMessage(MessageRole.ASSISTANT, "ok")),
                        Map.of("input", "unused-args"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        String input =
                (String)
                        DecisionModelRoutingExecutor.buildRequest(
                                        Strategies.decision("dm").withMaxContextChars(60),
                                        context,
                                        unresolvableAnchor(),
                                        metadata)
                                .getInput();
        assertThat(input).isEqualTo("ASSISTANT: ok");
        assertThat(metadata)
                .containsEntry(DecisionModelRoutingExecutor.CONTEXT_TRUNCATED_KEY, true);
    }

    /** A media-only request carries no text the decision model can read, so the args step in. */
    @Test
    void mediaOnlyRequestFallsBackToPromptArgs() throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        String input =
                (String)
                        DecisionModelRoutingExecutor.buildRequest(
                                        Strategies.decision("dm"),
                                        ctx(
                                                List.of(
                                                        ChatMessage.user(
                                                                List.of(
                                                                        ImageBlock.fromBase64(
                                                                                "image/png",
                                                                                "aGk=")))),
                                                Map.of("input", "describe the picture")),
                                        unresolvableAnchor(),
                                        metadata)
                                .getInput();
        assertThat(input).contains("Request arguments:").contains("input: describe the picture");
        assertThat(metadata).containsEntry(DecisionModelRoutingExecutor.MEDIA_DROPPED_KEY, 1);
    }

    @Test
    void promptArgsAreTheInputWhenTheConversationCarriesNoRequestText() throws Exception {
        RoutingContext context =
                ctx(
                        List.of(
                                new ChatMessage(MessageRole.SYSTEM, "You are a helpful assistant"),
                                new ChatMessage(MessageRole.USER, "")),
                        Map.of("input", "write some sql for active users"));
        String input =
                (String)
                        DecisionModelRoutingExecutor.buildRequest(
                                        Strategies.decision("dm"),
                                        context,
                                        unresolvableAnchor(),
                                        new LinkedHashMap<>())
                                .getInput();
        assertThat(input)
                .contains("SYSTEM: You are a helpful assistant")
                .contains("Request arguments:")
                .contains("input: write some sql for active users");
    }

    @Test
    void choiceSelectsItsCandidateWithTheReportedProbabilityAsScore() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(DecisionModelRoutingExecutor.DECISION_MODEL_KEY, "dm");
        RoutingDecision decision =
                DecisionModelRoutingExecutor.toDecision(
                        Strategies.decision("dm"),
                        ctx(List.of(), Map.of()),
                        choice("big", Map.of("small", 0.18, "big", 0.82), 0.9),
                        metadata);
        assertThat(decision.isAbstain()).isFalse();
        assertThat(decision.getSelectedModel()).isEqualTo("big");
        assertThat(decision.getScore()).isEqualTo(0.82);
        assertThat(decision.getReason()).isEqualTo("decision model choice");
        assertThat(decision.getMetadata())
                .containsEntry(DecisionModelRoutingExecutor.DECISION_MODEL_KEY, "dm")
                .containsEntry(DecisionModelRoutingExecutor.PROVIDER_CONFIDENCE_KEY, 0.9)
                // JSON shape: whole numbers that fit an int are Integers, fresh or replayed.
                .containsEntry(DecisionModelRoutingExecutor.INPUT_TOKENS_KEY, 120)
                .containsEntry(DecisionModelRoutingExecutor.OUTPUT_TOKENS_KEY, 4);
        @SuppressWarnings("unchecked")
        Map<String, Double> probabilities =
                (Map<String, Double>)
                        decision.getMetadata().get(DecisionModelRoutingExecutor.PROBABILITIES_KEY);
        assertThat(probabilities).containsEntry("big", 0.82).containsEntry("small", 0.18);
    }

    /** The provider's choice is trusted even when it is not the highest probability. */
    @Test
    void scoreIsTheChosenCandidateProbabilityNotTheMaximum() {
        RoutingDecision decision =
                DecisionModelRoutingExecutor.toDecision(
                        Strategies.decision("dm"),
                        ctx(List.of(), Map.of()),
                        choice("small", Map.of("small", 0.4, "big", 0.6), null),
                        new LinkedHashMap<>());
        assertThat(decision.getSelectedModel()).isEqualTo("small");
        assertThat(decision.getScore()).isEqualTo(0.4);
        assertThat(decision.getMetadata())
                .doesNotContainKey(DecisionModelRoutingExecutor.PROVIDER_CONFIDENCE_KEY);
    }

    @Test
    void probabilityBelowMinimumAbstainsAndRecordsTheChoice() {
        RoutingDecision decision =
                DecisionModelRoutingExecutor.toDecision(
                        Strategies.decision("dm").withMinProbability(0.9),
                        ctx(List.of(), Map.of()),
                        choice("big", Map.of("small", 0.18, "big", 0.82), null),
                        new LinkedHashMap<>());
        assertThat(decision.isAbstain()).isTrue();
        assertThat(decision.getSelectedModel()).isNull();
        assertThat(decision.getScore()).isNull();
        assertThat(decision.getReason()).contains("0.82").contains("below min_probability 0.9");
        assertThat(decision.getMetadata())
                .containsEntry(DecisionModelRoutingExecutor.CHOICE_KEY, "big")
                .containsKey(DecisionModelRoutingExecutor.PROBABILITIES_KEY);
    }

    /** A provider may omit the chosen candidate from the map; with a minimum set that abstains. */
    @Test
    void missingProbabilityForTheChoiceAbstainsWhenAMinimumIsSet() {
        RoutingDecision decision =
                DecisionModelRoutingExecutor.toDecision(
                        Strategies.decision("dm").withMinProbability(0.6),
                        ctx(List.of(), Map.of()),
                        choice("big", Map.of("small", 1.0), null),
                        new LinkedHashMap<>());
        assertThat(decision.isAbstain()).isTrue();
        assertThat(decision.getReason())
                .isEqualTo(
                        "decision model reported no probability for 'big', required by"
                                + " min_probability 0.6");
        assertThat(decision.getMetadata())
                .containsEntry(DecisionModelRoutingExecutor.CHOICE_KEY, "big");
    }

    /** Nothing to decide on: no text and no prompt arguments abstains without a provider call. */
    @Test
    void emptyRequestAbstainsWithoutCallingTheProvider() throws Exception {
        BaseDecisionModelSetup setup = mock(BaseDecisionModelSetup.class);
        RunnerContext runner = mock(RunnerContext.class);
        when(runner.getResource(any(), any()))
                .thenAnswer(
                        invocation -> {
                            if (invocation.getArgument(1) == ResourceType.DECISION_MODEL) {
                                return setup;
                            }
                            throw new IllegalArgumentException("no chat model");
                        });
        RoutingDecision decision =
                new DecisionModelRoutingExecutor()
                        .route(
                                Strategies.decision("dm"),
                                ctx(
                                        List.of(
                                                ChatMessage.user(
                                                        List.of(
                                                                ImageBlock.fromBase64(
                                                                        "image/png", "aGk=")))),
                                        Map.of()),
                                runner);
        assertThat(decision.isAbstain()).isTrue();
        assertThat(decision.getReason()).contains("no text");
        org.mockito.Mockito.verifyNoInteractions(setup);
    }

    /**
     * A request that cannot be rendered (here: a tool call whose input Jackson cannot serialize) is
     * an ordinary routing failure for this request, not a runner failure.
     */
    @Test
    void unrenderableRequestIsARoutingFailure() throws Exception {
        RunnerContext runner = mock(RunnerContext.class);
        when(runner.getResource(any(), any()))
                .thenAnswer(
                        invocation -> {
                            if (invocation.getArgument(1) == ResourceType.DECISION_MODEL) {
                                return mock(BaseDecisionModelSetup.class);
                            }
                            throw new IllegalArgumentException("no chat model");
                        });
        RoutingContext context =
                ctx(
                        List.of(
                                ChatMessage.assistant(
                                        List.of(
                                                new ToolCallBlock(
                                                        "call-1",
                                                        "lookup",
                                                        Map.of("query", new Object()))))),
                        Map.of());
        assertThatThrownBy(
                        () ->
                                new DecisionModelRoutingExecutor()
                                        .route(Strategies.decision("dm"), context, runner))
                .isInstanceOf(ModelRoutingResolver.RoutingFailure.class)
                .hasMessageContaining("could not build the decision request");
    }

    @Test
    void probabilityAtTheMinimumIsAccepted() {
        RoutingDecision decision =
                DecisionModelRoutingExecutor.toDecision(
                        Strategies.decision("dm").withMinProbability(0.82),
                        ctx(List.of(), Map.of()),
                        choice("big", Map.of("small", 0.18, "big", 0.82), null),
                        new LinkedHashMap<>());
        assertThat(decision.isAbstain()).isFalse();
        assertThat(decision.getSelectedModel()).isEqualTo("big");
    }

    @Test
    void refusalAbstainsWithTheProviderReason() {
        DecisionResult refused =
                new DecisionResult(
                        Map.of(
                                DecisionModelRoutingExecutor.QUESTION_NAME,
                                new DecisionResult.RefusalAnswer("not enough evidence")),
                        null,
                        null,
                        Map.of());
        RoutingDecision decision =
                DecisionModelRoutingExecutor.toDecision(
                        Strategies.decision("dm"),
                        ctx(List.of(), Map.of()),
                        refused,
                        new LinkedHashMap<>());
        assertThat(decision.isAbstain()).isTrue();
        assertThat(decision.getReason()).isEqualTo("decision model refused: not enough evidence");
        assertThat(decision.getMetadata())
                .doesNotContainKeys(
                        DecisionModelRoutingExecutor.INPUT_TOKENS_KEY,
                        DecisionModelRoutingExecutor.OUTPUT_TOKENS_KEY);
    }

    @Test
    void nonChoiceAnswerAbstains() {
        DecisionResult predicate =
                new DecisionResult(
                        Map.of(
                                DecisionModelRoutingExecutor.QUESTION_NAME,
                                new DecisionResult.PredicateAnswer(0.4)),
                        null,
                        null,
                        Map.of());
        RoutingDecision decision =
                DecisionModelRoutingExecutor.toDecision(
                        Strategies.decision("dm"),
                        ctx(List.of(), Map.of()),
                        predicate,
                        new LinkedHashMap<>());
        assertThat(decision.isAbstain()).isTrue();
        assertThat(decision.getReason()).isEqualTo("decision answer was not a choice");
    }

    /** Defensive: the provider contract forbids it, but a non-candidate choice never routes. */
    @Test
    void nonCandidateChoiceAbstains() {
        RoutingDecision decision =
                DecisionModelRoutingExecutor.toDecision(
                        Strategies.decision("dm"),
                        ctx(List.of(), Map.of()),
                        choice("gpt-attacker", Map.of("gpt-attacker", 1.0), null),
                        new LinkedHashMap<>());
        assertThat(decision.isAbstain()).isTrue();
        assertThat(decision.getReason()).contains("gpt-attacker").contains("not a candidate");
        assertThat(decision.getMetadata())
                .containsEntry(DecisionModelRoutingExecutor.CHOICE_KEY, "gpt-attacker");
    }

    @Test
    void declarationAccessorsReadTheArguments() {
        RoutingStrategy strategy = Strategies.decision("dm").withMinProbability(0.6);
        assertThat(DecisionModelRoutingExecutor.decisionModel(strategy)).isEqualTo("dm");
        assertThat(DecisionModelRoutingExecutor.minProbability(strategy)).isEqualTo(0.6);
        assertThat(DecisionModelRoutingExecutor.minProbability(Strategies.decision("dm"))).isNull();
        assertThat(DecisionModelRoutingExecutor.instructions(Strategies.decision("dm")))
                .isEqualTo(DecisionModelRoutingExecutor.DEFAULT_INSTRUCTIONS);
    }
}
