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
"""Tests for the pure part of context management.

They cover which messages a policy sends to the model: rounds, truncation, masking and
the place of the summary.

The expected texts are the same as in the Java test ``ContextCompactionTest``; a
change to one side has to be made on the other.
"""

from typing import Any, Dict, List

from flink_agents.api.chat_message import (
    ChatMessage,
    ImageBlock,
    MessageRole,
    TextBlock,
    ToolCallBlock,
    ToolResultBlock,
)
from flink_agents.api.context_management import ContextManagementPolicy
from flink_agents.plan.context_management import compaction

OFF = ContextManagementPolicy.disabled()


def call(call_id: str, tool: str, call_input: Dict[str, Any]) -> ChatMessage:
    return ChatMessage.assistant(
        [ToolCallBlock(call_id=call_id, name=tool, input=call_input)]
    )


def result(call_id: str, text: str) -> ChatMessage:
    return ChatMessage.tool(
        ToolResultBlock(call_id=call_id, blocks=[TextBlock(text=text)])
    )


def conversation(rounds: int) -> List[ChatMessage]:
    """system, user, then ``rounds`` rounds of one call with a 500 character result."""
    messages = [
        ChatMessage.system("You are a careful analyst."),
        ChatMessage.user("Sum the amounts of every account."),
    ]
    for i in range(1, rounds + 1):
        messages.append(call(f"call-{i}", "lookup", {"account": i}))
        messages.append(result(f"call-{i}", str(i % 10) * 500))
    return messages


def text(message: ChatMessage) -> str:
    block = message.blocks[0]
    return block.text if isinstance(block, ToolResultBlock) else message.text


# ── Rounds ──────────────────────────────────────────────────────────────


def test_a_round_is_an_assistant_message_with_tool_calls_and_its_results() -> None:
    messages = [
        ChatMessage.system("s"),
        ChatMessage.user("u"),
        call("a", "lookup", {}),
        result("a", "ra"),
        ChatMessage.assistant(
            [
                TextBlock(text="two at once"),
                ToolCallBlock(call_id="b", name="lookup", input={}),
                ToolCallBlock(call_id="c", name="lookup", input={}),
            ]
        ),
        result("b", "rb"),
        result("c", "rc"),
        ChatMessage.assistant("done"),
    ]

    rounds = compaction.find_rounds(messages)

    # Parallel calls of one reply are one round.
    assert [(r.start, r.end) for r in rounds] == [(2, 4), (4, 7)]


def test_messages_outside_rounds_are_not_rounds() -> None:
    messages = [
        ChatMessage.user("u"),
        ChatMessage.assistant("plain answer"),
        # A tool message with no call before it belongs to no round.
        result("orphan", "r"),
        ChatMessage.user("again"),
        # A call whose results have not arrived is a round without results.
        call("a", "lookup", {}),
    ]

    rounds = compaction.find_rounds(messages)

    assert [(r.start, r.end) for r in rounds] == [(4, 5)]


# ── Which rounds are masked ─────────────────────────────────────────────


def test_masking_keeps_the_last_rounds_and_advances_in_steps() -> None:
    # Step 1: every round beyond the kept ones is masked.
    assert compaction.masked_rounds(10, 3, 1) == 7
    assert compaction.masked_rounds(3, 3, 1) == 0
    assert compaction.masked_rounds(2, 3, 1) == 0
    # Step 5: wait until five rounds are eligible, then mask them together.
    assert compaction.masked_rounds(7, 3, 5) == 0
    assert compaction.masked_rounds(8, 3, 5) == 5
    assert compaction.masked_rounds(12, 3, 5) == 5
    assert compaction.masked_rounds(13, 3, 5) == 10
    # Not set: nothing is masked.
    assert compaction.masked_rounds(10, None, 1) == 0


# ── A disabled policy ───────────────────────────────────────────────────


def test_a_disabled_policy_sends_the_stored_messages_unchanged() -> None:
    messages = conversation(6)

    view = compaction.view(messages, OFF, 0, None)

    assert view.messages == messages
    assert all(
        sent is stored for sent, stored in zip(view.messages, messages, strict=True)
    )
    assert view.rounds == 6
    assert view.truncated == []
    assert view.masked == []


# ── Truncation ──────────────────────────────────────────────────────────


