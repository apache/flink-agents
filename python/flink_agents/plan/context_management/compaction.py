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
"""Derive the messages sent to the model from the stored messages of a chat request.

Under a ``ContextManagementPolicy`` the model is sent truncated tool results,
placeholders for the results of older rounds, and a summary in place of the rounds it
covers.

Nothing here changes its input or keeps state. A round is an assistant message with
tool calls and the tool messages that follow it; only messages of rounds are changed,
every other message is passed through. Sizes are counted in characters (code points),
so a result is never cut inside one and both sides cut at the same place. The texts
that are added are the same as on the Java side. The arguments of a tool call are
written as JSON by each side's own library. The two differ for a floating-point number
that one of them writes with an exponent (``25000000.0`` here, ``2.5E7`` in Java), for
control characters, and in the order of keys outside the Basic Multilingual Plane.
With such arguments a placeholder can differ between the sides, and with it the choice
a limit makes between its long and its short form.

The result of the built-in ``load_skill`` tool is the instructions of a skill, which
the model has to keep following. Such a result is always sent as it is. A round that
loads a skill stays in the messages when the rounds around it are summarized, and
because a summarizer is not shown it, its other results are not masked either.
"""

import json
import sys
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, List, Mapping, Sequence

from flink_agents.api.chat_message import (
    ChatMessage,
    MessageRole,
    TextBlock,
    ToolCallBlock,
    ToolResultBlock,
)
from flink_agents.api.skills import LOAD_SKILL_TOOL

if TYPE_CHECKING:
    from flink_agents.api.chat_message import ContentBlock, DataContentBlock
    from flink_agents.api.context_management import ContextManagementPolicy

# The most characters of a tool call's arguments shown in a placeholder or to a
# summarizer.
MAX_ARGUMENT_CHARS = 200

# What separates a summary from the text of the user message it is added to.
SUMMARY_SEPARATOR = "\n\n"

# A limit on the characters of a result that cuts nothing.
UNLIMITED_CHARS = sys.maxsize


@dataclass(frozen=True)
class Round:
    """One tool-call round.

    Attributes:
    ----------
    start : int
        Index of the assistant message that requests the tool calls.
    end : int
        Index after the last tool message of the round.
    """

    start: int
    end: int


@dataclass(frozen=True)
class ResultChange:
    """What was done to one tool result.

    Attributes:
    ----------
    round : int
        Index of the result's round, counted from 0 over the whole request.
    call_id : str
        The id of the tool call the result answers.
    tool : str | None
        The tool that produced the result, or None when no call of the round has
        its id.
    original_chars : int
        Characters of the stored result's text.
    sent_chars : int
        Characters of the text sent in its place.
    """

    round: int
    call_id: str
    tool: str | None
    original_chars: int
    sent_chars: int


@dataclass(frozen=True)
class View:
    """The messages to send for one model call, and what was changed to get them.

    Attributes:
    ----------
    messages : List[ChatMessage]
        The messages to send.
    rounds : int
        How many rounds the request has so far.
    truncated : List[ResultChange]
        The results that are sent truncated, in message order.
    masked : List[ResultChange]
        The results that are sent as placeholders, in message order.
    """

    messages: List[ChatMessage]
    rounds: int
    truncated: List[ResultChange]
    masked: List[ResultChange]


def find_rounds(messages: Sequence[ChatMessage]) -> List[Round]:
    """Return the rounds of a message list, in order."""
    rounds: List[Round] = []
    index = 0
    while index < len(messages):
        message = messages[index]
        if message.role == MessageRole.ASSISTANT and message.tool_calls:
            end = index + 1
            while end < len(messages) and messages[end].role == MessageRole.TOOL:
                end += 1
            rounds.append(Round(index, end))
            index = end
        else:
            index += 1
    return rounds


def masked_rounds(rounds: int, keep_last_rounds: int | None, step_rounds: int) -> int:
    """Return how many of the oldest rounds are masked.

    ``keep_last_rounds`` rounds keep their results and the boundary moves
    ``step_rounds`` rounds at a time. With a step above 1 the boundary waits until
    that many rounds are eligible, so the messages before it are the same for every
    model call between two steps.
    """
    if keep_last_rounds is None:
        return 0
    eligible = max(rounds - keep_last_rounds, 0)
    return eligible // step_rounds * step_rounds


