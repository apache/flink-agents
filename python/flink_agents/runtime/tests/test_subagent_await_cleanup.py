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
from threading import Event
from typing import Any

import pytest
from pydantic import PrivateAttr

from flink_agents.api.subagent import SubagentCall, SubagentResult
from flink_agents.plan.actions.tool_call_action import process_tool_request
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.exceptions import UncapturableException
from flink_agents.plan.subagent_constants import TOOL_NAME_PREFIX
from flink_agents.plan.tests.actions.test_tool_call_action_subagent import (
    _Context,
    _request,
)
from flink_agents.runtime.async_subagent import BaseAsyncSubagentSetup, RunStatus
from flink_agents.runtime.deferred_subagent import DeferredSubagentSetup
from flink_agents.runtime.internal_subagent import InternalSubagentSetup
from flink_agents.runtime.tests.future_test_utils import (
    close_runtime_contexts,  # noqa: F401
)
from flink_agents.runtime.tests.test_base_subagent import _FakeTask, _run


class _AwaitContext(_Context):
    pass


class _DeferredSetup(DeferredSubagentSetup):
    input_schema: str | None = '{"type":"object"}'
    _failure: BaseException | None = PrivateAttr(default=None)
    _stage: str = PrivateAttr(default="call")

    def prepare(self, ctx, prompt, session_id, call_id) -> SubagentCall:
        if self._stage == "prepare":
            raise self._failure

        def call() -> SubagentResult:
            if self._failure is not None:
                raise self._failure
            return SubagentResult.error("remote declined")

        return SubagentCall(call)


class _AsyncSetup(BaseAsyncSubagentSetup):
    input_schema: str | None = '{"type":"object"}'
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
    ("mode", "stage", "raises"),
    [
        ("deferred", "prepare", True),
        ("deferred", "call", True),
        ("async", "status", True),
        ("async", "fetch", True),
        ("deferred", "call", False),
        ("async", "fetch", False),
    ],
)
def test_model_handled_failure_allows_action_finalization(
    mode, stage, parallel, raises
):
    failure = RuntimeError("remote unavailable") if raises else None
    setup = _setup(mode, stage, failure)
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
                    "name": TOOL_NAME_PREFIX + "reviewer",
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
        self.fault = RuntimeError("framework operation failed")
        from flink_agents.runtime.tests.future_test_utils import SubgraphBridge

        bridge = SubgraphBridge()
        self._j_runner_context = bridge

        def fail(*args: Any) -> None:
            raise self.fault

        if stage == "bootstrap":
            bridge.prepareInternalSubagentCall = fail
        else:
            bridge.isSubgraphCallReady = lambda identity: True
            bridge.finishSubgraphCall = fail


@pytest.mark.parametrize("parallel", [False, True])
@pytest.mark.parametrize("stage", ["bootstrap", "completion"])
def test_internal_framework_fault_reaches_caller_without_tool_response(parallel, stage):
    setup = InternalSubagentSetup(
        plan=AgentPlan(actions={}), scope="reviewer", input_schema='{"type":"object"}'
    )
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
                    "name": TOOL_NAME_PREFIX + "reviewer",
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


class _PreparingAsyncSetup(_AsyncSetup):
    _prepares: int = PrivateAttr(default=0)

    def submit_request(self, ctx, session_id, call_id, prompt) -> Any:
        self._prepares += 1
        raise self._failure


class _PreparingDeferredSetup(_DeferredSetup):
    _prepares: int = PrivateAttr(default=0)

    def prepare(self, ctx, prompt, session_id, call_id) -> Any:
        self._prepares += 1
        raise self._failure


@pytest.mark.parametrize("mode", ["deferred", "async"])
@pytest.mark.parametrize("framework_fault", [False, True])
def test_preparation_failure_uses_reserved_slot_and_terminal_replay_skips_preparation(
    mode, framework_fault
):
    from flink_agents.runtime.tests.future_test_utils import RuntimeContext
    from flink_agents.runtime.tests.test_flink_runner_context_reconcilable import (
        _FakeJavaRunnerContext,
    )

    setup = _PreparingDeferredSetup() if mode == "deferred" else _PreparingAsyncSetup()
    setup._failure = (
        UncapturableException("framework unavailable")
        if framework_fault
        else ValueError("request preparation failed")
    )
    store = _FakeJavaRunnerContext()
    for _ in range(2):
        store.current_call_index = 0
        ctx = RuntimeContext(store)

        async def invoke(context=ctx) -> None:
            future = await setup.submit(context, "hello", "session", "call")
            await future

        with pytest.raises(type(setup._failure), match=str(setup._failure)):
            _run(invoke())
        assert [call.function_id for call in store.call_results] == ["session#call"]
        assert store.call_results[0].status == (
            "PENDING" if framework_fault else "FAILED"
        )
    assert setup._prepares == (2 if framework_fault else 1)


@pytest.mark.parametrize("gather", [False, True])
def test_cancelling_queued_subagent_ends_in_progress_await(gather):
    from flink_agents.runtime.tests.test_flink_runner_context_reconcilable import (
        _close_runner_context,
        _create_runner_context,
        _FakeJavaRunnerContext,
    )

    calls = []

    class QueuedSetup(_DeferredSetup):
        def prepare(self, ctx, prompt, session_id, call_id) -> SubagentCall:
            def call() -> SubagentResult:
                calls.append(prompt)
                return SubagentResult.ok("done")

            return SubagentCall(call)

    setup = QueuedSetup()
    setup.set_subagent_name("reviewer")
    task = _FakeTask()
    setup.on_action_prepared(task)
    store = _FakeJavaRunnerContext()
    ctx = _create_runner_context(store, executor_workers=1)
    release_worker = Event()
    blocker = ctx.executor.submit(release_worker.wait)
    future = _run(setup.submit(ctx, "hello"))
    waiter = (ctx.gather(future) if gather else future).__await__()
    try:
        # The only worker is occupied, so this await submits the call but yields
        # before it can execute. Resume this same await after cancellation.
        next(waiter)
        future.cancel()
        with pytest.raises(CancelledError):
            next(waiter)
        setup.on_action_finishing(task)
        assert [slot.status for slot in store.call_results] == ["PENDING"]
    finally:
        waiter.close()
        release_worker.set()
        blocker.result(timeout=5)
        _close_runner_context(ctx)
    assert calls == []