def test_an_oversized_result_is_cut_in_the_middle_and_never_exceeds_the_limit() -> None:
    original = "a" * 300 + "b" * 400 + "c" * 300
    messages = [ChatMessage.user("u"), call("a", "lookup", {}), result("a", original)]
    policy = ContextManagementPolicy(tool_result_max_chars=200)

    view = compaction.view(messages, policy, 0, None)

    sent = text(view.messages[2])
    # 1000 characters, 200 allowed: 35 are reserved for the marker, 165 are kept
    # (83 + 82) and 835 are omitted.
    assert sent == "a" * 83 + "\n...[835 characters truncated]...\n" + "c" * 82
    assert len(sent) <= 200
    # The stored message is not touched, and the pairing is kept.
    assert text(messages[2]) == original
    assert view.messages[2].blocks[0].call_id == "a"
    assert view.messages[2].role == MessageRole.TOOL
    assert view.messages[0] is messages[0]
    assert view.messages[1] is messages[1]

    assert len(view.truncated) == 1
    change = view.truncated[0]
    assert change.round == 0
    assert change.call_id == "a"
    assert change.tool == "lookup"
    assert change.original_chars == 1000
    assert change.sent_chars == len(sent)


def test_a_result_at_the_limit_is_sent_as_it_is() -> None:
    messages = [ChatMessage.user("u"), call("a", "lookup", {}), result("a", "x" * 200)]
    policy = ContextManagementPolicy(tool_result_max_chars=200)

    view = compaction.view(messages, policy, 0, None)

    assert view.messages[2] is messages[2]
    assert view.truncated == []


def test_truncation_counts_characters_and_never_splits_one() -> None:
    # 300 emoji are 300 characters, and 600 UTF-16 units on the Java side.
    emoji = "\U0001f600"
    messages = [
        ChatMessage.user("u"),
        call("a", "lookup", {}),
        result("a", emoji * 300),
    ]
    policy = ContextManagementPolicy(tool_result_max_chars=200)

    view = compaction.view(messages, policy, 0, None)

    sent = text(view.messages[2])
    # 300 characters: the marker takes 34, 166 are kept (83 + 83), 134 omitted.
    assert sent == emoji * 83 + "\n...[134 characters truncated]...\n" + emoji * 83
    assert view.truncated[0].original_chars == 300
    assert view.truncated[0].sent_chars == 200


def test_truncation_keeps_each_text_block_in_its_place_around_media() -> None:
    image = ImageBlock.from_url("image/png", "https://example.org/chart.png")
    tool_message = ChatMessage.tool(
        ToolResultBlock(
            call_id="a",
            blocks=[TextBlock(text="a" * 400), image, TextBlock(text="b" * 400)],
            is_error=True,
        )
    )
    messages = [ChatMessage.user("u"), call("a", "chart", {}), tool_message]
    policy = ContextManagementPolicy(tool_result_max_chars=100)

    view = compaction.view(messages, policy, 0, None)

    block = view.messages[2].blocks[0]
    # The text is cut as one text of 800 characters, and what is kept of each block
    # stays in that block: the text after the image still follows it.
    assert len(block.blocks) == 3
    assert block.blocks[0].text == "a" * 33 + "\n...[734 characters truncated]...\n"
    assert block.blocks[1] == image
    assert block.blocks[2].text == "b" * 33
    assert block.is_error
    assert view.truncated[0].original_chars == 800
    assert view.truncated[0].sent_chars == 100


def test_a_text_block_that_is_cut_away_whole_is_left_out() -> None:
    tool_message = ChatMessage.tool(
        ToolResultBlock(
            call_id="a",
            blocks=[
                TextBlock(text="a" * 400),
                TextBlock(text="m" * 400),
                TextBlock(text="b" * 400),
            ],
        )
    )
    messages = [ChatMessage.user("u"), call("a", "chart", {}), tool_message]
    policy = ContextManagementPolicy(tool_result_max_chars=100)

    view = compaction.view(messages, policy, 0, None)

    block = view.messages[2].blocks[0]
    # 1200 characters: the marker takes 35, 65 are kept (33 + 32); the middle block
    # is gone.
    assert len(block.blocks) == 2
    assert block.text == "a" * 33 + "\n...[1135 characters truncated]...\n" + "b" * 32


# ── Masking ─────────────────────────────────────────────────────────────


