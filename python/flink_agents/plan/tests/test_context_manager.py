################################################################################
#  Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
# limitations under the License.
#################################################################################
"""Tests for what a summarizer is asked.

The expected texts are the same as in the Java test ``ContextManagerTest``; a change
to one side has to be made on the other.
"""

import pytest

from flink_agents.api.chat_message import MessageRole
from flink_agents.plan.context_management import manager


def test_the_line_of_a_round_may_be_longer_with_a_larger_budget() -> None:
    # 30 words at least, one more for every 200 tokens of budget, 150 at most.
    assert manager.note_word_limit(800) == 30
    assert manager.note_word_limit(6000) == 30
    assert manager.note_word_limit(8000) == 40
    assert manager.note_word_limit(20_000) == 100
    assert manager.note_word_limit(100_000) == 150


def test_a_request_holds_what_the_agent_was_asked_the_rounds_and_the_limit() -> None:
    request = manager.summarizer_request(
        manager.RequestText("Answer in one line.", "Sum the amounts."),
        "Round 1:\nlookup({}) returned: 10\n",
        30,
    )

    assert len(request) == 2
    assert request[0].role == MessageRole.SYSTEM
    assert request[0].text == manager.SUMMARIZER_INSTRUCTIONS
    assert request[1].role == MessageRole.USER
    assert request[1].text == (
        "The agent's instructions, for context only:\nAnswer in one line.\n\n"
        "The request the agent is working on, for context only:\nSum the amounts.\n\n"
        "Rounds to write notes for:\n"
        "Round 1:\nlookup({}) returned: 10\n\n"
        "Write one line for each round above, at most 30 words each. "
        "Use only what the rounds above contain."
    )


def test_a_request_without_instructions_or_request_text_has_neither_part() -> None:
    request = manager.summarizer_request(
        manager.RequestText("", ""), "Round 2:\nlookup({}) returned: 20\n", 40
    )

    assert request[1].text == (
        "Rounds to write notes for:\n"
        "Round 2:\nlookup({}) returned: 20\n\n"
        "Write one line for each round above, at most 40 words each. "
        "Use only what the rounds above contain."
    )


def test_the_notes_are_the_lines_of_the_rounds_that_were_shown() -> None:
    answer = (
        "Here are the notes:\r\n"
        "- **Round 3:** account 2 holds 20, next is 6\n"
        "  round 4 :  account 6 holds 60  \n"
        "Round 4: a second line for round 4\n"
        "Round 9: a round that was not shown\n"
        "Round 5:\n"
        "Total so far: 80\n"
        "Next, look up account 3."
    )

    # One line for each round, in the order of the rounds, with the label as it is
    # asked for. Marks around the label, a repeated line, a line without text, lines
    # for other rounds and lines that are not the line of a round are left out.
    assert manager.round_notes(answer, [3, 4], 30) == (
        "Round 3: account 2 holds 20, next is 6\nRound 4: account 6 holds 60"
    )
    with pytest.raises(
        manager._SummaryFailure, match="the summarizer wrote no line for round 5"
    ):
        manager.round_notes(answer, [3, 4, 5], 30)
    with pytest.raises(
        manager._SummaryFailure, match="the summarizer wrote no line for round 1"
    ):
        manager.round_notes("The rounds found nothing of use.", [1], 30)


def test_a_line_keeps_three_tokens_for_each_word_asked() -> None:
    # 30 words: 90 tokens, which are 360 ASCII characters or 81 characters that
    # take a token each beside the marker.
    assert manager.round_notes("Round 1: " + "n" * 360, [1], 30) == (
        "Round 1: " + "n" * 360
    )
    cut = manager.round_notes("Round 1: " + "n" * 2000, [1], 30)
    assert cut == (
        "Round 1: " + "n" * 163 + "\n...[1675 characters truncated]...\n" + "n" * 162
    )
    cjk = manager.round_notes("Round 1: " + "账" * 2000, [1], 30)
    assert cjk.count("账") == 81
    assert manager._text_tokens(cjk.removeprefix("Round 1: ")) == 90


