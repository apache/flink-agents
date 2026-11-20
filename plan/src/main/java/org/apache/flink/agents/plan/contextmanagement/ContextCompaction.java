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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.flink.agents.api.chat.contextmanagement.ContextManagementPolicy;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ContentBlock;
import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.apache.flink.agents.api.skills.Skills;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the stored messages of a chat request into the messages sent to the model under a {@link
 * ContextManagementPolicy}: truncated tool results, placeholders for the results of older rounds,
 * and a summary in place of the rounds it covers.
 *
 * <p>Nothing here changes its input or keeps state. A round is an assistant message with tool calls
 * and the tool messages that follow it; only messages of rounds are changed, every other message is
 * passed through. Sizes are counted in characters (code points), so a result is never cut inside
 * one and the Java and Python sides cut at the same place. The texts that are added are the same on
 * both sides. The arguments of a tool call are written as JSON by each side's own library. The two
 * differ for a floating-point number that one of them writes with an exponent ({@code 2.5E7} here,
 * {@code 25000000.0} in Python), for control characters, and in the order of keys outside the Basic
 * Multilingual Plane. With such arguments a placeholder can differ between the sides, and with it
 * the choice a limit makes between its long and its short form.
 *
 * <p>The result of the built-in {@code load_skill} tool is the instructions of a skill, which the
 * model has to keep following. Such a result is always sent as it is. A round that loads a skill
 * stays in the messages when the rounds around it are summarized, and because a summarizer is not
 * shown it, its other results are not masked either.
 */
public final class ContextCompaction {

    /** The most characters of a tool call's arguments shown in a placeholder or to a summarizer. */
    static final int MAX_ARGUMENT_CHARS = 200;

    /** What separates a summary from the text of the user message it is added to. */
    public static final String SUMMARY_SEPARATOR = "\n\n";

    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private ContextCompaction() {}

    /**
     * One tool-call round: the assistant message at {@code start}, its results up to {@code end}.
     */
    public static final class Round {
        /** Index of the assistant message that requests the tool calls. */
        public final int start;

        /** Index after the last tool message of the round. */
        public final int end;