def test_the_results_of_older_rounds_become_placeholders() -> None:
    messages = conversation(5)
    policy = ContextManagementPolicy(tool_result_keep_last_rounds=2)

    view = compaction.view(messages, policy, 0, None)

    assert len(view.messages) == len(messages)
    assert view.rounds == 5
    # Rounds 1 to 3: the call stays, the result is a placeholder with the same call
    # id.
    for round_index in range(3):
        call_index = 2 + 2 * round_index
        assert view.messages[call_index] is messages[call_index]
        masked = view.messages[call_index + 1]
        assert masked.role == MessageRole.TOOL
        block = masked.blocks[0]
        assert block.call_id == f"call-{round_index + 1}"
        assert block.text == (
            '[Tool result omitted by context management: lookup({"account":'
            f"{round_index + 1}}}) returned 500 characters]"
        )
    # Rounds 4 and 5 are sent as they are.
    for index in range(8, len(messages)):
        assert view.messages[index] is messages[index]
    # Nothing stored was changed.
    assert text(messages[3]) == "1" * 500

    assert [change.round for change in view.masked] == [0, 1, 2]
    assert view.masked[0].call_id == "call-1"
    assert view.masked[0].tool == "lookup"
    assert view.masked[0].original_chars == 500
    assert view.truncated == []


def test_masking_in_steps_leaves_the_messages_before_the_boundary_unchanged() -> None:
    policy = ContextManagementPolicy(
        tool_result_keep_last_rounds=2, tool_result_mask_step_rounds=3
    )

    # Rounds 3 and 4: fewer than three rounds are eligible, nothing is masked.
    assert compaction.view(conversation(4), policy, 0, None).masked == []
    # Round 5: three rounds are eligible and are masked together.
    five = compaction.view(conversation(5), policy, 0, None)
    assert len(five.masked) == 3
    # Rounds 6 and 7: the masked prefix is the same as at round 5.
    seven = compaction.view(conversation(7), policy, 0, None)
    assert len(seven.masked) == 3
    assert seven.messages[:8] == five.messages[:8]
    # Round 8: the next three rounds are masked.
    assert len(compaction.view(conversation(8), policy, 0, None).masked) == 6


def test_a_result_shorter_than_its_placeholder_is_not_masked() -> None:
    messages = [
        ChatMessage.user("u"),
        call("a", "lookup", {"account": 1}),
        result("a", "42"),
        call("b", "lookup", {"account": 2}),
        result("b", "43"),
    ]
    policy = ContextManagementPolicy(tool_result_keep_last_rounds=1)

    view = compaction.view(messages, policy, 0, None)

    assert view.messages[2] is messages[2]
    assert view.masked == []


def test_the_placeholder_caps_long_arguments_and_counts_media_blocks() -> None:
    tool_message = ChatMessage.tool(
        ToolResultBlock(
            call_id="a",
            blocks=[
                TextBlock(text="r" * 2000),
                ImageBlock.from_url("image/png", "https://example.org/1"),
                ImageBlock.from_url("image/png", "https://example.org/2"),
            ],
        )
    )
    messages = [
        ChatMessage.user("u"),
        call("a", "search", {"query": "q" * 400}),
        tool_message,
        call("b", "search", {}),
        result("b", "kept"),
    ]
    policy = ContextManagementPolicy(tool_result_keep_last_rounds=1)

    view = compaction.view(messages, policy, 0, None)

    block = view.messages[2].blocks[0]
    # The arguments are cut at 200 characters; the media blocks are dropped and
    # counted.
    assert len(block.blocks) == 1
    assert block.text == (
        "[Tool result omitted by context management: search("
        + ('{"query":"' + "q" * 400 + '"}')[:200]
        + "...) returned 2000 characters and 2 media blocks]"
    )


def test_a_masked_result_without_a_matching_call_still_reports_its_size() -> None:
    messages = [
        ChatMessage.user("u"),
        call("a", "lookup", {}),
        result("other-id", "r" * 300),
        call("b", "lookup", {}),
        result("b", "kept"),
    ]
    policy = ContextManagementPolicy(tool_result_keep_last_rounds=1)

    view = compaction.view(messages, policy, 0, None)

    assert (
        text(view.messages[2])
        == "[Tool result omitted by context management: 300 characters]"
    )
    assert view.masked[0].tool is None