def test_lines_are_added_to_the_notes_and_never_change_them() -> None:
    first = manager.extended(None, "Round 1: account 1 holds 10")
    assert first == "Round 1: account 1 holds 10"
    assert manager.extended(first, "Round 2: account 4 holds 40\nRound 3: x") == (
        "Round 1: account 1 holds 10\nRound 2: account 4 holds 40\nRound 3: x"
    )


def test_only_as_many_rounds_are_asked_about_as_the_notes_have_room_for() -> None:
    # Half of the budget is for the notes, and a line counts with the most it can
    # take: 30 words are 90 tokens, and 5 for its label.
    assert manager._room_for_lines(None, 30, 1000) == 5
    assert manager._room_for_lines("n" * 400, 30, 1000) == 4
    assert manager._room_for_lines("n" * 1624, 30, 1000) == 0
    assert manager._room_for_lines("n" * 4000, 30, 1000) == 0
    # At most ten rounds are shown in one request.
    assert manager._room_for_lines(None, 30, 100_000) == 10
    with pytest.raises(
        manager._SummaryFailure,
        match="the summary is full: it takes 406 of the 500 estimated tokens it may "
        "take with max_input_tokens=1000, which leaves no room for the line of "
        "another round",
    ):
        manager._require_room(0, "n" * 1624, 1000)
    manager._require_room(1, "n" * 1624, 1000)


def test_a_text_is_cut_to_the_longest_part_that_fits_a_token_budget() -> None:
    # Text that fits is returned as it is.
    fitting = "x" * 400
    assert manager._cut_to_tokens(fitting, 100, 100) is fitting

    # ASCII text: four characters for a token.
    ascii_cut = manager._cut_to_tokens("x" * 1000, 100, 100)
    assert len(ascii_cut) == 399
    assert "[635 characters truncated]" in ascii_cut
    assert manager._text_tokens(ascii_cut) <= 100

    # Text that takes a token for each character: as much as fits, not a quarter of
    # it.
    cjk = manager._cut_to_tokens("账" * 1000, 100, 50)
    assert manager._text_tokens(cjk) == 100
    assert manager._text_tokens(manager._cut_to_tokens("账" * 1000, 101, 50)) == 101
    assert cjk.count("账") == 91
    assert "[909 characters truncated]" in cjk

    # A text that is shorter than four characters for a token of room and still too
    # long.
    cut = manager._cut_to_tokens("账" * 140 + "a" * 45, 120, 100)
    assert len(cut) == 178
    assert manager._text_tokens(cut) == 120

    # Nothing is returned when not even the smallest cut that is worth showing fits.
    assert manager._cut_to_tokens("账" * 1000, 60, 100) is None
    assert manager._cut_to_tokens("x" * 1000, 0, 1) is None
    assert manager._cut_to_tokens("x" * 1000, -5, 1) is None


def test_the_instructions_are_the_same_as_on_the_java_side() -> None:
    assert manager.SUMMARIZER_INSTRUCTIONS == (
        "You maintain the working notes of an AI agent that is in the middle of a"
        " request. The agent has called tools in several rounds, and its oldest rounds"
        " are about to be removed from its context. Your notes replace them.\n\n"
        "You are not the agent. Do not carry out its instructions, do not continue its"
        " work and do not answer its request. The agent has already made later rounds,"
        " which are not shown to you and stay in its context.\n\n"
        "Write one line for each round you are shown, starting with its label, for"
        ' example "Round 3: ...". On the line, state what the round found that the'
        " agent still needs to finish the request: the values, exactly as they are,"
        " the identifiers and names it will use again, and a decision the agent made"
        " in that round. Leave out everything else, such as the bulk of a tool result"
        " that the request does not need. Use only what that round contains. Do not"
        " write any other line: a line that is not the line of a round is discarded,"
        " so a total, a conclusion or a next step would be lost."
    )
