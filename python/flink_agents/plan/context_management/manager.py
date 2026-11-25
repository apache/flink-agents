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
own policy when the request carries one, the lines of the summary and how many rounds
they cover, and the factor that corrects approximate token estimates. It lives in
sensory memory at a path of the request's own under ``STATE_KEY``, so requests of one
key that overlap do not write each other's state, and it is emptied when the request
completes.

Truncation and masking are pure functions of the stored messages, so they add no
durable call and a replay sends the same messages. With an input budget, the input of
a model call is estimated when a round is due for a summary, as one durable call under
the id ``ESTIMATE_CALL_ID``: estimating renders the chat model's bound prompt, which
can be a remote call, and a provider can count the tokens itself. A summary is one
durable call per batch under the id ``SUMMARIZE_CALL_ID``, made before the round's
model call. Whether a summary is written is decided from the stored messages, the kept
state and the recorded estimate, so a replay reaches the same decision and reuses the
recorded summary.

The summary has one line for each summarized round. A summarizer writes the lines of
the rounds it is shown, only those lines are kept, and they are added to the summary:
a line is written once, from its own round, and nothing rewrites it. Lines are used
from the model call on whose messages are smaller with them than without, both
measured by their size; until then they are held. A round gets no line when a line
could not stand for it or would save nothing: it loads a skill, it returned media, or
its results are already masked. Such a round is passed over and stays in the messages.
"""

import logging
import math
import re
from concurrent.futures import CancelledError
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, Dict, List, Mapping, Sequence, Set, cast

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelSetup,
    provider_instructions,
)
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
from flink_agents.plan.actions.utils import support_async
from flink_agents.plan.context_management import compaction
from flink_agents.plan.exceptions import find_java_exception, raise_if_uncapturable
from flink_agents.plan.resource.java.java_chat_model import (
    JavaChatModelConnection,
    JavaChatModelSetup,
)

if TYPE_CHECKING:
    from uuid import UUID

    from flink_agents.api.chat_result import ChatResult, TokenUsage
    from flink_agents.api.memory_object import MemoryObject
    from flink_agents.api.runner_context import RunnerContext

# Sensory-memory key under which the state of each request has a path of its own.
STATE_KEY = "_CONTEXT_MANAGEMENT_CONTEXT"
# Durable call id of the estimate of a model call's input.
ESTIMATE_CALL_ID = "estimate"
# Durable call id of a summarizer call.
SUMMARIZE_CALL_ID = "summarize"

_POLICY = "policy"
_SUMMARY = "summary"
_PENDING_SUMMARY = "pending_summary"
_WRITTEN_ROUNDS = "written_rounds"
_FAILED_STEPS = "failed_steps"
_RETRY_AT_ROUNDS = "retry_at_rounds"
_UNUSABLE_ANSWERS = "unusable_answers"
_CALIBRATION = "calibration"
_CALIBRATION_TOKENS = "calibration_tokens"
_CALIBRATION_AT_LARGEST = "calibration_at_largest"

# Bounds of the factor by which a reported input size raises an approximate estimate.
# The factor never lowers an estimate: some providers report cached input tokens apart
# from the input size, so a reported size can be smaller than the request, and an
# estimate that is too low is the harmful direction.
_MIN_CALIBRATION = 1.0
_MAX_CALIBRATION = 4.0
# Java failures that are an interruption and not a failure of a provider. A network
# timeout, java.io.InterruptedIOException, is left out: it is an ordinary failure.
# These are the failures that durable execution keeps out of its record.
_JAVA_INTERRUPTIONS = frozenset(
    {"java.lang.InterruptedException", "java.nio.channels.ClosedByInterruptException"}
)
# The most tokens of the instructions, and of the request text, shown to a
# summarizer.
_MAX_TASK_TOKENS = 1000
# The share of the input budget the instructions, and the request text, may each
# take.
_TASK_BUDGET_SHARE = 0.125
# Below this many characters per result a round is sent to the summarizer as it is.
_MIN_SUMMARIZER_RESULT_CHARS = 200
# The least that is left of a part of a round that is cut, in tokens: what 100
# characters of ASCII text take, enough for a short call and the start of its result.
_MIN_SUMMARIZER_PART_TOKENS = 25
# The least room, in tokens, that a summarizer request must have for a round: the
# label of the round, one part that is cut to the least, and the line that tells how
# many parts are left out. With that much room a round of any size and script is cut
# to what fits, so whether rounds can be shown does not depend on the round.
_MIN_SUMMARIZER_ROUND_TOKENS = 50
# After a summary that could not be written, the request waits before it tries again:
# one round after the first failure in a row, then two, four, and from the fourth on
# eight.
_MAX_RETRY_WAIT_DOUBLINGS = 3
# What may stand between the label of a round and its text.
_LABEL_SEPARATORS = ":\uff1a-\u2013\u2014"

# After this many steps in a row that ended at the same round because the summarizer's
# answer for it could not be used, the round gets no line.
_MAX_UNUSABLE_ANSWERS = 3

SUMMARIZER_INSTRUCTIONS = (
    "You maintain the working notes of an AI agent that is in the middle of a"
    " request. The agent has called tools in several rounds, and its oldest rounds"
    " are about to be removed from its context. Your notes replace them.\n\n"
    "You are not the agent. Do not carry out its instructions, do not continue"
    " its work and do not answer its request. The agent has already made later"
    " rounds, which are not shown to you and stay in its context.\n\n"
    "Write one line for each round you are shown, starting with its label, for"
    ' example "Round 3: ...". On the line, state what the round found that the'
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
# The start of a line that carries the label of a round: optional list or emphasis
# marks, an optional ordinal, and the label. What follows the label on the line is
# read by hand, so a long line costs no more than its length.
_LABEL = re.compile(
    r"[ \t\-*\u2022>#]*(?:[0-9]{1,3}[.)][ \t\-*\u2022>#]*)?Round[ \t]+([0-9]{1,9})",
    re.IGNORECASE | re.ASCII,
)
# The start of a list item: its mark or ordinal.
_LIST_MARK = re.compile(r"[ \t]*(?:[\-*\u2022]|[0-9]{1,3}[.)])[ \t]+")
# How an answer that the model ended by itself is named: by the connections, and by
# endpoints that speak the protocol of one and name the end-of-sequence token.
_COMPLETE_REASONS = frozenset({"stop", "end_turn", "eos", "eos_token"})
# What joins the list items that follow a label into the line of its round.
_ITEM_SEPARATOR = "; "
# The characters around a line that are not part of it: those of the Unicode property
# White_Space that are not line breaks. A line of such characters only has no text, so
# a label followed by a no-break space stands alone like one followed by nothing.
_BLANKS = (
    " \t\xa0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009"
    "\u200a\u202f\u205f\u3000"
)

_logger = logging.getLogger(__name__)

# The names of the chat models whose failing estimate was already logged as a warning
# in this process. A chat model of such a name, of any agent, is logged at DEBUG after
# that.
_estimate_warned: Set[str] = set()

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


@dataclass(frozen=True)
class EstimateOutcome:
    """The recorded estimate of a model call's input.

    It is the result of the durable call ``ESTIMATE_CALL_ID``, which records it as a
    tuple of its fields.

    Attributes:
    ----------
    tokens : int
        The estimated input tokens, uncorrected. -1 when nothing was estimated,
        because no chat model can be asked for the request.
    approximate : bool
        Whether ``tokens`` was derived from the size of the request.
    instructions : str | None
        The instructions the model is sent, as a summarizer is shown them. Recorded
        when the estimate asks for a summary of more rounds, None otherwise.
    request : str | None
        The text of the request's user messages, recorded with ``instructions``.
    """

    tokens: int
    approximate: bool
    instructions: str | None = None
    request: str | None = None


class _SummaryFailure(Exception):
    """A summary that could not be written; the request goes on without it.

    ``unusable_answer`` tells whether the summarizer answered and the answer cannot be
    used for the rounds it was asked about: it has no line for one of them, it is
    empty, or the provider did not finish it. Such a failure is about those rounds and
    not about the summarizer as a whole.
    """

    def __init__(self, message: str, *, unusable_answer: bool = False) -> None:
        super().__init__(message)
        self.unusable_answer = unusable_answer


class _SummarizerCallFailure(Exception):
    """The textual failure of a summarizer call, as reported on its LLM span."""


@dataclass(frozen=True)
class _SummaryStep:
    """What one summarization step did.

    ``start`` and ``folded`` are how many rounds had their line or were passed over
    before and after the step. ``summary`` is all lines after the step, held ones
    included, None when there is none.
    ``error`` tells why the step stopped before its target, None when it reached it.
    ``unusable_answer`` tells whether it stopped because the answer for one round
    could not be used.
    ``added`` is how many rounds got their line in the step; rounds that stay get
    none. ``held_lines`` is how many lines are held after the step, written in it or
    before.
    """

    summarizer: str
    start: int
    folded: int
    summary: str | None
    error: str | None
    unusable_answer: bool
    added: int
    held_lines: int


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


def clear_request(sensory_memory: "MemoryObject", request_id: "UUID") -> None:
    """Remove what is kept for a request.

    Memory has no way to delete a field, so the state is replaced by an empty one;
    sensory memory drops it with the run.
    """
    if _read_state(sensory_memory, request_id):
        _write_state(sensory_memory, request_id, {})


async def prepare(
    request_id: "UUID",
    model: str,
    messages: List[ChatMessage],
    prompt_args: Mapping[str, Any] | None,
    ctx: "RunnerContext",
    *,
    first_call: bool,
    candidates: Sequence[str],
    output_schema: Any,
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
        candidates: The chat model resources the call can go to, in the order in
            which they are tried: ``model``, then those a router falls back to.
        output_schema: The output schema of the request, or None.
    """
    # Only tool-call rounds are ever changed. A request that has none yet is sent as
    # it is, whatever its policy: nothing is read for it and nothing can fail.
    if not compaction.has_round(messages):
        return Prepared(messages)
    memory = ctx.sensory_memory
    state = _read_state(memory, request_id)
    policy = _policy_of(state, ctx)
    # Without a policy the rounds are not listed. A list with a message that is None
    # is sent as it is too: the model call reports it, as it does without a policy.
    if not policy.is_enabled or any(message is None for message in messages):
        return Prepared(messages)
    rounds = compaction.find_rounds(messages)

    view = compaction.view(messages, policy, state.get(_SUMMARY), rounds)
    raw_estimate = -1
    approximate = False
    budget = policy.max_input_tokens
    if budget is not None:
        target = len(rounds) - policy.effective_summarization_keep_last_rounds
        masked = compaction.masked_rounds(
            len(rounds),
            policy.tool_result_keep_last_rounds,
            policy.effective_tool_result_mask_step_rounds,
        )
        # The first round that is due for its line: rounds that get none are passed
        # over. How far that went is kept, so that such a round is looked at once and
        # not before every model call.
        written = _written_rounds(state)
        due = written
        full = state.get(_PENDING_SUMMARY) is not None and (
            _room_for_lines(
                joined(state.get(_SUMMARY), state.get(_PENDING_SUMMARY)),
                note_word_limit(budget),
                budget,
                state.get(_CALIBRATION),
            )
            == 0
        )
        while due < target and _gets_no_line(
            messages, rounds, due, masked, policy, held_lines_fill_the_notes=full
        ):
            due += 1
        if due > written:
            state[_WRITTEN_ROUNDS] = due
            # A count of answers that could not be used was for a round that is
            # behind now.
            state.pop(_UNUSABLE_ANSWERS, None)
            _write_state(memory, request_id, state)
        # The input is estimated only when a round is due for its line, and after a
        # summary that could not be written not before the round the request waits
        # for.
        if due < target and len(rounds) >= _retry_at_rounds(state):
            chat_model = _resolve_chat_model(ctx, model)
            calibration = state.get(_CALIBRATION)
            trigger = policy.effective_summarization_trigger_ratio * budget
            # A request that no chat model can be asked for fails before its model
            # call. No provider is asked anything for it: that is recorded in place of
            # an estimate, which is then below any trigger.
            estimate = await _estimate(
                model,
                chat_model,
                view.messages,
                messages,
                prompt_args,
                policy,
                ctx,
                calibration=calibration,
                asked_above=trigger,
                attemptable=_can_be_attempted(ctx, candidates, output_schema),
            )
            estimated = _corrected(
                estimate.tokens, calibration, approximate=estimate.approximate
            )
            raw_estimate = estimate.tokens
            approximate = estimate.approximate
            if estimated > trigger:
                step = await _summarize(
                    request_id,
                    model,
                    policy,
                    messages,
                    rounds,
                    RequestText(estimate.instructions or "", estimate.request or ""),
                    calibration,
                    masked,
                    state,
                    ctx,
                )
                estimated_after = None
                held_lines = step.held_lines
                if step.added > 0:
                    # The lines are used from the call on whose messages are smaller
                    # with them than without: a summary of short rounds can be longer
                    # than the rounds. Until then they are held, and their rounds are
                    # asked about no more. Both lists are measured the same way, by
                    # their size. What else the request holds, its prompt and tools,
                    # is the same for both, and a count that a provider makes may be
                    # had for one of them and not for the other.
                    candidate = compaction.view(messages, policy, step.summary, rounds)
                    shown = _tokens(view.messages)
                    sent = _tokens(candidate.messages)
                    if sent < shown:
                        state[_SUMMARY] = step.summary
                        state.pop(_PENDING_SUMMARY, None)
                        view = candidate
                        held_lines = 0
                        raw_estimate = _estimate_after(estimate.tokens, shown, sent)
                        estimated_after = _corrected(
                            raw_estimate, calibration, approximate=approximate
                        )
                retry_changed = _record_outcome(
                    state, len(rounds), failed=step.error is not None
                )
                passed_over = _pass_over_unusable_round(state, step)
                if passed_over is not None:
                    # What held the steps up is behind now, so the rounds after it
                    # are tried with the next model call. The count of failed steps
                    # in a row stays: when the answers for those rounds cannot be
                    # used either, the request waits longer between its attempts, as
                    # it does for a summarizer that keeps failing.
                    state.pop(_RETRY_AT_ROUNDS, None)
                    _logger.warning(
                        "Chat request %s: round %d gets no line, because the answers "
                        "of '%s' for it could not be used in %d steps in a row. It "
                        "stays in the messages.",
                        request_id,
                        passed_over,
                        step.summarizer,
                        _MAX_UNUSABLE_ANSWERS,
                    )
                if step.folded > step.start or retry_changed:
                    # New lines, rounds that were passed over, or a wait that
                    # changed. A count of answers that could not be used changes
                    # only with the wait.
                    _write_state(memory, request_id, state)
                # A round was due, so the step asked the summarizer or failed before
                # it could.
                _report_summary_step(
                    request_id,
                    model,
                    policy,
                    step,
                    ctx,
                    shown=_line_count(state.get(_SUMMARY)),
                    held_lines=held_lines,
                    passed_over=passed_over,
                    tokens_before=estimated,
                    approximate=approximate,
                    tokens_after=estimated_after,
                )
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
    estimates. The factor that is kept is the larger of two: that of the call with
    the largest reported input, and that of the latest call. Only approximate
    estimates are corrected, and only upward.
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
    # So a call with a larger reported input replaces the factor of the largest call,
    # also with a smaller one. A call that reports less does not: the provider can
    # have served it from its cache. The factor of the latest call counts while it is
    # the larger of the two: the input has become shorter, by a summary for example,
    # and the fixed part weighs more in it.
    largest = state.get(_CALIBRATION_TOKENS)
    at_largest = state.get(_CALIBRATION_AT_LARGEST)
    if (
        not isinstance(largest, int)
        or not isinstance(at_largest, int | float)
        or prompt_tokens >= largest
    ):
        largest, at_largest = prompt_tokens, factor
    kept = {
        _CALIBRATION: max(at_largest, factor),
        _CALIBRATION_TOKENS: largest,
        _CALIBRATION_AT_LARGEST: at_largest,
    }
    if all(state.get(key) == value for key, value in kept.items()):
        # Nothing that would change: the state is not written.
        return
    state.update(kept)
    _write_state(sensory_memory, request_id, state)


