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
import asyncio
from typing import Any, Callable
from unittest.mock import MagicMock
from uuid import uuid4

import pytest

from flink_agents.api.events.model_routing_event import ModelRoutingEvent
from flink_agents.plan.routing import executor
from flink_agents.plan.routing.executor import (
    CandidateAttemptFailed,
    ExecutionResult,
    ModelRoutingExecutionException,
)
from flink_agents.plan.routing.metadata import ROUTING_METADATA_CONTEXT
from flink_agents.plan.routing.resolved_route import ResolvedModelRoute


class _Memory:
    def __init__(self) -> None:
        self.data = {}

    def get(self, key: str) -> Any:
        return self.data.get(key)

    def set(self, key: str, value: Any) -> None:
        self.data[key] = value


def _ctx() -> MagicMock:
    ctx = MagicMock()
    ctx.sensory_memory = _Memory()
    return ctx


def _route(*, fallback: bool = True) -> ResolvedModelRoute:
    return ResolvedModelRoute(
        "router",
        "small",
        ["small", "big"],
        is_router=True,
        fallback_enabled=fallback,
        decision_source="strategy",
    )


def _attempts(failing: set[str]) -> tuple[Callable, list]:
    calls = []

    async def attempt(candidate: str, durable_id: str) -> str:
        calls.append((candidate, durable_id))
        if candidate in failing:
            raise CandidateAttemptFailed(RuntimeError(f"{candidate} down"))
        return f"answer from {candidate}"

    return attempt, calls


def test_selected_candidate_answers_with_its_metadata_and_stores_nothing():
    ctx, request_id = _ctx(), uuid4()
    attempt, calls = _attempts(set())
    result = asyncio.run(executor.execute(request_id, _route(), attempt, ctx))
    assert (result.model, result.attempt) == ("small", "answer from small")
    assert calls == [("small", "chat:router:small")]
    ctx.send_event.assert_not_called()
    assert result.routing_metadata["final_model"] == "small"
    assert result.routing_metadata["decision_source"] == "strategy"
    assert ctx.sensory_memory.get(ROUTING_METADATA_CONTEXT) is None


def test_fallback_answer_sends_a_routing_event():
    ctx, request_id = _ctx(), uuid4()
    attempt, calls = _attempts({"small"})
    result = asyncio.run(executor.execute(request_id, _route(), attempt, ctx))
    assert result.model == "big"
    assert [c for c, _ in calls] == ["small", "big"]
    event = ctx.send_event.call_args.args[0]
    assert isinstance(event, ModelRoutingEvent)
    assert event.selected_model == "big"
    assert event.decision_source == "fallback"
    assert result.routing_metadata["fallback_attempted"] is True
    assert result.routing_metadata["fallback_models_tried"] == ["big"]


def test_direct_route_has_no_metadata():
    ctx, request_id = _ctx(), uuid4()
    attempt, calls = _attempts(set())
    result = asyncio.run(
        executor.execute(request_id, ResolvedModelRoute.direct("m"), attempt, ctx)
    )
    assert calls == [("m", "chat")]
    assert result.routing_metadata is None


def test_every_candidate_failing_raises_with_causes_in_order():
    ctx = _ctx()
    attempt, _ = _attempts({"small", "big"})
    with pytest.raises(ModelRoutingExecutionException) as caught:
        asyncio.run(executor.execute(uuid4(), _route(), attempt, ctx))
    assert caught.value.tried == ["small", "big"]
    assert [str(e) for e in caught.value.errors] == ["small down", "big down"]
    assert ctx.sensory_memory.get(ROUTING_METADATA_CONTEXT) is None


def test_saved_block_stands_for_a_tool_round_and_is_cleared_with_the_response():
    memory, request_id = _Memory(), uuid4()
    initial = ExecutionResult(request_id, "small", "a", {"final_model": "small"})
    executor.save_routing_metadata(memory, initial)
    tool_round = ExecutionResult(request_id, "small", "b", None)
    executor.save_routing_metadata(memory, tool_round)
    assert executor.get_and_clear_routing_metadata(memory, tool_round) == {
        "final_model": "small"
    }
    assert memory.get(ROUTING_METADATA_CONTEXT) == {}


def test_a_rounds_own_block_wins_over_the_saved_one():
    memory, request_id = _Memory(), uuid4()
    executor.save_routing_metadata(
        memory, ExecutionResult(request_id, "small", "a", {"final_model": "small"})
    )
    final = ExecutionResult(request_id, "big", "b", {"final_model": "big"})
    assert executor.get_and_clear_routing_metadata(memory, final) == {
        "final_model": "big"
    }
    assert memory.get(ROUTING_METADATA_CONTEXT) == {}


def test_direct_request_has_no_block_and_leaves_memory_untouched():
    memory, request_id = _Memory(), uuid4()
    result = ExecutionResult(request_id, "m", "a", None)
    assert executor.get_and_clear_routing_metadata(memory, result) is None
    executor.clear_routing_metadata(memory, request_id)
    assert memory.get(ROUTING_METADATA_CONTEXT) is None
