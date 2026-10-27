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
import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.model.routing.RoutingCandidate;
import org.apache.flink.agents.api.chat.model.routing.RoutingContext;
import org.apache.flink.agents.api.chat.model.routing.RoutingDecision;
import org.apache.flink.agents.api.chat.model.routing.RoutingStrategy;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.decision.BaseDecisionModelSetup;
import org.apache.flink.agents.api.decision.DecisionRequest;
import org.apache.flink.agents.api.decision.DecisionResult;
import org.apache.flink.agents.api.event.ModelRoutingEvent;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.actions.ChatModelAction;
import org.apache.flink.agents.plan.actions.ChatModelInvoker;
import org.apache.flink.agents.plan.actions.DecisionModelInvoker;
import org.apache.flink.agents.plan.utils.CancellationUtils;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes the framework-managed decision-model strategy ({@code Strategies.decision(...)}): the
 * engine — not the strategy — asks a registered {@code DECISION_MODEL} resource one choice question
 * whose options are the router's candidates and whose criteria are their {@code describe(...)}
 * descriptions, through a flat durable call with the id {@code "decide:<router>"} (issued
 * <i>before</i> the resolver's decision record — see the sequencing contract on {@link
 * RoutingExecutor}), then derives the decision from the answer as a pure function.
 *
 * <p>The answer is constrained by construction: the provider must pick one of the requested
 * choices, so the decision model cannot steer routing outside the declared candidates. The choice
 * becomes the selected model and its reported probability the routing score. A refusal, a
 * non-choice answer, a result without an answer for the question, or a probability below the
 * declared {@code min_probability} abstains to the router's default model with the cause recorded.
 * The decision model sees the conversation as text only (tool calls and results included, media and
 * reasoning excluded), rendered like the LLM judge's input and subject to the same optional context
 * cap.
 *
 * <p>Failure policy: a decision call that exhausts its retries fails the request, like a throwing
 * rule/custom strategy or a failed judge call. Cancellation propagates and is never persisted as a
 * routing outcome. The call shares {@link DecisionModelInvoker} with the built-in decision action
 * but runs under its own durable id, so it does not collide with the action's flat {@code "decide"}
 * record; retries are accumulated on the routed request like the judge's.
 */
final class DecisionModelRoutingExecutor implements RoutingExecutor {

    /** The name of the single choice question in the decision request and its answer. */
    static final String QUESTION_NAME = "model";

    /** The built-in choice instructions, used unless the declaration carries its own. */
    static final String DEFAULT_INSTRUCTIONS =
            "Choose which ONE candidate model should answer the conversation below. Each option"
                    + " describes the requests its model should handle; pick the option whose"
                    + " description fits the conversation best.";

    /** Decision-metadata key: the decision-model setup that produced the choice. */
    static final String DECISION_MODEL_KEY = "decision_model";

    /** Decision-metadata key: the provider's probability per candidate, as reported. */
    static final String PROBABILITIES_KEY = "decision_probabilities";

    /** Decision-metadata key: the provider's separate confidence measure, when it reports one. */
    static final String PROVIDER_CONFIDENCE_KEY = "provider_confidence";

    /** Decision-metadata key: the chosen candidate when the choice abstained to the default. */
    static final String CHOICE_KEY = "decision_choice";

    /** Decision-metadata flag set when the context cap dropped part of the conversation. */
    static final String CONTEXT_TRUNCATED_KEY = "decision_context_truncated";

    /** Decision-metadata key: media blocks left out of the text-only decision input. */
    static final String MEDIA_DROPPED_KEY = "decision_media_dropped";

    /** Decision-metadata keys: the decision call's token usage, when the provider reports both. */
    static final String INPUT_TOKENS_KEY = "decision_input_tokens";

    static final String OUTPUT_TOKENS_KEY = "decision_output_tokens";

    @Override
    public boolean usesDurableExecutionInternally() {
        return true;
    }

    @Override
    public String decisionSource() {
        return ModelRoutingEvent.SOURCE_DECISION_MODEL;
    }