def view(
    messages: Sequence[ChatMessage],
    policy: "ContextManagementPolicy",
    folded_rounds: int,
    summary: str | None,
    rounds: Sequence[Round] | None = None,
) -> View:
    """Return the messages to send for ``messages`` under ``policy``.

    Args:
        messages: The stored messages of the request.
        policy: The policy to apply.
        folded_rounds: How many of the oldest rounds ``summary`` covers; 0 without a
            summary.
        summary: The summary that stands for the folded rounds, or None.
        rounds: The rounds of ``messages``, for a caller that already has them.
    """
    if rounds is None:
        rounds = find_rounds(messages)
    folded = 0 if summary is None else min(folded_rounds, len(rounds))
    masked = masked_rounds(
        len(rounds),
        policy.tool_result_keep_last_rounds,
        policy.effective_tool_result_mask_step_rounds,
    )
    max_chars = policy.tool_result_max_chars

    visible: List[ChatMessage] = []
    truncated_results: List[ResultChange] = []
    masked_results: List[ResultChange] = []
    skill_rounds_shown = False
    round_index = 0
    index = 0
    while index < len(messages):
        current = (
            rounds[round_index]
            if round_index < len(rounds) and rounds[round_index].start == index
            else None
        )
        if current is None:
            visible.append(messages[index])
            index += 1
            continue
        assistant = messages[current.start]
        skill = loads_skill(assistant)
        # A summarized round is left out, unless it loads a skill.
        if round_index >= folded or skill:
            skill_rounds_shown = skill_rounds_shown or round_index < folded
            visible.append(assistant)
            visible.extend(
                _manage(
                    tool_message,
                    assistant,
                    round_index,
                    max_chars,
                    truncated_results,
                    masked_results,
                    # No summary will hold what a round that loads a skill returned,
                    # so its results are not masked.
                    past_masking_boundary=round_index < masked and not skill,
                )
                for tool_message in messages[current.start + 1 : current.end]
            )
        if round_index == folded - 1:
            _place_summary(
                visible, folded, summary, skill_rounds_shown=skill_rounds_shown
            )
        index = current.end
        round_index += 1
    return View(visible, len(rounds), truncated_results, masked_results)


def loads_skill(assistant: ChatMessage) -> bool:
    """Tell whether a round loads a skill.

    It does when one of its calls is to the built-in skill-loading tool.
    """
    return any(call.name == LOAD_SKILL_TOOL for call in assistant.tool_calls)


def _place_summary(
    visible: List[ChatMessage], folded: int, summary: str, *, skill_rounds_shown: bool
) -> None:
    """Put the summary where the last folded round was.

    There it follows every message that came before the rounds it stands for. It
    joins a user message right before that place as a further text block, so user and
    assistant turns keep alternating; without such a message it becomes a user
    message of its own.
    """
    text = summary_text(folded, summary, skill_rounds_shown=skill_rounds_shown)
    if visible and visible[-1].role == MessageRole.USER:
        # A chat model integration may send the text blocks of a message as one text,
        # joined as they are. The separator keeps the summary apart from the text
        # before it.
        block = TextBlock(text=SUMMARY_SEPARATOR + text)
        visible[-1] = visible[-1].with_blocks([*visible[-1].blocks, block])
    else:
        visible.append(ChatMessage.user([TextBlock(text=text)]))


def summary_text(
    folded_rounds: int, summary: str, *, skill_rounds_shown: bool = False
) -> str:
    """Return the text that stands for the first ``folded_rounds`` rounds.

    Args:
        folded_rounds: How many rounds the summary stands for.
        summary: The summary.
        skill_rounds_shown: Whether some of these rounds load a skill and are
            therefore still shown.
    """
    shown = " except those that load a skill" if skill_rounds_shown else ""
    return (
        "[Summary of the earlier tool-call rounds of this request (rounds 1 to "
        f"{folded_rounds}), which are no longer shown{shown}]\n{summary}"
    )


def _manage(
    tool_message: ChatMessage,
    assistant: ChatMessage,
    round_index: int,
    max_chars: int | None,
    truncated_results: List[ResultChange],
    masked_results: List[ResultChange],
    *,
    past_masking_boundary: bool,
) -> ChatMessage:
    """Return one tool message as it is sent.

    Each of its results is a placeholder when its round is past the masking boundary,
    cut when it is longer than the limit, or as it is.
    """
    blocks: List[ContentBlock] = []
    changed = False
    for block in tool_message.blocks:
        if not isinstance(block, ToolResultBlock):
            blocks.append(block)
            continue
        call = _call_of(assistant, block.call_id)
        if call is not None and call.name == LOAD_SKILL_TOOL:
            # The instructions of a skill: the model has to keep them as they are.
            blocks.append(block)
            continue
        tool = None if call is None else call.name
        text = block.text
        original_chars = len(text)
        # What is sent without masking: the result, cut when it is longer than the
        # limit.
        unmasked_chars = (
            original_chars
            if max_chars is None or original_chars <= max_chars
            else len(truncate_middle(text, max_chars))
        )
        if past_masking_boundary:
            media = _media_blocks(block)
            placeholder = _placeholder(call, original_chars, media)
            if max_chars is not None and len(placeholder) > max_chars:
                # The call and its arguments do not fit the limit; the size alone
                # does.
                placeholder = _placeholder(None, original_chars, media)
            # A placeholder that is not shorter than the result would send more, not
            # less.
            if media > 0 or len(placeholder) < unmasked_chars:
                blocks.append(
                    ToolResultBlock(
                        call_id=block.call_id,
                        blocks=[TextBlock(text=placeholder)],
                        is_error=block.is_error,
                        metadata=block.metadata,
                    )
                )
                masked_results.append(
                    ResultChange(
                        round_index,
                        block.call_id,
                        tool,
                        original_chars,
                        len(placeholder),
                    )
                )
                changed = True
                continue
        if unmasked_chars == original_chars:
            blocks.append(block)
            continue
        blocks.append(
            ToolResultBlock(
                call_id=block.call_id,
                blocks=_truncate_blocks(block.blocks, original_chars, max_chars),
                is_error=block.is_error,
                metadata=block.metadata,
            )
        )
        truncated_results.append(
            ResultChange(
                round_index, block.call_id, tool, original_chars, unmasked_chars
            )
        )
        changed = True
    return tool_message.with_blocks(blocks) if changed else tool_message