def test_a_placeholder_that_does_not_fit_the_limit_leaves_out_the_call() -> None:
    messages = [
        ChatMessage.user("u"),
        call("a", "search", {"query": "q" * 180}),
        result("a", "r" * 250),
        call("b", "search", {}),
        result("b", "kept"),
    ]
    policy = ContextManagementPolicy(
        tool_result_max_chars=100, tool_result_keep_last_rounds=1
    )

    view = compaction.view(messages, policy, 0, None)

    # With its call the placeholder would have about 270 characters, more than the
    # result cut to the limit. The call is in the assistant message anyway.
    assert (
        text(view.messages[2])
        == "[Tool result omitted by context management: 250 characters]"
    )
    assert len(view.masked) == 1
    assert view.masked[0].sent_chars == 59
    assert view.truncated == []


def test_a_result_that_its_placeholder_would_not_shorten_is_sent_cut() -> None:
    # The placeholder of this call has exactly 100 characters.
    tool = "t" * 27
    messages = [
        ChatMessage.user("u"),
        call("a", tool, {}),
        result("a", "r" * 150),
        call("b", "lookup", {}),
        result("b", "kept"),
    ]
    policy = ContextManagementPolicy(
        tool_result_max_chars=100, tool_result_keep_last_rounds=1
    )

    view = compaction.view(messages, policy, 0, None)

    # Cut to the limit the result has 99 characters, one fewer than its placeholder:
    # it is sent cut, as it was before its round crossed the masking boundary.
    assert (
        text(view.messages[2])
        == "r" * 33 + "\n...[84 characters truncated]...\n" + "r" * 33
    )
    assert view.masked == []
    assert len(view.truncated) == 1
    assert view.truncated[0].round == 0


# ── Skills ──────────────────────────────────────────────────────────────


def test_the_result_of_loading_a_skill_is_always_sent_as_it_is() -> None:
    messages = [
        ChatMessage.user("u"),
        call("s", "load_skill", {"name": "audit"}),
        result("s", "i" * 5000),
        call("a", "lookup", {"account": 1}),
        result("a", "1" * 500),
        call("b", "lookup", {"account": 2}),
        result("b", "2" * 500),
    ]
    policy = ContextManagementPolicy(
        tool_result_max_chars=100, tool_result_keep_last_rounds=1
    )

    view = compaction.view(messages, policy, 0, None)

    # The instructions of the skill are neither cut nor masked; the other results
    # are.
    assert view.messages[2] is messages[2]
    assert text(view.messages[4]).startswith("[Tool result omitted")
    assert "characters truncated" in text(view.messages[6])
    assert [change.call_id for change in view.masked] == ["a"]
    assert [change.call_id for change in view.truncated] == ["b"]


def test_the_other_results_of_a_round_that_loads_a_skill_are_not_masked() -> None:
    messages = [
        ChatMessage.user("u"),
        # One reply loads a skill and looks up an account.
        ChatMessage.assistant(
            [
                ToolCallBlock(call_id="s", name="load_skill", input={"name": "audit"}),
                ToolCallBlock(call_id="a", name="lookup", input={"account": 1}),
            ]
        ),
        result("s", "i" * 5000),
        result("a", "1" * 500),
        call("b", "lookup", {"account": 2}),
        result("b", "2" * 500),
        call("c", "lookup", {"account": 3}),
        result("c", "3" * 500),
    ]
    policy = ContextManagementPolicy(
        tool_result_max_chars=100, tool_result_keep_last_rounds=1
    )

    view = compaction.view(messages, policy, 0, None)

    # A summarizer is never shown a round that loads a skill, so a masked result of
    # it would be in neither the input nor a summary. It is cut like a result of a
    # kept round; the round after it is masked.
    assert view.messages[2] is messages[2]
    assert "characters truncated" in text(view.messages[3])
    assert text(view.messages[5]).startswith("[Tool result omitted")
    assert [change.call_id for change in view.masked] == ["b"]
    assert [change.call_id for change in view.truncated] == ["a", "c"]