# ── Policy and state ────────────────────────────────────────────────────


def _policy_of(state: Dict[str, Any], ctx: "RunnerContext") -> ContextManagementPolicy:
    own = state.get(_POLICY)
    if isinstance(own, dict):
        return ContextManagementPolicy.model_validate(own)
    return ContextManagementPolicy.from_configuration(ctx.config)


def _written_rounds(state: Dict[str, Any]) -> int:
    """Return how many of the request's oldest rounds have been gone through.

    Each of them has its line, sent or held, or gets none.
    """
    rounds = state.get(_WRITTEN_ROUNDS)
    return rounds if isinstance(rounds, int) else 0


def _retry_at_rounds(state: Dict[str, Any]) -> int:
    """Return the number of rounds from which the request tries a summary again.

    It is 0 without a wait.
    """
    rounds = state.get(_RETRY_AT_ROUNDS)
    return rounds if isinstance(rounds, int) else 0


def _record_outcome(state: Dict[str, Any], rounds: int, *, failed: bool) -> bool:
    """Keep how a summarization step ended, for the wait before the next one.

    A step that fails makes the request wait: one round after the first failure in a
    row, then two, four, and from the fourth on eight, so a summarizer that keeps
    failing is not called for every round. A step that does not fail ends the wait.

    Args:
        state: The state of the request, which is updated.
        rounds: How many rounds the request has.
        failed: Whether the step failed.

    Returns:
        Whether the state changed.
    """
    if not failed:
        waited = state.pop(_FAILED_STEPS, None) is not None
        return state.pop(_RETRY_AT_ROUNDS, None) is not None or waited
    before = state.get(_FAILED_STEPS)
    failures = (before if isinstance(before, int) else 0) + 1
    state[_FAILED_STEPS] = failures
    state[_RETRY_AT_ROUNDS] = rounds + (
        1 << min(failures - 1, _MAX_RETRY_WAIT_DOUBLINGS)
    )
    return True


