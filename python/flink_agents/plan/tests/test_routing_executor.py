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
    ROUTING_METADATA_CONTEXT,
    CandidateAttemptFailed,
    ModelRoutingExecutionException,
)
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


def test_selected_candidate_answers_and_metadata_is_stored():
    ctx, request_id = _ctx(), uuid4()
    attempt, calls = _attempts(set())
    model, result = asyncio.run(executor.execute(request_id, _route(), attempt, ctx))
    assert (model, result) == ("small", "answer from small")
    assert calls == [("small", "chat:router:small")]
    ctx.send_event.assert_not_called()
    stored = executor.response_metadata(ctx.sensory_memory, request_id)
    assert stored["final_model"] == "small"
    assert ctx.sensory_memory.get(ROUTING_METADATA_CONTEXT)[str(request_id)] == stored


def test_fallback_answer_sends_a_routing_event():
    ctx, request_id = _ctx(), uuid4()
    attempt, calls = _attempts({"small"})
    model, _ = asyncio.run(executor.execute(request_id, _route(), attempt, ctx))
    assert model == "big"
    assert [c for c, _ in calls] == ["small", "big"]
    event = ctx.send_event.call_args.args[0]
    assert isinstance(event, ModelRoutingEvent)
    assert event.selected_model == "big"
    assert event.decision_source == "fallback"
    stored = executor.response_metadata(ctx.sensory_memory, request_id)
    assert stored["fallback_attempted"] is True


def test_direct_route_stores_no_metadata():
    ctx, request_id = _ctx(), uuid4()
    attempt, calls = _attempts(set())
    asyncio.run(
        executor.execute(request_id, ResolvedModelRoute.direct("m"), attempt, ctx)
    )
    assert calls == [("m", "chat")]
    assert executor.response_metadata(ctx.sensory_memory, request_id) is None


def test_every_candidate_failing_raises_with_causes_in_order():
    ctx = _ctx()
    attempt, _ = _attempts({"small", "big"})
    with pytest.raises(ModelRoutingExecutionException) as caught:
        asyncio.run(executor.execute(uuid4(), _route(), attempt, ctx))
    assert caught.value.tried == ["small", "big"]
    assert [str(e) for e in caught.value.errors] == ["small down", "big down"]
    assert ctx.sensory_memory.get(ROUTING_METADATA_CONTEXT) is None