def test_a_round_that_loads_a_skill_stays_when_the_rounds_around_it_are_summarized() -> (
    None
):
    messages = [
        ChatMessage.user("u"),
        call("a", "lookup", {}),
        result("a", "ra"),
        call("s", "load_skill", {"name": "audit"}),
        result("s", "skill instructions"),
        call("b", "lookup", {}),
        result("b", "rb"),
        call("c", "lookup", {}),
        result("c", "rc"),
    ]

    view = compaction.view(messages, OFF, 3, "notes")

    # Rounds 1 and 3 are left out, round 2 loads a skill and stays, and the summary
    # takes the place of the last round it stands for.
    assert [message.role for message in view.messages] == [
        MessageRole.USER,
        MessageRole.ASSISTANT,
        MessageRole.TOOL,
        MessageRole.USER,
        MessageRole.ASSISTANT,
        MessageRole.TOOL,
    ]
    assert view.messages[1] is messages[3]
    assert view.messages[2] is messages[4]
    assert view.messages[3].text == (
        "[Summary of the earlier tool-call rounds of this request (rounds 1 to 3), "
        "which are no longer shown except those that load a skill]\nnotes"
    )
    assert view.messages[4] is messages[7]


def test_truncation_applies_to_the_rounds_that_are_not_masked() -> None:
    messages = conversation(4)
    policy = ContextManagementPolicy(
        tool_result_max_chars=100, tool_result_keep_last_rounds=2
    )

    view = compaction.view(messages, policy, 0, None)

    # Rounds 1 and 2 are masked and report the size of the original result.
    assert "returned 500 characters]" in text(view.messages[3])
    assert [change.round for change in view.masked] == [0, 1]
    # Rounds 3 and 4 are truncated.
    assert len(text(view.messages[7])) == 100
    assert "characters truncated" in text(view.messages[7])
    assert [change.round for change in view.truncated] == [2, 3]


# ── The summary ─────────────────────────────────────────────────────────


def test_folded_rounds_are_replaced_by_the_summary() -> None:
    messages = conversation(5)

    view = compaction.view(messages, OFF, 3, "Accounts 1 to 3 hold 10, 20 and 30.")

    # system, user with the summary added as a second text block, then rounds 4 and
    # 5.
    assert len(view.messages) == 6
    assert view.messages[0] is messages[0]
    user = view.messages[1]
    assert user.role == MessageRole.USER
    assert len(user.blocks) == 2
    assert user.blocks[0].text == "Sum the amounts of every account."
    # The summary starts after a blank line: an integration that sends the text
    # blocks of a message as one text does not run it into the user's own text.
    assert user.blocks[1].text == (
        "\n\n[Summary of the earlier tool-call rounds of this request (rounds 1 to 3),"
        " which are no longer shown]\n"
        "Accounts 1 to 3 hold 10, 20 and 30."
    )
    assert user.text.startswith(
        "Sum the amounts of every account.\n\n[Summary of the earlier"
    )
    assert view.messages[2:6] == messages[8:12]
    # The stored user message is unchanged.
    assert len(messages[1].blocks) == 1
    assert view.rounds == 5


def test_the_summary_is_its_own_user_message_without_one_before_the_rounds() -> None:
    messages = [
        ChatMessage.system("s"),
        call("a", "lookup", {}),
        result("a", "ra"),
        call("b", "lookup", {}),
        result("b", "rb"),
    ]

    view = compaction.view(messages, OFF, 1, "A returned ra.")

    assert len(view.messages) == 4
    assert view.messages[0] is messages[0]
    assert view.messages[1].role == MessageRole.USER
    assert view.messages[1].text == (
        "[Summary of the earlier tool-call rounds of this request (rounds 1 to 1),"
        " which are no longer shown]\n"
        "A returned ra."
    )
    assert view.messages[2:4] == messages[3:5]


def test_masking_counts_folded_rounds_so_its_boundary_does_not_move() -> None:
    messages = conversation(6)
    policy = ContextManagementPolicy(tool_result_keep_last_rounds=2)

    # Rounds 1 and 2 folded; rounds 3 and 4 masked; rounds 5 and 6 as they are.
    view = compaction.view(messages, policy, 2, "summary")

    assert [change.round for change in view.masked] == [2, 3]
    assert len(view.messages) == 2 + 8
    assert text(view.messages[3]).startswith("[Tool result omitted")
    assert view.messages[6:10] == messages[10:14]


