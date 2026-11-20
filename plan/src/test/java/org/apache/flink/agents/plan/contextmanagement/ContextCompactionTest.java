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

import org.apache.flink.agents.api.chat.contextmanagement.ContextManagementPolicy;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ContentBlock;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transformations that turn the stored messages of a chat request into what is sent to the
 * model: rounds, truncation, masking and the place of the summary.
 *
 * <p>The expected texts are the same as in the Python test {@code test_context_compaction.py}; a
 * change to one side has to be made on the other.
 */
class ContextCompactionTest {

    private static final ContextManagementPolicy OFF = ContextManagementPolicy.disabled();

    private static ChatMessage call(String callId, String tool, Map<String, Object> input) {
        return ChatMessage.assistant(List.of(new ToolCallBlock(callId, tool, input)));
    }

    private static ChatMessage result(String callId, String text) {
        return ChatMessage.tool(new ToolResultBlock(callId, List.of(new TextBlock(text)), false));
    }

    private static String repeat(String unit, int times) {
        return unit.repeat(times);
    }

    /** system, user, then {@code rounds} rounds of one call each with a 500 character result. */
    private static List<ChatMessage> conversation(int rounds) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("You are a careful analyst."));
        messages.add(ChatMessage.user("Sum the amounts of every account."));
        for (int i = 1; i <= rounds; i++) {
            messages.add(call("call-" + i, "lookup", Map.of("account", i)));
            messages.add(result("call-" + i, repeat(String.valueOf(i % 10), 500)));
        }
        return messages;
    }

    private static String text(ChatMessage message) {
        ContentBlock block = message.getBlocks().get(0);
        return block instanceof ToolResultBlock
                ? ((ToolResultBlock) block).getText()
                : message.getText();
    }

    // ── Rounds ─────────────────────────────────────────────────────────────

    @Test
    void aRoundIsAnAssistantMessageWithToolCallsAndTheToolMessagesAfterIt() {
        List<ChatMessage> messages =
                List.of(
                        ChatMessage.system("s"),
                        ChatMessage.user("u"),
                        call("a", "lookup", Map.of()),
                        result("a", "ra"),
                        ChatMessage.assistant(
                                List.of(
                                        new TextBlock("two at once"),
                                        new ToolCallBlock("b", "lookup", Map.of()),
                                        new ToolCallBlock("c", "lookup", Map.of()))),
                        result("b", "rb"),
                        result("c", "rc"),
                        ChatMessage.assistant("done"));

        List<ContextCompaction.Round> rounds = ContextCompaction.findRounds(messages);

        assertThat(rounds).hasSize(2);
        assertThat(rounds.get(0).start).isEqualTo(2);
        assertThat(rounds.get(0).end).isEqualTo(4);
        // Parallel calls of one reply are one round.
        assertThat(rounds.get(1).start).isEqualTo(4);
        assertThat(rounds.get(1).end).isEqualTo(7);
    }

    @Test
    void messagesOutsideRoundsAreNotRounds() {
        List<ChatMessage> messages =
                List.of(
                        ChatMessage.user("u"),
                        ChatMessage.assistant("plain answer"),
                        // A tool message with no call before it belongs to no round.
                        result("orphan", "r"),
                        ChatMessage.user("again"),
                        // A call whose results have not arrived is a round without results.
                        call("a", "lookup", Map.of()));

        List<ContextCompaction.Round> rounds = ContextCompaction.findRounds(messages);

        assertThat(rounds).hasSize(1);
        assertThat(rounds.get(0).start).isEqualTo(4);
        assertThat(rounds.get(0).end).isEqualTo(5);
    }

    // ── Which rounds are masked ────────────────────────────────────────────

    @Test
    void maskingKeepsTheLastRoundsAndAdvancesInSteps() {
        // Step 1: every round beyond the kept ones is masked.
        assertThat(ContextCompaction.maskedRounds(10, 3, 1)).isEqualTo(7);
        assertThat(ContextCompaction.maskedRounds(3, 3, 1)).isZero();
        assertThat(ContextCompaction.maskedRounds(2, 3, 1)).isZero();
        // Step 5: wait until five rounds are eligible, then mask them together.
        assertThat(ContextCompaction.maskedRounds(7, 3, 5)).isZero();
        assertThat(ContextCompaction.maskedRounds(8, 3, 5)).isEqualTo(5);
        assertThat(ContextCompaction.maskedRounds(12, 3, 5)).isEqualTo(5);
        assertThat(ContextCompaction.maskedRounds(13, 3, 5)).isEqualTo(10);
        // Not set: nothing is masked.
        assertThat(ContextCompaction.maskedRounds(10, null, 1)).isZero();
    }

    // ── A disabled policy ──────────────────────────────────────────────────

    @Test
    void aDisabledPolicySendsTheStoredMessagesUnchanged() {
        List<ChatMessage> messages = conversation(6);

        ContextCompaction.View view = ContextCompaction.view(messages, OFF, 0, null);

        assertThat(view.messages).isEqualTo(messages);
        assertThat(view.rounds).isEqualTo(6);
        assertThat(view.truncated).isEmpty();
        assertThat(view.masked).isEmpty();
    }

    // ── Truncation ─────────────────────────────────────────────────────────

    @Test
    void anOversizedResultIsCutInTheMiddleAndNeverExceedsTheLimit() {
        String original = repeat("a", 300) + repeat("b", 400) + repeat("c", 300);
        List<ChatMessage> messages =
                List.of(
                        ChatMessage.user("u"),
                        call("a", "lookup", Map.of()),
                        result("a", original));
        ContextManagementPolicy policy =
                ContextManagementPolicy.builder().toolResultMaxChars(200).build();

        ContextCompaction.View view = ContextCompaction.view(messages, policy, 0, null);

        String sent = text(view.messages.get(2));
        // 1000 characters, 200 allowed: 35 are reserved for the marker, 165 are kept (83 + 82)
        // and 835 are omitted.
        assertThat(sent)
                .isEqualTo(
                        repeat("a", 83) + "\n...[835 characters truncated]...\n" + repeat("c", 82));
        assertThat(sent.length()).isLessThanOrEqualTo(200);
        // The stored message is not touched, and the pairing is kept.
        assertThat(text(messages.get(2))).isEqualTo(original);
        ToolResultBlock block = (ToolResultBlock) view.messages.get(2).getBlocks().get(0);
        assertThat(block.getCallId()).isEqualTo("a");
        assertThat(view.messages.get(2).getRole()).isEqualTo(MessageRole.TOOL);
        assertThat(view.messages.get(0)).isSameAs(messages.get(0));
        assertThat(view.messages.get(1)).isSameAs(messages.get(1));

        assertThat(view.truncated).hasSize(1);
        ContextCompaction.ResultChange change = view.truncated.get(0);
        assertThat(change.round).isZero();
        assertThat(change.callId).isEqualTo("a");
        assertThat(change.tool).isEqualTo("lookup");
        assertThat(change.originalChars).isEqualTo(1000);
        assertThat(change.sentChars).isEqualTo(sent.length());
    }

    @Test
    void aResultAtTheLimitIsSentAsItIs() {
        List<ChatMessage> messages =
                List.of(
                        ChatMessage.user("u"),
                        call("a", "lookup", Map.of()),
                        result("a", repeat("x", 200)));
        ContextManagementPolicy policy =
                ContextManagementPolicy.builder().toolResultMaxChars(200).build();

        ContextCompaction.View view = ContextCompaction.view(messages, policy, 0, null);

        assertThat(view.messages.get(2)).isSameAs(messages.get(2));
        assertThat(view.truncated).isEmpty();
    }

    @Test
    void truncationCountsCharactersNotUtf16UnitsAndNeverSplitsOne() {
        // 300 emoji are 300 characters and 600 UTF-16 units.
        String emoji = "😀";
        List<ChatMessage> messages =
                List.of(
                        ChatMessage.user("u"),
                        call("a", "lookup", Map.of()),
                        result("a", repeat(emoji, 300)));
        ContextManagementPolicy policy =
                ContextManagementPolicy.builder().toolResultMaxChars(200).build();

        ContextCompaction.View view = ContextCompaction.view(messages, policy, 0, null);

        String sent = text(view.messages.get(2));
        // 300 characters: the marker takes 34, 166 are kept (83 + 83), 134 omitted.
        assertThat(sent)
                .isEqualTo(
                        repeat(emoji, 83)
                                + "\n...[134 characters truncated]...\n"
                                + repeat(emoji, 83));
        assertThat(view.truncated.get(0).originalChars).isEqualTo(300);
        assertThat(view.truncated.get(0).sentChars).isEqualTo(200);
    }

    @Test
    void truncationKeepsEachTextBlockInItsPlaceAroundMedia() {
        ImageBlock image = ImageBlock.fromUrl("image/png", "https://example.org/chart.png");
        ChatMessage toolMessage =
                ChatMessage.tool(
                        new ToolResultBlock(
                                "a",
                                List.of(
                                        new TextBlock(repeat("a", 400)),
                                        image,
                                        new TextBlock(repeat("b", 400))),
                                true));
        List<ChatMessage> messages =
                List.of(ChatMessage.user("u"), call("a", "chart", Map.of()), toolMessage);
        ContextManagementPolicy policy =
                ContextManagementPolicy.builder().toolResultMaxChars(100).build();

        ContextCompaction.View view = ContextCompaction.view(messages, policy, 0, null);

        ToolResultBlock block = (ToolResultBlock) view.messages.get(2).getBlocks().get(0);
        // The text is cut as one text of 800 characters, and what is kept of each block stays in
        // that block: the text after the image still follows it.
        assertThat(block.getBlocks()).hasSize(3);
        assertThat(((TextBlock) block.getBlocks().get(0)).getText())
                .isEqualTo(repeat("a", 33) + "\n...[734 characters truncated]...\n");
        assertThat(block.getBlocks().get(1)).isEqualTo(image);
        assertThat(((TextBlock) block.getBlocks().get(2)).getText()).isEqualTo(repeat("b", 33));
        assertThat(block.isError()).isTrue();
        assertThat(view.truncated.get(0).originalChars).isEqualTo(800);
        assertThat(view.truncated.get(0).sentChars).isEqualTo(100);
    }

    @Test
    void aTextBlockThatIsCutAwayWholeIsLeftOut() {
        ChatMessage toolMessage =
                ChatMessage.tool(
                        new ToolResultBlock(
                                "a",
                                List.of(
                                        new TextBlock(repeat("a", 400)),
                                        new TextBlock(repeat("m", 400)),
                                        new TextBlock(repeat("b", 400))),
                                false));
        List<ChatMessage> messages =
                List.of(ChatMessage.user("u"), call("a", "chart", Map.of()), toolMessage);
        ContextManagementPolicy policy =
                ContextManagementPolicy.builder().toolResultMaxChars(100).build();

        ContextCompaction.View view = ContextCompaction.view(messages, policy, 0, null);

        ToolResultBlock block = (ToolResultBlock) view.messages.get(2).getBlocks().get(0);
        // 1200 characters: the marker takes 35, 65 are kept (33 + 32); the middle block is gone.
        assertThat(block.getBlocks()).hasSize(2);
        assertThat(block.getText())
                .isEqualTo(
                        repeat("a", 33)
                                + "\n...[1135 characters truncated]...\n"
                                + repeat("b", 32));
    }

    // ── Masking ────────────────────────────────────────────────────────────

    @Test
    void theResultsOfOlderRoundsBecomePlaceholdersAndTheLastRoundsStay() {
        List<ChatMessage> messages = conversation(5);
        ContextManagementPolicy policy =
                ContextManagementPolicy.builder().toolResultKeepLastRounds(2).build();

        ContextCompaction.View view = ContextCompaction.view(messages, policy, 0, null);

        assertThat(view.messages).hasSameSizeAs(messages);
        assertThat(view.rounds).isEqualTo(5);
        // Rounds 1 to 3: the call stays, the result is a placeholder with the same call id.
        for (int round = 0; round < 3; round++) {
            int callIndex = 2 + 2 * round;
            assertThat(view.messages.get(callIndex)).isSameAs(messages.get(callIndex));
            ChatMessage masked = view.messages.get(callIndex + 1);
            assertThat(masked.getRole()).isEqualTo(MessageRole.TOOL);
            ToolResultBlock block = (ToolResultBlock) masked.getBlocks().get(0);
            assertThat(block.getCallId()).isEqualTo("call-" + (round + 1));
            assertThat(block.getText())
                    .isEqualTo(
                            "[Tool result omitted by context management: lookup({\"account\":"
                                    + (round + 1)
                                    + "}) returned 500 characters]");
        }
        // Rounds 4 and 5 are sent as they are.
        for (int index = 8; index < messages.size(); index++) {
            assertThat(view.messages.get(index)).isSameAs(messages.get(index));
        }
        // Nothing stored was changed.
        assertThat(text(messages.get(3))).isEqualTo(repeat("1", 500));

        assertThat(view.masked).hasSize(3);
        assertThat(view.masked.stream().map(change -> change.round).collect(Collectors.toList()))
                .containsExactly(0, 1, 2);
        assertThat(view.masked.get(0).callId).isEqualTo("call-1");
        assertThat(view.masked.get(0).tool).isEqualTo("lookup");
        assertThat(view.masked.get(0).originalChars).isEqualTo(500);
        assertThat(view.truncated).isEmpty();
    }

    @Test
    void maskingInStepsLeavesTheMessagesBeforeTheBoundaryUnchangedBetweenSteps() {
        ContextManagementPolicy policy =
                ContextManagementPolicy.builder()
                        .toolResultKeepLastRounds(2)
                        .toolResultMaskStepRounds(3)
                        .build();

        // Rounds 3 and 4: fewer than three rounds are eligible, nothing is masked.
        assertThat(ContextCompaction.view(conversation(4), policy, 0, null).masked).isEmpty();
        // Round 5: three rounds are eligible and are masked together.
        ContextCompaction.View five = ContextCompaction.view(conversation(5), policy, 0, null);
        assertThat(five.masked).hasSize(3);
        // Rounds 6 and 7: the masked prefix is the same as at round 5.
        ContextCompaction.View seven = ContextCompaction.view(conversation(7), policy, 0, null);
        assertThat(seven.masked).hasSize(3);
        assertThat(seven.messages.subList(0, 8)).isEqualTo(five.messages.subList(0, 8));
        // Round 8: the next three rounds are masked.
        assertThat(ContextCompaction.view(conversation(8), policy, 0, null).masked).hasSize(6);
    }

    @Test
    void aResultShorterThanItsPlaceholderIsNotMasked() {
        List<ChatMessage> messages =
                List.of(
                        ChatMessage.user("u"),
                        call("a", "lookup", Map.of("account", 1)),
                        result("a", "42"),
                        call("b", "lookup", Map.of("account", 2)),
                        result("b", "43"));
        ContextManagementPolicy policy =
                ContextManagementPolicy.builder().toolResultKeepLastRounds(1).build();

        ContextCompaction.View view = ContextCompaction.view(messages, policy, 0, null);

        assertThat(view.messages.get(2)).isSameAs(messages.get(2));
        assertThat(view.masked).isEmpty();
    }

    @Test
    void thePlaceholderCapsLongArgumentsAndCountsMediaBlocks() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", repeat("q", 400));
        ChatMessage toolMessage =
                ChatMessage.tool(
                        new ToolResultBlock(
                                "a",
                                List.of(
                                        new TextBlock(repeat("r", 2000)),
                                        ImageBlock.fromUrl("image/png", "https://example.org/1"),
                                        ImageBlock.fromUrl("image/png", "https://example.org/2")),
                                false));
        List<ChatMessage> messages =
                List.of(
                        ChatMessage.user("u"),
                        call("a", "search", input),
                        toolMessage,
                        call("b", "search", Map.of()),
                        result("b", "kept"));
        ContextManagementPolicy policy =
                ContextManagementPolicy.builder().toolResultKeepLastRounds(1).build();

        ContextCompaction.View view = ContextCompaction.view(messages, policy, 0, null);

        ToolResultBlock block = (ToolResultBlock) view.messages.get(2).getBlocks().get(0);
        // The arguments are cut at 200 characters; the media blocks are dropped and counted.
        assertThat(block.getBlocks()).hasSize(1);
        assertThat(block.getText())
                .isEqualTo(
                        "[Tool result omitted by context management: search("
                                + ("{\"query\":\"" + repeat("q", 400) + "\"}").substring(0, 200)
                                + "...) returned 2000 characters and 2 media blocks]");
    }

    @Test
    void aMaskedResultWithoutAMatchingCallStillReportsItsSize() {
        List<ChatMessage> messages =
                List.of(
                        ChatMessage.user("u"),
                        call("a", "lookup", Map.of()),
                        result("other-id", repeat("r", 300)),
                        call("b", "lookup", Map.of()),
                        result("b", "kept"));
        ContextManagementPolicy policy =
                ContextManagementPolicy.builder().toolResultKeepLastRounds(1).build();

        ContextCompaction.View view = ContextCompaction.view(messages, policy, 0, null);

        assertThat(text(view.messages.get(2)))
                .isEqualTo("[Tool result omitted by context management: 300 characters]");
        assertThat(view.masked.get(0).tool).isNull();
    }

    @Test
    void aPlaceholderThatDoesNotFitTheLimitLeavesOutTheCall() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", repeat("q", 180));
        List<ChatMessage> messages =
                List.of(
                        ChatMessage.user("u"),
                        call("a", "search", input),
                        result("a", repeat("r", 250)),
                        call("b", "search", Map.of()),
                        result("b", "kept"));
        ContextManagementPolicy policy =
                ContextManagementPolicy.builder()
                        .toolResultMaxChars(100)
                        .toolResultKeepLastRounds(1)
                        .build();

        ContextCompaction.View view = ContextCompaction.view(messages, policy, 0, null);

        // With its call the placeholder would have about 270 characters, more than the result
        // cut to the limit. The call is in the assistant message anyway.
        assertThat(text(view.messages.get(2)))
                .isEqualTo("[Tool result omitted by context management: 250 characters]");
        assertThat(view.masked).hasSize(1);
        assertThat(view.masked.get(0).sentChars).isEqualTo(59);
        assertThat(view.truncated).isEmpty();
    }

    @Test
    void aResultThatItsPlaceholderWouldNotShortenIsSentCutToTheLimit() {
        // The placeholder of this call has exactly 100 characters.
        String tool = repeat("t", 27);
        List<ChatMessage> messages =
                List.of(
                        ChatMessage.user("u"),
                        call("a", tool, Map.of()),
                        result("a", repeat("r", 150)),
                        call("b", "lookup", Map.of()),
                        result("b", "kept"));
        ContextManagementPolicy policy =
                ContextManagementPolicy.builder()
                        .toolResultMaxChars(100)
                        .toolResultKeepLastRounds(1)
                        .build();

        ContextCompaction.View view = ContextCompaction.view(messages, policy, 0, null);

        // Cut to the limit the result has 99 characters, one fewer than its placeholder: it is
        // sent cut, as it was before its round crossed the masking boundary.
        assertThat(text(view.messages.get(2)))
                .isEqualTo(
                        repeat("r", 33) + "\n...[84 characters truncated]...\n" + repeat("r", 33));
        assertThat(view.masked).isEmpty();
        assertThat(view.truncated).hasSize(1);
        assertThat(view.truncated.get(0).round).isZero();
    }

    // ── Skills ─────────────────────────────────────────────────────────────

    @Test
    void theResultOfLoadingASkillIsAlwaysSentAsItIs() {
        List<ChatMessage> messages =
                List.of(
                        ChatMessage.user("u"),
                        call("s", "load_skill", Map.of("name", "audit")),
                        result("s", repeat("i", 5000)),
                        call("a", "lookup", Map.of("account", 1)),
                        result("a", repeat("1", 500)),
                        call("b", "lookup", Map.of("account", 2)),
                        result("b", repeat("2", 500)));
        ContextManagementPolicy policy =
                ContextManagementPolicy.builder()
                        .toolResultMaxChars(100)
                        .toolResultKeepLastRounds(1)
                        .build();

        ContextCompaction.View view = ContextCompaction.view(messages, policy, 0, null);

        // The instructions of the skill are neither cut nor masked; the other results are.
        assertThat(view.messages.get(2)).isSameAs(messages.get(2));
        assertThat(text(view.messages.get(4))).startsWith("[Tool result omitted");
        assertThat(text(view.messages.get(6))).contains("characters truncated");
        assertThat(view.masked).extracting(change -> change.callId).containsExactly("a");
        assertThat(view.truncated).extracting(change -> change.callId).containsExactly("b");
    }

    @Test
    void theOtherResultsOfARoundThatLoadsASkillAreNotMasked() {
        List<ChatMessage> messages =
                List.of(
                        ChatMessage.user("u"),
                        // One reply loads a skill and looks up an account.
                        ChatMessage.assistant(
                                List.of(
                                        new ToolCallBlock(
                                                "s", "load_skill", Map.of("name", "audit")),
                                        new ToolCallBlock("a", "lookup", Map.of("account", 1)))),
                        result("s", repeat("i", 5000)),
                        result("a", repeat("1", 500)),
                        call("b", "lookup", Map.of("account", 2)),
                        result("b", repeat("2", 500)),
                        call("c", "lookup", Map.of("account", 3)),
                        result("c", repeat("3", 500)));
        ContextManagementPolicy policy =
                ContextManagementPolicy.builder()
                        .toolResultMaxChars(100)
                        .toolResultKeepLastRounds(1)
                        .build();

        ContextCompaction.View view = ContextCompaction.view(messages, policy, 0, null);

        // A summarizer is never shown a round that loads a skill, so a masked result of it
        // would be in neither the input nor a summary. It is cut like a result of a kept round;
        // the round after it is masked.
        assertThat(view.messages.get(2)).isSameAs(messages.get(2));
        assertThat(text(view.messages.get(3))).contains("characters truncated");
        assertThat(text(view.messages.get(5))).startsWith("[Tool result omitted");
        assertThat(view.masked).extracting(change -> change.callId).containsExactly("b");
        assertThat(view.truncated).extracting(change -> change.callId).containsExactly("a", "c");
    }

    @Test
    void aRoundThatLoadsASkillStaysWhenTheRoundsAroundItAreSummarized() {
        List<ChatMessage> messages =
                List.of(
                        ChatMessage.user("u"),
                        call("a", "lookup", Map.of()),
                        result("a", "ra"),
                        call("s", "load_skill", Map.of("name", "audit")),
                        result("s", "skill instructions"),
                        call("b", "lookup", Map.of()),
                        result("b", "rb"),
                        call("c", "lookup", Map.of()),
                        result("c", "rc"));

        ContextCompaction.View view = ContextCompaction.view(messages, OFF, 3, "notes");

        // Rounds 1 and 3 are left out, round 2 loads a skill and stays, and the summary takes the
        // place of the last round it stands for.
        assertThat(view.messages.stream().map(ChatMessage::getRole).collect(Collectors.toList()))
                .containsExactly(
                        MessageRole.USER,
                        MessageRole.ASSISTANT,
                        MessageRole.TOOL,
                        MessageRole.USER,
                        MessageRole.ASSISTANT,
                        MessageRole.TOOL);
        assertThat(view.messages.get(1)).isSameAs(messages.get(3));
        assertThat(view.messages.get(2)).isSameAs(messages.get(4));
        assertThat(view.messages.get(3).getText())
                .isEqualTo(
                        "[Summary of the earlier tool-call rounds of this request (rounds 1 to"
                                + " 3), which are no longer shown except those that load a"
                                + " skill]\nnotes");
        assertThat(view.messages.get(4)).isSameAs(messages.get(7));
    }

    @Test
    void truncationAppliesToTheRoundsThatAreNotMasked() {
        List<ChatMessage> messages = conversation(4);
        ContextManagementPolicy policy =
                ContextManagementPolicy.builder()
                        .toolResultMaxChars(100)
                        .toolResultKeepLastRounds(2)
                        .build();

        ContextCompaction.View view = ContextCompaction.view(messages, policy, 0, null);

        // Rounds 1 and 2 are masked and report the size of the original result.
        assertThat(text(view.messages.get(3))).contains("returned 500 characters]");
        assertThat(view.masked.stream().map(change -> change.round).collect(Collectors.toList()))
                .containsExactly(0, 1);
        // Rounds 3 and 4 are truncated.
        assertThat(text(view.messages.get(7))).hasSize(100).contains("characters truncated");
        assertThat(view.truncated.stream().map(change -> change.round).collect(Collectors.toList()))
                .containsExactly(2, 3);
    }

    // ── The summary ────────────────────────────────────────────────────────

    @Test
    void foldedRoundsAreReplacedByTheSummaryAfterTheMessageBeforeThem() {
        List<ChatMessage> messages = conversation(5);

        ContextCompaction.View view =
                ContextCompaction.view(messages, OFF, 3, "Accounts 1 to 3 hold 10, 20 and 30.");

        // system, user with the summary added as a second text block, then rounds 4 and 5.
        assertThat(view.messages).hasSize(6);
        assertThat(view.messages.get(0)).isSameAs(messages.get(0));
        ChatMessage user = view.messages.get(1);
        assertThat(user.getRole()).isEqualTo(MessageRole.USER);
        assertThat(user.getBlocks()).hasSize(2);
        assertThat(((TextBlock) user.getBlocks().get(0)).getText())
                .isEqualTo("Sum the amounts of every account.");
        // The summary starts after a blank line: an integration that sends the text blocks of a
        // message as one text does not run it into the user's own text.
        assertThat(((TextBlock) user.getBlocks().get(1)).getText())
                .isEqualTo(
                        "\n\n[Summary of the earlier tool-call rounds of this request (rounds 1"
                                + " to 3), which are no longer shown]\n"
                                + "Accounts 1 to 3 hold 10, 20 and 30.");
        assertThat(user.getText())
                .startsWith("Sum the amounts of every account.\n\n[Summary of the earlier");
        assertThat(view.messages.subList(2, 6)).isEqualTo(messages.subList(8, 12));
        // The stored user message is unchanged.
        assertThat(messages.get(1).getBlocks()).hasSize(1);
        assertThat(view.rounds).isEqualTo(5);
    }

    @Test
    void theSummaryIsItsOwnUserMessageWhenNoUserMessagePrecedesTheFirstRound() {
        List<ChatMessage> messages =
                List.of(
                        ChatMessage.system("s"),
                        call("a", "lookup", Map.of()),
                        result("a", "ra"),
                        call("b", "lookup", Map.of()),
                        result("b", "rb"));

        ContextCompaction.View view = ContextCompaction.view(messages, OFF, 1, "A returned ra.");

        assertThat(view.messages).hasSize(4);
        assertThat(view.messages.get(0)).isSameAs(messages.get(0));
        assertThat(view.messages.get(1).getRole()).isEqualTo(MessageRole.USER);
        assertThat(view.messages.get(1).getText())
                .isEqualTo(
                        "[Summary of the earlier tool-call rounds of this request (rounds 1 to 1),"
                                + " which are no longer shown]\n"
                                + "A returned ra.");
        assertThat(view.messages.subList(2, 4)).isEqualTo(messages.subList(3, 5));
    }

    @Test
    void maskingCountsFoldedRoundsSoItsBoundaryDoesNotMoveWhenRoundsAreFolded() {
        List<ChatMessage> messages = conversation(6);
        ContextManagementPolicy policy =
                ContextManagementPolicy.builder().toolResultKeepLastRounds(2).build();

        // Rounds 1 and 2 folded; rounds 3 and 4 masked; rounds 5 and 6 as they are.
        ContextCompaction.View view = ContextCompaction.view(messages, policy, 2, "summary");

        assertThat(view.masked.stream().map(change -> change.round).collect(Collectors.toList()))
                .containsExactly(2, 3);
        assertThat(view.messages).hasSize(2 + 8);
        assertThat(text(view.messages.get(3))).startsWith("[Tool result omitted");
        assertThat(view.messages.subList(6, 10)).isEqualTo(messages.subList(10, 14));
    }

    @Test
    void messagesBetweenFoldedRoundsStayInPlace() {
        List<ChatMessage> messages =
                List.of(
                        ChatMessage.user("first"),
                        call("a", "lookup", Map.of()),
                        result("a", "ra"),
                        ChatMessage.user("second"),
                        call("b", "lookup", Map.of()),
                        result("b", "rb"),
                        call("c", "lookup", Map.of()),
                        result("c", "rc"));

        ContextCompaction.View view = ContextCompaction.view(messages, OFF, 2, "summary");

        assertThat(view.messages.stream().map(ChatMessage::getRole).collect(Collectors.toList()))
                .containsExactly(
                        MessageRole.USER,
                        MessageRole.USER,
                        MessageRole.ASSISTANT,
                        MessageRole.TOOL);
        // The summary follows the message that came before the last round it stands for.
        assertThat(view.messages.get(0)).isSameAs(messages.get(0));
        assertThat(view.messages.get(1).getBlocks()).hasSize(2);
        assertThat(((TextBlock) view.messages.get(1).getBlocks().get(0)).getText())
                .isEqualTo("second");
    }

    @Test
    void theSummaryFollowsTheMessagesThatCameBeforeTheRoundsItStandsFor() {
        // An earlier turn with a tool round and its answer, then a new question with rounds.
        List<ChatMessage> messages =
                List.of(
                        ChatMessage.user("first question"),
                        call("a", "lookup", Map.of()),
                        result("a", "ra"),
                        ChatMessage.assistant("first answer"),
                        ChatMessage.user("second question"),
                        call("b", "lookup", Map.of()),
                        result("b", "rb"),
                        call("c", "lookup", Map.of()),
                        result("c", "rc"));

        ContextCompaction.View view = ContextCompaction.view(messages, OFF, 2, "notes");

        // What the notes say about the second question does not come before that question.
        assertThat(view.messages).hasSize(5);
        assertThat(view.messages.get(0)).isSameAs(messages.get(0));
        assertThat(view.messages.get(1)).isSameAs(messages.get(3));
        assertThat(view.messages.get(2).getText())
                .isEqualTo(
                        "second question"
                                + ContextCompaction.SUMMARY_SEPARATOR
                                + ContextCompaction.summaryText(2, "notes"));
        assertThat(view.messages.subList(3, 5)).isEqualTo(messages.subList(7, 9));
    }

    // ── What the summarizer reads ──────────────────────────────────────────

    @Test
    void aRoundIsRenderedFromItsStoredMessagesForTheSummarizer() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("b", 2);
        input.put("a", "x");
        List<ChatMessage> messages =
                List.of(
                        ChatMessage.user("u"),
                        ChatMessage.assistant(
                                List.of(
                                        new TextBlock("Checking two accounts."),
                                        new ToolCallBlock("c1", "lookup", input),
                                        new ToolCallBlock("c2", "chart", Map.of()))),
                        result("c1", repeat("r", 300)),
                        ChatMessage.tool(
                                new ToolResultBlock(
                                        "c2",
                                        List.of(
                                                new TextBlock("failed"),
                                                ImageBlock.fromUrl(
                                                        "image/png", "https://example.org/1")),
                                        true)));
        ContextCompaction.Round round = ContextCompaction.findRounds(messages).get(0);

        String rendered = ContextCompaction.renderRound(messages, round, 7, 120);

        assertThat(rendered)
                .isEqualTo(
                        "Round 7:\n"
                                + "Assistant: Checking two accounts.\n"
                                + "lookup({\"a\":\"x\",\"b\":2}) returned: "
                                + repeat("r", 43)
                                + "\n...[214 characters truncated]...\n"
                                + repeat("r", 43)
                                + "\n"
                                + "chart({}) failed: failed (and 1 media block not shown)\n");
    }

    @Test
    void aResultIsShownWithTheArgumentsOfItsOwnCall() {
        List<ChatMessage> messages =
                List.of(
                        ChatMessage.user("u"),
                        ChatMessage.assistant(
                                List.of(
                                        new ToolCallBlock("c1", "lookup", Map.of("account", 1)),
                                        new ToolCallBlock("c2", "lookup", Map.of("account", 2)),
                                        new ToolCallBlock("c3", "lookup", Map.of("account", 3)))),
                        // The results arrive in another order than the calls, and one is missing.
                        result("c2", "twenty"),
                        result("c1", "ten"),
                        result("other", "stray"));
        ContextCompaction.Round round = ContextCompaction.findRounds(messages).get(0);

        String rendered = ContextCompaction.renderRound(messages, round, 1, 100);

        // Two calls of one tool cannot be told apart by the tool's name.
        assertThat(rendered)
                .isEqualTo(
                        "Round 1:\n"
                                + "lookup({\"account\":2}) returned: twenty\n"
                                + "lookup({\"account\":1}) returned: ten\n"
                                + "A tool returned: stray\n"
                                + "lookup({\"account\":3}) has no result\n");
    }
}