def _pass_over_unusable_round(state: Dict[str, Any], step: _SummaryStep) -> int | None:
    """Count the steps in a row that ended at one round with an unusable answer.

    With the third such step the round gets no line: it is passed over and stays in
    the messages, so that one round cannot hold up every round after it. A step that
    fails for another reason, such as a summarizer that cannot be reached, says
    nothing about the round and starts the count again.

    Returns:
        The number, from 1, of the round that is passed over, or None.
    """
    if not step.unusable_answer:
        state.pop(_UNUSABLE_ANSWERS, None)
        return None
    before = state.get(_UNUSABLE_ANSWERS)
    # A step that moved on before it stopped is at another round than the step before
    # it.
    same_round = step.folded == step.start and isinstance(before, int)
    answers = (before if same_round else 0) + 1
    if answers < _MAX_UNUSABLE_ANSWERS:
        state[_UNUSABLE_ANSWERS] = answers
        return None
    state.pop(_UNUSABLE_ANSWERS, None)
    state[_WRITTEN_ROUNDS] = step.folded + 1
    return step.folded + 1


def _gets_no_line(
    messages: Sequence[ChatMessage],
    rounds: Sequence[compaction.Round],
    index: int,
    masked: int,
    policy: ContextManagementPolicy,
    *,
    held_lines_fill_the_notes: bool = False,
) -> bool:
    """Tell whether a round gets no line.

    One that stays while the rounds around it are summarized gets none, see
    ``compaction.stays_when_summarized``. Neither does a short round in two cases. A
    round is short when no more is sent of it than a line of the fewest words may
    take, whatever the budget.

    - The round is past the masking boundary: its results are placeholders by then,
      and a line would cost a call to the summarizer without making the round
      shorter. A round past the boundary that is still longer, because of its
      assistant message or of results that are not masked, is due like any other.
    - The notes have no room, and lines are held because the messages were no
      smaller with them. A line for another short round would be held like them, and
      asking for it would give those lines up for nothing.

    Args:
        messages: The stored messages of the request.
        rounds: The rounds of ``messages``.
        index: The index of the round.
        masked: How many of the oldest rounds are past the masking boundary.
        policy: The policy of the request.
        held_lines_fill_the_notes: Whether the notes have no room for another line
            and hold lines that are not sent.
    """
    round_ = rounds[index]
    if compaction.stays_when_summarized(messages, round_):
        return True
    past_boundary = index < masked
    if not past_boundary and not held_lines_fill_the_notes:
        return False
    sent = compaction.sent_round(
        messages,
        round_,
        index,
        policy.tool_result_max_chars,
        past_masking_boundary=past_boundary,
    )
    return _tokens(sent) <= _line_tokens(_MIN_NOTE_WORDS)


def _line_tokens(words: int) -> int:
    """Return the most tokens the line of a round takes in the notes, with its label."""
    return words * _NOTE_TOKENS_PER_WORD + _NOTE_LABEL_TOKENS


def _rethrow_unless_provider_failure(error: Exception) -> None:
    """Raise what is not a failure of a provider.

    A fault of the framework and an interruption of a Java call must not be recorded
    as the outcome of a call or absorbed as a missing estimate, and durable execution
    records neither. A Java call that was cancelled is a failed call, as it is for
    the chat call: durable execution records every other failure, so a cancellation
    raised for it here would be recorded and raised again after every recovery.
    """
    raise_if_uncapturable(error)
    if find_java_exception(error, _JAVA_INTERRUPTIONS) is not None:
        raise error


def state_path(request_id: "UUID") -> str:
    """Return the sensory-memory path of a request's state.

    Every request has a path of its own: an action writes only the paths it sets, so
    two requests of one key that overlap cannot replace each other's state, as they
    could with one value for all requests.
    """
    return f"{STATE_KEY}.{request_id}"


def _read_state(memory: "MemoryObject", request_id: "UUID") -> Dict[str, Any]:
    """Return the state of a request as a copy that can be changed.

    The copy is empty when nothing is kept.
    """
    state = memory.get(state_path(request_id))
    return dict(state) if isinstance(state, Mapping) else {}


def _write_state(
    memory: "MemoryObject", request_id: "UUID", state: Dict[str, Any]
) -> None:
    memory.set(state_path(request_id), state)


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
    except Exception as error:
        _rethrow_unless_provider_failure(error)
        # The model call itself reports a chat model that cannot be resolved.
        return None


def _can_be_attempted(
    ctx: "RunnerContext", candidates: Sequence[str], output_schema: Any
) -> bool:
    """Tell whether a chat model can be asked for a request at all.

    A candidate can when it resolves and, with an output schema, can be asked for
    it. These are the checks a model attempt makes before its call, so a request
    none of whose candidates passes them fails without a model call.
    """
    for candidate in candidates:
        chat_model = _resolve_chat_model(ctx, candidate)
        if chat_model is None:
            continue
        if output_schema is None:
            return True
        try:
            chat_model.will_apply_native_structured_output(output_schema)
        except (CancelledError, InterruptedError):
            raise
        except Exception as error:
            _rethrow_unless_provider_failure(error)
            continue
        return True
    return False