def test_messages_between_folded_rounds_stay_in_place() -> None:
    messages = [
        ChatMessage.user("first"),
        call("a", "lookup", {}),
        result("a", "ra"),
        ChatMessage.user("second"),
        call("b", "lookup", {}),
        result("b", "rb"),
        call("c", "lookup", {}),
        result("c", "rc"),
    ]

    view = compaction.view(messages, OFF, 2, "summary")

    assert [message.role for message in view.messages] == [
        MessageRole.USER,
        MessageRole.USER,
        MessageRole.ASSISTANT,
        MessageRole.TOOL,
    ]
    # The summary follows the message that came before the last round it stands
    # for.
    assert view.messages[0] is messages[0]
    assert len(view.messages[1].blocks) == 2
    assert view.messages[1].blocks[0].text == "second"


def test_the_summary_follows_the_messages_that_came_before_the_rounds_it_stands_for() -> (
    None
):
    # An earlier turn with a tool round and its answer, then a new question with
    # rounds.
    messages = [
        ChatMessage.user("first question"),
        call("a", "lookup", {}),
        result("a", "ra"),
        ChatMessage.assistant("first answer"),
        ChatMessage.user("second question"),
        call("b", "lookup", {}),
        result("b", "rb"),
        call("c", "lookup", {}),
        result("c", "rc"),
    ]

    view = compaction.view(messages, OFF, 2, "notes")

    # What the notes say about the second question does not come before that
    # question.
    assert len(view.messages) == 5
    assert view.messages[0] is messages[0]
    assert view.messages[1] is messages[3]
    assert view.messages[2].text == (
        "second question"
        + compaction.SUMMARY_SEPARATOR
        + compaction.summary_text(2, "notes")
    )
    assert view.messages[3:5] == messages[7:9]


# ── What the summarizer reads ───────────────────────────────────────────


def test_a_round_is_rendered_from_its_stored_messages_for_the_summarizer() -> None:
    messages = [
        ChatMessage.user("u"),
        ChatMessage.assistant(
            [
                TextBlock(text="Checking two accounts."),
                ToolCallBlock(call_id="c1", name="lookup", input={"b": 2, "a": "x"}),
                ToolCallBlock(call_id="c2", name="chart", input={}),
            ]
        ),
        result("c1", "r" * 300),
        ChatMessage.tool(
            ToolResultBlock(
                call_id="c2",
                blocks=[
                    TextBlock(text="failed"),
                    ImageBlock.from_url("image/png", "https://example.org/1"),
                ],
                is_error=True,
            )
        ),
    ]
    round_ = compaction.find_rounds(messages)[0]

    rendered = compaction.render_round(messages, round_, 7, 120)

    assert rendered == (
        "Round 7:\n"
        "Assistant: Checking two accounts.\n"
        'lookup({"a":"x","b":2}) returned: '
        + "r" * 43
        + "\n...[214 characters truncated]...\n"
        + "r" * 43
        + "\n"
        "chart({}) failed: failed (and 1 media block not shown)\n"
    )


def test_a_result_is_shown_with_the_arguments_of_its_own_call() -> None:
    messages = [
        ChatMessage.user("u"),
        ChatMessage.assistant(
            [
                ToolCallBlock(call_id="c1", name="lookup", input={"account": 1}),
                ToolCallBlock(call_id="c2", name="lookup", input={"account": 2}),
                ToolCallBlock(call_id="c3", name="lookup", input={"account": 3}),
            ]
        ),
        # The results arrive in another order than the calls, and one is missing.
        result("c2", "twenty"),
        result("c1", "ten"),
        result("other", "stray"),
    ]
    round_ = compaction.find_rounds(messages)[0]

    rendered = compaction.render_round(messages, round_, 1, 100)

    # Two calls of one tool cannot be told apart by the tool's name.
    assert rendered == (
        "Round 1:\n"
        'lookup({"account":2}) returned: twenty\n'
        'lookup({"account":1}) returned: ten\n'
        "A tool returned: stray\n"
        'lookup({"account":3}) has no result\n'
    )


def test_arguments_that_cannot_be_written_as_json_are_shown_as_text() -> None:
    messages = [
        ChatMessage.user("u"),
        call("a", "lookup", {"when": set}),
        result("a", "r" * 300),
        call("b", "lookup", {}),
        result("b", "kept"),
    ]
    policy = ContextManagementPolicy(tool_result_keep_last_rounds=1)

    view = compaction.view(messages, policy, 0, None)

    assert text(view.messages[2]) == (
        "[Tool result omitted by context management: lookup({'when': <class 'set'>})"
        " returned 300 characters]"
    )
