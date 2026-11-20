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
"""Apply a chat request's context management policy for the built-in chat action.

The manager decides what one model call is sent, writes a summary when the input
budget asks for one, and reports each step as a ``ContextManagementEvent``.

The stored conversation of the request is only read. What is kept for a request is its
own policy when the request carries one, the summary and how many rounds it covers,
and the factor that corrects approximate token estimates; all of it lives in sensory
memory under ``STATE_KEY`` and is removed with the request's other state when the
request completes.

Truncation and masking are pure functions of the stored messages, so they add no
durable call and a replay sends the same messages. A summary is one durable call per
batch under the id ``SUMMARIZE_CALL_ID``, made before the round's model call; whether
it runs is decided from the stored messages and the kept state, so a replay reaches
the same decision and reuses the recorded summary.

The summary has one line for each summarized round. A summarizer writes the lines of
the rounds it is shown, only those lines are kept, and they are added to the summary:
a line is written once, from its own round, and nothing rewrites it.
"""

import logging
import math
import re
from concurrent.futures import CancelledError
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, Dict, List, Mapping, Sequence, cast

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.token_estimate import (
    TokenEstimate,
    approximate_token_estimate,
)
from flink_agents.api.context_management import ContextManagementPolicy
from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.events.context_management_event import ContextManagementEvent
from flink_agents.api.resource import ResourceType
from flink_agents.api.tools.tool import Tool
from flink_agents.api.trace import (
    ExecutionEntityTypes,
    ExecutionProblemCategories,
    ExecutionReporters,
    LLMExecutionMetadataKeys,
)
from flink_agents.plan.context_management import compaction
from flink_agents.plan.resource.java.java_chat_model import JavaChatModelSetup

if TYPE_CHECKING:
    from uuid import UUID

    from flink_agents.api.chat_models.chat_model import BaseChatModelSetup
    from flink_agents.api.chat_result import ChatResult, TokenUsage
    from flink_agents.api.memory_object import MemoryObject
    from flink_agents.api.runner_context import RunnerContext

# Sensory-memory key of the context management state, by initial request id.
STATE_KEY = "_CONTEXT_MANAGEMENT_CONTEXT"
# Durable call id of a summarizer call.
SUMMARIZE_CALL_ID = "summarize"

_POLICY = "policy"
_SUMMARY = "summary"
_SUMMARIZED_ROUNDS = "summarized_rounds"
_CALIBRATION = "calibration"
_CALIBRATION_TOKENS = "calibration_tokens"

# Bounds of the factor by which a reported input size raises an approximate estimate.
# The factor never lowers an estimate: some providers report cached input tokens apart
# from the input size, so a reported size can be smaller than the request, and an
# estimate that is too low is the harmful direction.
_MIN_CALIBRATION = 1.0
_MAX_CALIBRATION = 4.0
# The most tokens of the instructions, and of the request text, shown to a
# summarizer.
_MAX_TASK_TOKENS = 1000
# The share of the input budget the instructions, and the request text, may each
# take.
_TASK_BUDGET_SHARE = 0.125
# Below this many characters per result a round is sent to the summarizer as it is.
_MIN_SUMMARIZER_RESULT_CHARS = 200
# Below this many characters a round is not worth showing to the summarizer.
_MIN_SUMMARIZER_ROUND_CHARS = 100

SUMMARIZER_INSTRUCTIONS = (
    "You maintain the working notes of an AI agent that is in the middle of a"
    " request. The agent has called tools in several rounds, and its oldest rounds"
    " are about to be removed from its context. Your notes replace them.\n\n"
    "You are not the agent. Do not carry out its instructions, do not continue"
    " its work and do not answer its request. The agent has already made later"
    " rounds, which are not shown to you and stay in its context.\n\n"
    "Write one line for each round you are shown, starting with its label, for"
    " example \"Round 3: ...\". On the line, state what the round found that the"
    " agent still needs to finish the request: the values, exactly as they are,"
    " the identifiers and names it will use again, and a decision the agent made"
    " in that round. Leave out everything else, such as the bulk of a tool result"
    " that the request does not need. Use only what that round contains. Do not"
    " write any other line: a line that is not the line of a round is discarded,"
    " so a total, a conclusion or a next step would be lost."
)