async def _estimate(
    model: str,
    chat_model: "BaseChatModelSetup | None",
    sent: Sequence[ChatMessage],
    stored: Sequence[ChatMessage],
    prompt_args: Mapping[str, Any] | None,
    policy: ContextManagementPolicy,
    ctx: "RunnerContext",
    *,
    calibration: float | None,
    asked_above: float,
    attemptable: bool,
) -> EstimateOutcome:
    """Estimate the input of a model call as one durable call.

    The estimate is recorded, so a recovery decides from the same number. Estimating
    renders the chat model's bound prompt, which is a remote call for a prompt served
    by an MCP server, and a connection may ask its provider for the count; neither is
    repeated after a recovery, and with ``chat.async`` neither runs on the operator's
    thread. The prompt is rendered a second time in this call when a summary is due,
    for what the summarizer is told about the request.

    Args:
        model: The name of the chat model resource.
        chat_model: The chat model of the request, or None when it cannot be
            resolved.
        sent: The messages the model would be sent.
        stored: The stored messages of the request.
        prompt_args: The request's prompt arguments.
        policy: The policy of the request.
        ctx: The runner context of the chat action.
        calibration: The factor that corrects an approximate estimate.
        asked_above: When the corrected estimate is above this many tokens, what the
            agent was asked is recorded with it for the summarizer.
        attemptable: Whether a chat model can be asked for the request. When none
            can, nothing is estimated, and that is what is recorded: a recovery
            then goes the way the first run went, whatever the chat models say by
            then.
    """

    def invoke() -> tuple[int, bool, str | None, str | None]:
        if not attemptable:
            return -1, False, None, None
        estimate = _estimate_now(model, chat_model, sent, prompt_args)
        tokens, approximate = estimate.tokens, estimate.is_approximate
        if _corrected(tokens, calibration, approximate=approximate) <= asked_above:
            return tokens, approximate, None, None
        asked = _request_text(
            _with_provider_instructions(
                chat_model, _request_messages(chat_model, stored, prompt_args)
            ),
            policy,
            calibration,
        )
        return tokens, approximate, asked.instructions, asked.request

    if _runs_async(ctx, chat_model):
        recorded = await ctx.durable_execute_async(invoke, durable_id=ESTIMATE_CALL_ID)
    else:
        recorded = ctx.durable_execute(invoke, durable_id=ESTIMATE_CALL_ID)
    return EstimateOutcome(*recorded)


def _runs_async(ctx: "RunnerContext", chat_model: "BaseChatModelSetup | None") -> bool:
    """Tell whether a durable call that uses a chat model leaves the operator's thread.

    It follows ``chat.async``, as the chat call does. A call that reaches a Java
    resource, the chat model or its connection, is made on the operator's thread
    where the Flink version cannot make it from another thread.
    """
    if not ctx.config.get(AgentExecutionOptions.CHAT_ASYNC):
        return False
    return support_async() or not _reaches_java(ctx, chat_model)


def _reaches_java(
    ctx: "RunnerContext", chat_model: "BaseChatModelSetup | None"
) -> bool:
    """Tell whether a call of a chat model reaches a Java resource.

    That is the chat model itself or its connection. The bound prompt and tools of a
    chat model that is declared in Python are Python resources.

    The connection is looked up where the chat model looked it up when it was opened,
    in its own resource context: a chat model that an agent inherits is bound to the
    connection of the agent that declares it, whatever the name stands for here.
    """
    if chat_model is None:
        return False
    if isinstance(chat_model, JavaChatModelSetup):
        return True
    connection = getattr(chat_model, "connection", None)
    if not isinstance(connection, str):
        return False
    resources = getattr(chat_model, "resource_context", None) or ctx
    try:
        resolved = resources.get_resource(
            connection, ResourceType.CHAT_MODEL_CONNECTION
        )
    except (CancelledError, InterruptedError):
        raise
    except Exception as error:
        _rethrow_unless_provider_failure(error)
        # The call itself reports a connection that cannot be resolved.
        return False
    return isinstance(resolved, JavaChatModelConnection)


def _estimate_now(
    model: str,
    chat_model: "BaseChatModelSetup | None",
    messages: Sequence[ChatMessage],
    prompt_args: Mapping[str, Any] | None,
) -> TokenEstimate:
    """Return the chat model's estimate of the request it would send.

    The approximation is used when the chat model cannot give an estimate: its
    estimate fails or is not a ``TokenEstimate``.
    """
    if chat_model is None:
        return approximate_token_estimate(messages, [])
    try:
        # A list of the chat model's own: what a chat model does with the list it is
        # handed, put a message into it for example, must not reach the messages the
        # request is managed with, nor the ones it is sent.
        estimate = chat_model.estimate_input_tokens(list(messages), prompt_args)
        if isinstance(estimate, TokenEstimate):
            return estimate
        _warn_once(
            model, f"returned {type(estimate).__name__} instead of a TokenEstimate"
        )
    except (CancelledError, InterruptedError):
        raise
    except Exception as error:
        _rethrow_unless_provider_failure(error)
        _warn_once(model, f"could not estimate its input ({_error_text(error)})")
        _logger.debug("The failed estimate of chat model '%s'", model, exc_info=True)
    tools = getattr(chat_model, "tools", None)
    bound = [tool for tool in tools if isinstance(tool, Tool)] if tools else []
    # What the chat model's connection counts by default: the request it sends, the
    # instructions it gives its provider beside the messages, and its tools.
    request = _with_provider_instructions(
        chat_model, _request_messages(chat_model, messages, prompt_args)
    )
    try:
        return approximate_token_estimate(request, bound)
    except (CancelledError, InterruptedError):
        raise
    except Exception as error:
        _rethrow_unless_provider_failure(error)
        # A tool that cannot be measured: the request is counted without the tools,
        # so that the estimate a model call falls back on does not fail it.
        _logger.debug("The tools of chat model '%s'", model, exc_info=True)
        return approximate_token_estimate(request, [])


def _warn_once(model: str, what: str) -> bool:
    """Log a failing estimate of a chat model as a warning once.

    It is not logged for every model call.

    Returns:
        Whether the warning was logged.
    """
    if model in _estimate_warned:
        return False
    _estimate_warned.add(model)
    _logger.warning(
        "Chat model '%s' %s; the approximation is used for it. This is logged once.",
        model,
        what,
    )
    return True


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
        # A list of the chat model's own, as for its estimate.
        return chat_model.prepare_request_messages(list(messages), prompt_args)
    except (CancelledError, InterruptedError):
        raise
    except Exception as error:
        _rethrow_unless_provider_failure(error)
        _logger.debug(
            "Chat model could not prepare its request; using the request's messages",
            exc_info=True,
        )
        return messages


def _with_provider_instructions(
    chat_model: "BaseChatModelSetup | None", messages: Sequence[ChatMessage]
) -> Sequence[ChatMessage]:
    """Return the messages, led by the instructions a chat model gives its provider.

    These are instructions that the chat model passes as a parameter and not as a
    message. A summarizer is shown them like the instructions of a bound prompt.
    """
    if chat_model is None:
        return messages
    try:
        instructions = provider_instructions(chat_model.model_kwargs)
    except (CancelledError, InterruptedError):
        raise
    except Exception as error:
        _rethrow_unless_provider_failure(error)
        return messages
    if instructions is None:
        return messages
    return [ChatMessage.system(instructions), *messages]


def _estimate_after(before: int, shown: int, sent: int) -> int:
    """Return the estimate of a request whose messages went from ``shown`` to ``sent``.

    Both are tokens of the approximation. ``before`` is the estimate of the request
    with the messages that take ``shown``: these, the chat model's prompt and its
    tools.

    An estimate on the scale of the approximation is lowered by what the messages
    save. One that counts fewer tokens than the approximation does for the messages
    alone is on another scale, that of a chat model that counts tokens itself for
    example. It is lowered by the same share as the messages, so that the difference
    cannot take more off than the estimate has.
    """
    if before < shown:
        return before * sent // shown
    return before - (shown - sent)


