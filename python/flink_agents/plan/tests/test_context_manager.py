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

import logging
import re
import time
import uuid

import pytest

from flink_agents.api import context_management
from flink_agents.api.chat_message import MessageRole
from flink_agents.plan.context_management import compaction, manager


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
    # The marker of a cut is on the line: a note stays one line.
    assert cut == (
        "Round 1: " + "n" * 163 + " ...[1675 characters truncated]... " + "n" * 162
    )
    cjk = manager.round_notes("Round 1: " + "账" * 2000, [1], 30)
    assert cjk.count("账") == 81
    assert manager._text_tokens(cjk.removeprefix("Round 1: ")) == 90


def test_a_label_can_follow_an_ordinal_and_can_stand_above_its_list_items() -> None:
    answer = (
        "1. Round 1: account 1 holds 10\n"
        "2) **Round 2**: account 2 holds 20\n"
        "### Round 3\n"
        "- account 3 holds 30\n"
        "\n"
        "  * it is frozen  \n"
        "3. see the audit\n"
        "Total so far: 60\n"
        "- a list item that follows no label\n"
        "Round 4:\n"
        "Total so far: 60\n"
        "Round 5 holds 50\n"
        "ROUND 6\uff1a account 6 holds 60"
    )

    # The list items under a label that stands alone are its line. A plain line under
    # such a label is not: it can be a total. A label needs a colon or a dash before
    # text on its line.
    assert manager.round_notes(answer, [1, 2, 3, 6], 30) == (
        "Round 1: account 1 holds 10\n"
        "Round 2: account 2 holds 20\n"
        "Round 3: account 3 holds 30; it is frozen; see the audit\n"
        "Round 6: account 6 holds 60"
    )
    with pytest.raises(
        manager._SummaryFailure, match="the summarizer wrote no line for round 4"
    ):
        manager.round_notes(answer, [4], 30)
    with pytest.raises(
        manager._SummaryFailure, match="the summarizer wrote no line for round 5"
    ):
        manager.round_notes(answer, [5], 30)


def test_a_label_followed_by_white_space_only_stands_alone() -> None:
    answer = (
        "Round 1:\u00a0\n"
        "- account 1 holds 10\n"
        "- it is frozen\n"
        "Round 2: \u2003account 2 holds 20\u3000\n"
        "Round 3:\u00a0\u2003\n"
        "Round 4\u00a0: account 4 holds 40"
    )

    # A no-break space after the label is no text, so the list items under the label
    # are its line. White space around a text is not part of it, and neither is white
    # space between the label and its colon.
    assert manager.round_notes(answer, [1, 2, 4], 30) == (
        "Round 1: account 1 holds 10; it is frozen\n"
        "Round 2: account 2 holds 20\n"
        "Round 4: account 4 holds 40"
    )
    with pytest.raises(
        manager._SummaryFailure, match="the summarizer wrote no line for round 3"
    ):
        manager.round_notes(answer, [3], 30)


def test_an_estimate_is_lowered_on_its_own_scale_when_lines_are_used() -> None:
    # The messages go from 800 to 368 tokens of the approximation. An estimate on
    # that scale, with 200 tokens for the prompt and the tools, loses the difference.
    assert manager._estimate_after(1000, 800, 368) == 568
    assert manager._estimate_after(800, 800, 368) == 368
    # A chat model that counts fewer tokens than the approximation does for the
    # messages alone is on another scale. The difference of 432 would leave 61 of
    # its 493 tokens; the share of the messages that is left leaves 226.
    assert manager._estimate_after(493, 800, 368) == 226
    assert manager._estimate_after(0, 800, 368) == 0


def _approximate(text: str) -> int:
    return manager._text_tokens(text)