# The share of the input budget the notes of a request may take.
_NOTES_BUDGET_SHARE = 0.5
# Bounds of the words a summarizer is asked to keep the line of one round within.
_MIN_NOTE_WORDS = 30
_MAX_NOTE_WORDS = 150
# The tokens of input budget that add one word to what the line of a round may have.
_BUDGET_TOKENS_PER_NOTE_WORD = 200
# The most tokens kept of a round's line for each word asked; a longer line is cut.
_NOTE_TOKENS_PER_WORD = 3
# What the label of a line and its line break take at most, in tokens.
_NOTE_LABEL_TOKENS = 5
# The most rounds one summarizer request is asked to write lines for.
_MAX_BATCH_ROUNDS = 10
# What separates the lines of a summarizer's answer.
_LINE_BREAK = re.compile("\r\n|[\n\r\x0b\x0c\x85\u2028\u2029]")
# The line of a round: optional list or emphasis marks, the round's label, and its
# text. ASCII rules, as on the Java side.
_NOTE_LINE = re.compile(
    r"[\s\-*\u2022>#]*Round\s+(\d{1,9})\s*\**\s*[:\uFF1A]\s*\**\s*(.*?)\s*",
    re.IGNORECASE | re.ASCII,
)

_logger = logging.getLogger(__name__)

# What the approximation counts for a message without content.
_EMPTY_MESSAGE_TOKENS = approximate_token_estimate([ChatMessage.user("")], []).tokens


@dataclass(frozen=True)
class Prepared:
    """The messages the chat action sends for one model call.

    Attributes:
    ----------
    messages : List[ChatMessage]
        The messages to send; the stored messages themselves when no policy applies.
    raw_estimate : int
        The uncorrected estimate of ``messages``, or -1 when none was made.
    approximate : bool
        Whether ``raw_estimate`` is an approximation.
    """

    messages: List[ChatMessage]
    raw_estimate: int = -1
    approximate: bool = False


class _SummaryFailure(Exception):
    """A summary that could not be written; the request goes on without it."""


class _SummarizerCallFailure(Exception):
    """The textual failure of a summarizer call, as reported on its LLM span."""


@dataclass(frozen=True)
class _SummaryStep:
    """What one summarization step did.

    ``start`` and ``folded`` are how many rounds the summary covered before and after
    the step. ``summary`` is the summary after the step, None when there is none.
    ``error`` tells why the step stopped before its target, None when it reached it.
    ``calls`` is how many summarizer requests the step made. ``added`` is how many
    rounds the step added to the summary; rounds that load a skill are not.
    """

    summarizer: str
    start: int
    folded: int
    summary: str | None
    error: str | None
    calls: int
    added: int


def set_request_policy(
    sensory_memory: "MemoryObject",
    request_id: "UUID",
    policy: ContextManagementPolicy | None,
) -> None:
    """Keep the policy a chat request carries for the rounds of its tool-call loop.

    A request without a policy keeps nothing and uses the agent's default policy.
    """
    if policy is None:
        return
    state = _read_state(sensory_memory, request_id)
    state[_POLICY] = policy.to_map()
    _write_state(sensory_memory, request_id, state)


async def prepare(
    request_id: "UUID",
    model: str,
    messages: List[ChatMessage],
    prompt_args: Mapping[str, Any] | None,
    ctx: "RunnerContext",
    *,
    first_call: bool,
) -> Prepared:
    """Decide what the next model call of a request is sent.

    Args:
        request_id: The id of the initial chat request.
        model: The chat model resource the call goes to; it estimates the input and,
            unless the policy names another one, writes summaries.
        messages: The stored messages of the request, which are not changed.
        prompt_args: The request's prompt arguments.
        ctx: The runner context of the chat action.
        first_call: Whether this is the first model call of the request, as opposed
            to a call that follows a tool round.
    """
    # Only tool-call rounds are ever changed. A request that has none yet is sent as
    # it is, whatever its policy: nothing is read for it and nothing can fail.
    rounds = compaction.find_rounds(messages)
    if not rounds:
        return Prepared(messages)
    memory = ctx.sensory_memory
    state = _read_state(memory, request_id)
    policy = _policy_of(state, ctx)
    if not policy.is_enabled:
        return Prepared(messages)

    view = compaction.view(
        messages, policy, _summarized_rounds(state), state.get(_SUMMARY), rounds
    )
    raw_estimate = -1
    approximate = False
    budget = policy.max_input_tokens
    if budget is not None:
        chat_model = _resolve_chat_model(ctx, model)
        calibration = state.get(_CALIBRATION)
        estimate = _estimate(chat_model, view.messages, prompt_args)
        tokens = _corrected(estimate, calibration)
        unsummarized = view.rounds - _summarized_rounds(state)
        if (
            tokens > policy.effective_summarization_trigger_ratio * budget
            and unsummarized > policy.effective_summarization_keep_last_rounds
        ):
            approximate_before = estimate.is_approximate
            step = await _summarize(
                request_id,
                model,
                chat_model,
                policy,
                messages,
                rounds,
                prompt_args,
                state,
                ctx,
            )
            tokens_after = None
            if step.folded > step.start:
                _write_state(memory, request_id, state)
                view = compaction.view(
                    messages, policy, step.folded, state.get(_SUMMARY), rounds
                )
                estimate = _estimate(chat_model, view.messages, prompt_args)
                tokens_after = _corrected(estimate, calibration)
            if step.calls > 0 or step.error is not None:
                _report_summary_step(
                    request_id,
                    model,
                    policy,
                    step,
                    ctx,
                    tokens_before=tokens,
                    approximate=approximate_before,
                    tokens_after=tokens_after,
                )
        raw_estimate = estimate.tokens
        approximate = estimate.is_approximate
    _report_tool_result_steps(
        request_id, model, policy, view, ctx, first_call=first_call
    )
    return Prepared(view.messages, raw_estimate, approximate)