def _truncate_blocks(
    blocks: Sequence["DataContentBlock"], total_chars: int, max_chars: int
) -> List["DataContentBlock"]:
    """Cut the text of a result's blocks in the middle, as ``truncate_middle`` does.

    Every text block keeps what is left of its own text, and media blocks stay where
    they are, so text that follows a media block still follows it. The marker comes
    right after the kept beginning.
    """
    keep = max(max_chars - len(_truncation_marker(total_chars)), 0)
    head = (keep + 1) // 2
    tail_start = total_chars - (keep - head)
    marker = _truncation_marker(total_chars - keep)
    cut: List[DataContentBlock] = []
    start = 0
    marker_placed = False
    for block in blocks:
        if not isinstance(block, TextBlock):
            cut.append(block)
            continue
        text = block.text
        end = start + len(text)
        kept = ""
        if head > start:
            kept += text[: head - start]
        if not marker_placed and end >= head:
            kept += marker
            marker_placed = True
        if tail_start < end:
            kept += text[max(tail_start - start, 0) :]
        if kept:
            cut.append(TextBlock(text=kept))
        start = end
    return cut


def render_round(
    messages: Sequence[ChatMessage],
    round_: Round,
    round_number: int,
    max_result_chars: int,
) -> str:
    """Return one round as text for a summarizer, from its stored messages.

    The text has the assistant's text, then each result with the call it answers and
    the result's text cut to ``max_result_chars``. A result is shown with the
    arguments of its own call, so two calls of one tool cannot be mixed up.

    Args:
        messages: The stored messages of the request.
        round_: The round to render.
        round_number: The number of the round, counted from 1.
        max_result_chars: The most characters of one result.
    """
    assistant = messages[round_.start]
    parts = [f"Round {round_number}:\n"]
    if assistant.text:
        parts.append(f"Assistant: {assistant.text}\n")
    answered = set()
    for tool_message in messages[round_.start + 1 : round_.end]:
        for block in tool_message.blocks:
            if not isinstance(block, ToolResultBlock):
                continue
            call = _call_of(assistant, block.call_id)
            answered.add(block.call_id)
            line = "A tool" if call is None else _describe(call)
            line += " failed: " if block.is_error else " returned: "
            line += truncate_middle(block.text, max_result_chars)
            media = _media_blocks(block)
            if media > 0:
                line += f" (and {_media_text(media)} not shown)"
            parts.append(line + "\n")
    parts.extend(
        f"{_describe(call)} has no result\n"
        for call in assistant.tool_calls
        if call.call_id not in answered
    )
    return "".join(parts)


def _describe(call: ToolCallBlock) -> str:
    return f"{call.name}({_arguments(call.input)})"


def truncate_middle(text: str, max_chars: int) -> str:
    """Cut ``text`` in the middle so that it has at most ``max_chars`` characters.

    The marker that tells how much is missing counts toward the limit. A limit
    smaller than the marker leaves only the marker.
    """
    total = len(text)
    if total <= max_chars:
        return text
    # The marker names the omitted count, which has at most as many digits as the
    # total.
    keep = max(max_chars - len(_truncation_marker(total)), 0)
    head = (keep + 1) // 2
    tail = keep - head
    return text[:head] + _truncation_marker(total - keep) + text[total - tail :]


def _truncation_marker(omitted_chars: int) -> str:
    return f"\n...[{omitted_chars} characters truncated]...\n"


def _placeholder(call: ToolCallBlock | None, original_chars: int, media: int) -> str:
    text = "[Tool result omitted by context management: "
    if call is not None:
        text += f"{_describe(call)} returned "
    text += f"{original_chars} characters"
    if media > 0:
        text += f" and {_media_text(media)}"
    return text + "]"


def _media_text(media: int) -> str:
    return f"{media} media block" if media == 1 else f"{media} media blocks"


def _arguments(call_input: Mapping[str, Any]) -> str:
    """Return the arguments of a tool call as compact JSON with sorted keys.

    Long arguments are cut.
    """
    try:
        text = json.dumps(
            call_input, sort_keys=True, separators=(",", ":"), ensure_ascii=False
        )
    except (TypeError, ValueError):
        text = str(call_input)
    if len(text) <= MAX_ARGUMENT_CHARS:
        return text
    return text[:MAX_ARGUMENT_CHARS] + "..."


def _call_of(assistant: ChatMessage, call_id: str) -> ToolCallBlock | None:
    for call in assistant.tool_calls:
        if call.call_id == call_id:
            return call
    return None


def _media_blocks(result: ToolResultBlock) -> int:
    return sum(1 for part in result.blocks if not isinstance(part, TextBlock))
