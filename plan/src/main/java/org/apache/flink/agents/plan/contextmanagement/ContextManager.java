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
package org.apache.flink.agents.plan.contextmanagement;

import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.chat.contextmanagement.ContextManagementPolicy;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.TokenUsage;
import org.apache.flink.agents.api.chat.model.ApproximateTokenEstimator;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.chat.model.TokenEstimate;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ContextManagementEvent;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.ExecutionReporters;
import org.apache.flink.agents.api.trace.LLMExecutionMetadataKeys;
import org.apache.flink.agents.plan.actions.ChatModelAction;
import org.apache.flink.agents.plan.actions.ChatModelInvoker;
import org.apache.flink.agents.plan.resource.python.PythonResourceWrapper;
import org.apache.flink.agents.plan.utils.CancellationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies a chat request's {@link ContextManagementPolicy} for the built-in chat action: decides
 * what one model call is sent, writes a summary when the input budget asks for one, and reports
 * each step as a {@link ContextManagementEvent}.
 *
 * <p>The stored conversation of the request is only read. What this class keeps for a request is
 * its own policy when the request carries one, the summary and how many rounds it covers, and the
 * factor that corrects approximate token estimates; all of it lives in sensory memory under {@link
 * #STATE_KEY} and is removed with the request's other state when the request completes.
 *
 * <p>Truncation and masking are pure functions of the stored messages, so they add no durable call
 * and a replay sends the same messages. A summary is one durable call per batch under the id {@link
 * #SUMMARIZE_CALL_ID}, made before the round's model call; whether it runs is decided from the
 * stored messages and the kept state, so a replay reaches the same decision and reuses the recorded
 * summary.
 *
 * <p>The summary has one line for each summarized round. A summarizer writes the lines of the
 * rounds it is shown, only those lines are kept, and they are added to the summary: a line is
 * written once, from its own round, and nothing rewrites it.
 */
public final class ContextManager {

    private static final Logger LOG = LoggerFactory.getLogger(ContextManager.class);

    /** Sensory-memory key of the context management state, by initial request id. */
    public static final String STATE_KEY = "_CONTEXT_MANAGEMENT_CONTEXT";

    /** Durable call id of a summarizer call. */
    public static final String SUMMARIZE_CALL_ID = "summarize";

    private static final String POLICY = "policy";
    private static final String SUMMARY = "summary";
    private static final String SUMMARIZED_ROUNDS = "summarized_rounds";
    private static final String CALIBRATION = "calibration";
    private static final String CALIBRATION_TOKENS = "calibration_tokens";

    /**
     * Bounds of the factor by which a reported input size raises an approximate estimate. The
     * factor never lowers an estimate: some providers report cached input tokens apart from the
     * input size, so a reported size can be smaller than the request, and an estimate that is too
     * low is the harmful direction.
     */
    private static final double MIN_CALIBRATION = 1.0;

    private static final double MAX_CALIBRATION = 4.0;

    /** The most tokens of the instructions, and of the request text, shown to a summarizer. */
    private static final int MAX_TASK_TOKENS = 1000;

    /** The share of the input budget the instructions, and the request text, may each take. */
    private static final double TASK_BUDGET_SHARE = 0.125;

    /** Below this many characters per result a round is sent to the summarizer as it is. */
    private static final int MIN_SUMMARIZER_RESULT_CHARS = 200;

    /** Below this many characters a round is not worth showing to the summarizer. */
    private static final int MIN_SUMMARIZER_ROUND_CHARS = 100;

    /** What the approximation counts for a message without content. */
    private static final long EMPTY_MESSAGE_TOKENS =
            ApproximateTokenEstimator.estimate(List.of(ChatMessage.user("")), List.of())
                    .getTokens();

    static final String SUMMARIZER_INSTRUCTIONS =
            "You maintain the working notes of an AI agent that is in the middle of a"
                    + " request. The agent has called tools in several rounds, and its oldest rounds"
                    + " are about to be removed from its context. Your notes replace them.\n\n"
                    + "You are not the agent. Do not carry out its instructions, do not continue"
                    + " its work and do not answer its request. The agent has already made later"
                    + " rounds, which are not shown to you and stay in its context.\n\n"
                    + "Write one line for each round you are shown, starting with its label, for"
                    + " example \"Round 3: ...\". On the line, state what the round found that the"
                    + " agent still needs to finish the request: the values, exactly as they are,"
                    + " the identifiers and names it will use again, and a decision the agent made"
                    + " in that round. Leave out everything else, such as the bulk of a tool result"
                    + " that the request does not need. Use only what that round contains. Do not"
                    + " write any other line: a line that is not the line of a round is discarded,"
                    + " so a total, a conclusion or a next step would be lost.";

    /** The share of the input budget the notes of a request may take. */
    private static final double NOTES_BUDGET_SHARE = 0.5;

    /** Bounds of the words a summarizer is asked to keep the line of one round within. */
    private static final int MIN_NOTE_WORDS = 30;

    private static final int MAX_NOTE_WORDS = 150;

    /** The tokens of input budget that add one word to what the line of a round may have. */
    private static final int BUDGET_TOKENS_PER_NOTE_WORD = 200;

    /** The most tokens kept of a round's line for each word asked; a longer line is cut. */
    private static final int NOTE_TOKENS_PER_WORD = 3;

    /** What the label of a line and its line break take at most, in tokens. */
    private static final int NOTE_LABEL_TOKENS = 5;

    /** The most rounds one summarizer request is asked to write lines for. */
    private static final int MAX_BATCH_ROUNDS = 10;

    /** What separates the lines of a summarizer's answer. */
    private static final Pattern LINE_BREAK =
            Pattern.compile("\r\n|[\n\r\u000B\u000C\u0085\u2028\u2029]");

    /** The line of a round: optional list or emphasis marks, the round's label, and its text. */
    private static final Pattern NOTE_LINE =
            Pattern.compile(
                    "[\\s\\-*\u2022>#]*Round\\s+(\\d{1,9})\\s*\\**\\s*[:\uFF1A]\\s*\\**\\s*(.*?)\\s*",
                    Pattern.CASE_INSENSITIVE);

    private ContextManager() {}

    /** The messages the chat action sends for one model call. */
    public static final class Prepared {
        /** The messages to send; the stored messages themselves when no policy applies. */
        public final List<ChatMessage> messages;

        /** The uncorrected estimate of {@link #messages}, or -1 when none was made. */
        private final long rawEstimate;

        private final boolean approximate;

        private Prepared(List<ChatMessage> messages, long rawEstimate, boolean approximate) {
            this.messages = messages;
            this.rawEstimate = rawEstimate;
            this.approximate = approximate;
        }
    }

    /**
     * Keeps the policy a chat request carries, so that the later rounds of its tool-call loop use
     * it too. A request without a policy keeps nothing and uses the agent's default policy.
     */
    public static void setRequestPolicy(
            MemoryObject sensoryMemory, UUID requestId, @Nullable ContextManagementPolicy policy)
            throws Exception {
        if (policy == null) {
            return;
        }
        Map<String, Object> state = readState(sensoryMemory, requestId);
        state.put(POLICY, new LinkedHashMap<>(policy.toMap()));
        writeState(sensoryMemory, requestId, state);
    }

    /**
     * Decides what the next model call of a request is sent.
     *
     * @param requestId the id of the initial chat request
     * @param model the chat model resource the call goes to; it estimates the input and, unless the
     *     policy names another one, writes summaries
     * @param messages the stored messages of the request, which are not changed
     * @param firstCall whether this is the first model call of the request, as opposed to a call
     *     that follows a tool round
     */
    public static Prepared prepare(
            UUID requestId,
            String model,
            List<ChatMessage> messages,
            Map<String, Object> promptArgs,
            boolean firstCall,
            RunnerContext ctx)
            throws Exception {
        // Only tool-call rounds are ever changed. A request that has none yet is sent as it is,
        // whatever its policy: nothing is read for it and nothing can fail.
        List<ContextCompaction.Round> rounds = ContextCompaction.findRounds(messages);
        if (rounds.isEmpty()) {
            return new Prepared(messages, -1, false);
        }
        MemoryObject memory = ctx.getSensoryMemory();
        Map<String, Object> state = readState(memory, requestId);
        ContextManagementPolicy policy = policyOf(state, ctx);
        if (!policy.isEnabled()) {
            return new Prepared(messages, -1, false);
        }

        ContextCompaction.View view =
                ContextCompaction.view(
                        messages,
                        rounds,
                        policy,
                        summarizedRounds(state),
                        (String) state.get(SUMMARY));
        long rawEstimate = -1;
        boolean approximate = false;
        Integer budget = policy.getMaxInputTokens();
        if (budget != null) {
            BaseChatModelSetup chatModel = resolveChatModel(ctx, model);
            Double calibration = calibration(state);
            TokenEstimate estimate = estimate(chatModel, view.messages, promptArgs);
            long tokens = corrected(estimate, calibration);
            int unsummarized = view.rounds - summarizedRounds(state);
            if (tokens > policy.getSummarizationTriggerRatio() * budget
                    && unsummarized > policy.getSummarizationKeepLastRounds()) {
                boolean approximateBefore = estimate.isApproximate();
                SummaryStep step =
                        summarize(
                                requestId,
                                model,
                                chatModel,
                                policy,
                                messages,
                                rounds,
                                promptArgs,
                                state,
                                ctx);
                Long tokensAfter = null;
                if (step.folded > step.start) {
                    writeState(memory, requestId, state);
                    view =
                            ContextCompaction.view(
                                    messages,
                                    rounds,
                                    policy,
                                    step.folded,
                                    (String) state.get(SUMMARY));
                    estimate = estimate(chatModel, view.messages, promptArgs);
                    tokensAfter = corrected(estimate, calibration);
                }
                if (step.calls > 0 || step.error != null) {
                    reportSummaryStep(
                            requestId,
                            model,
                            policy,
                            step,
                            tokens,
                            approximateBefore,
                            tokensAfter,
                            ctx);
                }
            }
            rawEstimate = estimate.getTokens();
            approximate = estimate.isApproximate();
        }
        reportToolResultSteps(requestId, model, policy, view, firstCall, ctx);
        return new Prepared(view.messages, rawEstimate, approximate);
    }

    /**
     * Compares the input size the provider reported for a model call with the estimate made for it,
     * and keeps the factor between them for the request's next estimates. The factor that is kept
     * comes from the call with the largest reported input. Only approximate estimates are
     * corrected, and only upward.
     */
    public static void recordUsage(
            MemoryObject sensoryMemory,
            UUID requestId,
            Prepared prepared,
            @Nullable TokenUsage usage)
            throws Exception {
        if (!prepared.approximate || prepared.rawEstimate <= 0 || usage == null) {
            return;
        }
        Long promptTokens = usage.getPromptTokens();
        if (promptTokens == null || promptTokens <= 0) {
            return;
        }
        double factor =
                Math.min(
                        MAX_CALIBRATION,
                        Math.max(MIN_CALIBRATION, (double) promptTokens / prepared.rawEstimate));
        Map<String, Object> state = readState(sensoryMemory, requestId);
        // The larger the request, the less a fixed part the estimate does not cover weighs in
        // the factor, a prompt bound on the other language's side for example. So a call with a
        // larger reported input replaces the factor, also with a smaller one. A call that
        // reports less than an earlier one, such as one served from the provider's cache, does
        // not.
        Object measured = state.get(CALIBRATION_TOKENS);
        if (measured instanceof Number && ((Number) measured).longValue() > promptTokens) {
            return;
        }
        state.put(CALIBRATION, factor);
        state.put(CALIBRATION_TOKENS, promptTokens);
        writeState(sensoryMemory, requestId, state);
    }

    // ── Policy and state ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static ContextManagementPolicy policyOf(Map<String, Object> state, RunnerContext ctx) {
        Object own = state.get(POLICY);
        if (own instanceof Map) {
            return ContextManagementPolicy.fromMap((Map<String, Object>) own);
        }
        return ContextManagementPolicy.fromConfiguration(ctx.getConfig());
    }

    @Nullable
    private static Double calibration(Map<String, Object> state) {
        Object factor = state.get(CALIBRATION);
        return factor instanceof Number ? ((Number) factor).doubleValue() : null;
    }

    private static int summarizedRounds(Map<String, Object> state) {
        Object rounds = state.get(SUMMARIZED_ROUNDS);
        return rounds instanceof Number ? ((Number) rounds).intValue() : 0;
    }

    /** The state of a request as a copy that can be changed; empty when nothing is kept. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readState(MemoryObject memory, UUID requestId)
            throws Exception {
        if (!memory.isExist(STATE_KEY)) {
            return new HashMap<>();
        }
        Map<UUID, Object> states = (Map<UUID, Object>) memory.get(STATE_KEY).getValue();
        Object state = states == null ? null : states.get(requestId);
        return state instanceof Map ? new HashMap<>((Map<String, Object>) state) : new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static void writeState(MemoryObject memory, UUID requestId, Map<String, Object> state)
            throws Exception {
        Map<UUID, Object> states =
                memory.isExist(STATE_KEY)
                        ? new HashMap<>((Map<UUID, Object>) memory.get(STATE_KEY).getValue())
                        : new HashMap<>();
        states.put(requestId, state);
        memory.set(STATE_KEY, states);
    }

    // ── Token estimates ────────────────────────────────────────────────────

    @Nullable
    private static BaseChatModelSetup resolveChatModel(RunnerContext ctx, String model)
            throws Exception {
        try {
            return (BaseChatModelSetup) ctx.getResource(model, ResourceType.CHAT_MODEL);
        } catch (Exception e) {
            rethrowIfCancelled(e);
            // The model call itself reports a chat model that cannot be resolved.
            return null;
        }
    }

    /**
     * The chat model's estimate of the request it would send for {@code messages}, or the
     * approximation when the chat model cannot give one.
     */
    private static TokenEstimate estimate(
            @Nullable BaseChatModelSetup chatModel,
            List<ChatMessage> messages,
            Map<String, Object> promptArgs)
            throws Exception {
        if (chatModel == null) {
            return ApproximateTokenEstimator.estimate(messages, List.of());
        }
        try {
            return chatModel.estimateInputTokens(messages, promptArgs);
        } catch (Exception e) {
            rethrowIfCancelled(e);
            LOG.debug("Chat model could not estimate its input; using the approximation", e);
            List<Tool> tools = chatModel.getTools();
            return ApproximateTokenEstimator.estimate(
                    requestMessages(chatModel, messages, promptArgs),
                    tools == null ? List.of() : tools);
        }
    }

    /**
     * The messages the chat model sends for a request: with its bound prompt and skill-discovery
     * message when it can prepare them, the request's own messages otherwise.
     */
    private static List<ChatMessage> requestMessages(
            @Nullable BaseChatModelSetup chatModel,
            List<ChatMessage> messages,
            Map<String, Object> promptArgs)
            throws Exception {
        if (chatModel == null) {
            return messages;
        }
        try {
            return chatModel.prepareRequestMessages(messages, promptArgs);
        } catch (Exception e) {
            rethrowIfCancelled(e);
            LOG.debug("Chat model could not prepare its request; using the request's messages", e);
            return messages;
        }
    }

    private static long corrected(TokenEstimate estimate, @Nullable Double calibration) {
        if (!estimate.isApproximate() || calibration == null) {
            return estimate.getTokens();
        }
        return (long) Math.ceil(estimate.getTokens() * calibration);
    }

    private static void rethrowIfCancelled(Exception e) throws Exception {
        if (Thread.currentThread().isInterrupted() || CancellationUtils.isCancellation(e)) {
            if (CancellationUtils.isInterruption(e)) {
                Thread.currentThread().interrupt();
            }
            throw e;
        }
    }

    // ── Truncation and masking reports ─────────────────────────────────────

    /**
     * Reports the results that are sent truncated for the first time and the rounds that are masked
     * for the first time by this call. Both follow from the number of rounds: the calls of a
     * request see one more round each, so what the previous call sent is known without keeping it.
     */
    private static void reportToolResultSteps(
            UUID requestId,
            String model,
            ContextManagementPolicy policy,
            ContextCompaction.View view,
            boolean firstCall,
            RunnerContext ctx) {
        int firstNewRound = firstCall ? 0 : view.rounds - 1;
        List<Map<String, Object>> truncated = new ArrayList<>();
        for (ContextCompaction.ResultChange change : view.truncated) {
            if (change.round >= firstNewRound) {
                Map<String, Object> result = resultDetails(change);
                result.put("original_chars", change.originalChars);
                result.put("sent_chars", change.sentChars);
                truncated.add(result);
            }
        }
        if (!truncated.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("max_chars", policy.getToolResultMaxChars());
            details.put("results", truncated);
            ctx.sendEvent(
                    new ContextManagementEvent(
                            requestId, model, ContextManagementEvent.STEP_TRUNCATE, details));
        }

        int maskedBefore =
                firstCall
                        ? 0
                        : ContextCompaction.maskedRounds(
                                view.rounds - 1,
                                policy.getToolResultKeepLastRounds(),
                                policy.getToolResultMaskStepRounds());
        List<Map<String, Object>> masked = new ArrayList<>();
        // A round that crossed the boundary counts when one of its results became a placeholder:
        // not when the summary already covers it, or when its results are too short to mask.
        Set<Integer> maskedRounds = new HashSet<>();
        for (ContextCompaction.ResultChange change : view.masked) {
            if (change.round >= maskedBefore) {
                Map<String, Object> result = resultDetails(change);
                result.put("omitted_chars", change.originalChars);
                masked.add(result);
                maskedRounds.add(change.round);
            }
        }
        if (!masked.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("keep_last_rounds", policy.getToolResultKeepLastRounds());
            details.put("step_rounds", policy.getToolResultMaskStepRounds());
            details.put("rounds", maskedRounds.size());
            details.put("results", masked);
            ctx.sendEvent(
                    new ContextManagementEvent(
                            requestId, model, ContextManagementEvent.STEP_MASK, details));
        }
    }

    private static Map<String, Object> resultDetails(ContextCompaction.ResultChange change) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("call_id", change.callId);
        if (change.tool != null) {
            result.put("tool", change.tool);
        }
        return result;
    }

    // ── Summarization ──────────────────────────────────────────────────────

    /** A summary that could not be written; the request goes on without it. */
    static final class SummaryFailure extends Exception {
        SummaryFailure(String message) {
            super(message);
        }
    }

    /** What one summarization step did. */
    private static final class SummaryStep {
        final String summarizer;

        /** How many rounds the summary covered before the step. */
        final int start;

        /** How many rounds the summary covers after the step. */
        final int folded;

        /** The summary after the step; null when there is none. */
        @Nullable final String summary;

        /** Why the step stopped before its target; null when it reached it. */
        @Nullable final String error;

        /** How many summarizer requests the step made. */
        final int calls;

        /** How many rounds the step added to the summary; rounds that load a skill are not. */
        final int added;

        SummaryStep(
                String summarizer,
                int start,
                int folded,
                @Nullable String summary,
                @Nullable String error,
                int calls,
                int added) {
            this.summarizer = summarizer;
            this.start = start;
            this.folded = folded;
            this.summary = summary;
            this.error = error;
            this.calls = calls;
            this.added = added;
        }
    }

    /**
     * Folds the rounds before the last kept ones into the summary: asks the summarizer for the line
     * of each round, in batches that fit the input budget, and adds the lines to the summary.
     * Updates {@code state} with what was folded.
     */
    private static SummaryStep summarize(
            UUID requestId,
            String model,
            @Nullable BaseChatModelSetup chatModel,
            ContextManagementPolicy policy,
            List<ChatMessage> messages,
            List<ContextCompaction.Round> rounds,
            Map<String, Object> promptArgs,
            Map<String, Object> state,
            RunnerContext ctx)
            throws Exception {
        int start = summarizedRounds(state);
        int target = rounds.size() - policy.getSummarizationKeepLastRounds();
        String summarizerName = policy.getSummarizer() == null ? model : policy.getSummarizer();

        int folded = start;
        int calls = 0;
        int added = 0;
        String summary = (String) state.get(SUMMARY);
        String error = null;
        try {
            // A summarizer that cannot be used still gets its durable call, which records why. A
            // replay then takes the same path as the first run, whether or not the summarizer can
            // be resolved at that time.
            BaseChatModelSetup summarizer = null;
            String unavailable = null;
            try {
                summarizer = resolveSummarizer(ctx, summarizerName);
            } catch (SummaryFailure e) {
                unavailable = e.getMessage();
            }
            RequestText asked =
                    requestText(requestMessages(chatModel, messages, promptArgs), policy);
            int budget = policy.getMaxInputTokens();
            int words = noteWordLimit(budget);
            // The most rounds one request shows. It drops to one when a summarizer that was
            // asked for the lines of several rounds left one out.
            int most = MAX_BATCH_ROUNDS;
            while (folded < target) {
                // The notes take a line for each round. Only as many rounds are asked about
                // as the notes have room for, so every line that is written can be kept.
                int room = roomForLines(summary, words, budget);
                Batch batch =
                        nextBatch(
                                messages,
                                rounds,
                                folded,
                                target,
                                asked,
                                words,
                                Math.max(Math.min(room, most), 1),
                                policy);
                if (!batch.rounds.isEmpty()) {
                    if (room == 0) {
                        throw full(summary, budget);
                    }
                    calls++;
                    String answer =
                            callSummarizer(
                                    summarizer, unavailable, summarizerName, batch.request, ctx);
                    String lines;
                    try {
                        lines = roundNotes(answer, batch.rounds, words);
                    } catch (SummaryFailure incomplete) {
                        if (batch.rounds.size() == 1) {
                            throw incomplete;
                        }
                        // Nothing is kept of the answer. The rounds are asked about again, one
                        // at a time.
                        most = 1;
                        continue;
                    }
                    summary = extended(summary, lines);
                    added += batch.rounds.size();
                    state.put(SUMMARY, summary);
                }
                folded = batch.end;
                state.put(SUMMARIZED_ROUNDS, folded);
            }
        } catch (SummaryFailure e) {
            error = e.getMessage();
            LOG.warn(
                    "Chat request {}: rounds {} to {} could not be summarized by '{}': {}. The"
                            + " request continues without the summary.",
                    requestId,
                    folded + 1,
                    target,
                    summarizerName,
                    error);
        }
        return new SummaryStep(summarizerName, start, folded, summary, error, calls, added);
    }

    /** The most tokens the notes of a request may take: half of the input budget. */
    private static long notesTokens(int budget) {
        return (long) (budget * NOTES_BUDGET_SHARE);
    }

    /**
     * How many words a summarizer is asked to keep the line of one round within: more with a larger
     * budget, whose rounds tend to hold more.
     */
    static int noteWordLimit(int maxInputTokens) {
        return Math.max(
                MIN_NOTE_WORDS,
                Math.min(MAX_NOTE_WORDS, maxInputTokens / BUDGET_TOKENS_PER_NOTE_WORD));
    }

    /**
     * How many more lines the notes have room for, up to the most rounds of one summarizer request.
     * A line counts with the most tokens it can take, so the lines of that many rounds always fit.
     */
    static int roomForLines(@Nullable String notes, int words, int budget) {
        long left = notesTokens(budget) - (notes == null ? 0 : textTokens(notes));
        long line = (long) words * NOTE_TOKENS_PER_WORD + NOTE_LABEL_TOKENS;
        return (int) Math.max(0, Math.min(MAX_BATCH_ROUNDS, left / line));
    }

    /**
     * The notes with the lines of more rounds added. The lines of earlier rounds stay as they are:
     * nothing rewrites them.
     */
    static String extended(@Nullable String notes, String lines) {
        return notes == null ? lines : notes + "\n" + lines;
    }

    static SummaryFailure full(@Nullable String notes, int budget) {
        return new SummaryFailure(
                "the summary is full: it takes "
                        + (notes == null ? 0 : textTokens(notes))
                        + " of the "
                        + notesTokens(budget)
                        + " estimated tokens it may take with "
                        + ContextManagementPolicy.MAX_INPUT_TOKENS
                        + "="
                        + budget
                        + ", which leaves no room for the line of another round");
    }

    /**
     * The lines of a summarizer's answer that are the notes on {@code roundNumbers}, one line for
     * each round, in order. Every other line of the answer is left out: a line for a round that was
     * not shown, a total, a conclusion. A line that is far longer than asked is cut.
     *
     * @throws SummaryFailure if the answer has no line for one of the rounds
     */
    static String roundNotes(String answer, List<Integer> roundNumbers, int words)
            throws SummaryFailure {
        Map<Integer, String> lines = new HashMap<>();
        for (String line : LINE_BREAK.split(answer)) {
            Matcher matcher = NOTE_LINE.matcher(line);
            if (matcher.matches() && !matcher.group(2).isEmpty()) {
                lines.putIfAbsent(Integer.parseInt(matcher.group(1)), matcher.group(2));
            }
        }
        long maxTokens = (long) words * NOTE_TOKENS_PER_WORD;
        StringBuilder notes = new StringBuilder();
        for (int number : roundNumbers) {
            String text = lines.get(number);
            if (text == null) {
                throw new SummaryFailure("the summarizer wrote no line for round " + number);
            }
            if (notes.length() > 0) {
                notes.append('\n');
            }
            notes.append("Round ")
                    .append(number)
                    .append(": ")
                    .append(cutToTokens(text, maxTokens, 1));
        }
        return notes.toString();
    }

    /**
     * Reports a summarization step, whether it folded rounds or failed.
     *
     * @param tokensBefore the estimated input that asked for the step
     * @param tokensAfter the estimated input with the new summary; null when nothing was folded
     */
    private static void reportSummaryStep(
            UUID requestId,
            String model,
            ContextManagementPolicy policy,
            SummaryStep step,
            long tokensBefore,
            boolean approximate,
            @Nullable Long tokensAfter,
            RunnerContext ctx) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("summarizer", step.summarizer);
        details.put("rounds", step.added);
        details.put("summarized_rounds", step.folded);
        details.put("max_input_tokens", policy.getMaxInputTokens());
        details.put("estimated_input_tokens_before", tokensBefore);
        details.put("estimate_approximate", approximate);
        if (tokensAfter != null && step.summary != null) {
            details.put("summary_chars", ContextCompaction.length(step.summary));
            details.put("estimated_input_tokens_after", tokensAfter);
        }
        if (step.error != null) {
            details.put("error", step.error);
        }
        ctx.sendEvent(
                new ContextManagementEvent(
                        requestId, model, ContextManagementEvent.STEP_SUMMARIZE, details));
    }

    private static BaseChatModelSetup resolveSummarizer(RunnerContext ctx, String name)
            throws Exception {
        BaseChatModelSetup summarizer;
        try {
            summarizer = (BaseChatModelSetup) ctx.getResource(name, ResourceType.CHAT_MODEL);
        } catch (Exception e) {
            rethrowIfCancelled(e);
            throw new SummaryFailure(
                    "chat model '" + name + "' cannot be used: " + ChatModelInvoker.errorText(e));
        }
        if (summarizer instanceof PythonResourceWrapper) {
            throw new SummaryFailure(
                    "chat model '"
                            + name
                            + "' is declared in Python, and a summary can only be written by a"
                            + " chat model declared in Java. Set "
                            + AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL.getKey()
                            + " to a Java chat model");
        }
        return summarizer;
    }

    /** The rounds one summarizer call folds, and the request that asks for it. */
    private static final class Batch {
        /** Index after the last round of the batch. */
        final int end;

        /**
         * The numbers, from 1, of the rounds the request shows; rounds that load a skill are not.
         */
        final List<Integer> rounds;

        /** Null when every round of the batch loads a skill: nothing is asked. */
        @Nullable final List<ChatMessage> request;

        Batch(int end, List<Integer> rounds, @Nullable List<ChatMessage> request) {
            this.end = end;
            this.rounds = rounds;
            this.request = request;
        }
    }

    /**
     * Takes as many rounds from {@code from} on as fit the input budget, at least one and at most
     * {@code maxRounds}. A round that does not fit a request of its own is cut until it does and is
     * a batch by itself, so the rounds after it are not cut with it. A round that loads a skill
     * stays in the messages and is not shown to the summarizer.
     *
     * @param maxRounds the most rounds the request may show
     * @throws SummaryFailure if not even a cut round fits beside the rest of the request
     */
    private static Batch nextBatch(
            List<ChatMessage> messages,
            List<ContextCompaction.Round> rounds,
            int from,
            int target,
            RequestText asked,
            int words,
            int maxRounds,
            ContextManagementPolicy policy)
            throws SummaryFailure {
        int budget = policy.getMaxInputTokens();
        int resultChars =
                policy.getToolResultMaxChars() == null
                        ? Integer.MAX_VALUE
                        : policy.getToolResultMaxChars();
        // What the rounds may take: the budget without the rest of the request. Each round is
        // counted once, so a long request is put together in one pass.
        long room = budget - tokens(summarizerRequest(asked, "", words));
        StringBuilder rendered = new StringBuilder();
        List<Integer> shown = new ArrayList<>();
        long used = 0;
        int end = from;
        while (end < target && shown.size() < maxRounds) {
            ContextCompaction.Round round = rounds.get(end);
            if (ContextCompaction.loadsSkill(messages.get(round.start))) {
                end++;
                continue;
            }
            String text = ContextCompaction.renderRound(messages, round, end + 1, resultChars);
            long size = textTokens(text);
            if (used + size > room) {
                if (shown.isEmpty()) {
                    rendered.append(fit(messages, round, end + 1, resultChars, room, budget));
                    shown.add(end + 1);
                    end++;
                }
                break;
            }
            rendered.append(text);
            used += size;
            shown.add(end + 1);
            end++;
        }
        return new Batch(
                end,
                shown,
                shown.isEmpty() ? null : summarizerRequest(asked, rendered.toString(), words));
    }

    /**
     * One round as text that fits {@code room} tokens: its results are cut further, and when that
     * is not enough, long assistant text or many results for example, its text as a whole.
     */
    private static String fit(
            List<ChatMessage> messages,
            ContextCompaction.Round round,
            int roundNumber,
            int resultChars,
            long room,
            int budget)
            throws SummaryFailure {
        String text = ContextCompaction.renderRound(messages, round, roundNumber, resultChars);
        while (textTokens(text) > room && resultChars > MIN_SUMMARIZER_RESULT_CHARS) {
            resultChars =
                    Math.max(
                            MIN_SUMMARIZER_RESULT_CHARS,
                            Math.min(resultChars, ContextCompaction.length(text)) / 2);
            text = ContextCompaction.renderRound(messages, round, roundNumber, resultChars);
        }
        String cut = cutToTokens(text, room, MIN_SUMMARIZER_ROUND_CHARS);
        if (cut == null) {
            throw new SummaryFailure(
                    "round "
                            + roundNumber
                            + " does not fit a summarizer request within "
                            + ContextManagementPolicy.MAX_INPUT_TOKENS
                            + "="
                            + budget);
        }
        return cut;
    }

    /**
     * Cuts {@code text} in the middle so that it takes at most {@code maxTokens}, keeping as much
     * of it as fits. Four ASCII characters are one token and any other character is one, so what
     * fits is between one and four characters for a token; the longest cut that fits is searched
     * for. Every cut is made from the whole text, so the marker tells how much of it is missing.
     *
     * @return the text that fits, or null when a cut to {@code minChars} characters does not
     */
    @Nullable
    static String cutToTokens(String text, long maxTokens, int minChars) {
        if (textTokens(text) <= maxTokens) {
            return text;
        }
        long most = Math.min(maxTokens * 4, ContextCompaction.length(text) - 1L);
        if (most < minChars) {
            return null;
        }
        // Text that is all ASCII fits at four characters for a token.
        String longest = ContextCompaction.truncateMiddle(text, (int) most);
        if (textTokens(longest) <= maxTokens) {
            return longest;
        }
        if (textTokens(ContextCompaction.truncateMiddle(text, minChars)) > maxTokens) {
            return null;
        }
        int fits = minChars;
        int tooLong = (int) most;
        while (tooLong - fits > 1) {
            int chars = fits + (tooLong - fits) / 2;
            if (textTokens(ContextCompaction.truncateMiddle(text, chars)) <= maxTokens) {
                fits = chars;
            } else {
                tooLong = chars;
            }
        }
        return ContextCompaction.truncateMiddle(text, fits);
    }

    private static long tokens(List<ChatMessage> request) {
        return ApproximateTokenEstimator.estimate(request, List.of()).getTokens();
    }

    /** The tokens a text adds to a message that holds it. */
    private static long textTokens(String text) {
        return tokens(List.of(ChatMessage.user(text))) - EMPTY_MESSAGE_TOKENS;
    }

    /** What the agent was asked, as it is shown to a summarizer. */
    static final class RequestText {
        /** The text of the system messages the model is sent, the bound prompt included. */
        final String instructions;

        /** The text of the user messages of the request. */
        final String request;

        RequestText(String instructions, String request) {
            this.instructions = instructions;
            this.request = request;
        }
    }

    /**
     * What the agent was asked: the instructions and the user messages among {@code shown}, the
     * messages the chat model is sent for the request. The summarizer needs both to tell what the
     * request still needs. All user messages are taken, also those that come after the rounds to
     * summarize, because the notes have to serve the latest of them.
     */
    private static RequestText requestText(
            List<ChatMessage> shown, ContextManagementPolicy policy) {
        // Each part may take an eighth of the budget, so that both leave room for the notes and
        // the rounds whatever their script.
        long maxTokens =
                Math.min(MAX_TASK_TOKENS, (long) (policy.getMaxInputTokens() * TASK_BUDGET_SHARE));
        return new RequestText(
                text(shown, MessageRole.SYSTEM, maxTokens),
                text(shown, MessageRole.USER, maxTokens));
    }

    private static String text(List<ChatMessage> messages, MessageRole role, long maxTokens) {
        StringBuilder text = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message.getRole() == role && !message.getText().isEmpty()) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(message.getText());
            }
        }
        String cut = cutToTokens(text.toString(), maxTokens, 1);
        return cut == null ? "" : cut;
    }

    static List<ChatMessage> summarizerRequest(
            RequestText asked, String renderedRounds, int wordLimit) {
        StringBuilder body = new StringBuilder();
        if (!asked.instructions.isEmpty()) {
            body.append("The agent's instructions, for context only:\n")
                    .append(asked.instructions)
                    .append("\n\n");
        }
        if (!asked.request.isEmpty()) {
            body.append("The request the agent is working on, for context only:\n")
                    .append(asked.request)
                    .append("\n\n");
        }
        body.append("Rounds to write notes for:\n")
                .append(renderedRounds)
                .append("\nWrite one line for each round above, at most ")
                .append(wordLimit)
                .append(" words each. Use only what the rounds above contain.");
        return List.of(
                ChatMessage.system(SUMMARIZER_INSTRUCTIONS), ChatMessage.user(body.toString()));
    }

    /**
     * Asks the summarizer for the notes, as one durable call with its own LLM span and token
     * metrics. The request is sent as it is: without the chat model's bound prompt, its tools and
     * the parameters that only apply to a request with tools.
     *
     * <p>A summarizer that cannot be used still takes the durable call, which records why, and no
     * LLM span is reported: no model is called.
     *
     * @param summarizer the chat model that writes the notes, or null when it cannot be used
     * @param unavailable why the summarizer cannot be used; the durable call records it as the
     *     outcome
     * @throws SummaryFailure if the call fails or returns no usable text
     */
    private static String callSummarizer(
            @Nullable BaseChatModelSetup summarizer,
            @Nullable String unavailable,
            String summarizerName,
            List<ChatMessage> request,
            RunnerContext ctx)
            throws Exception {
        DurableCallable<ChatModelInvoker.InvocationOutcome> callable =
                new DurableCallable<>() {
                    @Override
                    public String getId() {
                        return SUMMARIZE_CALL_ID;
                    }

                    @Override
                    public Class<ChatModelInvoker.InvocationOutcome> getResultClass() {
                        return ChatModelInvoker.InvocationOutcome.class;
                    }

                    @Override
                    public ChatModelInvoker.InvocationOutcome call() throws Exception {
                        ChatModelInvoker.InvocationOutcome outcome =
                                new ChatModelInvoker.InvocationOutcome();
                        if (summarizer == null) {
                            outcome.error = unavailable;
                            return outcome;
                        }
                        try {
                            outcome.response = summarizer.chatWithoutTools(request);
                        } catch (Exception e) {
                            rethrowIfCancelled(e);
                            LOG.debug("Summarizer call failed", e);
                            outcome.error = ChatModelInvoker.errorText(e);
                        }
                        return outcome;
                    }
                };
        Map<String, Object> llmMetadata =
                summarizer == null || summarizer.getModel() == null
                        ? Map.of()
                        : Map.of(LLMExecutionMetadataKeys.MODEL, summarizer.getModel());
        String executionId =
                summarizer == null
                        ? null
                        : ExecutionReporters.started(
                                ctx,
                                ExecutionReporter.EntityTypes.LLM,
                                summarizerName,
                                llmMetadata);
        // A failure of durable execution itself is not a failed summary: it propagates.
        ChatModelInvoker.InvocationOutcome outcome;
        try {
            outcome =
                    ctx.getConfig().get(AgentExecutionOptions.CHAT_ASYNC)
                            ? ctx.durableExecuteAsync(callable).await()
                            : ctx.durableExecute(callable);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
        ChatResult response = outcome.response;
        String failure =
                outcome.error != null
                        ? outcome.error
                        : response == null ? "the chat model returned no response" : null;
        if (failure != null) {
            if (executionId != null) {
                ExecutionReporters.failed(
                        ctx,
                        executionId,
                        ExecutionReporter.EntityTypes.LLM,
                        summarizerName,
                        llmMetadata,
                        new ChatModelInvoker.InvocationFailure(failure),
                        ExecutionReporter.ProblemCategories.MODEL_CALL_FAILED);
            }
            throw new SummaryFailure(failure);
        }
        // Reported and vetted as a chat call's response is. A recorded response can be replayed
        // while the summarizer cannot be resolved; it is then used without a span of its own.
        if (executionId != null) {
            ChatModelInvoker.reportSuccessfulResponse(
                    ctx,
                    executionId,
                    summarizerName,
                    llmMetadata,
                    summarizer,
                    response,
                    ctx.getActionMetricGroup());
        }
        try {
            ChatModelAction.rejectIncompleteResponse(response);
        } catch (IllegalStateException incomplete) {
            throw new SummaryFailure(
                    "the summary is incomplete (finish_reason='"
                            + response.getFinishReason()
                            + "')");
        }
        String summary = response.getText().trim();
        if (summary.isEmpty()) {
            throw new SummaryFailure("the chat model returned no text");
        }
        return summary;
    }
}