def record_usage(
    sensory_memory: "MemoryObject",
    request_id: "UUID",
    prepared: Prepared,
    usage: "TokenUsage | None",
) -> None:
    """Keep the factor between a reported input size and its estimate.

    The input size the provider reported for a model call is compared with the
    estimate made for it, and the factor between them is kept for the request's next
    estimates. The factor that is kept comes from the call with the largest reported
    input. Only approximate estimates are corrected, and only upward.
    """
    if not prepared.approximate or prepared.raw_estimate <= 0 or usage is None:
        return
    prompt_tokens = usage.prompt_tokens
    if prompt_tokens is None or prompt_tokens <= 0:
        return
    factor = min(
        _MAX_CALIBRATION, max(_MIN_CALIBRATION, prompt_tokens / prepared.raw_estimate)
    )
    state = _read_state(sensory_memory, request_id)
    # The larger the request, the less a fixed part the estimate does not cover
    # weighs in the factor, a prompt bound on the other language's side for example.
    # So a call with a larger reported input replaces the factor, also with a smaller
    # one. A call that reports less than an earlier one, such as one served from the
    # provider's cache, does not.
    measured = state.get(_CALIBRATION_TOKENS)
    if isinstance(measured, int) and measured > prompt_tokens:
        return
    state[_CALIBRATION] = factor
    state[_CALIBRATION_TOKENS] = prompt_tokens
    _write_state(sensory_memory, request_id, state)


# ── Policy and state ────────────────────────────────────────────────────


def _policy_of(state: Dict[str, Any], ctx: "RunnerContext") -> ContextManagementPolicy:
    own = state.get(_POLICY)
    if isinstance(own, dict):
        return ContextManagementPolicy.model_validate(own)
    return ContextManagementPolicy.from_configuration(ctx.config)


def _summarized_rounds(state: Dict[str, Any]) -> int:
    rounds = state.get(_SUMMARIZED_ROUNDS)
    return rounds if isinstance(rounds, int) else 0


def _read_state(memory: "MemoryObject", request_id: "UUID") -> Dict[str, Any]:
    """Return the state of a request as a copy that can be changed.

    The copy is empty when nothing is kept.
    """
    state = (memory.get(STATE_KEY) or {}).get(str(request_id))
    return dict(state) if isinstance(state, dict) else {}


def _write_state(
    memory: "MemoryObject", request_id: "UUID", state: Dict[str, Any]
) -> None:
    states = dict(memory.get(STATE_KEY) or {})
    states[str(request_id)] = state
    memory.set(STATE_KEY, states)


# ── Token estimates ─────────────────────────────────────────────────────


def _resolve_chat_model(
    ctx: "RunnerContext", model: str
) -> "BaseChatModelSetup | None":
    try:
        return cast(
            "BaseChatModelSetup", ctx.get_resource(model, ResourceType.CHAT_MODEL)
        )
    except (CancelledError, InterruptedError):
        raise
    except Exception:
        # The model call itself reports a chat model that cannot be resolved.
        return None