    @Override
    public RoutingDecision route(
            RoutingStrategy strategy, RoutingContext context, RunnerContext ctx) throws Exception {
        String decisionModel = decisionModel(strategy);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(DECISION_MODEL_KEY, decisionModel);
        DecisionRequest request;
        try {
            request = buildRequest(strategy, context, ctx, metadata);
        } catch (Exception e) {
            throw routingFailure(context, "could not build the decision request", e);
        }
        if (String.valueOf(request.getInput()).isEmpty()) {
            // Nothing to decide on (a media-only or empty request with no prompt arguments):
            // abstain to the default model instead of paying for a call the provider rejects.
            // Resolved before the setup, so an unreachable provider cannot fail such a request.
            return abstain("request carries no text for the decision model", metadata);
        }
        BaseDecisionModelSetup setup;
        try {
            setup =
                    (BaseDecisionModelSetup)
                            ctx.getResource(decisionModel, ResourceType.DECISION_MODEL);
        } catch (Exception e) {
            CancellationUtils.rethrowIfCancellation(e);
            // An unresolvable decision model is an ordinary routing failure (failed response),
            // not a runner failure: the request cannot be routed, the job keeps running.
            throw new ModelRoutingResolver.RoutingFailure(
                    "Decision model '"
                            + decisionModel
                            + "' could not be resolved: "
                            + ChatModelInvoker.errorText(e));
        }
        DecisionModelInvoker.DecisionAttemptResult result =
                DecisionModelInvoker.decideWithRetries(
                        ctx,
                        decisionModel,
                        setup,
                        request,
                        Map.of(),
                        "decide:" + context.getRouter(),
                        "for router '" + context.getRouter() + "'",
                        false);
        // Retries count against the routed request, like the judge's: response-level totals and
        // the model_resource counters both come from this one call.
        ChatModelAction.recordAttemptRetryStats(
                ctx, context.getRequestId(), decisionModel, result.retryCount, result.totalWaitSec);
        if (!result.isSuccess()) {
            throw new ModelRoutingResolver.RoutingFailure(
                    "Decision model '" + decisionModel + "' failed: " + result.error);
        }
        try {
            return toDecision(strategy, context, result.response, metadata);
        } catch (Exception e) {
            throw routingFailure(context, "could not read the decision answer", e);
        }
    }

    /**
     * An ordinary routing failure (failed chat response, no candidate fallback) for a request that
     * could not be prepared or whose answer could not be read. Runtime failures of the durable
     * substrate never pass through here: they surface from the invoker and propagate to the runner,
     * as the {@link RoutingExecutor} contract requires. Cancellation is rethrown as is.
     */
    private static ModelRoutingResolver.RoutingFailure routingFailure(
            RoutingContext context, String what, Exception cause) throws Exception {
        CancellationUtils.rethrowIfCancellation(cause);
        return new ModelRoutingResolver.RoutingFailure(
                "Decision routing for router '"
                        + context.getRouter()
                        + "' "
                        + what
                        + ": "
                        + ChatModelInvoker.errorText(cause));
    }

    /**
     * Builds the decision request: the conversation rendered as text (the judge's view, so the
     * decision model routes on what the selected model will actually receive, including a bound
     * prompt rendered by the anchor candidate) plus one choice question over the candidates.
     * Truncation and dropped media are recorded in {@code metadata}.
     */
    static DecisionRequest buildRequest(
            RoutingStrategy strategy,
            RoutingContext context,
            RunnerContext ctx,
            Map<String, Object> metadata)
            throws Exception {
        List<ChatMessage> effective = LlmJudgeRoutingExecutor.effectiveJudgeMessages(context, ctx);
        Set<Integer> pinned =
                LlmJudgeRoutingExecutor.pinnedRenderedIndices(context.getMessages(), effective);
        boolean[] truncated = new boolean[1];
        String input = renderInput(strategy, context, effective, pinned, truncated, metadata);
        if (truncated[0]) {
            metadata.put(CONTEXT_TRUNCATED_KEY, true);
        }
        return new DecisionRequest(
                input,
                Map.of(
                        QUESTION_NAME,
                        DecisionRequest.Question.choice(
                                criteria(context), instructions(strategy))));
    }

