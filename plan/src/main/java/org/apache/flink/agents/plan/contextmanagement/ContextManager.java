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
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.chat.model.TokenEstimate;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ContextManagementEvent;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.ExecutionReporters;
import org.apache.flink.agents.api.trace.LLMExecutionMetadataKeys;
import org.apache.flink.agents.plan.actions.ChatModelInvoker;
import org.apache.flink.agents.plan.actions.Utils;
import org.apache.flink.agents.plan.exceptions.UncapturableException;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies a chat request's {@link ContextManagementPolicy} for the built-in chat action: decides
 * what one model call is sent, writes a summary when the input budget asks for one, and reports
 * each step as a {@link ContextManagementEvent}.
 *
 * <p>The stored conversation of the request is only read. What this class keeps for a request is
 * its own policy when the request carries one, the lines of the summary and how many rounds they
 * cover, and the factor that corrects approximate token estimates. It lives in sensory memory at a
 * path of the request's own under {@link #STATE_KEY}, so requests of one key that overlap do not
 * write each other's state, and it is emptied when the request completes.
 *
 * <p>Truncation and masking are pure functions of the stored messages, so they add no durable call
 * and a replay sends the same messages. With an input budget, the input of a model call is
 * estimated when a round is due for a summary, as one durable call under the id {@link
 * #ESTIMATE_CALL_ID}: estimating renders the chat model's bound prompt, which can be a remote call,
 * and a provider can count the tokens itself. A summary is one durable call per batch under the id
 * {@link #SUMMARIZE_CALL_ID}, made before the round's model call. Whether a summary is written is
 * decided from the stored messages, the kept state and the recorded estimate, so a replay reaches
 * the same decision and reuses the recorded summary.
 *
 * <p>The summary has one line for each summarized round. A summarizer writes the lines of the
 * rounds it is shown, only those lines are kept, and they are added to the summary: a line is
 * written once, from its own round, and nothing rewrites it. Lines are used from the model call on
 * whose messages are smaller with them than without, both measured by their size; until then they
 * are held. A round gets no line when a line could not stand for it or would save nothing: it loads
 * a skill, it returned media, or its results are already masked. Such a round is passed over and
 * stays in the messages.
 */
public final class ContextManager {

    private static final Logger LOG = LoggerFactory.getLogger(ContextManager.class);

    /** Sensory-memory key under which the state of each request has a path of its own. */
    public static final String STATE_KEY = "_CONTEXT_MANAGEMENT_CONTEXT";

    /** Durable call id of the estimate of a model call's input. */
    public static final String ESTIMATE_CALL_ID = "estimate";

    /** Durable call id of a summarizer call. */
    public static final String SUMMARIZE_CALL_ID = "summarize";

    private static final String POLICY = "policy";
    private static final String SUMMARY = "summary";
    private static final String PENDING_SUMMARY = "pending_summary";
    private static final String WRITTEN_ROUNDS = "written_rounds";
    private static final String FAILED_STEPS = "failed_steps";
    private static final String RETRY_AT_ROUNDS = "retry_at_rounds";
    private static final String UNUSABLE_ANSWERS = "unusable_answers";
    private static final String CALIBRATION = "calibration";
    private static final String CALIBRATION_TOKENS = "calibration_tokens";
    private static final String CALIBRATION_AT_LARGEST = "calibration_at_largest";

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

    /**
     * The least that is left of a part of a round that is cut, in tokens: what 100 characters of
     * ASCII text take, enough for a short call and the start of its result.
     */
    private static final int MIN_SUMMARIZER_PART_TOKENS = 25;

    /**
     * The least room, in tokens, that a summarizer request must have for a round: the label of the
     * round, one part that is cut to the least, and the line that tells how many parts are left
     * out. With that much room a round of any size and script is cut to what fits, so whether
     * rounds can be shown does not depend on the round.
     */
    static final int MIN_SUMMARIZER_ROUND_TOKENS = 50;

    /**
     * After a summary that could not be written, the request waits before it tries again: one round
     * after the first failure in a row, then two, four, and from the fourth on eight.
     */
    private static final int MAX_RETRY_WAIT_DOUBLINGS = 3;

    /**
     * After this many steps in a row that ended at the same round because the summarizer's answer
     * for it could not be used, the round gets no line.
     */
    private static final int MAX_UNUSABLE_ANSWERS = 3;

    /** What may stand between the label of a round and its text. */
    private static final String LABEL_SEPARATORS = ":\uFF1A-\u2013\u2014";

    /**
     * The names of the chat models whose failing estimate was already logged as a warning in this
     * process. A chat model of such a name, of any agent, is logged at DEBUG after that.
     */
    private static final Set<String> ESTIMATE_WARNED = ConcurrentHashMap.newKeySet();

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

    /**
     * The start of a line that carries the label of a round: optional list or emphasis marks, an
     * optional ordinal, and the label. What follows the label on the line is read by hand, so a
     * long line costs no more than its length.
     */
    private static final Pattern LABEL =
            Pattern.compile(
                    "[ \\t\\-*\u2022>#]*(?:[0-9]{1,3}[.)][ \\t\\-*\u2022>#]*)?Round[ \\t]+([0-9]{1,9})",
                    Pattern.CASE_INSENSITIVE);

    /** The start of a list item: its mark or ordinal. */
    private static final Pattern LIST_MARK =
            Pattern.compile("[ \\t]*(?:[\\-*\u2022]|[0-9]{1,3}[.)])[ \\t]+");

    /**
     * How an answer that the model ended by itself is named: by the connections, and by endpoints
     * that speak the protocol of one and name the end-of-sequence token.
     */
    private static final Set<String> COMPLETE_REASONS =
            Set.of("stop", "end_turn", "eos", "eos_token");

    /** What joins the list items that follow a label into the line of its round. */
    private static final String ITEM_SEPARATOR = "; ";

    private ContextManager() {}

    /** The messages the chat action sends for one model call. */
    public static final class Prepared {
        /**
         * The messages to send: the stored messages themselves when no policy applies, and a list
         * of the chat model's own otherwise.
         */
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
     * The recorded estimate of a model call's input, the result of the durable call {@link
     * #ESTIMATE_CALL_ID}. The fields are public for the durable call's serialization.
     */
    public static final class EstimateOutcome {
        /**
         * The estimated input tokens, uncorrected. -1 when nothing was estimated, because no chat
         * model can be asked for the request.
         */
        public long tokens;

        /** Whether {@link #tokens} was derived from the size of the request. */
        public boolean approximate;

        /**
         * The instructions the model is sent, as a summarizer is shown them. Recorded when the
         * estimate asks for a summary of more rounds, null otherwise.
         */
        @Nullable public String instructions;

        /** The text of the request's user messages, recorded with {@link #instructions}. */
        @Nullable public String request;

        public EstimateOutcome() {}
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
     * Removes what is kept for a request. Memory has no way to delete a field, so the state is
     * replaced by an empty one; sensory memory drops it with the run.
     */
    public static void clearRequest(MemoryObject sensoryMemory, UUID requestId) throws Exception {
        if (!readState(sensoryMemory, requestId).isEmpty()) {
            writeState(sensoryMemory, requestId, new HashMap<>());
        }
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
     * @param candidates the chat model resources the call can go to, in the order in which they are
     *     tried: {@code model}, then those a router falls back to
     * @param outputSchema the output schema of the request, or null
     */
    public static Prepared prepare(
            UUID requestId,
            String model,
            List<ChatMessage> messages,
            Map<String, Object> promptArgs,
            boolean firstCall,
            RunnerContext ctx,
            List<String> candidates,
            @Nullable Object outputSchema)
            throws Exception {
        // Only tool-call rounds are ever changed. A request that has none yet is sent as it is,
        // whatever its policy: nothing is read for it and nothing can fail.
        if (!ContextCompaction.hasRound(messages)) {
            return new Prepared(messages, -1, false);
        }
        MemoryObject memory = ctx.getSensoryMemory();
        Map<String, Object> state = readState(memory, requestId);
        ContextManagementPolicy policy = policyOf(state, ctx);
        // Without a policy the rounds are not listed. A list with a message that is null is
        // sent as it is too: the model call reports it, as it does without a policy.
        if (!policy.isEnabled() || hasNull(messages)) {
            return new Prepared(messages, -1, false);
        }
        List<ContextCompaction.Round> rounds = ContextCompaction.findRounds(messages);

        ContextCompaction.View view =
                ContextCompaction.view(messages, rounds, policy, (String) state.get(SUMMARY));
        long rawEstimate = -1;
        boolean approximate = false;
        Integer budget = policy.getMaxInputTokens();
        if (budget != null) {
            int target = rounds.size() - policy.getSummarizationKeepLastRounds();
            int masked =
                    ContextCompaction.maskedRounds(
                            rounds.size(),
                            policy.getToolResultKeepLastRounds(),
                            policy.getToolResultMaskStepRounds());
            // The first round that is due for its line: rounds that get none are passed over.
            // How far that went is kept, so that such a round is looked at once and not
            // before every model call.
            int written = writtenRounds(state);
            int due = written;
            String pending = (String) state.get(PENDING_SUMMARY);
            boolean full =
                    pending != null
                            && roomForLines(
                                            joined((String) state.get(SUMMARY), pending),
                                            noteWordLimit(budget),
                                            budget,
                                            calibration(state))
                                    == 0;
            while (due < target && getsNoLine(messages, rounds, due, masked, policy, full)) {
                due++;
            }
            if (due > written) {
                state.put(WRITTEN_ROUNDS, due);
                // A count of answers that could not be used was for a round that is behind
                // now.
                state.remove(UNUSABLE_ANSWERS);
                writeState(memory, requestId, state);
            }
            // The input is estimated only when a round is due for its line, and after a
            // summary that could not be written not before the round the request waits for.
            if (due < target && rounds.size() >= retryAtRounds(state)) {
                BaseChatModelSetup chatModel = resolveChatModel(ctx, model);
                Double calibration = calibration(state);
                double trigger = policy.getSummarizationTriggerRatio() * budget;
                // A request that no chat model can be asked for fails before its model call. No
                // provider is asked anything for it: that is recorded in place of an estimate,
                // which is then below any trigger.
                EstimateOutcome estimate =
                        estimate(
                                model,
                                chatModel,
                                view.messages,
                                messages,
                                promptArgs,
                                policy,
                                calibration,
                                trigger,
                                canBeAttempted(ctx, candidates, outputSchema),
                                ctx);
                long estimated = corrected(estimate.tokens, estimate.approximate, calibration);
                rawEstimate = estimate.tokens;
                approximate = estimate.approximate;
                if (estimated > trigger) {
                    SummaryStep step =
                            summarize(
                                    requestId,
                                    model,
                                    policy,
                                    messages,
                                    rounds,
                                    new RequestText(
                                            estimate.instructions == null
                                                    ? ""
                                                    : estimate.instructions,
                                            estimate.request == null ? "" : estimate.request),
                                    calibration,
                                    masked,
                                    state,
                                    ctx);
                    Long estimatedAfter = null;
                    int heldLines = step.heldLines;
                    if (step.added > 0) {
                        // The lines are used from the call on whose messages are smaller with
                        // them than without: a summary of short rounds can be longer than the
                        // rounds. Until then they are held, and their rounds are asked about
                        // no more. Both lists are measured the same way, by their size. What
                        // else the request holds, its prompt and tools, is the same for both,
                        // and a count that a provider makes may be had for one of them and
                        // not for the other.
                        ContextCompaction.View candidate =
                                ContextCompaction.view(messages, rounds, policy, step.summary);
                        long shown = tokens(view.messages);
                        long sent = tokens(candidate.messages);
                        if (sent < shown) {
                            state.put(SUMMARY, step.summary);
                            state.remove(PENDING_SUMMARY);
                            view = candidate;
                            heldLines = 0;
                            rawEstimate = estimateAfter(estimate.tokens, shown, sent);
                            estimatedAfter = corrected(rawEstimate, approximate, calibration);
                        }
                    }
                    boolean retryChanged = recordOutcome(state, step.error != null, rounds.size());
                    Integer passedOver = passOverUnusableRound(state, step);
                    if (passedOver != null) {
                        // What held the steps up is behind now, so the rounds after it are
                        // tried with the next model call. The count of failed steps in a row
                        // stays: when the answers for those rounds cannot be used either, the
                        // request waits longer between its attempts, as it does for a
                        // summarizer that keeps failing.
                        state.remove(RETRY_AT_ROUNDS);
                        LOG.warn(
                                "Chat request {}: round {} gets no line, because the answers of"
                                        + " '{}' for it could not be used in {} steps in a row. It"
                                        + " stays in the messages.",
                                requestId,
                                passedOver,
                                step.summarizer,
                                MAX_UNUSABLE_ANSWERS);
                    }
                    if (step.folded > step.start || retryChanged) {
                        // New lines, rounds that were passed over, or a wait that changed. A
                        // count of answers that could not be used changes only with the wait.
                        writeState(memory, requestId, state);
                    }
                    // A round was due, so the step asked the summarizer or failed before it
                    // could.
                    reportSummaryStep(
                            requestId,
                            model,
                            policy,
                            step,
                            lineCount((String) state.get(SUMMARY)),
                            heldLines,
                            passedOver,
                            estimated,
                            approximate,
                            estimatedAfter,
                            ctx);
                }
            }
        }
        reportToolResultSteps(requestId, model, policy, view, firstCall, ctx);
        // A chat model may change the list it is handed, as it may without a policy.
        return new Prepared(new ArrayList<>(view.messages), rawEstimate, approximate);
    }

    /**
     * Compares the input size the provider reported for a model call with the estimate made for it,
     * and keeps the factor between them for the request's next estimates. The factor that is kept
     * is the larger of two: that of the call with the largest reported input, and that of the
     * latest call. Only approximate estimates are corrected, and only upward.
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
        // larger reported input replaces the factor of the largest call, also with a smaller
        // one. A call that reports less does not: the provider can have served it from its
        // cache. The factor of the latest call counts while it is the larger of the two: the
        // input has become shorter, by a summary for example, and the fixed part weighs more in
        // it.
        Object measured = state.get(CALIBRATION_TOKENS);
        Object measuredFactor = state.get(CALIBRATION_AT_LARGEST);
        long largest = promptTokens;
        double atLargest = factor;
        if (measured instanceof Number
                && measuredFactor instanceof Number
                && ((Number) measured).longValue() > promptTokens) {
            largest = ((Number) measured).longValue();
            atLargest = ((Number) measuredFactor).doubleValue();
        }
        double kept = Math.max(atLargest, factor);
        Double keptBefore = calibration(state);
        if (keptBefore != null
                && keptBefore == kept
                && measured instanceof Number
                && ((Number) measured).longValue() == largest
                && measuredFactor instanceof Number
                && ((Number) measuredFactor).doubleValue() == atLargest) {
            // Nothing that would change: the state is not written.
            return;
        }
        state.put(CALIBRATION, kept);
        state.put(CALIBRATION_TOKENS, largest);
        state.put(CALIBRATION_AT_LARGEST, atLargest);
        writeState(sensoryMemory, requestId, state);
    }

    /**
     * Drops the factor that corrects a request's estimates. For a request whose tool loop goes on
     * with another chat model than the one the factor was learned from, the next tier of an
     * escalation for example: what one model's provider reports says nothing about the estimates of
     * another.
     */
    public static void forgetCorrection(MemoryObject sensoryMemory, UUID requestId)
            throws Exception {
        Map<String, Object> state = readState(sensoryMemory, requestId);
        boolean kept = state.remove(CALIBRATION) != null;
        kept |= state.remove(CALIBRATION_TOKENS) != null;
        kept |= state.remove(CALIBRATION_AT_LARGEST) != null;
        if (kept) {
            writeState(sensoryMemory, requestId, state);
        }
    }

    private static boolean hasNull(List<ChatMessage> messages) {
        for (ChatMessage message : messages) {
            if (message == null) {
                return true;
            }
        }
        return false;
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

    /**
     * How many of the request's oldest rounds have been gone through: each of them has its line,
     * sent or held, or gets none.
     */
    private static int writtenRounds(Map<String, Object> state) {
        Object rounds = state.get(WRITTEN_ROUNDS);
        return rounds instanceof Number ? ((Number) rounds).intValue() : 0;
    }

    /** The number of rounds from which the request tries a summary again; 0 without a wait. */
    private static int retryAtRounds(Map<String, Object> state) {
        Object rounds = state.get(RETRY_AT_ROUNDS);
        return rounds instanceof Number ? ((Number) rounds).intValue() : 0;
    }

    /**
     * Keeps how a summarization step ended, for the wait before the next one. A step that fails
     * makes the request wait: one round after the first failure in a row, then two, four, and from
     * the fourth on eight, so a summarizer that keeps failing is not called for every round. A step
     * that does not fail ends the wait.
     *
     * @param rounds how many rounds the request has
     * @return whether the state changed
     */
    private static boolean recordOutcome(Map<String, Object> state, boolean failed, int rounds) {
        if (!failed) {
            boolean waited = state.remove(FAILED_STEPS) != null;
            return state.remove(RETRY_AT_ROUNDS) != null || waited;
        }
        Object before = state.get(FAILED_STEPS);
        int failures = (before instanceof Number ? ((Number) before).intValue() : 0) + 1;
        state.put(FAILED_STEPS, failures);
        state.put(
                RETRY_AT_ROUNDS, rounds + (1 << Math.min(failures - 1, MAX_RETRY_WAIT_DOUBLINGS)));
        return true;
    }

    /**
     * Counts the steps in a row that ended at the same round because the summarizer's answer for it
     * could not be used. With the third such step the round gets no line: it is passed over and
     * stays in the messages, so that one round cannot hold up every round after it. A step that
     * fails for another reason, such as a summarizer that cannot be reached, says nothing about the
     * round and starts the count again.
     *
     * @return the number, from 1, of the round that is passed over, or null
     */
    @Nullable
    private static Integer passOverUnusableRound(Map<String, Object> state, SummaryStep step) {
        if (!step.unusableAnswer) {
            state.remove(UNUSABLE_ANSWERS);
            return null;
        }
        Object before = state.get(UNUSABLE_ANSWERS);
        // A step that moved on before it stopped is at another round than the step before it.
        int answers =
                (step.folded == step.start && before instanceof Number
                                ? ((Number) before).intValue()
                                : 0)
                        + 1;
        if (answers < MAX_UNUSABLE_ANSWERS) {
            state.put(UNUSABLE_ANSWERS, answers);
            return null;
        }
        state.remove(UNUSABLE_ANSWERS);
        state.put(WRITTEN_ROUNDS, step.folded + 1);
        return step.folded + 1;
    }

    /**
     * Whether a round gets no line. One that {@linkplain ContextCompaction#staysWhenSummarized
     * stays} when the rounds around it are summarized gets none. Neither does a short round in two
     * cases. A round is short when no more is sent of it than a line of the fewest words may take,
     * whatever the budget.
     *
     * <ul>
     *   <li>The round is past the masking boundary: its results are placeholders by then, and a
     *       line would cost a call to the summarizer without making the round shorter. A round past
     *       the boundary that is still longer, because of its assistant message or of results that
     *       are not masked, is due like any other.
     *   <li>The notes have no room, and lines are held because the messages were no smaller with
     *       them. A line for another short round would be held like them, and asking for it would
     *       give those lines up for nothing.
     * </ul>
     *
     * @param masked how many of the oldest rounds are past the masking boundary
     * @param heldLinesFillTheNotes whether the notes have no room for another line and hold lines
     *     that are not sent
     */
    private static boolean getsNoLine(
            List<ChatMessage> messages,
            List<ContextCompaction.Round> rounds,
            int index,
            int masked,
            ContextManagementPolicy policy,
            boolean heldLinesFillTheNotes) {
        ContextCompaction.Round round = rounds.get(index);
        if (ContextCompaction.staysWhenSummarized(messages, round)) {
            return true;
        }
        boolean pastBoundary = index < masked;
        if (!pastBoundary && !heldLinesFillTheNotes) {
            return false;
        }
        List<ChatMessage> sent =
                ContextCompaction.sentRound(
                        messages, round, index, policy.getToolResultMaxChars(), pastBoundary);
        return tokens(sent) <= lineTokens(MIN_NOTE_WORDS);
    }

    /** The most tokens the line of a round takes in the notes, its label included. */
    private static long lineTokens(int words) {
        return (long) words * NOTE_TOKENS_PER_WORD + NOTE_LABEL_TOKENS;
    }

    /**
     * Rethrows what is not a failure of a provider: a cancellation, and a fault of the framework,
     * which must not be recorded as the outcome of a call or absorbed as a missing estimate.
     */
    private static void rethrowUnlessProviderFailure(Exception e) throws Exception {
        CancellationUtils.rethrowIfCancellation(e);
        UncapturableException.rethrowIfPresent(e);
    }

    /**
     * The sensory-memory path of a request's state. Every request has a path of its own: an action
     * writes only the paths it sets, so two requests of one key that overlap cannot replace each
     * other's state, as they could with one value for all requests.
     */
    static String statePath(UUID requestId) {
        return STATE_KEY + "." + requestId;
    }

    /** The state of a request as a copy that can be changed; empty when nothing is kept. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readState(MemoryObject memory, UUID requestId)
            throws Exception {
        String path = statePath(requestId);
        if (!memory.isExist(path)) {
            return new HashMap<>();
        }
        Object state = memory.get(path).getValue();
        return state instanceof Map ? new HashMap<>((Map<String, Object>) state) : new HashMap<>();
    }

    private static void writeState(MemoryObject memory, UUID requestId, Map<String, Object> state)
            throws Exception {
        memory.set(statePath(requestId), state);
    }

    // ── Token estimates ────────────────────────────────────────────────────

    @Nullable
    private static BaseChatModelSetup resolveChatModel(RunnerContext ctx, String model)
            throws Exception {
        try {
            return (BaseChatModelSetup) ctx.getResource(model, ResourceType.CHAT_MODEL);
        } catch (Exception e) {
            rethrowUnlessProviderFailure(e);
            // The model call itself reports a chat model that cannot be resolved.
            return null;
        }
    }

    /**
     * Whether a chat model can be asked for a request at all. A candidate can when it resolves and,
     * with an output schema, can be asked for it. These are the checks a model attempt makes before
     * its call, so a request none of whose candidates passes them fails without a model call.
     */
    private static boolean canBeAttempted(
            RunnerContext ctx, List<String> candidates, @Nullable Object outputSchema)
            throws Exception {
        for (String candidate : candidates) {
            BaseChatModelSetup chatModel = resolveChatModel(ctx, candidate);
            if (chatModel == null) {
                continue;
            }
            if (outputSchema == null) {
                return true;
            }
            try {
                chatModel.willApplyNativeStructuredOutput(outputSchema);
                return true;
            } catch (Exception e) {
                rethrowUnlessProviderFailure(e);
            }
        }
        return false;
    }

    /**
     * Estimates the input of a model call as one durable call, so the estimate is recorded and a
     * recovery decides from the same number. Estimating renders the chat model's bound prompt,
     * which is a remote call for a prompt served by an MCP server, and a connection may ask its
     * provider for the count; neither is repeated after a recovery, and with {@code chat.async}
     * neither runs on the operator's thread. The prompt is rendered a second time in this call when
     * a summary is due, for what the summarizer is told about the request.
     *
     * @param model the name of the chat model resource
     * @param sent the messages the model would be sent
     * @param stored the stored messages of the request
     * @param askedAbove when the corrected estimate is above this many tokens, what the agent was
     *     asked is recorded with it for the summarizer
     * @param attemptable whether a chat model can be asked for the request; when none can, nothing
     *     is estimated, and that is what is recorded: a recovery then goes the way the first run
     *     went, whatever the chat models say by then
     */
    private static EstimateOutcome estimate(
            String model,
            @Nullable BaseChatModelSetup chatModel,
            List<ChatMessage> sent,
            List<ChatMessage> stored,
            Map<String, Object> promptArgs,
            ContextManagementPolicy policy,
            @Nullable Double calibration,
            double askedAbove,
            boolean attemptable,
            RunnerContext ctx)
            throws Exception {
        DurableCallable<EstimateOutcome> callable =
                new DurableCallable<>() {
                    @Override
                    public String getId() {
                        return ESTIMATE_CALL_ID;
                    }

                    @Override
                    public Class<EstimateOutcome> getResultClass() {
                        return EstimateOutcome.class;
                    }

                    @Override
                    public EstimateOutcome call() throws Exception {
                        EstimateOutcome outcome = new EstimateOutcome();
                        if (!attemptable) {
                            outcome.tokens = -1;
                            return outcome;
                        }
                        TokenEstimate estimate = estimateNow(model, chatModel, sent, promptArgs);
                        outcome.tokens = estimate.getTokens();
                        outcome.approximate = estimate.isApproximate();
                        if (corrected(outcome.tokens, outcome.approximate, calibration)
                                > askedAbove) {
                            RequestText asked =
                                    requestText(
                                            withProviderInstructions(
                                                    chatModel,
                                                    requestMessages(chatModel, stored, promptArgs)),
                                            policy,
                                            calibration);
                            outcome.instructions = asked.instructions;
                            outcome.request = asked.request;
                        }
                        return outcome;
                    }
                };
        try {
            // The estimate renders the bound prompt of the chat model.
            return runsAsync(ctx, chatModel, true)
                    ? ctx.durableExecuteAsync(callable).await()
                    : ctx.durableExecute(callable);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /**
     * Whether a durable call that uses a chat model is made off the operator's thread. It follows
     * {@code chat.async}, as the chat call does. A call that reaches a Python resource is made on
     * the operator's thread where the Flink version cannot make it from another thread.
     *
     * @param rendersPrompt whether the call renders the bound prompt of the chat model
     */
    private static boolean runsAsync(
            RunnerContext ctx, @Nullable BaseChatModelSetup chatModel, boolean rendersPrompt)
            throws Exception {
        if (!ctx.getConfig().get(AgentExecutionOptions.CHAT_ASYNC)) {
            return false;
        }
        return Utils.supportAsync() || !reachesPython(ctx, chatModel, rendersPrompt);
    }

    /**
     * Whether a call of a chat model reaches a Python resource: the chat model, its connection, or
     * the bound prompt when the call renders it. The bound tools are not such a resource for these
     * calls: only their descriptions are read, which are kept on the Java side.
     *
     * <p>The connection is looked up where the chat model looked it up when it was opened, in its
     * own resource context: a chat model that an agent inherits is bound to the connection of the
     * agent that declares it, whatever the name stands for here.
     */
    private static boolean reachesPython(
            RunnerContext ctx, @Nullable BaseChatModelSetup chatModel, boolean rendersPrompt)
            throws Exception {
        if (chatModel == null) {
            return false;
        }
        if (chatModel instanceof PythonResourceWrapper) {
            return true;
        }
        if (rendersPrompt && chatModel.getPrompt() instanceof PythonResourceWrapper) {
            return true;
        }
        String connection = chatModel.getConnectionName();
        if (connection == null) {
            return false;
        }
        try {
            ResourceContext resources = chatModel.getResourceContext();
            Resource resolved =
                    resources == null
                            ? ctx.getResource(connection, ResourceType.CHAT_MODEL_CONNECTION)
                            : resources.getResource(connection, ResourceType.CHAT_MODEL_CONNECTION);
            return resolved instanceof PythonResourceWrapper;
        } catch (Exception e) {
            rethrowUnlessProviderFailure(e);
            // The call itself reports a connection that cannot be resolved.
            return false;
        }
    }

    /**
     * The chat model's estimate of the request it would send for {@code messages}, or the
     * approximation when the chat model cannot give one: its estimate fails or is null.
     */
    private static TokenEstimate estimateNow(
            String model,
            @Nullable BaseChatModelSetup chatModel,
            List<ChatMessage> messages,
            Map<String, Object> promptArgs)
            throws Exception {
        if (chatModel == null) {
            return ApproximateTokenEstimator.estimate(messages, List.of());
        }
        try {
            // A list of the chat model's own: what a chat model does with the list it is
            // handed, put a message into it for example, must not reach the messages the
            // request is managed with, nor the ones it is sent.
            TokenEstimate estimate =
                    chatModel.estimateInputTokens(new ArrayList<>(messages), promptArgs);
            if (estimate != null) {
                return estimate;
            }
            warnOnce(model, "returned no estimate of its input");
        } catch (Exception e) {
            rethrowUnlessProviderFailure(e);
            warnOnce(model, "could not estimate its input (" + ChatModelInvoker.errorText(e) + ")");
            LOG.debug("The failed estimate of chat model '{}'", model, e);
        }
        // What the chat model's connection counts by default: the request it sends, the
        // instructions it gives its provider beside the messages, and its tools.
        List<ChatMessage> request =
                withProviderInstructions(
                        chatModel, requestMessages(chatModel, messages, promptArgs));
        try {
            List<Tool> tools = chatModel.getTools();
            return ApproximateTokenEstimator.estimate(request, tools == null ? List.of() : tools);
        } catch (Exception e) {
            rethrowUnlessProviderFailure(e);
            // A tool that cannot be measured: the request is counted without the tools, so
            // that the estimate a model call falls back on does not fail it.
            LOG.debug("The tools of chat model '{}'", model, e);
            return ApproximateTokenEstimator.estimate(request, List.of());
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
            // A list of the chat model's own, as for its estimate.
            return chatModel.prepareRequestMessages(new ArrayList<>(messages), promptArgs);
        } catch (Exception e) {
            rethrowUnlessProviderFailure(e);
            LOG.debug("Chat model could not prepare its request; using the request's messages", e);
            return messages;
        }
    }

    /**
     * Logs a failing estimate of a chat model as a warning once, and not for every model call.
     *
     * @return whether the warning was logged
     */
    static boolean warnOnce(String model, String what) {
        if (!ESTIMATE_WARNED.add(model)) {
            return false;
        }
        LOG.warn(
                "Chat model '{}' {}; the approximation is used for it. This is logged once.",
                model,
                what);
        return true;
    }

    /**
     * The messages, led by the instructions a chat model gives its provider as a parameter and not
     * as a message, so that a summarizer is shown them like the instructions of a bound prompt.
     */
    private static List<ChatMessage> withProviderInstructions(
            @Nullable BaseChatModelSetup chatModel, List<ChatMessage> messages) throws Exception {
        if (chatModel == null) {
            return messages;
        }
        String instructions;
        try {
            instructions = BaseChatModelConnection.providerInstructions(chatModel.getParameters());
        } catch (Exception e) {
            rethrowUnlessProviderFailure(e);
            return messages;
        }
        if (instructions == null) {
            return messages;
        }
        List<ChatMessage> all = new ArrayList<>(messages.size() + 1);
        all.add(ChatMessage.system(instructions));
        all.addAll(messages);
        return all;
    }

    /**
     * The estimate of a request whose messages went from {@code shown} to {@code sent} tokens of
     * the approximation. {@code before} is the estimate of the request with the messages that take
     * {@code shown}: these, the chat model's prompt and its tools.
     *
     * <p>An estimate on the scale of the approximation is lowered by what the messages save. One
     * that counts fewer tokens than the approximation does for the messages alone is on another
     * scale, that of a chat model that counts tokens itself for example. It is lowered by the same
     * share as the messages, so that the difference cannot take more off than the estimate has.
     */
    static long estimateAfter(long before, long shown, long sent) {
        if (before < shown) {
            return before * sent / shown;
        }
        return before - (shown - sent);
    }

    private static long corrected(long tokens, boolean approximate, @Nullable Double calibration) {
        if (!approximate || calibration == null) {
            return tokens;
        }
        return (long) Math.ceil(tokens * calibration);
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
        // The round tells two results apart whose calls have the same id: a provider can
        // number the calls of each answer from the start.
        result.put("round", change.round + 1);
        result.put("call_id", change.callId);
        if (change.tool != null) {
            result.put("tool", change.tool);
        }
        return result;
    }

    // ── Summarization ──────────────────────────────────────────────────────

    /** A summary that could not be written; the request goes on without it. */
    static final class SummaryFailure extends Exception {
        /**
         * Whether the summarizer answered and the answer cannot be used for the rounds it was asked
         * about: it has no line for one of them, it is empty, or the provider did not finish it.
         * Such a failure is about those rounds and not about the summarizer as a whole.
         */
        final boolean unusableAnswer;

        SummaryFailure(String message) {
            this(message, false);
        }

        SummaryFailure(String message, boolean unusableAnswer) {
            super(message);
            this.unusableAnswer = unusableAnswer;
        }
    }

    /** What one summarization step did. */
    private static final class SummaryStep {
        final String summarizer;

        /** How many rounds had their line or were passed over before the step. */
        final int start;

        /** How many rounds have their line or were passed over after the step. */
        final int folded;

        /** All lines after the step, held ones included; null when there is none. */
        @Nullable final String summary;

        /** Why the step stopped before its target; null when it reached it. */
        @Nullable final String error;

        /** Whether it stopped because the answer for one round could not be used. */
        final boolean unusableAnswer;

        /** How many rounds got their line in the step; rounds that stay get none. */
        final int added;

        /** How many lines are held after the step, written in it or before. */
        final int heldLines;

        SummaryStep(
                String summarizer,
                int start,
                int folded,
                @Nullable String summary,
                @Nullable String error,
                boolean unusableAnswer,
                int added,
                int heldLines) {
            this.summarizer = summarizer;
            this.start = start;
            this.folded = folded;
            this.summary = summary;
            this.error = error;
            this.unusableAnswer = unusableAnswer;
            this.added = added;
            this.heldLines = heldLines;
        }
    }

    /**
     * Writes the lines of the rounds before the last kept ones: asks the summarizer for the line of
     * each round, in batches that fit the input budget. The new lines are held in {@code state};
     * the caller decides whether the model is sent them.
     *
     * @param asked what the agent was asked, as recorded with the estimate that asked for the step
     * @param calibration the factor that corrects the request's approximate estimates
     * @param masked how many of the oldest rounds are masked
     */
    private static SummaryStep summarize(
            UUID requestId,
            String model,
            ContextManagementPolicy policy,
            List<ChatMessage> messages,
            List<ContextCompaction.Round> rounds,
            RequestText asked,
            @Nullable Double calibration,
            int masked,
            Map<String, Object> state,
            RunnerContext ctx)
            throws Exception {
        int start = writtenRounds(state);
        int target = rounds.size() - policy.getSummarizationKeepLastRounds();
        String summarizerName = policy.getSummarizer() == null ? model : policy.getSummarizer();

        int folded = start;
        int added = 0;
        String sent = (String) state.get(SUMMARY);
        // The lines that are held: those an earlier step left, and those this step writes.
        String heldBefore = (String) state.get(PENDING_SUMMARY);
        String written = null;
        // All lines written so far: those the model is sent and those that are held.
        String notes = joined(sent, heldBefore);
        String error = null;
        boolean unusableAnswer = false;
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
            int budget = policy.getMaxInputTokens();
            int words = noteWordLimit(budget);
            // The most rounds one request shows. When the answer for several rounds cannot be
            // used, the rounds of that answer are asked about one at a time, which are the
            // rounds before singleUntil, and the requests after them show half as many rounds
            // as before.
            int most = MAX_BATCH_ROUNDS;
            int singleUntil = 0;
            while (folded < target) {
                // The notes take a line for each round. Only as many rounds are asked about
                // as the notes have room for, so every line that is written can be kept, and
                // without room no round is rendered at all.
                int room = roomForLines(notes, words, budget, calibration);
                if (getsNoLine(
                        messages,
                        rounds,
                        folded,
                        masked,
                        policy,
                        room == 0 && heldBefore != null)) {
                    // Passed over: it stays in the messages.
                    folded++;
                    state.put(WRITTEN_ROUNDS, folded);
                    continue;
                }
                if (room == 0 && heldBefore != null) {
                    // Lines that an earlier step left held were never sent, because the
                    // messages were no smaller with them, and here they take the room of
                    // lines that could be. They are given up: their rounds stay in the
                    // messages and are not asked about again. The lines of this step stay:
                    // the caller has yet to look at what they save.
                    heldBefore = null;
                    notes = joined(sent, written);
                    if (written == null) {
                        state.remove(PENDING_SUMMARY);
                    } else {
                        state.put(PENDING_SUMMARY, written);
                    }
                    room = roomForLines(notes, words, budget, calibration);
                }
                if (room == 0) {
                    throw full(notes, budget, calibration);
                }
                Batch batch =
                        nextBatch(
                                messages,
                                rounds,
                                folded,
                                target,
                                asked,
                                words,
                                Math.min(room, folded < singleUntil ? 1 : most),
                                calibration,
                                masked,
                                policy);
                String lines;
                try {
                    String answer =
                            callSummarizer(
                                    summarizer, unavailable, summarizerName, batch.request, ctx);
                    lines = roundNotes(answer, batch.rounds, words);
                } catch (SummaryFailure failure) {
                    if (!failure.unusableAnswer || batch.rounds.size() <= 1) {
                        throw failure;
                    }
                    // An answer for several rounds that leaves one out, or that the provider
                    // did not finish, for example at the summarizer's output limit. Nothing is
                    // kept of it, and its rounds are asked about again, one at a time. A
                    // summarizer that could not answer for so many rounds is then asked about
                    // fewer.
                    singleUntil = batch.end;
                    most = Math.max(1, most / 2);
                    continue;
                }
                written = joined(written, lines);
                notes = joined(notes, lines);
                added += batch.rounds.size();
                state.put(PENDING_SUMMARY, joined(heldBefore, written));
                folded = batch.end;
                state.put(WRITTEN_ROUNDS, folded);
            }
        } catch (SummaryFailure e) {
            error = e.getMessage();
            unusableAnswer = e.unusableAnswer;
            LOG.warn(
                    "Chat request {}: rounds {} to {} could not be summarized by '{}': {}. The"
                            + " request continues without the summary.",
                    requestId,
                    folded + 1,
                    target,
                    summarizerName,
                    error);
        }
        return new SummaryStep(
                summarizerName,
                start,
                folded,
                notes,
                error,
                unusableAnswer,
                added,
                lineCount(joined(heldBefore, written)));
    }

    /** How many lines some notes have. */
    private static int lineCount(@Nullable String notes) {
        if (notes == null) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < notes.length(); i++) {
            if (notes.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /**
     * The most tokens the notes of a request may take, with their heading: half of the input
     * budget, in tokens of the approximation with the correction of the request's estimates.
     */
    private static long notesTokens(int budget, @Nullable Double calibration) {
        return (long) (budget / (calibration == null ? 1.0 : calibration) * NOTES_BUDGET_SHARE);
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
    static int roomForLines(
            @Nullable String notes, int words, int budget, @Nullable Double calibration) {
        long left = notesTokens(budget, calibration) - summaryTokens(notes);
        return (int) Math.max(0, Math.min(MAX_BATCH_ROUNDS, left / lineTokens(words)));
    }

    /** What the notes take in the messages, with their heading. */
    private static long summaryTokens(@Nullable String notes) {
        return textTokens(ContextCompaction.summaryText(notes == null ? "" : notes));
    }

    /**
     * The notes with the lines of more rounds added. The lines of earlier rounds stay as they are:
     * nothing rewrites them.
     */
    @Nullable
    static String joined(@Nullable String notes, @Nullable String lines) {
        if (notes == null) {
            return lines;
        }
        return lines == null ? notes : notes + "\n" + lines;
    }

    static SummaryFailure full(@Nullable String notes, int budget, @Nullable Double calibration) {
        if (notes == null) {
            // Nothing was written yet: the budget is too small, not the notes too long.
            return new SummaryFailure(
                    "the summary has no room for a line: it may take "
                            + notesTokens(budget, calibration)
                            + " estimated tokens with "
                            + ContextManagementPolicy.MAX_INPUT_TOKENS
                            + "="
                            + budget
                            + ", and its heading takes "
                            + summaryTokens(null)
                            + " of them");
        }
        return new SummaryFailure(
                "the summary is full: it takes "
                        + summaryTokens(notes)
                        + " of the "
                        + notesTokens(budget, calibration)
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
     * <p>A line is the label of a round, a colon or a dash, and the text after it. The label can
     * follow a list mark, an ordinal or emphasis marks. When a label stands alone on its line, its
     * text is the list items that follow it, joined into one line. A plain line after such a label
     * is not taken: it can as well be a total or a conclusion, which must stay out of the notes.
     *
     * @throws SummaryFailure if the answer has no line for one of the rounds
     */
    static String roundNotes(String answer, List<Integer> roundNumbers, int words)
            throws SummaryFailure {
        String[] answerLines = LINE_BREAK.split(answer);
        Map<Integer, String> lines = new HashMap<>();
        int index = 0;
        while (index < answerLines.length) {
            Label label = label(answerLines[index]);
            index++;
            if (label == null) {
                continue;
            }
            String text = label.text;
            if (text.isEmpty()) {
                StringBuilder parts = new StringBuilder();
                while (index < answerLines.length) {
                    String line = answerLines[index];
                    if (trimmed(line).isEmpty()) {
                        index++;
                        continue;
                    }
                    if (label(line) != null) {
                        break;
                    }
                    Matcher mark = LIST_MARK.matcher(line);
                    if (!mark.lookingAt()) {
                        break;
                    }
                    String part = trimmed(line.substring(mark.end()));
                    if (!part.isEmpty()) {
                        if (parts.length() > 0) {
                            parts.append(ITEM_SEPARATOR);
                        }
                        parts.append(part);
                    }
                    index++;
                }
                text = parts.toString();
            }
            if (!text.isEmpty()) {
                lines.putIfAbsent(label.round, text);
            }
        }
        long maxTokens = (long) words * NOTE_TOKENS_PER_WORD;
        StringBuilder notes = new StringBuilder();
        for (int number : roundNumbers) {
            String text = lines.get(number);
            if (text == null) {
                throw new SummaryFailure("the summarizer wrote no line for round " + number, true);
            }
            if (notes.length() > 0) {
                notes.append('\n');
            }
            // The marker of a cut has line breaks of its own; the note stays one line.
            notes.append(
                    ContextCompaction.noteLine(
                            number, cutToTokens(text, maxTokens, 1).replace('\n', ' ')));
        }
        return notes.toString();
    }

    /** The label of a round at the start of a line, and the text after it on that line. */
    private static final class Label {
        final int round;
        final String text;

        Label(int round, String text) {
            this.round = round;
            this.text = text;
        }
    }

    /**
     * Reads a line as the label of a round: the label, then nothing, or a colon or a dash and the
     * text. Emphasis marks around the colon are passed over.
     *
     * @return the label, with an empty text when it stands alone, or null when the line is not one
     */
    @Nullable
    private static Label label(String line) {
        Matcher matcher = LABEL.matcher(line);
        if (!matcher.lookingAt()) {
            return null;
        }
        int at = skip(line, matcher.end());
        if (at < line.length()) {
            if (LABEL_SEPARATORS.indexOf(line.charAt(at)) < 0) {
                return null;
            }
            // Emphasis around the label closes right after the separator. An asterisk that
            // follows a blank belongs to the text.
            at++;
            while (at < line.length() && line.charAt(at) == '*') {
                at++;
            }
        }
        return new Label(Integer.parseInt(matcher.group(1)), trimmed(line.substring(at)));
    }

    /** The index of the first character from {@code from} on that is not blank or emphasis. */
    private static int skip(String line, int from) {
        int at = from;
        while (at < line.length() && (isBlank(line.charAt(at)) || line.charAt(at) == '*')) {
            at++;
        }
        return at;
    }

    /** A line without the white space around it. */
    private static String trimmed(String line) {
        int start = 0;
        int end = line.length();
        while (start < end && isBlank(line.charAt(start))) {
            start++;
        }
        while (end > start && isBlank(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(start, end);
    }

    /**
     * Whether a character is white space within a line: one of the Unicode property White_Space
     * that is not a line break. A line of such characters only has no text, so a label followed by
     * a no-break space stands alone like one followed by nothing.
     */
    static boolean isBlank(char c) {
        return c == ' '
                || c == '\t'
                || c == '\u00a0'
                || c == '\u1680'
                || (c >= '\u2000' && c <= '\u200a')
                || c == '\u202f'
                || c == '\u205f'
                || c == '\u3000';
    }

    /**
     * Reports a summarization step, whether it wrote lines or failed.
     *
     * @param shown how many rounds the summary that is sent covers after the step
     * @param heldLines how many lines are held after the step
     * @param tokensBefore the estimated input that asked for the step
     * @param tokensAfter the estimated input with the lines of the step: the estimate before, less
     *     what the lines save by the size of the messages; null when the model is not sent them
     */
    private static void reportSummaryStep(
            UUID requestId,
            String model,
            ContextManagementPolicy policy,
            SummaryStep step,
            int shown,
            int heldLines,
            @Nullable Integer passedOver,
            long tokensBefore,
            boolean approximate,
            @Nullable Long tokensAfter,
            RunnerContext ctx) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("summarizer", step.summarizer);
        details.put("rounds", step.added);
        details.put("summarized_rounds", shown);
        if (heldLines > 0) {
            // Lines that are held: the messages would not be smaller with them.
            details.put("pending_rounds", heldLines);
        }
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
        if (passedOver != null) {
            // The round the step stopped at gets no line from now on.
            details.put("passed_over_round", passedOver);
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
            rethrowUnlessProviderFailure(e);
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

        /** The numbers, from 1, of the rounds the request shows; rounds that stay are not. */
        final List<Integer> rounds;

        final List<ChatMessage> request;

        Batch(int end, List<Integer> rounds, List<ChatMessage> request) {
            this.end = end;
            this.rounds = rounds;
            this.request = request;
        }
    }

    /**
     * Takes as many rounds from {@code from} on as fit the input budget, at least one and at most
     * {@code maxRounds}. A round that does not fit a request of its own is cut until it does and is
     * a batch by itself, so the rounds after it are not cut with it. A round that {@linkplain
     * #getsNoLine gets no line} is not shown to the summarizer.
     *
     * <p>The request is sized by the approximation, with the correction of the request's own
     * estimates: where a provider counts more tokens than the approximation, it does so for the
     * summarizer's request too.
     *
     * @param from the index of the first round, which is one that gets a line
     * @param maxRounds the most rounds the request may show, at least one
     * @param calibration the factor that corrects the request's approximate estimates
     * @param masked how many of the oldest rounds are past the masking boundary
     * @throws SummaryFailure if the rest of the request leaves no room for a round
     */
    private static Batch nextBatch(
            List<ChatMessage> messages,
            List<ContextCompaction.Round> rounds,
            int from,
            int target,
            RequestText asked,
            int words,
            int maxRounds,
            @Nullable Double calibration,
            int masked,
            ContextManagementPolicy policy)
            throws SummaryFailure {
        int budget = policy.getMaxInputTokens();
        int resultChars =
                policy.getToolResultMaxChars() == null
                        ? Integer.MAX_VALUE
                        : policy.getToolResultMaxChars();
        // What the rounds may take: the budget, in tokens of the approximation, without the
        // rest of the request. Each round is counted once, so a long request is put together in
        // one pass.
        long room =
                (long) (budget / (calibration == null ? 1.0 : calibration))
                        - tokens(summarizerRequest(asked, "", words));
        if (room < MIN_SUMMARIZER_ROUND_TOKENS) {
            // The same for every round, so that no round is tried and none holds up the
            // rounds after it.
            throw noRoomForARound(budget);
        }
        // A text takes a token for four characters at least. A result that is cut to more
        // characters than that for the room does not fit, so none is put together longer: a
        // round is rendered and counted in the size of the room, not of its results.
        resultChars = (int) Math.min(resultChars, room * 4);
        StringBuilder rendered = new StringBuilder();
        List<Integer> shown = new ArrayList<>();
        long used = 0;
        int end = from;
        while (end < target && shown.size() < maxRounds) {
            ContextCompaction.Round round = rounds.get(end);
            // A round that gets no line is passed over: one that stays, or one past the
            // masking boundary of which little is left.
            if (getsNoLine(messages, rounds, end, masked, policy, false)) {
                end++;
                continue;
            }
            // The text of the results is read once for the round, however often the round is
            // cut to fit.
            ContextCompaction.RoundText roundText =
                    ContextCompaction.roundText(messages, round, end + 1);
            // A round of more characters than four for each token that is left cannot fit. It
            // is not put together to find that out, however many results it has.
            String text = null;
            long size = 0;
            if (fitsByLength(roundText, resultChars, room - used)) {
                text = roundText.render(resultChars);
                size = textTokens(text);
            }
            if (text == null || used + size > room) {
                if (shown.isEmpty()) {
                    rendered.append(fit(roundText, resultChars, room));
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
        return new Batch(end, shown, summarizerRequest(asked, rendered.toString(), words));
    }

    /**
     * One round as text that fits {@code room} tokens. Its results are cut further, to the longest
     * limit with which the round fits. When no limit does, long assistant text or many results for
     * example, the parts of the round are cut each by itself, see {@link #fitParts}.
     */
    static String fit(ContextCompaction.RoundText round, int resultChars, long room) {
        // What a result may have at most, whatever the round is cut to.
        int mostChars = resultChars;
        int commentary = round.hasAssistantText() ? 1 : -1;
        // Longer limits than the round can fit with by its length are not rendered.
        resultChars = longestByLength(round, resultChars, room);
        if (!fitsByLength(round, resultChars, room)) {
            // Too long with the shortest results too: long assistant text or long arguments of
            // a call for example. The round is not put together to find that out.
            return fitParts(round.wholeParts(mostChars), room, commentary);
        }
        String text = round.render(resultChars);
        // A text that is put together is counted once.
        long tokens = textTokens(text);
        if (tokens <= room) {
            return text;
        }
        if (resultChars > MIN_SUMMARIZER_RESULT_CHARS) {
            // When the round does not fit with the shortest results either, no limit helps: long
            // arguments of a call for example. The limits in between are then not tried.
            int shortest = MIN_SUMMARIZER_RESULT_CHARS;
            if (textTokens(round.render(shortest)) > room) {
                return fitParts(round.wholeParts(mostChars), room, commentary);
            }
        }
        // The smallest limit that was tried and did not fit; 0 when the first one fits.
        int tooLong = 0;
        while (tokens > room && resultChars > MIN_SUMMARIZER_RESULT_CHARS) {
            // No result is longer than the text, so a limit above its length cuts nothing.
            tooLong = Math.min(resultChars, ContextCompaction.length(text));
            resultChars = Math.max(MIN_SUMMARIZER_RESULT_CHARS, tooLong / 2);
            text = round.render(resultChars);
            tokens = textTokens(text);
        }
        if (tokens > room) {
            return fitParts(round.wholeParts(mostChars), room, commentary);
        }
        if (tooLong > 0) {
            // Halving stops at the first limit that fits. The longest limit that fits lies
            // between it and the limit before it, so that the summarizer is shown as much of
            // the results as the request has room for.
            int fits = resultChars;
            while (tooLong - fits > 1) {
                int limit = fits + (tooLong - fits) / 2;
                String longer = round.render(limit);
                if (textTokens(longer) <= room) {
                    fits = limit;
                    text = longer;
                } else {
                    tooLong = limit;
                }
            }
        }
        return text;
    }

    /**
     * The longest limit on the results with which the round can fit by its length, see {@link
     * #fitsByLength}. The limit is {@code resultChars} at most. It is not lower than the shortest
     * limit a round is rendered with, whether or not the round can fit with that one.
     */
    static int longestByLength(ContextCompaction.RoundText round, int resultChars, long room) {
        if (fitsByLength(round, resultChars, room)) {
            return resultChars;
        }
        int fits = Math.min(resultChars, MIN_SUMMARIZER_RESULT_CHARS);
        int tooLong = resultChars;
        while (tooLong - fits > 1) {
            int limit = fits + (tooLong - fits) / 2;
            if (fitsByLength(round, limit, room)) {
                fits = limit;
            } else {
                tooLong = limit;
            }
        }
        return fits;
    }

    /**
     * Whether a round can fit {@code room} tokens by its length. A text takes a token for four
     * characters at least, so a round of more characters than that for {@code room} tokens does not
     * fit, and it is not rendered to find that out.
     */
    private static boolean fitsByLength(
            ContextCompaction.RoundText round, int resultChars, long room) {
        return (round.chars(resultChars) + 3) / 4 <= room;
    }

    /**
     * The parts of a round as one text that fits {@code room} tokens. Each part is cut by itself,
     * see {@link #cutParts}, so what is left of a result stays with the call it answers. When the
     * parts do not fit that way, a round of very many results for example, parts are left out and a
     * closing line tells how many: first the assistant's text, which says less about the round than
     * a result does, then the last results. The least room of a request holds the label, one part
     * and that line.
     *
     * @param parts the parts of the round, the label first; a result has its whole text
     * @param commentary the index of the part with the assistant's text, -1 when the round has none
     */
    static String fitParts(List<ContextCompaction.Part> parts, long room, int commentary) {
        String text = cutParts(parts, room);
        if (text != null) {
            return text;
        }
        // The assistant's text is left out, and of the other parts the most from the first on
        // that fit beside the closing line.
        List<ContextCompaction.Part> kept = new ArrayList<>(parts.size());
        for (int index = 0; index < parts.size(); index++) {
            if (index != commentary) {
                kept.add(parts.get(index));
            }
        }
        int fits = 1;
        int tooMany = kept.size() + (commentary < 0 ? 0 : 1);
        while (tooMany - fits > 1) {
            int shown = fits + (tooMany - fits) / 2;
            if (shownParts(kept.subList(0, shown), parts.size(), room) == null) {
                tooMany = shown;
            } else {
                fits = shown;
            }
        }
        String shown = shownParts(kept.subList(0, fits), parts.size(), room);
        return shown == null ? parts.get(0).whole() : shown;
    }

    /**
     * The parts that are shown and a line that tells how many of {@code all} parts are left out, or
     * null when they do not fit {@code room} tokens.
     */
    @Nullable
    private static String shownParts(List<ContextCompaction.Part> shown, int all, long room) {
        String note = "[" + (all - shown.size()) + " more lines of this round are not shown]\n";
        String text = cutParts(shown, room - textTokens(note));
        return text == null ? null : text + note;
    }

    /**
     * Cuts each part by itself so that the parts take at most {@code room} tokens together. All
     * parts get the same limit, the largest one with which they fit. A part within the limit stays
     * whole, and one over it is cut: a line in its own middle, a result as {@link #cutResult} does.
     * The limit is not lower than {@link #MIN_SUMMARIZER_PART_TOKENS}, so a part that is cut still
     * tells what it is.
     *
     * @return the parts as one text, or null when they do not fit with the lowest limit
     */
    @Nullable
    static String cutParts(List<ContextCompaction.Part> parts, long room) {
        long[] sizes = new long[parts.size()];
        long largest = 0;
        for (int i = 0; i < sizes.length; i++) {
            // A part that is certainly over the room is not counted: it is cut whatever its
            // size is, and it stands here as one token over the room.
            sizes[i] = partTokens(parts.get(i), room);
            largest = Math.max(largest, sizes[i]);
        }
        long fits = MIN_SUMMARIZER_PART_TOKENS;
        String text = cutTo(parts, sizes, fits);
        if (textTokens(text) > room) {
            return null;
        }
        // No part can get more tokens than the room, so no limit above it is tried. A part that
        // stands as one token over the room is then cut for every limit.
        long tooLong = Math.max(Math.min(largest, room) + 1, fits + 1);
        while (tooLong - fits > 1) {
            long limit = fits + (tooLong - fits) / 2;
            String longer = cutTo(parts, sizes, limit);
            if (textTokens(longer) <= room) {
                fits = limit;
                text = longer;
            } else {
                tooLong = limit;
            }
        }
        return text;
    }

    /** The parts as one text, each within {@code limit} tokens: whole, or cut. */
    private static String cutTo(List<ContextCompaction.Part> parts, long[] sizes, long limit) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < sizes.length; i++) {
            ContextCompaction.Part part = parts.get(i);
            text.append(sizes[i] <= limit ? part.whole() : cutPart(part, limit));
        }
        return text.toString();
    }

    /**
     * The tokens of a text, without counting one that is far over {@code most}. A text takes a
     * token for four characters at least. One of more characters than that for {@code most} tokens
     * is over it, and {@code most + 1} stands for its size.
     */
    private static long tokensUpTo(String text, long most) {
        if ((ContextCompaction.length(text) + 3L) / 4 > most) {
            return most + 1;
        }
        return textTokens(text);
    }

    /**
     * The tokens of a whole part, as {@link #tokensUpTo} gives them for a text. A result that is
     * far over {@code most} is not put together to find that out.
     */
    private static long partTokens(ContextCompaction.Part part, long most) {
        if (part.text == null) {
            return tokensUpTo(part.line, most);
        }
        long chars =
                (long) ContextCompaction.length(part.line)
                        + ContextCompaction.length(part.outcome)
                        + part.shownChars()
                        + 1;
        if ((chars + 3) / 4 > most) {
            return most + 1;
        }
        return textTokens(part.whole());
    }

    /** A part that is over {@code limit} tokens, cut so that it takes at most that many. */
    private static String cutPart(ContextCompaction.Part part, long limit) {
        if (part.text != null) {
            return cutResult(part, limit);
        }
        // The marker of a cut fits the lowest limit, so the cut is never null.
        String cut = cutToTokens(part.line, limit, 1);
        return cut == null ? part.line : cut;
    }

    /**
     * The line of a result, cut so that it takes at most {@code limit} tokens. What became of the
     * call stays as it is: a summarizer must not take a failed call for one that returned. The call
     * is cut at its end, so its name and the start of its arguments stay, and the result is cut in
     * its middle, once and from the whole result, so that its marker counts all that is missing.
     * Each of the two may take half of the line; what one of them does not need, the other one may
     * take. The result is shown no longer than the limit on a tool result allows.
     */
    private static String cutResult(ContextCompaction.Part part, long limit) {
        long room = limit - textTokens(part.outcome + "\n");
        long half = room / 2;
        long callSize = tokensUpTo(part.line, room);
        // The result as the limit on a tool result leaves it.
        long textSize = room + 1;
        if ((part.shownChars() + 3L) / 4 <= room) {
            textSize = textTokens(part.shown());
        }
        String call;
        String text;
        if (callSize <= half) {
            call = part.line;
            text = cutToTokens(part.text, room - callSize, 1, part.maxChars);
        } else if (textSize <= half) {
            call = cutCall(part.line, room - textSize);
            text = part.shown();
        } else {
            call = cutCall(part.line, half);
            text = cutToTokens(part.text, room - textTokens(call), 1, part.maxChars);
        }
        if (text == null) {
            // Not even the marker fits: the marker alone tells what is missing.
            text = ContextCompaction.truncateMiddle(part.text, 0);
        }
        return call + part.outcome + text + "\n";
    }

    /** What ends a call that is cut. */
    private static final String CUT_CALL_END = "...)";

    /**
     * A call, cut at its end so that it takes at most {@code maxTokens}. The name of the tool and
     * the start of its arguments stay, as much as fits.
     */
    private static String cutCall(String call, long maxTokens) {
        int fits = 0;
        int tooLong = (int) Math.min(maxTokens * 4, ContextCompaction.length(call)) + 1;
        while (tooLong - fits > 1) {
            int chars = fits + (tooLong - fits) / 2;
            String cut = call.substring(0, call.offsetByCodePoints(0, chars)) + CUT_CALL_END;
            if (textTokens(cut) <= maxTokens) {
                fits = chars;
            } else {
                tooLong = chars;
            }
        }
        return call.substring(0, call.offsetByCodePoints(0, fits)) + CUT_CALL_END;
    }

    /** The failure of a summarizer request that has no room for a round, whatever the round. */
    private static SummaryFailure noRoomForARound(int budget) {
        return new SummaryFailure(
                "a summarizer request has no room for a round within "
                        + ContextManagementPolicy.MAX_INPUT_TOKENS
                        + "="
                        + budget);
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
        return cutToTokens(text, maxTokens, minChars, Integer.MAX_VALUE);
    }

    /**
     * Cuts {@code text} as {@link #cutToTokens(String, long, int)} does, to {@code maxChars}
     * characters at most.
     */
    @Nullable
    static String cutToTokens(String text, long maxTokens, int minChars, int maxChars) {
        // The length is counted once: each cut below is given it, and reads only the ends of
        // the text that it keeps.
        int length = ContextCompaction.length(text);
        // A text takes a token for four characters at least, so one that is longer than that is
        // over the limit, and it is not counted to find that out.
        if (length <= maxChars && (length + 3L) / 4 <= maxTokens && textTokens(text) <= maxTokens) {
            return text;
        }
        long most = Math.min(Math.min(maxTokens * 4, length - 1L), maxChars);
        if (most < minChars) {
            return null;
        }
        // Text that is all ASCII fits at four characters for a token.
        String longest = ContextCompaction.truncateMiddle(text, length, (int) most);
        if (textTokens(longest) <= maxTokens) {
            return longest;
        }
        if (textTokens(ContextCompaction.truncateMiddle(text, length, minChars)) > maxTokens) {
            return null;
        }
        int fits = minChars;
        int tooLong = (int) most;
        while (tooLong - fits > 1) {
            int chars = fits + (tooLong - fits) / 2;
            if (textTokens(ContextCompaction.truncateMiddle(text, length, chars)) <= maxTokens) {
                fits = chars;
            } else {
                tooLong = chars;
            }
        }
        return ContextCompaction.truncateMiddle(text, length, fits);
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
            List<ChatMessage> shown, ContextManagementPolicy policy, @Nullable Double calibration) {
        // Each part may take an eighth of the budget, so that both leave room for the notes and
        // the rounds whatever their script. The budget is the one a summarizer request is put
        // together within: in tokens of the approximation, with the correction of the
        // request's estimates.
        double budget = policy.getMaxInputTokens() / (calibration == null ? 1.0 : calibration);
        long maxTokens = Math.min(MAX_TASK_TOKENS, (long) (budget * TASK_BUDGET_SHARE));
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
                            // A list the summarizer may change, as a chat model may the
                            // one it is handed for a request.
                            outcome.response =
                                    summarizer.chatWithoutTools(new ArrayList<>(request));
                        } catch (Exception e) {
                            rethrowUnlessProviderFailure(e);
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
                    runsAsync(ctx, summarizer, false)
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
        if (!endedByItself(response.getFinishReason())) {
            throw new SummaryFailure(
                    "the summary is incomplete (finish_reason='"
                            + response.getFinishReason()
                            + "')",
                    true);
        }
        if (!response.getToolCalls().isEmpty()) {
            // A summarizer is called without tools. An answer that asks for one all the same is
            // not a summary of the rounds, whatever text comes with it.
            throw new SummaryFailure("the summary asks for a tool call", true);
        }
        String summary = response.getText();
        if (isBlank(summary)) {
            throw new SummaryFailure("the chat model returned no text", true);
        }
        return summary;
    }

    /**
     * Whether a finish reason says that the model ended its answer by itself: no finish reason, or
     * {@code stop} or {@code end_turn} in any case, which is what the connections report for a
     * complete answer, or {@code eos} or {@code eos_token}, which is what some endpoints that speak
     * the OpenAI protocol report for one. Any other reason is taken as an answer that was cut
     * short: a token limit under one of its many names, a context window, a content filter, a
     * pause, or {@code stop_sequence}, a sequence of the request at which the answer stopped. A
     * chat call takes such an answer as it is unless the reason is {@code length} or {@code
     * content_filter}. A summary must not: its last line can stop in the middle and would stand for
     * a round all the same.
     */
    private static boolean endedByItself(@Nullable String finishReason) {
        return finishReason == null
                || finishReason.isEmpty()
                || COMPLETE_REASONS.contains(finishReason.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether a summarizer's answer has no text: none of its lines has more than white space. That
     * is how its lines are read, so the answer is judged the same way in Java and Python.
     */
    private static boolean isBlank(String answer) {
        for (String line : LINE_BREAK.split(answer)) {
            if (!trimmed(line).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