def test_the_parts_of_a_round_are_cut_each_by_itself() -> None:
    parts = [
        "Round 1:\n",
        "Assistant: looking both accounts up\n",
        'lookup({"account":1}) returned: ' + "a" * 400 + "\n",
        'lookup({"account":2}) returned: ' + "b" * 400 + "\n",
    ]

    # With room for all of it nothing is cut.
    assert manager._cut_parts(parts, 1000) == "".join(parts)
    # With less, the long parts get the same limit and the short ones stay whole.
    # Every result is left with the call it answers, at the start of its line.
    cut = manager._cut_parts(parts, 100)
    assert cut is not None
    assert _approximate(cut) <= 100
    lines = cut.split("\n")
    assert lines[:2] == ["Round 1:", "Assistant: looking both accounts up"]
    assert lines[2].startswith('lookup({"account":1}) returned: aaaa')
    assert lines[5].startswith('lookup({"account":2}) returned: bbbb')
    assert cut.count("characters truncated") == 2
    assert abs(cut.count("a") - cut.count("b")) <= 12
    # As much is kept as fits.
    assert _approximate(cut) >= 98
    # A part that is cut keeps 25 tokens at least, so that it still tells what it
    # is. With less room than that for each long part, the parts do not fit.
    assert manager._cut_parts(parts, 62) is not None
    assert manager._cut_parts(parts, 60) is None


