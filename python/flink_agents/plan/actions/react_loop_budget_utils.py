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
"""Manage per-loop budget state in sensory memory."""

import logging
import time
from datetime import timedelta
from uuid import UUID

from flink_agents.api.chat_result import ChatResult
from flink_agents.api.core_options import ReActLoopBudgetOptions
from flink_agents.api.events.chat_event import ChatRequestEvent
from flink_agents.api.memory_object import MemoryObject
from flink_agents.api.react_loop_budget import BudgetMode, ReActLoopBudget
from flink_agents.api.runner_context import RunnerContext

LOOP_BUDGET_CONTEXT = "_LOOP_BUDGET_CONTEXT"
_TURN_BUDGET_EXCEEDED = "TURN_BUDGET_EXCEEDED"
_TIME_BUDGET_EXCEEDED = "TIME_BUDGET_EXCEEDED"
_TOKEN_BUDGET_EXCEEDED = "TOKEN_BUDGET_EXCEEDED"

_TURNS_LEFT = "turns_left"
_TOKENS_LEFT = "tokens_left"
_MAX_TIME_MS = "max_time_ms"
_STARTED_AT_MS = "started_at_ms"
_MODE = "mode"
_logger = logging.getLogger(__name__)


def _budget_start_time_ms() -> int:
    """Return a replayable loop start time before the first model call."""
    return time.time_ns() // 1_000_000


def initialize(ctx: RunnerContext, event: ChatRequestEvent) -> None:
    """Initialize configured limits, preserving any existing loop state."""
    if get_budget_state(ctx.sensory_memory, event.id) is not None:
        return
    limits = _resolve_budget(ctx, event)
    budget_state = {}
    if limits.max_turns is not None:
        budget_state[_TURNS_LEFT] = limits.max_turns
    if limits.max_tokens is not None:
        budget_state[_TOKENS_LEFT] = limits.max_tokens
    if limits.max_time is not None:
        budget_state[_MAX_TIME_MS] = limits.max_time // timedelta(milliseconds=1)
        budget_state[_STARTED_AT_MS] = ctx.durable_execute(
            _budget_start_time_ms, durable_id="react-loop:budget-start"
        )
    if budget_state:
        budget_state[_MODE] = limits.mode.value
        save_budget_state(ctx.sensory_memory, event.id, budget_state)


def _resolve_budget(ctx: RunnerContext, event: ChatRequestEvent) -> ReActLoopBudget:
    """Use the request budget or resolve the job-level defaults."""
    limits = event.budget
    if limits is None:
        config = ctx.config
        limits = ReActLoopBudget(
            max_turns=config.get(ReActLoopBudgetOptions.LOOP_MAX_TURNS),
            max_tokens=config.get(ReActLoopBudgetOptions.LOOP_MAX_TOKENS),
            max_time=config.get(ReActLoopBudgetOptions.LOOP_MAX_TIME),
            mode=config.get(ReActLoopBudgetOptions.LOOP_BUDGET_MODE),
        )
    return limits


def _get_budget_contexts(memory: MemoryObject) -> dict:
    """Read the request-keyed budget contexts from sensory memory."""
    return memory.get(LOOP_BUDGET_CONTEXT) or {}


def get_budget_state(memory: MemoryObject, request_id: UUID) -> dict | None:
    """Read the budget state for one loop, if configured."""
    return _get_budget_contexts(memory).get(str(request_id))


def save_budget_state(
    memory: MemoryObject, request_id: UUID, budget_state: dict
) -> None:
    """Save one loop state without mutating existing snapshots."""
    contexts = dict(_get_budget_contexts(memory))
    contexts[str(request_id)] = dict(budget_state)
    memory.set(LOOP_BUDGET_CONTEXT, contexts)


def is_soft_limit(memory: MemoryObject, request_id: UUID) -> bool:
    """Return whether the loop uses soft budget enforcement."""
    budget_state = get_budget_state(memory, request_id)
    return budget_state is not None and budget_state[_MODE] == BudgetMode.SOFT.value


def try_start_turn(
    memory: MemoryObject,
    request_id: UUID,
    now_millis: int | None = None,
) -> str | None:
    """Check all limits and consume one turn when ordinary inference may start."""
    budget_state = get_budget_state(memory, request_id)
    if budget_state is None:
        return None
    max_time_ms = budget_state.get(_MAX_TIME_MS)
    if max_time_ms is not None:
        if now_millis is None:
            now_millis = time.time_ns() // 1_000_000
        elapsed = max(0, now_millis - budget_state[_STARTED_AT_MS])
        if elapsed >= max_time_ms:
            return _TIME_BUDGET_EXCEEDED
    if budget_state.get(_TOKENS_LEFT) == 0:
        return _TOKEN_BUDGET_EXCEEDED
    turns_left = budget_state.get(_TURNS_LEFT)
    if turns_left is not None:
        if turns_left == 0:
            return _TURN_BUDGET_EXCEEDED
        budget_state = dict(budget_state)
        budget_state[_TURNS_LEFT] = turns_left - 1
        save_budget_state(memory, request_id, budget_state)
    return None


def update_token_budget_state(
    memory: MemoryObject,
    request_id: UUID,
    response: ChatResult | None,
) -> None:
    """Deduct reported input and output tokens from the loop budget."""
    budget_state = get_budget_state(memory, request_id)
    if (
        not budget_state
        or _TOKENS_LEFT not in budget_state
        or not isinstance(response, ChatResult)
    ):
        return
    usage = response.usage
    if usage is None or (
        usage.prompt_tokens is None and usage.completion_tokens is None
    ):
        _logger.warning("Missing token usage for chat request %s", request_id)
        return
    budget_state = dict(budget_state)
    budget_state[_TOKENS_LEFT] = max(
        0,
        budget_state[_TOKENS_LEFT]
        - (usage.prompt_tokens or 0)
        - (usage.completion_tokens or 0),
    )
    save_budget_state(memory, request_id, budget_state)