def _estimate(
    chat_model: "BaseChatModelSetup | None",
    messages: Sequence[ChatMessage],
    prompt_args: Mapping[str, Any] | None,
) -> TokenEstimate:
    """Return the chat model's estimate of the request it would send.

    The approximation is used when the chat model cannot give an estimate.
    """
    if chat_model is None:
        return approximate_token_estimate(messages, [])
    try:
        estimate = chat_model.estimate_input_tokens(messages, prompt_args)
        if isinstance(estimate, TokenEstimate):
            return estimate
        _logger.debug(
            "Chat model returned %s instead of a TokenEstimate; using the "
            "approximation",
            type(estimate).__name__,
        )
    except (CancelledError, InterruptedError):
        raise
    except Exception:
        _logger.debug(
            "Chat model could not estimate its input; using the approximation",
            exc_info=True,
        )
    tools = getattr(chat_model, "tools", None)
    bound = [tool for tool in tools if isinstance(tool, Tool)] if tools else []
    return approximate_token_estimate(
        _request_messages(chat_model, messages, prompt_args), bound
    )


def _request_messages(
    chat_model: "BaseChatModelSetup | None",
    messages: Sequence[ChatMessage],
    prompt_args: Mapping[str, Any] | None,
) -> Sequence[ChatMessage]:
    """Return the messages the chat model sends for a request.

    They have its bound prompt and skill-discovery message when it can prepare them,
    and are the request's own messages otherwise.
    """
    if chat_model is None:
        return messages
    try:
        return chat_model.prepare_request_messages(messages, prompt_args)
    except (CancelledError, InterruptedError):
        raise
    except Exception:
        _logger.debug(
            "Chat model could not prepare its request; using the request's messages",
            exc_info=True,
        )
        return messages


def _corrected(estimate: TokenEstimate, calibration: float | None) -> int:
    if not estimate.is_approximate or calibration is None:
        return estimate.tokens
    return math.ceil(estimate.tokens * calibration)


def _error_text(error: Exception) -> str:
    return f"{type(error).__name__}: {error}"


# ── Truncation and masking reports ──────────────────────────────────────


def _report_tool_result_steps(
    request_id: "UUID",
    model: str,
    policy: ContextManagementPolicy,
    view: compaction.View,
    ctx: "RunnerContext",
    *,
    first_call: bool,
) -> None:
    """Report the results first sent truncated and the rounds first masked.

    Both follow from the number of rounds: the calls of a request see one more round
    each, so what the previous call sent is known without keeping it.
    """
    first_new_round = 0 if first_call else view.rounds - 1
    truncated = [
        {
            **_result_details(change),
            "original_chars": change.original_chars,
            "sent_chars": change.sent_chars,
        }
        for change in view.truncated
        if change.round >= first_new_round
    ]
    if truncated:
        ctx.send_event(
            ContextManagementEvent(
                request_id=request_id,
                model=model,
                step=ContextManagementEvent.STEP_TRUNCATE,
                details={
                    "max_chars": policy.tool_result_max_chars,
                    "results": truncated,
                },
            )
        )

    masked_before = (
        0
        if first_call
        else compaction.masked_rounds(
            view.rounds - 1,
            policy.tool_result_keep_last_rounds,
            policy.effective_tool_result_mask_step_rounds,
        )
    )
    newly_masked = [change for change in view.masked if change.round >= masked_before]
    masked = [
        {**_result_details(change), "omitted_chars": change.original_chars}
        for change in newly_masked
    ]
    if masked:
        ctx.send_event(
            ContextManagementEvent(
                request_id=request_id,
                model=model,
                step=ContextManagementEvent.STEP_MASK,
                details={
                    "keep_last_rounds": policy.tool_result_keep_last_rounds,
                    "step_rounds": policy.effective_tool_result_mask_step_rounds,
                    # A round that crossed the boundary counts when one of its
                    # results became a placeholder: not when the summary already
                    # covers it, or when its results are too short to mask.
                    "rounds": len({change.round for change in newly_masked}),
                    "results": masked,
                },
            )
        )


def _result_details(change: compaction.ResultChange) -> Dict[str, Any]:
    details: Dict[str, Any] = {"call_id": change.call_id}
    if change.tool is not None:
        details["tool"] = change.tool
    return details


# ── Summarization ───────────────────────────────────────────────────────