def test_a_round_far_over_the_room_is_not_counted_for_each_limit_that_is_tried(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    counted = []
    text_tokens = manager._text_tokens

    def counting(text: str) -> int:
        counted.append(len(text))
        return text_tokens(text)

    monkeypatch.setattr(manager, "_text_tokens", counting)
    # A call whose arguments are far longer than a request has room for, and a short
    # result.
    long_call = 'write({"payload":"' + "\u8d26" * 250_000 + '"})'
    round_text = compaction.RoundText(
        label="Round 1:\n",
        assistant=None,
        results=(compaction.ResultPart(long_call, " returned: ", "OK"),),
        unanswered=(),
    )

    text = manager._fit(round_text, 8000, 800)

    # The call is cut at its end, and what became of it and its result stay.
    assert text.startswith('Round 1:\nwrite({"payload":"\u8d26')
    assert text.endswith("\u8d26...) returned: OK\n")
    assert text_tokens(text) == 800
    # No limit on the results helps, no part can get more tokens than the room, and a
    # part that is far over a limit is not counted to find that out. So the round is
    # not put together and no text of its length is counted, not even once: all the
    # texts that are counted to cut the call are together shorter than the call.
    assert max(counted) < 10_000
    assert sum(counted) < len(long_call)


def test_a_part_far_over_the_room_is_not_put_together_beside_a_part_of_other_characters(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    counted = []
    text_tokens = manager._text_tokens

    def counting(text: str) -> int:
        counted.append(len(text))
        return text_tokens(text)

    monkeypatch.setattr(manager, "_text_tokens", counting)
    # A call of a million characters, which is not counted, and a result short enough
    # to be counted that takes a token for each character: more tokens than the room.
    long_call = 'write({"payload":"' + "a" * 1_000_000 + '"}) returned: OK\n'
    other = "\u8d26" * 3600 + "\n"

    text = manager._cut_parts(["Round 1:\n", long_call, other], 1000)

    assert text is not None
    assert text_tokens(text) == 1000
    assert text.startswith('Round 1:\nwrite({"payload":"aaa')
    # No part can get more tokens than the room, so no limit above it is tried: the
    # call is cut for every limit, and is not put together once.
    assert max(counted) < 10_000


def test_a_round_with_long_assistant_text_is_not_put_together_to_be_cut(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    counted = []
    text_tokens = manager._text_tokens

    def counting(text: str) -> int:
        counted.append(len(text))
        return text_tokens(text)

    monkeypatch.setattr(manager, "_text_tokens", counting)
    # The assistant's text alone, 400,000 characters that take a token each, is far
    # over the room of a request.
    round_text = compaction.RoundText(
        label="Round 1:\n",
        assistant="Assistant: " + "\u8d26" * 400_000 + "\n",
        results=(
            compaction.ResultPart('lookup({"account":1})', " returned: ", "r" * 5000),
        ),
        unanswered=(),
    )

    whole = manager._fit(round_text, 64_000, 800)
    capped = manager._fit(round_text, 100, 800)

    # Each part is cut by itself. The result is cut from its whole text, to the limit
    # on a tool result when the policy has one.
    assert whole.startswith("Round 1:\nAssistant: \u8d26")
    assert text_tokens(whole) == 799
    assert whole.count("\u8d26") == 386
    assert "\n...[399614 characters truncated]...\n" in whole
    assert "\n...[3475 characters truncated]...\n" in whole
    assert capped.startswith("Round 1:\nAssistant: \u8d26")
    assert text_tokens(capped) == 800
    assert capped.count("\u8d26") == 752
    assert "\n...[399248 characters truncated]...\n" in capped
    assert "\n...[4935 characters truncated]...\n" in capped
    # The round is not put together to find out that it is too long: no text of its
    # length is counted, and all that is counted is less than the round has.
    assert max(counted) < 10_000
    assert sum(counted) < 400_000


def test_a_result_that_is_cut_keeps_what_became_of_its_call() -> None:
    # A failed call with long arguments: where the line is cut, a cut in the middle
    # of the line would take " failed: " with it.
    failed = compaction.ResultPart(
        'lookup({"query":"' + "q" * 700 + '"})', " failed: ", "e" * 200
    )

    text = manager._cut_parts(["Round 1:\n", failed], 50)

    assert text is not None
    assert _approximate(text) == 50
    lines = text.split("\n")
    assert lines[0] == "Round 1:"
    # The name of the tool and the start of its arguments, what became of the call,
    # and the start and the end of the result with what is missing between them.
    assert re.fullmatch(r'lookup\(\{"query":"q{67}\.\.\.\) failed: e+', lines[1])
    assert lines[2] == "...[142 characters truncated]..."
    assert re.fullmatch("e+", lines[3])
    # What the marker counts and what is left are the whole result.
    assert len(lines[1].split(" failed: ")[1]) + len(lines[3]) + 142 == 200
    # What one of the two does not need, the other one may take.
    short_result = compaction.ResultPart(failed.call, " returned: ", "OK")
    cut = manager._cut_parts(["Round 1:\n", short_result], 50)
    assert cut.endswith("q...) returned: OK\n")
    assert _approximate(cut) == 50
    short_call = compaction.ResultPart("lookup({})", " failed: ", "e" * 2000)
    cut = manager._cut_parts(["Round 1:\n", short_call], 50)
    assert cut.startswith("Round 1:\nlookup({}) failed: eee")
    assert "[1867 characters truncated]" in cut
    assert _approximate(cut) == 50


def test_a_result_that_is_cut_with_its_round_keeps_the_limit_on_a_tool_result() -> None:
    # The policy cuts a tool result to 100 characters, and the model is sent that.
    capped = compaction.ResultPart("lookup({})", " returned: ", "r" * 606, 100)
    long_reply = "Assistant: " + "a" * 40_000 + "\n"

    text = manager._cut_parts(["Round 1:\n", long_reply, capped], 1000)

    # The reply is cut. The result is within the limit of the parts, and it is shown
    # as the limit on a tool result leaves it, not whole.
    assert text is not None
    assert _approximate(text) == 1000
    assert text.endswith(
        "lookup({}) returned: " + compaction.truncate_middle("r" * 606, 100) + "\n"
    )
    assert "[540 characters truncated]" in text
    # With less room the result is cut further, and still from the whole result.
    assert manager._cut_parts(["Round 1:\n", capped], 30) == (
        "Round 1:\nlookup({}) returned: "
        + "r" * 27
        + "\n...[552 characters truncated]...\n"
        + "r" * 27
        + "\n"
    )
    # Without a limit on a tool result the whole result is shown where it fits.
    whole = compaction.ResultPart("lookup({})", " returned: ", "r" * 606)
    text = manager._cut_parts(["Round 1:\n", long_reply, whole], 1000)
    assert text is not None
    assert text.endswith("lookup({}) returned: " + "r" * 606 + "\n")
    # Beside a call that has to be cut, the result is shown as the limit leaves it.
    long_call = 'lookup({"query":"' + "q" * 700 + '"})'
    text = manager._cut_parts(
        ["Round 1:\n", compaction.ResultPart(long_call, " returned: ", "r" * 606, 100)],
        200,
    )
    assert text is not None
    assert _approximate(text) == 200
    assert text.endswith(
        "q...) returned: " + compaction.truncate_middle("r" * 606, 100) + "\n"
    )
    # A line that fits as the limit leaves it is not cut at all, however long the
    # whole result is.
    call = 'lookup({"query":"' + "q" * 220 + '"})'
    line = compaction.ResultPart(call, " returned: ", "r" * 1_000_000, 100)
    assert manager._cut_parts(["Round 1:\n", line], 95) == (
        "Round 1:\n"
        + call
        + " returned: "
        + compaction.truncate_middle("r" * 1_000_000, 100)
        + "\n"
    )
    # A text is cut to the characters it may have also when more tokens are left.
    assert manager._cut_to_tokens("r" * 606, 1000, 1, 100) == (
        compaction.truncate_middle("r" * 606, 100)
    )
    assert manager._cut_to_tokens("r" * 50, 1000, 1, 100) == "r" * 50


def test_a_result_keeps_its_own_limit_when_its_round_is_cut_part_by_part() -> None:
    # The arguments of the call take a token for each character: by its length the
    # round could fit, by its tokens it does not, with any limit on its result.
    round_text = compaction.RoundText(
        label="Round 1:\n",
        assistant=None,
        results=(
            compaction.ResultPart(
                'write({"payload":"' + "\u8d26" * 2000 + '"})',
                " returned: ",
                "z" * 606,
            ),
        ),
        unanswered=(),
    )

    text = manager._fit(round_text, 64_000, 800)

    # The call is cut. The result has room, and it is shown whole: not cut to the
    # shortest limit that results are tried with.
    assert _approximate(text) == 800
    assert text.count("z") == 606
    assert "\u8d26...) returned: zzz" in text
    # With a limit on a tool result, the result is shown as that limit leaves it.
    text = manager._fit(round_text, 100, 800)
    assert text.endswith(
        "...) returned: " + compaction.truncate_middle("z" * 606, 100) + "\n"
    )


def test_a_round_with_many_long_results_is_not_put_together_to_be_cut(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    counted = []
    text_tokens = manager._text_tokens

    def counting(text: str) -> int:
        counted.append(len(text))
        return text_tokens(text)

    monkeypatch.setattr(manager, "_text_tokens", counting)
    # Sixty-four results of 70,000 characters each, about 4.5 million characters.
    round_text = compaction.RoundText(
        label="Round 1:\n",
        assistant=None,
        results=tuple(
            compaction.ResultPart(
                f'lookup({{"account":{account}}})',
                " returned: ",
                f"amount={account}00 ".ljust(70_000, "x"),
            )
            for account in range(1, 65)
        ),
        unanswered=(),
    )

    text = manager._fit(round_text, 64_000, 15_000)

    # By its length the round can fit with 903 characters of each result at most.
    # It is rendered with that limit, and not with the longer ones before it.
    assert manager._longest_by_length(round_text, 64_000, 15_000) == 903
    assert text_tokens(text) == 14_992
    assert text.count("[69133 characters truncated]") == 64
    assert sum(counted) < 100_000


def test_the_marker_of_a_cut_result_counts_from_the_whole_result(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    counted = []
    text_tokens = manager._text_tokens

    def counting(text: str) -> int:
        counted.append(len(text))
        return text_tokens(text)

    monkeypatch.setattr(manager, "_text_tokens", counting)
    round_text = compaction.RoundText(
        label="Round 1:\n",
        assistant=None,
        results=(
            compaction.ResultPart(
                'lookup({"account":1})', " returned: ", "z" * 100_000
            ),
        ),
        unanswered=(),
    )

    # Less room than the round takes with the shortest results: its parts are cut.
    text = manager._fit(round_text, 8000, 50)

    assert text.startswith('Round 1:\nlookup({"account":1}) returned: zzz')
    assert text_tokens(text) == 50
    omitted = re.search(r"\[(\d+) characters truncated\]", text)
    assert omitted is not None
    # The result is cut once, from the whole result: what the marker counts and what
    # is left are all of it.
    assert int(omitted.group(1)) + text.count("z") == 100_000
    assert text.count("characters truncated") == 1
    # And the whole result is never counted to cut it.
    assert sum(counted) < 100_000


def test_parts_that_do_not_fit_in_their_shortest_form_are_left_out_and_counted() -> (
    None
):
    parts = ["Round 1:\n"] + [
        f'lookup({{"account":{account}}}) returned: ' + "r" * 300 + "\n"
        for account in range(1, 11)
    ]

    text = manager._fit_parts(parts, 60, commentary=None)

    assert _approximate(text) <= 60
    lines = text.split("\n")
    assert lines[0] == "Round 1:"
    assert lines[1].startswith('lookup({"account":1}) returned: ')
    shown = sum(1 for line in lines if line.startswith("lookup("))
    assert 0 < shown < 10
    assert lines[-2] == f"[{10 - shown} more lines of this round are not shown]"
    # With the least room a request has, the label, one result with its call and
    # the closing line still fit.
    least = manager._fit_parts(
        parts, manager._MIN_SUMMARIZER_ROUND_TOKENS, commentary=None
    )
    assert _approximate(least) <= manager._MIN_SUMMARIZER_ROUND_TOKENS
    assert least.startswith('Round 1:\nlookup({"account":1}) returned: r')
    assert least.endswith("\n[9 more lines of this round are not shown]\n")

    # The text the assistant wrote is left out before a result is: it says less
    # about what the round found. The parts that are shown keep their order.
    with_text = [parts[0], "Assistant: " + "t" * 400 + "\n", *parts[1:3]]
    text = manager._fit_parts(with_text, 70, commentary=1)
    assert text.split("\n")[1].startswith('lookup({"account":1}) returned: ')
    assert text.split("\n")[4].startswith('lookup({"account":2}) returned: ')
    assert "Assistant:" not in text
    assert text.endswith("\n[1 more lines of this round are not shown]\n")
    assert _approximate(text) <= 70
    # With room for it as well, it stands where it belongs, after the label.
    whole = manager._fit_parts(with_text, 100, commentary=1)
    assert whole.split("\n")[1].startswith("Assistant: tttt")
    assert "not shown" not in whole


def test_white_space_is_the_same_for_a_name_and_for_the_lines_of_an_answer() -> None:
    # A blank summarizer name and a blank line of an answer are judged by lists of
    # characters that are written out in two places. A line holds no line break, so
    # its list is the other one without the line breaks.
    for code in range(0x10000):
        character = chr(code)
        in_a_name = context_management._BLANK.fullmatch(character) is not None
        in_a_line = character in manager._BLANKS
        line_break = manager._LINE_BREAK.fullmatch(character) is not None
        assert in_a_name == (in_a_line or line_break), hex(code)


def test_a_dash_can_stand_between_a_label_and_its_text() -> None:
    answer = (
        "Round 1 - account 1 holds 10\n"
        "**Round 2** \u2013 account 2 holds 20\n"
        "- Round 3 \u2014 account 3 holds 30\n"
        "Round 4 holds 40"
    )

    assert manager.round_notes(answer, [1, 2, 3], 30) == (
        "Round 1: account 1 holds 10\n"
        "Round 2: account 2 holds 20\n"
        "Round 3: account 3 holds 30"
    )
    with pytest.raises(
        manager._SummaryFailure, match="the summarizer wrote no line for round 4"
    ):
        manager.round_notes(answer, [4], 30)


def test_a_failing_estimate_is_logged_once_for_a_chat_model(
    caplog: pytest.LogCaptureFixture,
) -> None:
    model = f"model-{uuid.uuid4()}"
    what = "returned no estimate of its input"

    with caplog.at_level(logging.WARNING):
        assert manager._warn_once(model, what) is True
        assert manager._warn_once(model, what) is False
        # Another chat model has a warning of its own.
        assert manager._warn_once(f"{model}-other", what) is True

    logged = [record.getMessage() for record in caplog.records if model in record.args]
    assert logged == [
        f"Chat model '{model}' {what}; the approximation is used for it. This is "
        "logged once."
    ]


def test_the_text_of_a_failure_is_had_also_when_the_failure_cannot_tell_it() -> None:
    class Unprintable(Exception):
        def __str__(self) -> str:
            msg = "no text"
            raise RuntimeError(msg)

    assert manager._error_text(ValueError("bad value")) == "ValueError: bad value"
    assert manager._error_text(Unprintable()) == "Unprintable: <message unavailable>"


def test_an_asterisk_at_the_start_of_a_text_is_part_of_the_text() -> None:
    answer = (
        "Round 1: *.parquet files were listed\n"
        "**Round 2:** account 2 holds 20\n"
        "Round 3:** account 3 holds 30\n"
        "Round 4 - **bold** start"
    )

    # Emphasis around a label ends right after the separator. What follows a blank is
    # the text as the summarizer wrote it.
    assert manager.round_notes(answer, [1, 2, 3, 4], 30) == (
        "Round 1: *.parquet files were listed\n"
        "Round 2: account 2 holds 20\n"
        "Round 3: account 3 holds 30\n"
        "Round 4: **bold** start"
    )


def test_a_long_line_is_read_in_time_that_grows_with_its_length() -> None:
    # Lines that make a pattern with nested repetition try every split: marks without
    # a label, a label without its colon, and text with a long run of spaces inside.
    length = 200_000
    answer = (
        "- " * length
        + "x\n"
        + "Round 1"
        + " " * length
        + "x\n"
        + "Round 2: a"
        + " " * length
        + "b"
        + " " * length
    )

    started = time.monotonic()
    notes = manager.round_notes(answer, [2], 150)

    assert time.monotonic() - started < 5
    assert notes.startswith("Round 2: a ")
    assert notes.endswith(" b")
    assert "characters truncated" in notes
    assert "\n" not in notes


def test_lines_are_added_to_the_notes_and_never_change_them() -> None:
    first = manager.joined(None, "Round 1: account 1 holds 10")
    assert first == "Round 1: account 1 holds 10"
    assert manager.joined(first, "Round 2: account 4 holds 40\nRound 3: x") == (
        "Round 1: account 1 holds 10\nRound 2: account 4 holds 40\nRound 3: x"
    )
    assert manager.joined(first, None) is first
    assert manager.joined(None, None) is None


def test_only_as_many_rounds_are_asked_about_as_the_notes_have_room_for() -> None:
    # Half of the budget is for the notes with their heading of 77 tokens, and a line
    # counts with the most it can take: 30 words are 90 tokens, and 5 for its label.
    assert manager._room_for_lines(None, 30, 1000, None) == 4
    assert manager._room_for_lines("n" * 400, 30, 1000, None) == 3
    assert manager._room_for_lines("n" * 1624, 30, 1000, None) == 0
    assert manager._room_for_lines("n" * 4000, 30, 1000, None) == 0
    # At most ten rounds are shown in one request.
    assert manager._room_for_lines(None, 30, 100_000, None) == 10
    # Where the provider counts twice what the approximation does, half of the budget
    # is 250 tokens of the approximation.
    assert manager._room_for_lines(None, 30, 1000, 2.0) == 1
    with pytest.raises(
        manager._SummaryFailure,
        match="the summary is full: it takes 483 of the 500 estimated tokens it may "
        "take with max_input_tokens=1000, which leaves no room for the line of "
        "another round",
    ):
        manager._require_room(0, "n" * 1624, 1000, None)
    manager._require_room(1, "n" * 1624, 1000, None)


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