        Round(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    /** What was done to one tool result. */
    public static final class ResultChange {
        /** Index of the result's round, counted from 0 over the whole request. */
        public final int round;

        public final String callId;

        /** The tool that produced the result, or null when no call of the round has its id. */
        @Nullable public final String tool;

        /** Characters of the stored result's text. */
        public final int originalChars;

        /** Characters of the text sent in its place. */
        public final int sentChars;

        ResultChange(
                int round, String callId, @Nullable String tool, int originalChars, int sentChars) {
            this.round = round;
            this.callId = callId;
            this.tool = tool;
            this.originalChars = originalChars;
            this.sentChars = sentChars;
        }
    }

    /** The messages to send for one model call, and what was changed to get them. */
    public static final class View {
        public final List<ChatMessage> messages;

        /** How many rounds the request has so far. */
        public final int rounds;

        /** The results that are sent truncated, in message order. */
        public final List<ResultChange> truncated;

        /** The results that are sent as placeholders, in message order. */
        public final List<ResultChange> masked;

        View(
                List<ChatMessage> messages,
                int rounds,
                List<ResultChange> truncated,
                List<ResultChange> masked) {
            this.messages = messages;
            this.rounds = rounds;
            this.truncated = truncated;
            this.masked = masked;
        }
    }

    /** The rounds of a message list, in order. */
    public static List<Round> findRounds(List<ChatMessage> messages) {
        List<Round> rounds = new ArrayList<>();
        int index = 0;
        while (index < messages.size()) {
            ChatMessage message = messages.get(index);
            if (message.getRole() == MessageRole.ASSISTANT && !message.getToolCalls().isEmpty()) {
                int end = index + 1;
                while (end < messages.size() && messages.get(end).getRole() == MessageRole.TOOL) {
                    end++;
                }
                rounds.add(new Round(index, end));
                index = end;
            } else {
                index++;
            }
        }
        return rounds;
    }

    /**
     * How many of the oldest rounds are masked when {@code keepLastRounds} rounds keep their
     * results and the boundary moves {@code stepRounds} rounds at a time. With a step above 1 the
     * boundary waits until that many rounds are eligible, so the messages before it are the same
     * for every model call between two steps.
     */
    public static int maskedRounds(int rounds, @Nullable Integer keepLastRounds, int stepRounds) {
        if (keepLastRounds == null) {
            return 0;
        }
        int eligible = Math.max(rounds - keepLastRounds, 0);
        return eligible / stepRounds * stepRounds;
    }

    /**
     * The messages to send for {@code messages} under {@code policy}.
     *
     * @param messages the stored messages of the request
     * @param foldedRounds how many of the oldest rounds {@code summary} covers; 0 without a summary
     * @param summary the summary that stands for the folded rounds, or null
     */
    public static View view(
            List<ChatMessage> messages,
            ContextManagementPolicy policy,
            int foldedRounds,
            @Nullable String summary) {
        return view(messages, findRounds(messages), policy, foldedRounds, summary);
    }

    /**
     * The messages to send for {@code messages} under {@code policy}, for a caller that already has
     * the {@linkplain #findRounds rounds} of the messages.
     */
    public static View view(
            List<ChatMessage> messages,
            List<Round> rounds,
            ContextManagementPolicy policy,
            int foldedRounds,
            @Nullable String summary) {
        int folded = summary == null ? 0 : Math.min(foldedRounds, rounds.size());
        int masked =
                maskedRounds(
                        rounds.size(),
                        policy.getToolResultKeepLastRounds(),
                        policy.getToolResultMaskStepRounds());
        Integer maxChars = policy.getToolResultMaxChars();

        List<ChatMessage> visible = new ArrayList<>(messages.size());
        List<ResultChange> truncatedResults = new ArrayList<>();
        List<ResultChange> maskedResults = new ArrayList<>();
        boolean skillRoundsShown = false;
        int roundIndex = 0;
        int index = 0;
        while (index < messages.size()) {
            Round round =
                    roundIndex < rounds.size() && rounds.get(roundIndex).start == index
                            ? rounds.get(roundIndex)
                            : null;
            if (round == null) {
                visible.add(messages.get(index));
                index++;
                continue;
            }
            ChatMessage assistant = messages.get(round.start);
            boolean skill = loadsSkill(assistant);
            // A summarized round is left out, unless it loads a skill.
            if (roundIndex >= folded || skill) {
                skillRoundsShown |= roundIndex < folded;
                visible.add(assistant);
                for (int i = round.start + 1; i < round.end; i++) {
                    visible.add(
                            manage(
                                    messages.get(i),
                                    assistant,
                                    roundIndex,
                                    // No summary will hold what a round that loads a skill
                                    // returned, so its results are not masked.
                                    roundIndex < masked && !skill,
                                    maxChars,
                                    truncatedResults,
                                    maskedResults));
                }
            }
            if (roundIndex == folded - 1) {
                placeSummary(visible, folded, summary, skillRoundsShown);
            }
            index = round.end;
            roundIndex++;
        }
        return new View(
                Collections.unmodifiableList(visible),
                rounds.size(),
                Collections.unmodifiableList(truncatedResults),
                Collections.unmodifiableList(maskedResults));
    }

    /** Whether a round loads a skill: one of its calls is to the built-in skill-loading tool. */
    public static boolean loadsSkill(ChatMessage assistant) {
        for (ToolCallBlock call : assistant.getToolCalls()) {
            if (Skills.LOAD_SKILL_TOOL.equals(call.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Puts the summary where the last folded round was, so that it follows every message that came
     * before the rounds it stands for. It joins a user message right before that place as a further
     * text block, so user and assistant turns keep alternating; without such a message it becomes a
     * user message of its own.
     */
    private static void placeSummary(
            List<ChatMessage> visible, int folded, String summary, boolean skillRoundsShown) {
        String text = summaryText(folded, summary, skillRoundsShown);
        int last = visible.size() - 1;
        if (last >= 0 && visible.get(last).getRole() == MessageRole.USER) {
            List<ContentBlock> blocks = new ArrayList<>(visible.get(last).getBlocks());
            // A chat model integration may send the text blocks of a message as one text, joined
            // as they are. The separator keeps the summary apart from the text before it.
            blocks.add(new TextBlock(SUMMARY_SEPARATOR + text));
            visible.set(last, visible.get(last).withBlocks(blocks));
        } else {
            visible.add(ChatMessage.user(List.of(new TextBlock(text))));
        }
    }

    /** The text that stands for the first {@code foldedRounds} rounds of a request. */
    public static String summaryText(int foldedRounds, String summary) {
        return summaryText(foldedRounds, summary, false);
    }

    /**
     * The text that stands for the first {@code foldedRounds} rounds of a request.
     *
     * @param skillRoundsShown whether some of these rounds load a skill and are therefore still
     *     shown
     */
    public static String summaryText(int foldedRounds, String summary, boolean skillRoundsShown) {
        return "[Summary of the earlier tool-call rounds of this request (rounds 1 to "
                + foldedRounds
                + "), which are no longer shown"
                + (skillRoundsShown ? " except those that load a skill" : "")
                + "]\n"
                + summary;
    }

    /**
     * One tool message as it is sent: each of its results as a placeholder when its round is past
     * the masking boundary, cut when it is longer than the limit, or as it is.
     */
    private static ChatMessage manage(
            ChatMessage toolMessage,
            ChatMessage assistant,
            int round,
            boolean pastMaskingBoundary,
            @Nullable Integer maxChars,
            List<ResultChange> truncatedResults,
            List<ResultChange> maskedResults) {
        List<ContentBlock> blocks = new ArrayList<>(toolMessage.getBlocks().size());
        boolean changed = false;
        for (ContentBlock block : toolMessage.getBlocks()) {
            if (!(block instanceof ToolResultBlock)) {
                blocks.add(block);
                continue;
            }
            ToolResultBlock result = (ToolResultBlock) block;
            ToolCallBlock call = callOf(assistant, result.getCallId());
            if (call != null && Skills.LOAD_SKILL_TOOL.equals(call.getName())) {
                // The instructions of a skill: the model has to keep them as they are.
                blocks.add(result);
                continue;
            }
            String tool = call == null ? null : call.getName();
            int originalChars = length(result.getText());
            // What is sent without masking: the result, cut when it is longer than the limit.
            int unmaskedChars =
                    maxChars == null || originalChars <= maxChars
                            ? originalChars
                            : length(truncateMiddle(result.getText(), maxChars));
            if (pastMaskingBoundary) {
                int media = mediaBlocks(result);
                String placeholder = placeholder(call, originalChars, media);
                if (maxChars != null && length(placeholder) > maxChars) {
                    // The call and its arguments do not fit the limit; the size alone does.
                    placeholder = placeholder(null, originalChars, media);
                }
                // A placeholder that is not shorter than the result would send more, not less.
                if (media > 0 || length(placeholder) < unmaskedChars) {
                    blocks.add(
                            new ToolResultBlock(
                                    result.getCallId(),
                                    List.of(new TextBlock(placeholder)),
                                    result.isError(),
                                    result.getMetadata()));
                    maskedResults.add(
                            new ResultChange(
                                    round,
                                    result.getCallId(),
                                    tool,
                                    originalChars,
                                    length(placeholder)));
                    changed = true;
                    continue;
                }
            }
            if (unmaskedChars == originalChars) {
                blocks.add(result);
                continue;
            }
            blocks.add(
                    new ToolResultBlock(
                            result.getCallId(),
                            truncate(result.getBlocks(), originalChars, maxChars),
                            result.isError(),
                            result.getMetadata()));
            truncatedResults.add(
                    new ResultChange(
                            round, result.getCallId(), tool, originalChars, unmaskedChars));
            changed = true;
        }
        return changed ? toolMessage.withBlocks(blocks) : toolMessage;
    }

    /**
     * Cuts the text of a result's blocks in the middle, as {@link #truncateMiddle} cuts one text.
     * Every text block keeps what is left of its own text, and media blocks stay where they are, so
     * text that follows a media block still follows it. The marker comes right after the kept
     * beginning.
     */
    private static List<DataContentBlock> truncate(
            List<DataContentBlock> blocks, int totalChars, int maxChars) {
        int keep = Math.max(maxChars - length(truncationMarker(totalChars)), 0);
        int head = (keep + 1) / 2;
        int tailStart = totalChars - (keep - head);
        String marker = truncationMarker(totalChars - keep);
        List<DataContentBlock> cut = new ArrayList<>(blocks.size());
        int start = 0;
        boolean markerPlaced = false;
        for (DataContentBlock block : blocks) {
            if (!(block instanceof TextBlock)) {
                cut.add(block);
                continue;
            }
            String text = ((TextBlock) block).getText();
            int chars = length(text);
            int end = start + chars;
            StringBuilder kept = new StringBuilder();
            if (head > start) {
                kept.append(text, 0, text.offsetByCodePoints(0, Math.min(head - start, chars)));
            }
            if (!markerPlaced && end >= head) {
                kept.append(marker);
                markerPlaced = true;
            }
            if (tailStart < end) {
                kept.append(
                        text.substring(text.offsetByCodePoints(0, Math.max(tailStart - start, 0))));
            }
            if (kept.length() > 0) {
                cut.add(new TextBlock(kept.toString()));
            }
            start = end;
        }
        return cut;
    }

    /**
     * One round as text for a summarizer, from its stored messages: the assistant's text, then each
     * result with the call it answers and the result's text cut to {@code maxResultChars}. A result
     * is shown with the arguments of its own call, so two calls of one tool cannot be mixed up.
     *
     * @param roundNumber the number of the round, counted from 1
     */
    public static String renderRound(
            List<ChatMessage> messages, Round round, int roundNumber, int maxResultChars) {
        ChatMessage assistant = messages.get(round.start);
        StringBuilder text = new StringBuilder("Round ").append(roundNumber).append(":\n");
        if (!assistant.getText().isEmpty()) {
            text.append("Assistant: ").append(assistant.getText()).append('\n');
        }
        Set<String> answered = new HashSet<>();
        for (int i = round.start + 1; i < round.end; i++) {
            for (ContentBlock block : messages.get(i).getBlocks()) {
                if (!(block instanceof ToolResultBlock)) {
                    continue;
                }
                ToolResultBlock result = (ToolResultBlock) block;
                ToolCallBlock call = callOf(assistant, result.getCallId());
                answered.add(result.getCallId());
                text.append(call == null ? "A tool" : describe(call))
                        .append(result.isError() ? " failed: " : " returned: ")
                        .append(truncateMiddle(result.getText(), maxResultChars));
                int media = mediaBlocks(result);
                if (media > 0) {
                    text.append(" (and ").append(mediaText(media)).append(" not shown)");
                }
                text.append('\n');
            }
        }
        for (ToolCallBlock call : assistant.getToolCalls()) {
            if (!answered.contains(call.getCallId())) {
                text.append(describe(call)).append(" has no result\n");
            }
        }
        return text.toString();
    }

    private static String describe(ToolCallBlock call) {
        return call.getName() + "(" + arguments(call.getInput()) + ")";
    }

    /**
     * Cuts {@code text} in the middle so that it has at most {@code maxChars} characters, the
     * marker that tells how much is missing included. A limit smaller than the marker leaves only
     * the marker.
     */
    static String truncateMiddle(String text, int maxChars) {
        int total = length(text);
        if (total <= maxChars) {
            return text;
        }
        // The marker names the omitted count, which has at most as many digits as the total.
        int keep = Math.max(maxChars - length(truncationMarker(total)), 0);
        int head = (keep + 1) / 2;
        int tail = keep - head;
        int headEnd = text.offsetByCodePoints(0, head);
        int tailStart = text.offsetByCodePoints(text.length(), -tail);
        return text.substring(0, headEnd)
                + truncationMarker(total - keep)
                + text.substring(tailStart);
    }

    private static String truncationMarker(int omittedChars) {
        return "\n...[" + omittedChars + " characters truncated]...\n";
    }

    private static String placeholder(@Nullable ToolCallBlock call, int originalChars, int media) {
        StringBuilder text = new StringBuilder("[Tool result omitted by context management: ");
        if (call != null) {
            text.append(describe(call)).append(" returned ");
        }
        text.append(originalChars).append(" characters");
        if (media > 0) {
            text.append(" and ").append(mediaText(media));
        }
        return text.append(']').toString();
    }

    private static String mediaText(int media) {
        return media + (media == 1 ? " media block" : " media blocks");
    }

    /** The arguments of a tool call as compact JSON with sorted keys, cut when long. */
    private static String arguments(Map<String, Object> input) {
        String json;
        try {
            json = MAPPER.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            json = String.valueOf(input);
        }
        if (length(json) <= MAX_ARGUMENT_CHARS) {
            return json;
        }
        return json.substring(0, json.offsetByCodePoints(0, MAX_ARGUMENT_CHARS)) + "...";
    }

    @Nullable
    private static ToolCallBlock callOf(ChatMessage assistant, String callId) {
        for (ToolCallBlock call : assistant.getToolCalls()) {
            if (call.getCallId().equals(callId)) {
                return call;
            }
        }
        return null;
    }

    private static int mediaBlocks(ToolResultBlock result) {
        int media = 0;
        for (DataContentBlock part : result.getBlocks()) {
            if (!(part instanceof TextBlock)) {
                media++;
            }
        }
        return media;
    }

    /** The number of characters (code points) of a text. */
    static int length(String text) {
        return text.codePointCount(0, text.length());
    }
}
