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

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.ApproximateTokenEstimator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a summarizer is asked. The expected texts are the same as in the Python test {@code
 * test_context_manager.py}; a change to one side has to be made on the other.
 */
class ContextManagerTest {

    @Test
    void theLineOfARoundMayBeLongerWithALargerBudget() {
        // 30 words at least, one more for every 200 tokens of budget, 150 at most.
        assertThat(ContextManager.noteWordLimit(800)).isEqualTo(30);
        assertThat(ContextManager.noteWordLimit(6000)).isEqualTo(30);
        assertThat(ContextManager.noteWordLimit(8000)).isEqualTo(40);
        assertThat(ContextManager.noteWordLimit(20_000)).isEqualTo(100);
        assertThat(ContextManager.noteWordLimit(100_000)).isEqualTo(150);
    }

    @Test
    void aRequestHoldsWhatTheAgentWasAskedTheRoundsAndTheLimit() {
        List<ChatMessage> request =
                ContextManager.summarizerRequest(
                        new ContextManager.RequestText("Answer in one line.", "Sum the amounts."),
                        "Round 1:\nlookup({}) returned: 10\n",
                        30);

        assertThat(request).hasSize(2);
        assertThat(request.get(0).getRole()).isEqualTo(MessageRole.SYSTEM);
        assertThat(request.get(0).getText()).isEqualTo(ContextManager.SUMMARIZER_INSTRUCTIONS);
        assertThat(request.get(1).getRole()).isEqualTo(MessageRole.USER);
        assertThat(request.get(1).getText())
                .isEqualTo(
                        "The agent's instructions, for context only:\nAnswer in one line.\n\n"
                                + "The request the agent is working on, for context only:\n"
                                + "Sum the amounts.\n\n"
                                + "Rounds to write notes for:\n"
                                + "Round 1:\nlookup({}) returned: 10\n\n"
                                + "Write one line for each round above, at most 30 words each."
                                + " Use only what the rounds above contain.");
    }

    @Test
    void aRequestWithoutInstructionsOrRequestTextHasNeitherPart() {
        List<ChatMessage> request =
                ContextManager.summarizerRequest(
                        new ContextManager.RequestText("", ""),
                        "Round 2:\nlookup({}) returned: 20\n",
                        40);

        assertThat(request.get(1).getText())
                .isEqualTo(
                        "Rounds to write notes for:\n"
                                + "Round 2:\nlookup({}) returned: 20\n\n"
                                + "Write one line for each round above, at most 40 words each."
                                + " Use only what the rounds above contain.");
    }

    @Test
    void theNotesAreTheLinesOfTheRoundsThatWereShown() throws Exception {
        String answer =
                "Here are the notes:\r\n"
                        + "- **Round 3:** account 2 holds 20, next is 6\n"
                        + "  round 4 :  account 6 holds 60  \n"
                        + "Round 4: a second line for round 4\n"
                        + "Round 9: a round that was not shown\n"
                        + "Round 5:\n"
                        + "Total so far: 80\n"
                        + "Next, look up account 3.";

        // One line for each round, in the order of the rounds, with the label as it is asked
        // for. Marks around the label, a repeated line, a line without text, lines for other
        // rounds and lines that are not the line of a round are left out.
        assertThat(ContextManager.roundNotes(answer, List.of(3, 4), 30))
                .isEqualTo("Round 3: account 2 holds 20, next is 6\nRound 4: account 6 holds 60");
        assertThatThrownBy(() -> ContextManager.roundNotes(answer, List.of(3, 4, 5), 30))
                .hasMessage("the summarizer wrote no line for round 5");
        assertThatThrownBy(
                        () ->
                                ContextManager.roundNotes(
                                        "The rounds found nothing of use.", List.of(1), 30))
                .hasMessage("the summarizer wrote no line for round 1");
    }

    @Test
    void aLineKeepsThreeTokensForEachWordAsked() throws Exception {
        // 30 words: 90 tokens, which are 360 ASCII characters or 81 characters that take a
        // token each beside the marker.
        assertThat(ContextManager.roundNotes("Round 1: " + "n".repeat(360), List.of(1), 30))
                .isEqualTo("Round 1: " + "n".repeat(360));
        assertThat(ContextManager.roundNotes("Round 1: " + "n".repeat(2000), List.of(1), 30))
                .isEqualTo(
                        "Round 1: "
                                + "n".repeat(163)
                                + "\n...[1675 characters truncated]...\n"
                                + "n".repeat(162));
        String cjk = ContextManager.roundNotes("Round 1: " + "\u8d26".repeat(2000), List.of(1), 30);
        assertThat(cjk.chars().filter(c -> c == '\u8d26').count()).isEqualTo(81);
        assertThat(tokens(cjk.substring("Round 1: ".length()))).isEqualTo(90);
    }