async def _summarize(
    request_id: "UUID",
    model: str,
    chat_model: "BaseChatModelSetup | None",
    policy: ContextManagementPolicy,
    messages: Sequence[ChatMessage],
    rounds: List[compaction.Round],
    prompt_args: Mapping[str, Any] | None,
    state: Dict[str, Any],
    ctx: "RunnerContext",
) -> _SummaryStep:
    """Fold the rounds before the last kept ones into the summary.

    The summarizer is asked for the line of each round, in batches that fit the input
    budget, and the lines are added to the summary. ``state`` is updated with what was
    folded.
    """
    start = _summarized_rounds(state)
    target = len(rounds) - policy.effective_summarization_keep_last_rounds
    summarizer_name = model if policy.summarizer is None else policy.summarizer

    folded = start
    calls = 0
    added = 0
    summary = state.get(_SUMMARY)
    error = None
    try:
        # A summarizer that cannot be used still gets its durable call, which records
        # why. A replay then takes the same path as the first run, whether or not the
        # summarizer can be resolved at that time.
        summarizer = None
        unavailable = None
        try:
            summarizer = _resolve_summarizer(ctx, summarizer_name)
        except _SummaryFailure as failure:
            unavailable = str(failure)
        asked = _request_text(
            _request_messages(chat_model, messages, prompt_args), policy
        )
        budget = policy.max_input_tokens
        words = note_word_limit(budget)
        # The most rounds one request shows. It drops to one when a summarizer that
        # was asked for the lines of several rounds left one out.
        most = _MAX_BATCH_ROUNDS
        while folded < target:
            # The notes take a line for each round. Only as many rounds are asked
            # about as the notes have room for, so every line that is written can be
            # kept.
            room = _room_for_lines(summary, words, budget)
            end, shown, request = _next_batch(
                messages,
                rounds,
                folded,
                target,
                asked,
                words,
                max(min(room, most), 1),
                policy,
            )
            if shown:
                _require_room(room, summary, budget)
                calls += 1
                answer = await _call_summarizer(
                    summarizer, unavailable, summarizer_name, request, ctx
                )
                try:
                    lines = round_notes(answer, shown, words)
                except _SummaryFailure:
                    if len(shown) == 1:
                        raise
                    # Nothing is kept of the answer. The rounds are asked about
                    # again, one at a time.
                    most = 1
                    continue
                summary = extended(summary, lines)
                added += len(shown)
                state[_SUMMARY] = summary
            folded = end
            state[_SUMMARIZED_ROUNDS] = folded
    except _SummaryFailure as failure:
        error = str(failure)
        _logger.warning(
            "Chat request %s: rounds %d to %d could not be summarized by '%s': %s. "
            "The request continues without the summary.",
            request_id,
            folded + 1,
            target,
            summarizer_name,
            error,
        )
    return _SummaryStep(summarizer_name, start, folded, summary, error, calls, added)


def _notes_tokens(budget: int) -> int:
    """Return the most tokens the notes of a request may take: half of the budget."""
    return int(budget * _NOTES_BUDGET_SHARE)