    /**
     * The choice criteria: candidate name to description. Descriptions are required by the router
     * declaration ({@code RoutingCandidateValidator#requireDescriptionsForDecisionStrategy}), so
     * every option carries one.
     */
    static Map<String, Object> criteria(RoutingContext context) {
        Map<String, Object> criteria = new LinkedHashMap<>();
        for (RoutingCandidate candidate : context.getCandidates()) {
            criteria.put(candidate.getName(), candidate.getDescription());
        }
        return criteria;
    }

    /**
     * Renders the (optionally capped) conversation to text for the decision input, one line per
     * message prefixed with its role, with tool interactions described as data. Media blocks are
     * counted and left out: decision models take text and JSON evidence only. When the request
     * itself contributes no text (no USER or rendered-template text: a media-only, empty or
     * SYSTEM-only conversation), the prompt arguments are the only signal and are appended; with
     * none, the input is empty and the strategy abstains.
     */
    static String renderInput(
            RoutingStrategy strategy,
            RoutingContext context,
            List<ChatMessage> effectiveMessages,
            Set<Integer> pinnedIndices,
            boolean[] truncatedOut,
            Map<String, Object> metadata) {
        List<ChatMessage> selected =
                LlmJudgeRoutingExecutor.selectWithinBudget(
                        effectiveMessages,
                        LlmJudgeRoutingExecutor.maxContextChars(strategy),
                        pinnedIndices,
                        truncatedOut);
        // Whether the request carries text is judged on the complete effective list, as the judge
        // does, so a context cap that drops the USER message does not by itself trigger the
        // prompt-argument fallback. Messages generated by the anchor's request shaping (the
        // rendered template) carry the request like USER text does.
        boolean requestHasText = false;
        for (int i = 0; i < effectiveMessages.size(); i++) {
            ChatMessage message = effectiveMessages.get(i);
            if (message.getRole() != MessageRole.USER && !pinnedIndices.contains(i)) {
                continue;
            }
            for (DataContentBlock block : LlmJudgeRoutingExecutor.judgeContent(message)) {
                if (block instanceof TextBlock && !((TextBlock) block).getText().isEmpty()) {
                    requestHasText = true;
                }
            }
        }
        StringBuilder text = new StringBuilder();
        int mediaDropped = 0;
        for (ChatMessage message : selected) {
            List<DataContentBlock> content = LlmJudgeRoutingExecutor.judgeContent(message);
            if (content.isEmpty()) {
                continue;
            }
            appendLine(text, message.getRole().name() + ": ");
            boolean firstBlock = true;
            for (DataContentBlock block : content) {
                if (block instanceof TextBlock) {
                    // Blocks of one message are separate pieces of text; keep them apart so a
                    // message split around media does not read as one run-on word.
                    String blockText = ((TextBlock) block).getText();
                    if (firstBlock) {
                        text.append(blockText);
                    } else {
                        appendLine(text, blockText);
                    }
                    firstBlock = false;
                } else {
                    mediaDropped++;
                }
            }
        }
        if (mediaDropped > 0) {
            metadata.put(MEDIA_DROPPED_KEY, mediaDropped);
        }
        if (!requestHasText && context.getPromptArgs().isEmpty()) {
            // No request text and no arguments: there is nothing to decide on, and role framing
            // alone is not evidence. An empty input makes the executor abstain.
            return "";
        }
        if (!requestHasText && !context.getPromptArgs().isEmpty()) {
            text.append(text.length() == 0 ? "" : "\n").append("Request arguments:");
            for (Map.Entry<String, Object> arg : context.getPromptArgs().entrySet()) {
                text.append('\n').append(arg.getKey()).append(": ").append(arg.getValue());
            }
        }
        return text.toString();
    }