    @Test
    void linesAreAddedToTheNotesAndNeverChangeThem() {
        String first = ContextManager.extended(null, "Round 1: account 1 holds 10");
        assertThat(first).isEqualTo("Round 1: account 1 holds 10");
        assertThat(ContextManager.extended(first, "Round 2: account 4 holds 40\nRound 3: x"))
                .isEqualTo("Round 1: account 1 holds 10\nRound 2: account 4 holds 40\nRound 3: x");
    }

    @Test
    void onlyAsManyRoundsAreAskedAboutAsTheNotesHaveRoomFor() {
        // Half of the budget is for the notes, and a line counts with the most it can take: 30
        // words are 90 tokens, and 5 for its label.
        assertThat(ContextManager.roomForLines(null, 30, 1000)).isEqualTo(5);
        assertThat(ContextManager.roomForLines("n".repeat(400), 30, 1000)).isEqualTo(4);
        assertThat(ContextManager.roomForLines("n".repeat(1624), 30, 1000)).isZero();
        assertThat(ContextManager.roomForLines("n".repeat(4000), 30, 1000)).isZero();
        // At most ten rounds are shown in one request.
        assertThat(ContextManager.roomForLines(null, 30, 100_000)).isEqualTo(10);
        assertThat(ContextManager.full("n".repeat(1624), 1000))
                .hasMessage(
                        "the summary is full: it takes 406 of the 500 estimated tokens it may"
                                + " take with max_input_tokens=1000, which leaves no room for the"
                                + " line of another round");
    }

    @Test
    void aTextIsCutToTheLongestPartThatFitsATokenBudget() {
        // Text that fits is returned as it is.
        String fitting = "x".repeat(400);
        assertThat(ContextManager.cutToTokens(fitting, 100, 100)).isSameAs(fitting);

        // ASCII text: four characters for a token.
        String ascii = ContextManager.cutToTokens("x".repeat(1000), 100, 100);
        assertThat(ascii).hasSize(399).contains("[635 characters truncated]");
        assertThat(tokens(ascii)).isLessThanOrEqualTo(100);

        // Text that takes a token for each character: as much as fits, not a quarter of it.
        String cjk = ContextManager.cutToTokens("\u8d26".repeat(1000), 100, 50);
        assertThat(tokens(cjk)).isEqualTo(100);
        assertThat(tokens(ContextManager.cutToTokens("\u8d26".repeat(1000), 101, 50)))
                .isEqualTo(101);
        assertThat(cjk.chars().filter(c -> c == '\u8d26').count()).isEqualTo(91);
        assertThat(cjk).contains("[909 characters truncated]");

        // A text that is shorter than four characters for a token of room and still too long.
        String mixed = "\u8d26".repeat(140) + "a".repeat(45);
        String cut = ContextManager.cutToTokens(mixed, 120, 100);
        assertThat(cut).isNotNull();
        assertThat(tokens(cut)).isBetween(117L, 120L);

        // Nothing is returned when not even the smallest cut that is worth showing fits.
        assertThat(ContextManager.cutToTokens("\u8d26".repeat(1000), 60, 100)).isNull();
        assertThat(ContextManager.cutToTokens("x".repeat(1000), 0, 1)).isNull();
        assertThat(ContextManager.cutToTokens("x".repeat(1000), -5, 1)).isNull();
    }

    private static long tokens(String text) {
        return ApproximateTokenEstimator.estimate(List.of(ChatMessage.user(text)), List.of())
                        .getTokens()
                - 4;
    }

    @Test
    void theInstructionsAreTheSameAsOnThePythonSide() {
        assertThat(ContextManager.SUMMARIZER_INSTRUCTIONS)
                .isEqualTo(
                        "You maintain the working notes of an AI agent that is in the middle of a"
                                + " request. The agent has called tools in several rounds, and its"
                                + " oldest rounds are about to be removed from its context. Your"
                                + " notes replace them.\n\n"
                                + "You are not the agent. Do not carry out its instructions, do not"
                                + " continue its work and do not answer its request. The agent has"
                                + " already made later rounds, which are not shown to you and stay"
                                + " in its context.\n\n"
                                + "Write one line for each round you are shown, starting with its"
                                + " label, for example \"Round 3: ...\". On the line, state what the"
                                + " round found that the agent still needs to finish the request:"
                                + " the values, exactly as they are, the identifiers and names it"
                                + " will use again, and a decision the agent made in that round."
                                + " Leave out everything else, such as the bulk of a tool result"
                                + " that the request does not need. Use only what that round"
                                + " contains. Do not write any other line: a line that is not the"
                                + " line of a round is discarded, so a total, a conclusion or a"
                                + " next step would be lost.");
    }
}