def note_word_limit(max_input_tokens: int) -> int:
    """Return how many words a summarizer is asked to keep the line of a round within.

    That is more with a larger budget, whose rounds tend to hold more.
    """
    return max(
        _MIN_NOTE_WORDS,
        min(_MAX_NOTE_WORDS, max_input_tokens // _BUDGET_TOKENS_PER_NOTE_WORD),
    )


def _room_for_lines(notes: str | None, words: int, budget: int) -> int:
    """Return how many more lines the notes have room for.

    At most the rounds of one summarizer request are counted. A line counts with the
    most tokens it can take, so the lines of that many rounds always fit.
    """
    left = _notes_tokens(budget) - (0 if notes is None else _text_tokens(notes))
    line = words * _NOTE_TOKENS_PER_WORD + _NOTE_LABEL_TOKENS
    return max(0, min(_MAX_BATCH_ROUNDS, left // line))


def _require_room(room: int, notes: str | None, budget: int) -> None:
    """Fail the summary when the notes have no room for the line of another round.

    Raises:
        _SummaryFailure: If ``room`` is 0.
    """
    if room == 0:
        taken = 0 if notes is None else _text_tokens(notes)
        msg = (
            f"the summary is full: it takes {taken} of the {_notes_tokens(budget)} "
            f"estimated tokens it may take with max_input_tokens={budget}, which "
            "leaves no room for the line of another round"
        )
        raise _SummaryFailure(msg)


def extended(notes: str | None, lines: str) -> str:
    """Return the notes with the lines of more rounds added.

    The lines of earlier rounds stay as they are: nothing rewrites them.
    """
    return lines if notes is None else f"{notes}\n{lines}"


def round_notes(answer: str, round_numbers: Sequence[int], words: int) -> str:
    """Return the lines of a summarizer's answer that are the notes on some rounds.

    There is one line for each round of ``round_numbers``, in order. Every other line
    of the answer is left out: a line for a round that was not shown, a total, a
    conclusion. A line that is far longer than asked is cut.

    Raises:
        _SummaryFailure: If the answer has no line for one of the rounds.
    """
    lines: Dict[int, str] = {}
    for line in _LINE_BREAK.split(answer):
        match = _NOTE_LINE.fullmatch(line)
        if match is not None and match.group(2):
            lines.setdefault(int(match.group(1)), match.group(2))
    max_tokens = words * _NOTE_TOKENS_PER_WORD
    notes = []
    for number in round_numbers:
        text = lines.get(number)
        if text is None:
            msg = f"the summarizer wrote no line for round {number}"
            raise _SummaryFailure(msg)
        notes.append(f"Round {number}: {_cut_to_tokens(text, max_tokens, 1)}")
    return "\n".join(notes)


def _report_summary_step(
    request_id: "UUID",
    model: str,
    policy: ContextManagementPolicy,
    step: _SummaryStep,
    ctx: "RunnerContext",
    *,
    tokens_before: int,
    approximate: bool,
    tokens_after: int | None,
) -> None:
    """Report a summarization step, whether it folded rounds or failed.

    Args:
        request_id: The id of the initial chat request.
        model: The chat model resource of the request.
        policy: The policy of the request.
        step: What the step did.
        ctx: The runner context of the chat action.
        tokens_before: The estimated input that asked for the step.
        approximate: Whether that estimate is an approximation.
        tokens_after: The estimated input with the new summary; None when nothing
            was folded.
    """
    details: Dict[str, Any] = {
        "summarizer": step.summarizer,
        "rounds": step.added,
        "summarized_rounds": step.folded,
        "max_input_tokens": policy.max_input_tokens,
        "estimated_input_tokens_before": tokens_before,
        "estimate_approximate": approximate,
    }
    if tokens_after is not None and step.summary is not None:
        details["summary_chars"] = len(step.summary)
        details["estimated_input_tokens_after"] = tokens_after
    if step.error is not None:
        details["error"] = step.error
    ctx.send_event(
        ContextManagementEvent(
            request_id=request_id,
            model=model,
            step=ContextManagementEvent.STEP_SUMMARIZE,
            details=details,
        )
    )


def _resolve_summarizer(ctx: "RunnerContext", name: str) -> "BaseChatModelSetup":
    try:
        summarizer = ctx.get_resource(name, ResourceType.CHAT_MODEL)
    except (CancelledError, InterruptedError):
        raise
    except Exception as error:
        msg = f"chat model '{name}' cannot be used: {_error_text(error)}"
        raise _SummaryFailure(msg) from error
    if isinstance(summarizer, JavaChatModelSetup):
        msg = (
            f"chat model '{name}' is declared in Java, and a summary can only be "
            "written by a chat model declared in Python. Set "
            f"{AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL.get_key()}"
            " to a Python chat model"
        )
        raise _SummaryFailure(msg)
    return cast("BaseChatModelSetup", summarizer)


def _next_batch(
    messages: Sequence[ChatMessage],
    rounds: List[compaction.Round],
    start: int,
    target: int,
    asked: "RequestText",
    words: int,
    max_rounds: int,
    policy: ContextManagementPolicy,
) -> tuple[int, List[int], List[ChatMessage] | None]:
    """Take as many rounds from ``start`` on as fit the input budget.

    At least one round is taken and at most ``max_rounds``. A round that does not fit
    a request of its own is cut until it does and is a batch by itself, so the rounds
    after it are not cut with it. A round that loads a skill stays in the messages and
    is not shown to the summarizer.

    Returns:
        The index after the last round of the batch, the numbers, from 1, of the
        rounds the request shows, and the request that asks the summarizer for
        their lines. The request is None when every round of the batch loads a skill:
        nothing is asked.

    Raises:
        _SummaryFailure: If not even a cut round fits beside the rest of the request.
    """
    budget = policy.max_input_tokens
    result_chars = (
        compaction.UNLIMITED_CHARS
        if policy.tool_result_max_chars is None
        else policy.tool_result_max_chars
    )
    # What the rounds may take: the budget without the rest of the request. Each
    # round is counted once, so a long request is put together in one pass.
    room = budget - _tokens(summarizer_request(asked, "", words))
    rendered: List[str] = []
    shown: List[int] = []
    used = 0
    end = start
    while end < target and len(shown) < max_rounds:
        round_ = rounds[end]
        if compaction.loads_skill(messages[round_.start]):
            end += 1
            continue
        text = compaction.render_round(messages, round_, end + 1, result_chars)
        size = _text_tokens(text)
        if used + size > room:
            if not shown:
                rendered.append(
                    _fit(messages, round_, end + 1, result_chars, room, budget)
                )
                shown.append(end + 1)
                end += 1
            break
        rendered.append(text)
        used += size
        shown.append(end + 1)
        end += 1
    if not shown:
        return end, shown, None
    return end, shown, summarizer_request(asked, "".join(rendered), words)


def _fit(
    messages: Sequence[ChatMessage],
    round_: compaction.Round,
    round_number: int,
    result_chars: int,
    room: int,
    budget: int,
) -> str:
    """Return one round as text that fits ``room`` tokens.

    Its results are cut further, and when that is not enough, long assistant text or
    many results for example, its text as a whole.
    """
    text = compaction.render_round(messages, round_, round_number, result_chars)
    while _text_tokens(text) > room and result_chars > _MIN_SUMMARIZER_RESULT_CHARS:
        result_chars = max(
            _MIN_SUMMARIZER_RESULT_CHARS, min(result_chars, len(text)) // 2
        )
        text = compaction.render_round(messages, round_, round_number, result_chars)
    cut = _cut_to_tokens(text, room, _MIN_SUMMARIZER_ROUND_CHARS)
    if cut is None:
        msg = (
            f"round {round_number} does not fit a summarizer request within "
            f"max_input_tokens={budget}"
        )
        raise _SummaryFailure(msg)
    return cut


def _cut_to_tokens(text: str, max_tokens: int, min_chars: int) -> str | None:
    """Cut ``text`` in the middle so that it takes at most ``max_tokens``.

    As much of the text as fits is kept. Four ASCII characters are one token and any
    other character is one, so what fits is between one and four characters for a
    token; the longest cut that fits is searched for. Every cut is made from the
    whole text, so the marker tells how much of it is missing.

    Returns:
        The text that fits, or None when a cut to ``min_chars`` characters does not.
    """
    if _text_tokens(text) <= max_tokens:
        return text
    most = min(max_tokens * 4, len(text) - 1)
    if most < min_chars:
        return None
    # Text that is all ASCII fits at four characters for a token.
    longest = compaction.truncate_middle(text, most)
    if _text_tokens(longest) <= max_tokens:
        return longest
    if _text_tokens(compaction.truncate_middle(text, min_chars)) > max_tokens:
        return None
    fits = min_chars
    too_long = most
    while too_long - fits > 1:
        chars = fits + (too_long - fits) // 2
        if _text_tokens(compaction.truncate_middle(text, chars)) <= max_tokens:
            fits = chars
        else:
            too_long = chars
    return compaction.truncate_middle(text, fits)


def _tokens(request: Sequence[ChatMessage]) -> int:
    return approximate_token_estimate(request, []).tokens


def _text_tokens(text: str) -> int:
    """Return the tokens a text adds to a message that holds it."""
    return _tokens([ChatMessage.user(text)]) - _EMPTY_MESSAGE_TOKENS


@dataclass(frozen=True)
class RequestText:
    """What the agent was asked, as it is shown to a summarizer.

    Attributes:
    ----------
    instructions : str
        The text of the system messages the model is sent, the bound prompt included.
    request : str
        The text of the user messages of the request.
    """

    instructions: str
    request: str


def _request_text(
    shown: Sequence[ChatMessage], policy: ContextManagementPolicy
) -> RequestText:
    """Return what the agent was asked.

    These are the instructions and the user messages among ``shown``, the messages
    the chat model is sent for the request. The summarizer needs both to tell what
    the request still needs. All user messages are taken, also those that come after
    the rounds to summarize, because the notes have to serve the latest of them.
    """
    # Each part may take an eighth of the budget, so that both leave room for the
    # notes and the rounds whatever their script.
    max_tokens = min(
        _MAX_TASK_TOKENS, int(policy.max_input_tokens * _TASK_BUDGET_SHARE)
    )
    return RequestText(
        _text(shown, MessageRole.SYSTEM, max_tokens),
        _text(shown, MessageRole.USER, max_tokens),
    )


def _text(messages: Sequence[ChatMessage], role: MessageRole, max_tokens: int) -> str:
    text = "\n".join(
        message.text for message in messages if message.role == role and message.text
    )
    return _cut_to_tokens(text, max_tokens, 1) or ""


def summarizer_request(
    asked: RequestText, rendered_rounds: str, word_limit: int
) -> List[ChatMessage]:
    """Return the request that asks a summarizer for the lines of some rounds."""
    body = ""
    if asked.instructions:
        body += f"The agent's instructions, for context only:\n{asked.instructions}\n\n"
    if asked.request:
        body += (
            "The request the agent is working on, for context only:\n"
            f"{asked.request}\n\n"
        )
    body += (
        f"Rounds to write notes for:\n{rendered_rounds}\n"
        f"Write one line for each round above, at most {word_limit} words each. "
        "Use only what the rounds above contain."
    )
    return [ChatMessage.system(SUMMARIZER_INSTRUCTIONS), ChatMessage.user(body)]


async def _call_summarizer(
    summarizer: "BaseChatModelSetup | None",
    unavailable: str | None,
    summarizer_name: str,
    request: List[ChatMessage],
    ctx: "RunnerContext",
) -> str:
    """Ask the summarizer for the notes.

    The call is one durable call with its own LLM span and token metrics. The request
    is sent as it is: without the chat model's bound prompt, its tools and the
    parameters that only apply to a request with tools.

    A summarizer that cannot be used still takes the durable call, which records why,
    and no LLM span is reported: no model is called.

    Args:
        summarizer: The chat model that writes the notes, or None when it cannot be
            used.
        unavailable: Why the summarizer cannot be used; the durable call records it
            as the outcome.
        summarizer_name: The name of the summarizer's chat model resource.
        request: The request that asks for the notes.
        ctx: The runner context of the chat action.

    Raises:
        _SummaryFailure: If the call fails or returns no usable text.
    """
    # The chat action imports this module, so its helpers are imported here.
    from flink_agents.plan.actions.chat_model_action import (
        _reject_incomplete_response,
        _report_successful_response,
    )

    def invoke(request: List[ChatMessage]) -> tuple["ChatResult | None", str | None]:
        if summarizer is None:
            return None, unavailable
        try:
            return summarizer.chat_without_tools(request), None
        except (CancelledError, InterruptedError):
            raise
        except Exception as error:
            _logger.debug("Summarizer call failed", exc_info=True)
            return None, _error_text(error)

    llm_metadata = (
        {}
        if summarizer is None or summarizer.model is None
        else {LLMExecutionMetadataKeys.MODEL: summarizer.model}
    )
    execution_id = (
        None
        if summarizer is None
        else ExecutionReporters.started(
            ctx, ExecutionEntityTypes.LLM, summarizer_name, llm_metadata
        )
    )
    # A failure of durable execution itself is not a failed summary: it propagates.
    if ctx.config.get(AgentExecutionOptions.CHAT_ASYNC):
        response, failure = await ctx.durable_execute_async(
            invoke, request, durable_id=SUMMARIZE_CALL_ID
        )
    else:
        response, failure = ctx.durable_execute(
            invoke, request, durable_id=SUMMARIZE_CALL_ID
        )
    if failure is None and response is None:
        failure = "the chat model returned no response"
    if failure is not None:
        if execution_id is not None:
            ExecutionReporters.failed(
                ctx,
                execution_id,
                ExecutionEntityTypes.LLM,
                summarizer_name,
                llm_metadata,
                _SummarizerCallFailure(failure),
                ExecutionProblemCategories.MODEL_CALL_FAILED,
            )
        raise _SummaryFailure(failure)
    # Reported and vetted as a chat call's response is. A recorded response can be
    # replayed while the summarizer cannot be resolved; it is then used without a
    # span of its own.
    if execution_id is not None:
        _report_successful_response(
            ctx,
            execution_id,
            summarizer_name,
            llm_metadata,
            summarizer,
            response,
            ctx.action_metric_group,
        )
    try:
        _reject_incomplete_response(response)
    except ValueError as incomplete:
        msg = f"the summary is incomplete (finish_reason='{response.finish_reason}')"
        raise _SummaryFailure(msg) from incomplete
    summary = response.text.strip()
    if not summary:
        msg = "the chat model returned no text"
        raise _SummaryFailure(msg)
    return summary
