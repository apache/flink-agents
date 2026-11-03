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
################################################################################
"""Await failures must not leave handles pending at action finalization."""

import asyncio
from concurrent.futures import CancelledError
from typing import Any

import pytest
from pydantic import PrivateAttr

from flink_agents.api.subagent import CALLABLE_NAME_PREFIX, SubagentResult
from flink_agents.plan.actions.tool_call_action import process_tool_request
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.exceptions import UncapturableException
from flink_agents.plan.tests.actions.test_tool_call_action_subagent import (
    _Context,
    _request,
)
from flink_agents.runtime.async_subagent import BaseAsyncSubagentSetup, RunStatus
from flink_agents.runtime.deferred_subagent import DeferredSubagentSetup
from flink_agents.runtime.internal_subagent import InternalSubagentSetup
from flink_agents.runtime.tests.test_base_subagent import _FakeTask, _run


class _AwaitContext(_Context):
    def durable_execute_async(self, func, *args: Any, **kwargs: Any) -> Any:
        kwargs.pop("durable_id", None)
        kwargs.pop("reconciler", None)

        async def execute() -> Any:
            return func(*args, **kwargs)

        return execute()


class _DeferredSetup(DeferredSubagentSetup):
    _failure: BaseException | None = PrivateAttr(default=None)
    _stage: str = PrivateAttr(default="call")

    def prepare(self, ctx, prompt, session_id, call_id) -> tuple:
        if self._stage == "prepare":
            raise self._failure

        def call() -> SubagentResult:
            if self._failure is not None:
                raise self._failure
            return SubagentResult.error("remote declined")

        return f"{session_id}#{call_id}", call, None


class _AsyncSetup(BaseAsyncSubagentSetup):
    _failure: BaseException | None = PrivateAttr(default=None)
    _stage: str = PrivateAttr(default="status")

    def call_submit_request(self, session_id, call_id, prompt) -> None:
        pass

    def call_query_status(self, session_id, call_id) -> RunStatus:
        if self._failure is not None and self._stage == "status":
            raise self._failure
        return RunStatus.completed()

    def call_fetch_result(self, session_id, call_id) -> SubagentResult:
        if self._failure is not None:
            raise self._failure
        return SubagentResult.error("remote declined")


def _setup(mode, stage, failure) -> _DeferredSetup | _AsyncSetup:
    setup = _DeferredSetup() if mode == "deferred" else _AsyncSetup()
    setup._stage = stage
    setup._failure = failure
    setup.set_subagent_name("reviewer")
    return setup


@pytest.mark.parametrize("parallel", [False, True])
@pytest.mark.parametrize(
    ("mode", "stage"),
    [
        ("deferred", "prepare"),
        ("deferred", "call"),
        ("async", "status"),
        ("async", "fetch"),
    ],
)
@pytest.mark.parametrize("raises", [False, True])
def test_model_handled_failure_allows_action_finalization(
    mode, stage, parallel, raises
):
    failure = RuntimeError("remote unavailable") if raises else None
    # A prepare failure has no result-returning variant.
    setup = _setup(mode, stage if raises else "call", failure)
    task = _FakeTask(action_name="tool_call_action")
    setup.on_action_prepared(task)
    ctx = _AwaitContext().with_agent("reviewer", setup)
    if parallel:
        ctx.with_parallel_tool_calls()
    request = _request(
        "model",
        [
            {
                "id": f"call-{i}",
                "function": {
                    "name": CALLABLE_NAME_PREFIX + "reviewer",
                    "arguments": {"code": "hello"},
                },
            }
            for i in range(2)
        ],
    )

    asyncio.run(process_tool_request(request, ctx))

    response = ctx.sent_events[0]
    for i in range(2):
        assert response.success[f"call-{i}"] is False
        assert (
            "remote unavailable" if raises else "remote declined"
        ) in response.error[f"call-{i}"]
    # This is the lifecycle check that used to fail after dispatch returned.
    setup.on_action_finishing(task)


@pytest.mark.parametrize(
    ("mode", "stage"),
    [
        ("deferred", "prepare"),
        ("deferred", "call"),
        ("async", "status"),
        ("async", "fetch"),
    ],
)
@pytest.mark.parametrize(
    "failure_type",
    [RuntimeError, CancelledError, asyncio.CancelledError, InterruptedError],
)
def test_direct_await_preserves_exception_and_releases_handle(
    mode, stage, failure_type
):
    failure = failure_type("await failed")
    setup = _setup(mode, stage, failure)
    task = _FakeTask()
    setup.on_action_prepared(task)
    future = _run(setup.submit(_AwaitContext(), "hello"))
    with pytest.raises(failure_type) as caught:
        _run(future)
    assert caught.value is failure
    setup.on_action_finishing(task)


class _InternalFaultContext(_AwaitContext):
    def __init__(self, stage) -> None:
        super().__init__()
        self.stage = stage
        self.fault = RuntimeError("framework operation failed")

    def bootstrap_internal_subagent_call(
        self, scope, session_id, call_id, prompt
    ) -> None:
        if self.stage == "bootstrap":
            raise self.fault

    def try_complete_internal_subagent_call(
        self, session_id, call_id
    ) -> SubagentResult | None:
        raise self.fault


@pytest.mark.parametrize("parallel", [False, True])
@pytest.mark.parametrize("stage", ["bootstrap", "completion"])
def test_internal_framework_fault_reaches_caller_without_tool_response(parallel, stage):
    setup = InternalSubagentSetup(plan=AgentPlan(actions={}), scope="reviewer")
    setup.set_subagent_name("reviewer")
    task = _FakeTask(action_name="tool_call_action")
    setup.on_action_prepared(task)
    ctx = _InternalFaultContext(stage).with_agent("reviewer", setup)
    if parallel:
        ctx.with_parallel_tool_calls()
    request = _request(
        "model",
        [
            {
                "id": f"call-{i}",
                "function": {
                    "name": CALLABLE_NAME_PREFIX + "reviewer",
                    "arguments": {"input": "hello"},
                },
            }
            for i in range(2)
        ],
    )
    with pytest.raises(UncapturableException) as caught:
        asyncio.run(process_tool_request(request, ctx))
    assert caught.value.__cause__ is ctx.fault
    assert ctx.sent_events == []