    /**
     * Turns the decision result into a routing decision. A pure function of the answer and the
     * declaration: the choice selects its candidate with the reported probability as score;
     * anything else abstains with the cause in the reason and the provider's answer in the
     * metadata.
     */
    static RoutingDecision toDecision(
            RoutingStrategy strategy,
            RoutingContext context,
            DecisionResult result,
            Map<String, Object> metadata) {
        DecisionResult.TokenUsage usage = result.getUsage();
        if (usage != null && usage.getInputTokens() != null && usage.getOutputTokens() != null) {
            metadata.put(INPUT_TOKENS_KEY, jsonNumber(usage.getInputTokens()));
            metadata.put(OUTPUT_TOKENS_KEY, jsonNumber(usage.getOutputTokens()));
        }
        DecisionResult.Answer answer = result.getAnswers().get(QUESTION_NAME);
        if (answer == null) {
            return abstain(
                    "decision model returned no answer for question '" + QUESTION_NAME + "'",
                    metadata);
        }
        if (answer instanceof DecisionResult.RefusalAnswer) {
            String reason = ((DecisionResult.RefusalAnswer) answer).getReason();
            return abstain(
                    "decision model refused" + (reason == null ? "" : ": " + reason), metadata);
        }
        if (!(answer instanceof DecisionResult.ChoiceAnswer)) {
            return abstain("decision answer was not a choice", metadata);
        }
        DecisionResult.ChoiceAnswer choice = (DecisionResult.ChoiceAnswer) answer;
        metadata.put(PROBABILITIES_KEY, new LinkedHashMap<>(choice.getProbabilities()));
        if (choice.getProviderConfidence() != null) {
            metadata.put(PROVIDER_CONFIDENCE_KEY, choice.getProviderConfidence());
        }
        String selected = choice.getChoice();
        if (!isCandidate(context, selected)) {
            metadata.put(CHOICE_KEY, selected);
            return abstain("decision choice '" + selected + "' is not a candidate", metadata);
        }
        Double score = choice.getProbabilities().get(selected);
        Double minProbability = minProbability(strategy);
        if (minProbability != null && score == null) {
            metadata.put(CHOICE_KEY, selected);
            return abstain(
                    "decision model reported no probability for '"
                            + selected
                            + "', required by min_probability "
                            + minProbability,
                    metadata);
        }
        if (minProbability != null && score < minProbability) {
            metadata.put(CHOICE_KEY, selected);
            return abstain(
                    String.format(
                            "decision probability %s for '%s' is below min_probability %s",
                            score, selected, minProbability),
                    metadata);
        }
        RoutingDecision.Builder builder =
                RoutingDecision.builder(selected).reason("decision model choice");
        if (score != null) {
            builder.score(score);
        }
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            builder.metadata(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    /**
     * Persisted as a real abstain: replay resolves to the router's <i>current</i> default, and the
     * provider's answer stays in the metadata for the routing event.
     */
    /**
     * Appends {@code piece} on a new line, without doubling a line break that either side already
     * carries (tool calls and results are rendered with their own framing newlines).
     */
    private static void appendLine(StringBuilder text, String piece) {
        if (text.length() > 0
                && text.charAt(text.length() - 1) != '\n'
                && !piece.startsWith("\n")) {
            text.append('\n');
        }
        text.append(piece);
    }

    /**
     * Metadata travels as JSON in the route record, where a whole number that fits an int comes
     * back as Integer. Store it in that shape up front so a fresh decision and a replayed one carry
     * the same types.
     */
    private static Object jsonNumber(long value) {
        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE
                ? (Object) (int) value
                : (Object) value;
    }

    private static RoutingDecision abstain(String reason, Map<String, Object> metadata) {
        return new RoutingDecision(null, true, reason, null, new HashMap<>(metadata), null);
    }

    private static boolean isCandidate(RoutingContext context, String model) {
        for (RoutingCandidate candidate : context.getCandidates()) {
            if (candidate.getName().equals(model)) {
                return true;
            }
        }
        return false;
    }

    static String decisionModel(RoutingStrategy strategy) {
        return (String) strategy.getArguments().get(RoutingStrategy.ARG_DECISION_MODEL);
    }

    static String instructions(RoutingStrategy strategy) {
        Object instructions = strategy.getArguments().get(RoutingStrategy.ARG_INSTRUCTIONS);
        return instructions instanceof String ? (String) instructions : DEFAULT_INSTRUCTIONS;
    }

    @Nullable
    static Double minProbability(RoutingStrategy strategy) {
        Object min = strategy.getArguments().get(RoutingStrategy.ARG_MIN_PROBABILITY);
        return min instanceof Number ? ((Number) min).doubleValue() : null;
    }
}