def _corrected(tokens: int, calibration: float | None, *, approximate: bool) -> int:
    if not approximate or calibration is None:
        return tokens
    return math.ceil(tokens * calibration)


def _error_text(error: Exception) -> str:
    """Return the text of a failure, also for one whose own text cannot be had."""
    try:
        message = str(error)
    except Exception:
        message = "<message unavailable>"
    return f"{type(error).__name__}: {message}"


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
    # The round tells two results apart whose calls have the same id: a provider can
    # number the calls of each answer from the start.
    details: Dict[str, Any] = {"round": change.round + 1, "call_id": change.call_id}
    if change.tool is not None:
        details["tool"] = change.tool
    return details


# ── Summarization ───────────────────────────────────────────────────────


async def _summarize(
    request_id: "UUID",
    model: str,
    policy: ContextManagementPolicy,
    messages: Sequence[ChatMessage],
    rounds: List[compaction.Round],
    asked: "RequestText",
    calibration: float | None,
    masked: int,
    state: Dict[str, Any],
    ctx: "RunnerContext",
) -> _SummaryStep:
    """Write the lines of the rounds before the last kept ones.

    The summarizer is asked for the line of each round, in batches that fit the input
    budget. The new lines are held in ``state``; the caller decides whether the model
    is sent them.

    Args:
        request_id: The id of the initial chat request.
        model: The chat model resource of the request.
        policy: The policy of the request.
        messages: The stored messages of the request.
        rounds: The rounds of ``messages``.
        asked: What the agent was asked, as recorded with the estimate that asked
            for the step.
        calibration: The factor that corrects the request's approximate estimates.
        masked: How many of the oldest rounds are masked.
        state: The state of the request, which is updated.
        ctx: The runner context of the chat action.
    """
    start = _written_rounds(state)
    target = len(rounds) - policy.effective_summarization_keep_last_rounds
    summarizer_name = model if policy.summarizer is None else policy.summarizer

    folded = start
    added = 0
    sent = state.get(_SUMMARY)
    # The lines that are held: those an earlier step left, and those this step writes.
    held_before = state.get(_PENDING_SUMMARY)
    written = None
    # All lines written so far: those the model is sent and those that are held.
    notes = joined(sent, held_before)
    error = None
    unusable_answer = False
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
        budget = policy.max_input_tokens
        words = note_word_limit(budget)
        # The most rounds one request shows. When the answer for several rounds
        # cannot be used, the rounds of that answer are asked about one at a time,
        # which are the rounds before ``single_until``, and the requests after them
        # show half as many rounds as before.
        most = _MAX_BATCH_ROUNDS
        single_until = 0
        while folded < target:
            # The notes take a line for each round. Only as many rounds are asked
            # about as the notes have room for, so every line that is written can be
            # kept, and without room no round is rendered at all.
            room = _room_for_lines(notes, words, budget, calibration)
            if _gets_no_line(
                messages,
                rounds,
                folded,
                masked,
                policy,
                held_lines_fill_the_notes=room == 0 and held_before is not None,
            ):
                # Passed over: it stays in the messages.
                folded += 1
                state[_WRITTEN_ROUNDS] = folded
                continue
            if room == 0 and held_before is not None:
                # Lines that an earlier step left held were never sent, because the
                # messages were no smaller with them, and here they take the room of
                # lines that could be. They are given up: their rounds stay in the
                # messages and are not asked about again. The lines of this step
                # stay: the caller has yet to look at what they save.
                held_before = None
                notes = joined(sent, written)
                if written is None:
                    state.pop(_PENDING_SUMMARY, None)
                else:
                    state[_PENDING_SUMMARY] = written
                room = _room_for_lines(notes, words, budget, calibration)
            _require_room(room, notes, budget, calibration)
            end, shown, request = _next_batch(
                messages,
                rounds,
                folded,
                target,
                asked,
                words,
                min(room, 1 if folded < single_until else most),
                calibration,
                masked,
                policy,
            )
            try:
                answer = await _call_summarizer(
                    summarizer, unavailable, summarizer_name, request, ctx
                )
                lines = round_notes(answer, shown, words)
            except _SummaryFailure as failure:
                if not failure.unusable_answer or len(shown) <= 1:
                    raise
                # An answer for several rounds that leaves one out, or that the
                # provider did not finish, for example at the summarizer's output
                # limit. Nothing is kept of it, and its rounds are asked about again,
                # one at a time. A summarizer that could not answer for so many
                # rounds is then asked about fewer.
                single_until = end
                most = max(1, most // 2)
                continue
            written = joined(written, lines)
            notes = joined(notes, lines)
            added += len(shown)
            state[_PENDING_SUMMARY] = joined(held_before, written)
            folded = end
            state[_WRITTEN_ROUNDS] = folded
    except _SummaryFailure as failure:
        error = str(failure)
        unusable_answer = failure.unusable_answer
        _logger.warning(
            "Chat request %s: rounds %d to %d could not be summarized by '%s': %s. "
            "The request continues without the summary.",
            request_id,
            folded + 1,
            target,
            summarizer_name,
            error,
        )
    return _SummaryStep(
        summarizer_name,
        start,
        folded,
        notes,
        error,
        unusable_answer,
        added,
        _line_count(joined(held_before, written)),
    )


def _line_count(notes: str | None) -> int:
    """Return how many lines some notes have."""
    return 0 if notes is None else notes.count("\n") + 1


def _notes_tokens(budget: int, calibration: float | None) -> int:
    """Return the most tokens the notes of a request may take, with their heading.

    That is half of the input budget, in tokens of the approximation with the
    correction of the request's estimates.
    """
    return int(budget / (calibration or 1.0) * _NOTES_BUDGET_SHARE)


def _summary_tokens(notes: str | None) -> int:
    """Return what the notes take in the messages, with their heading."""
    return _text_tokens(compaction.summary_text(notes or ""))


def note_word_limit(max_input_tokens: int) -> int:
    """Return how many words a summarizer is asked to keep the line of a round within.

    That is more with a larger budget, whose rounds tend to hold more.
    """
    return max(
        _MIN_NOTE_WORDS,
        min(_MAX_NOTE_WORDS, max_input_tokens // _BUDGET_TOKENS_PER_NOTE_WORD),
    )


def _room_for_lines(
    notes: str | None, words: int, budget: int, calibration: float | None
) -> int:
    """Return how many more lines the notes have room for.

    At most the rounds of one summarizer request are counted. A line counts with the
    most tokens it can take, so the lines of that many rounds always fit.
    """
    left = _notes_tokens(budget, calibration) - _summary_tokens(notes)
    return max(0, min(_MAX_BATCH_ROUNDS, left // _line_tokens(words)))


def _require_room(
    room: int, notes: str | None, budget: int, calibration: float | None
) -> None:
    """Fail the summary when the notes have no room for the line of another round.

    Raises:
        _SummaryFailure: If ``room`` is 0.
    """
    if room == 0 and notes is None:
        # Nothing was written yet: the budget is too small, not the notes too long.
        msg = (
            f"the summary has no room for a line: it may take "
            f"{_notes_tokens(budget, calibration)} estimated tokens with "
            f"max_input_tokens={budget}, and its heading takes "
            f"{_summary_tokens(None)} of them"
        )
        raise _SummaryFailure(msg)
    if room == 0:
        msg = (
            f"the summary is full: it takes {_summary_tokens(notes)} of the "
            f"{_notes_tokens(budget, calibration)} estimated tokens it may take with "
            f"max_input_tokens={budget}, which leaves no room for the line of another "
            "round"
        )
        raise _SummaryFailure(msg)


def joined(notes: str | None, lines: str | None) -> str | None:
    """Return the notes with the lines of more rounds added.

    The lines of earlier rounds stay as they are: nothing rewrites them.
    """
    if notes is None:
        return lines
    return notes if lines is None else f"{notes}\n{lines}"


def round_notes(answer: str, round_numbers: Sequence[int], words: int) -> str:
    """Return the lines of a summarizer's answer that are the notes on some rounds.

    There is one line for each round of ``round_numbers``, in order. Every other line
    of the answer is left out: a line for a round that was not shown, a total, a
    conclusion. A line that is far longer than asked is cut.

    A line is the label of a round, a colon or a dash, and the text after it. The
    label can follow a list mark, an ordinal or emphasis marks. When a label stands
    alone on its line, its text is the list items that follow it, joined into one
    line. A plain line after such a label is not taken: it can as well be a total or
    a conclusion, which must stay out of the notes.

    Raises:
        _SummaryFailure: If the answer has no line for one of the rounds.
    """
    answer_lines = _LINE_BREAK.split(answer)
    lines: Dict[int, str] = {}
    index = 0
    while index < len(answer_lines):
        label = _label(answer_lines[index])
        index += 1
        if label is None:
            continue
        number, text = label
        if not text:
            parts = []
            while index < len(answer_lines):
                line = answer_lines[index]
                if not line.strip(_BLANKS):
                    index += 1
                    continue
                if _label(line) is not None:
                    break
                mark = _LIST_MARK.match(line)
                if mark is None:
                    break
                part = line[mark.end() :].strip(_BLANKS)
                if part:
                    parts.append(part)
                index += 1
            text = _ITEM_SEPARATOR.join(parts)
        if text:
            lines.setdefault(number, text)
    max_tokens = words * _NOTE_TOKENS_PER_WORD
    notes = []
    for number in round_numbers:
        text = lines.get(number)
        if text is None:
            msg = f"the summarizer wrote no line for round {number}"
            raise _SummaryFailure(msg, unusable_answer=True)
        # The marker of a cut has line breaks of its own; the note stays one line.
        cut = cast("str", _cut_to_tokens(text, max_tokens, 1)).replace("\n", " ")
        notes.append(compaction.note_line(number, cut))
    return "\n".join(notes)


def _label(line: str) -> tuple[int, str] | None:
    """Read a line as the label of a round.

    A label line is the label, then nothing, or a colon or a dash and the text.
    Emphasis marks around the colon are passed over.

    Returns:
        The number of the round and the text on the line, which is empty when the
        label stands alone, or None when the line is not one.
    """
    match = _LABEL.match(line)
    if match is None:
        return None
    at = _skip(line, match.end())
    if at < len(line):
        if line[at] not in _LABEL_SEPARATORS:
            return None
        # Emphasis around the label closes right after the separator. An asterisk
        # that follows a blank belongs to the text.
        at += 1
        while at < len(line) and line[at] == "*":
            at += 1
    return int(match.group(1)), line[at:].strip(_BLANKS)


def _skip(line: str, start: int) -> int:
    """Return the index of the first character that is not blank or emphasis."""
    at = start
    while at < len(line) and (line[at] in _BLANKS or line[at] == "*"):
        at += 1
    return at


def _report_summary_step(
    request_id: "UUID",
    model: str,
    policy: ContextManagementPolicy,
    step: _SummaryStep,
    ctx: "RunnerContext",
    *,
    shown: int,
    held_lines: int,
    passed_over: int | None,
    tokens_before: int,
    approximate: bool,
    tokens_after: int | None,
) -> None:
    """Report a summarization step, whether it wrote lines or failed.

    Args:
        request_id: The id of the initial chat request.
        model: The chat model resource of the request.
        policy: The policy of the request.
        step: What the step did.
        ctx: The runner context of the chat action.
        shown: How many rounds the summary that is sent has a line for after the
            step.
        held_lines: How many lines are held after the step.
        passed_over: The number, from 1, of the round that gets no line from this
            step on because its answers could not be used; None when there is none.
        tokens_before: The estimated input that asked for the step.
        approximate: Whether that estimate is an approximation.
        tokens_after: The estimated input with the lines of the step: the estimate
            before, less what the lines save by the size of the messages; None when
            the model is not sent them.
    """
    details: Dict[str, Any] = {
        "summarizer": step.summarizer,
        "rounds": step.added,
        "summarized_rounds": shown,
    }
    if held_lines > 0:
        # Lines that are held: the messages would not be smaller with them.
        details["pending_rounds"] = held_lines
    details.update(
        {
            "max_input_tokens": policy.max_input_tokens,
            "estimated_input_tokens_before": tokens_before,
            "estimate_approximate": approximate,
        }
    )
    if tokens_after is not None and step.summary is not None:
        details["summary_chars"] = len(step.summary)
        details["estimated_input_tokens_after"] = tokens_after
    if step.error is not None:
        details["error"] = step.error
    if passed_over is not None:
        # The round the step stopped at gets no line from now on.
        details["passed_over_round"] = passed_over
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
        _rethrow_unless_provider_failure(error)
        msg = f"chat model '{name}' cannot be used: {_error_text(error)}"
        raise _SummaryFailure(msg) from error
    if not isinstance(summarizer, BaseChatModelSetup):
        # A resource that is declared as a chat model and is something else, a
        # connection for example.
        msg = (
            f"chat model '{name}' cannot be used: it is a "
            f"{type(summarizer).__name__}, not a chat model setup"
        )
        raise _SummaryFailure(msg)
    if isinstance(summarizer, JavaChatModelSetup):
        msg = (
            f"chat model '{name}' is declared in Java, and a summary can only be "
            "written by a chat model declared in Python. Set "
            f"{AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL.get_key()}"
            " to a Python chat model"
        )
        raise _SummaryFailure(msg)
    return summarizer


def _next_batch(
    messages: Sequence[ChatMessage],
    rounds: List[compaction.Round],
    start: int,
    target: int,
    asked: "RequestText",
    words: int,
    max_rounds: int,
    calibration: float | None,
    masked: int,
    policy: ContextManagementPolicy,
) -> tuple[int, List[int], List[ChatMessage]]:
    """Take as many rounds from ``start`` on as fit the input budget.

    At least one round is taken and at most ``max_rounds``, which is at least one.
    ``start`` is the index of a round that gets a line. A round that does not fit a
    request of its own is cut until it does and is a batch by itself, so the rounds
    after it are not cut with it. A round that gets no line, see ``_gets_no_line``,
    is not shown to the summarizer; ``masked`` is how many of the oldest rounds are
    past the masking boundary.

    The request is sized by the approximation, with the correction of the request's
    own estimates (``calibration``): where a provider counts more tokens than the
    approximation, it does so for the summarizer's request too.

    Returns:
        The index after the last round of the batch, the numbers, from 1, of the
        rounds the request shows, and the request that asks the summarizer for
        their lines.

    Raises:
        _SummaryFailure: If the rest of the request leaves no room for a round.
    """
    budget = policy.max_input_tokens
    result_chars = (
        compaction.UNLIMITED_CHARS
        if policy.tool_result_max_chars is None
        else policy.tool_result_max_chars
    )
    # What the rounds may take: the budget, in tokens of the approximation, without
    # the rest of the request. Each round is counted once, so a long request is put
    # together in one pass.
    room = int(budget / (calibration or 1.0)) - _tokens(
        summarizer_request(asked, "", words)
    )
    if room < _MIN_SUMMARIZER_ROUND_TOKENS:
        # The same for every round, so that no round is tried and none holds up the
        # rounds after it.
        raise _no_room_for_a_round(budget)
    # A text takes a token for four characters at least. A result that is cut to more
    # characters than that for the room does not fit, so none is put together longer:
    # a round is rendered and counted in the size of the room, not of its results.
    result_chars = min(result_chars, room * 4)
    rendered: List[str] = []
    shown: List[int] = []
    used = 0
    end = start
    while end < target and len(shown) < max_rounds:
        round_ = rounds[end]
        if _gets_no_line(messages, rounds, end, masked, policy):
            end += 1
            continue
        # The text of the results is read once for the round, however often the
        # round is cut to fit.
        round_text = compaction.round_text(messages, round_, end + 1)
        # A round of more characters than four for each token that is left cannot fit.
        # It is not put together to find that out, however many results it has.
        text = None
        size = 0
        if _fits_by_length(round_text, result_chars, room - used):
            text = round_text.render(result_chars)
            size = _text_tokens(text)
        if text is None or used + size > room:
            if not shown:
                rendered.append(_fit(round_text, result_chars, room))
                shown.append(end + 1)
                end += 1
            break
        rendered.append(text)
        used += size
        shown.append(end + 1)
        end += 1
    return end, shown, summarizer_request(asked, "".join(rendered), words)


def _fit(round_text: compaction.RoundText, result_chars: int, room: int) -> str:
    """Return one round as text that fits ``room`` tokens.

    Its results are cut further, to the longest limit with which the round fits. When
    no limit does, long assistant text or many results for example, the parts of the
    round are cut each by itself, see ``_fit_parts``.
    """
    # What a result may have at most, whatever the round is cut to.
    most_chars = result_chars
    commentary = None if round_text.assistant is None else 1
    # Longer limits than the round can fit with by its length are not rendered.
    result_chars = _longest_by_length(round_text, result_chars, room)
    if not _fits_by_length(round_text, result_chars, room):
        # Too long with the shortest results too: long assistant text or long
        # arguments of a call for example. The round is not put together to find
        # that out.
        return _fit_parts(
            round_text.whole_parts(most_chars), room, commentary=commentary
        )
    text = round_text.render(result_chars)
    # A text that is put together is counted once.
    tokens = _text_tokens(text)
    if tokens <= room:
        return text
    if result_chars > _MIN_SUMMARIZER_RESULT_CHARS:
        # When the round does not fit with the shortest results either, no limit
        # helps: long arguments of a call for example. The limits in between are
        # then not tried.
        shortest = _MIN_SUMMARIZER_RESULT_CHARS
        if _text_tokens(round_text.render(shortest)) > room:
            return _fit_parts(
                round_text.whole_parts(most_chars), room, commentary=commentary
            )
    # The smallest limit that was tried and did not fit; 0 when the first one fits.
    too_long = 0
    while tokens > room and result_chars > _MIN_SUMMARIZER_RESULT_CHARS:
        # No result is longer than the text, so a limit above its length cuts nothing.
        too_long = min(result_chars, len(text))
        result_chars = max(_MIN_SUMMARIZER_RESULT_CHARS, too_long // 2)
        text = round_text.render(result_chars)
        tokens = _text_tokens(text)
    if tokens > room:
        return _fit_parts(
            round_text.whole_parts(most_chars), room, commentary=commentary
        )
    if too_long > 0:
        # Halving stops at the first limit that fits. The longest limit that fits lies
        # between it and the limit before it, so that the summarizer is shown as much
        # of the results as the request has room for.
        fits = result_chars
        while too_long - fits > 1:
            limit = fits + (too_long - fits) // 2
            longer = round_text.render(limit)
            if _text_tokens(longer) <= room:
                fits = limit
                text = longer
            else:
                too_long = limit
    return text


def _longest_by_length(
    round_text: compaction.RoundText, result_chars: int, room: int
) -> int:
    """Return the longest limit on the results with which the round can fit by length.

    See ``_fits_by_length``. The limit is ``result_chars`` at most. It is not lower
    than the shortest limit a round is rendered with, whether or not the round can fit
    with that one.
    """
    if _fits_by_length(round_text, result_chars, room):
        return result_chars
    fits = min(result_chars, _MIN_SUMMARIZER_RESULT_CHARS)
    too_long = result_chars
    while too_long - fits > 1:
        limit = fits + (too_long - fits) // 2
        if _fits_by_length(round_text, limit, room):
            fits = limit
        else:
            too_long = limit
    return fits


def _fits_by_length(
    round_text: compaction.RoundText, result_chars: int, room: int
) -> bool:
    """Tell whether a round can fit ``room`` tokens by its length.

    A text takes a token for four characters at least, so a round of more characters
    than that for ``room`` tokens does not fit, and it is not rendered to find that
    out.
    """
    return -(-round_text.chars(result_chars) // 4) <= room


def _fit_parts(
    parts: Sequence["str | compaction.ResultPart"],
    room: int,
    *,
    commentary: int | None,
) -> str:
    """Return the parts of a round as one text that fits ``room`` tokens.

    Each part is cut by itself, see ``_cut_parts``, so what is left of a result stays
    with the call it answers. When the parts do not fit that way, a round of very many
    results for example, parts are left out and a closing line tells how many: first
    the assistant's text, which says less about the round than a result does, then
    the last results. The least room of a request holds the label, one part and that
    line.

    Args:
        parts: The parts of the round, the label first. A result is a
            ``ResultPart`` with its whole text.
        room: The most tokens the text may take.
        commentary: The index of the part with the assistant's text, None when the
            round has none.
    """
    text = _cut_parts(parts, room)
    if text is not None:
        return text
    # The assistant's text is left out, and of the other parts the most from the
    # first on that fit beside the closing line.
    kept = [index for index in range(len(parts)) if index != commentary]
    fits, too_many = 1, len(kept) + (0 if commentary is None else 1)
    while too_many - fits > 1:
        shown = fits + (too_many - fits) // 2
        if _shown_parts(parts, kept[:shown], room) is None:
            too_many = shown
        else:
            fits = shown
    return _shown_parts(parts, kept[:fits], room) or _whole(parts[0])


def _shown_parts(
    parts: Sequence["str | compaction.ResultPart"], shown: Sequence[int], room: int
) -> str | None:
    """Return the parts at ``shown`` and a line that tells how many are left out.

    Returns None when they do not fit ``room`` tokens.
    """
    note = f"[{len(parts) - len(shown)} more lines of this round are not shown]\n"
    text = _cut_parts([parts[index] for index in shown], room - _text_tokens(note))
    return None if text is None else text + note


def _cut_parts(parts: Sequence["str | compaction.ResultPart"], room: int) -> str | None:
    """Cut each part by itself so that the parts take at most ``room`` tokens together.

    All parts get the same limit, the largest one with which they fit. A part within
    the limit stays whole, and one over it is cut: a line in its own middle, a result
    as ``_cut_result`` does. The limit is not lower than
    ``_MIN_SUMMARIZER_PART_TOKENS``, so a part that is cut still tells what it is.

    Returns:
        The parts as one text, or None when they do not fit with the lowest limit.
    """
    # A part that is certainly over the room is not counted: it is cut whatever its
    # size is, and it stands here as one token over the room.
    sizes = [_part_tokens(part, room) for part in parts]

    def cut_to(limit: int) -> str:
        return "".join(
            _whole(part) if size <= limit else _cut_part(part, limit)
            for part, size in zip(parts, sizes, strict=True)
        )

    fits = _MIN_SUMMARIZER_PART_TOKENS
    text = cut_to(fits)
    if _text_tokens(text) > room:
        return None
    # No part can get more tokens than the room, so no limit above it is tried. A
    # part that stands as one token over the room is then cut for every limit.
    too_long = max(min(max(sizes), room) + 1, fits + 1)
    while too_long - fits > 1:
        limit = fits + (too_long - fits) // 2
        longer = cut_to(limit)
        if _text_tokens(longer) <= room:
            fits = limit
            text = longer
        else:
            too_long = limit
    return text


def _whole(part: "str | compaction.ResultPart") -> str:
    """Return a part as the line it is when nothing of it is cut."""
    if isinstance(part, str):
        return part
    return f"{part.call}{part.outcome}{part.shown()}\n"


def _tokens_up_to(text: str, most: int) -> int:
    """Return the tokens of a text, without counting one that is far over ``most``.

    A text takes a token for four characters at least. One of more characters than
    that for ``most`` tokens is over it, and ``most + 1`` stands for its size.
    """
    if -(-len(text) // 4) > most:
        return most + 1
    return _text_tokens(text)


def _part_tokens(part: "str | compaction.ResultPart", most: int) -> int:
    """Return the tokens of a whole part, as ``_tokens_up_to`` does for a text.

    A result that is far over ``most`` is not put together to find that out.
    """
    if isinstance(part, str):
        return _tokens_up_to(part, most)
    chars = len(part.call) + len(part.outcome) + part.shown_chars() + 1
    if -(-chars // 4) > most:
        return most + 1
    return _text_tokens(_whole(part))


def _cut_part(part: "str | compaction.ResultPart", limit: int) -> str:
    """Cut a part that is over ``limit`` tokens so that it takes at most that many."""
    if isinstance(part, str):
        # The marker of a cut fits the lowest limit, so the cut is never None.
        return _cut_to_tokens(part, limit, 1) or part
    return _cut_result(part, limit)


def _cut_result(part: compaction.ResultPart, limit: int) -> str:
    """Cut the line of a result so that it takes at most ``limit`` tokens.

    What became of the call stays as it is: a summarizer must not take a failed call
    for one that returned. The call is cut at its end, so its name and the start of
    its arguments stay, and the result is cut in its middle, once and from the whole
    result, so that its marker counts all that is missing. Each of the two may take
    half of the line; what one of them does not need, the other one may take. The
    result is shown no longer than the limit on a tool result allows.
    """
    room = limit - _text_tokens(f"{part.outcome}\n")
    half = room // 2
    call_tokens = _tokens_up_to(part.call, room)
    # The result as the limit on a tool result leaves it.
    text_tokens = room + 1
    if -(-part.shown_chars() // 4) <= room:
        text_tokens = _text_tokens(part.shown())
    if call_tokens <= half:
        call = part.call
        text = _cut_to_tokens(part.text, room - call_tokens, 1, part.max_chars)
    elif text_tokens <= half:
        call = _cut_call(part.call, room - text_tokens)
        text = part.shown()
    else:
        call = _cut_call(part.call, half)
        text = _cut_to_tokens(part.text, room - _text_tokens(call), 1, part.max_chars)
    if text is None:
        # Not even the marker fits: the marker alone tells what is missing.
        text = compaction.truncate_middle(part.text, 0)
    return f"{call}{part.outcome}{text}\n"


# What ends a call that is cut.
_CUT_CALL_END = "...)"


def _cut_call(call: str, max_tokens: int) -> str:
    """Cut a call at its end so that it takes at most ``max_tokens``.

    The name of the tool and the start of its arguments stay, as much as fits.
    """
    fits = 0
    too_long = min(max_tokens * 4, len(call)) + 1
    while too_long - fits > 1:
        chars = fits + (too_long - fits) // 2
        if _text_tokens(call[:chars] + _CUT_CALL_END) <= max_tokens:
            fits = chars
        else:
            too_long = chars
    return call[:fits] + _CUT_CALL_END


def _no_room_for_a_round(budget: int) -> _SummaryFailure:
    """Return the failure of a summarizer request that has no room for a round.

    That is so whatever the round.
    """
    msg = (
        f"a summarizer request has no room for a round within max_input_tokens={budget}"
    )
    return _SummaryFailure(msg)


def _cut_to_tokens(
    text: str,
    max_tokens: int,
    min_chars: int,
    max_chars: int = compaction.UNLIMITED_CHARS,
) -> str | None:
    """Cut ``text`` in the middle so that it takes at most ``max_tokens``.

    As much of the text as fits is kept, and ``max_chars`` characters at most. Four
    ASCII characters are one token and any other character is one, so what fits is
    between one and four characters for a token; the longest cut that fits is searched
    for. Every cut is made from the whole text, so the marker tells how much of it is
    missing.

    Returns:
        The text that fits, or None when a cut to ``min_chars`` characters does not.
    """
    # A text takes a token for four characters at least, so one that is longer than
    # that is over the limit, and it is not counted to find that out.
    if (
        len(text) <= max_chars
        and -(-len(text) // 4) <= max_tokens
        and _text_tokens(text) <= max_tokens
    ):
        return text
    most = min(max_tokens * 4, len(text) - 1, max_chars)
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
    shown: Sequence[ChatMessage],
    policy: ContextManagementPolicy,
    calibration: float | None,
) -> RequestText:
    """Return what the agent was asked.

    These are the instructions and the user messages among ``shown``, the messages
    the chat model is sent for the request. The summarizer needs both to tell what
    the request still needs. All user messages are taken, also those that come after
    the rounds to summarize, because the notes have to serve the latest of them.
    """
    # Each part may take an eighth of the budget, so that both leave room for the
    # notes and the rounds whatever their script. The budget is the one a summarizer
    # request is put together within: in tokens of the approximation, with the
    # correction of the request's estimates.
    budget = policy.max_input_tokens / (calibration or 1.0)
    max_tokens = min(_MAX_TASK_TOKENS, int(budget * _TASK_BUDGET_SHARE))
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
        _report_successful_response,
        _require_model_response,
    )

    def invoke(request: List[ChatMessage]) -> tuple["ChatResult | None", str | None]:
        if summarizer is None:
            return None, unavailable
        try:
            response = summarizer.chat_without_tools(request)
            if response is None:
                # Recorded as no response, and reported as on the Java side.
                return None, None
            # What is not a chat result is a failed summary, recorded as one: a
            # provider that returns something else must not fail the request.
            return _require_model_response(response), None
        except (CancelledError, InterruptedError):
            raise
        except Exception as error:
            _rethrow_unless_provider_failure(error)
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
    if _runs_async(ctx, summarizer):
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
    if not _ended_by_itself(response.finish_reason):
        msg = f"the summary is incomplete (finish_reason='{response.finish_reason}')"
        raise _SummaryFailure(msg, unusable_answer=True)
    if response.tool_calls:
        # A summarizer is called without tools. An answer that asks for one all the
        # same is not a summary of the rounds, whatever text comes with it.
        msg = "the summary asks for a tool call"
        raise _SummaryFailure(msg, unusable_answer=True)
    summary = response.text
    if _is_blank(summary):
        msg = "the chat model returned no text"
        raise _SummaryFailure(msg, unusable_answer=True)
    return summary


def _ended_by_itself(finish_reason: str | None) -> bool:
    """Tell whether a finish reason says that the model ended its answer by itself.

    That is no finish reason, or ``stop`` or ``end_turn`` in any case, which is what
    the connections report for a complete answer, or ``eos`` or ``eos_token``, which
    is what some endpoints that speak the OpenAI protocol report for one. Any other
    reason is taken as an answer that was cut short: a token limit under one of its
    many names, a context window, a content filter, a pause, or ``stop_sequence``, a
    sequence of the request at which the answer stopped. A chat call takes such an
    answer as it is unless the reason is ``length`` or ``content_filter``. A summary
    must not: its last line can stop in the middle and would stand for a round all
    the same.
    """
    return not finish_reason or (
        isinstance(finish_reason, str) and finish_reason.lower() in _COMPLETE_REASONS
    )


def _is_blank(answer: str) -> bool:
    """Tell whether a summarizer's answer has no text.

    None of its lines has more than white space. That is how its lines are read, so
    the answer is judged the same way in Java and Python.
    """
    return all(not line.strip(_BLANKS) for line in _LINE_BREAK.split(answer))
