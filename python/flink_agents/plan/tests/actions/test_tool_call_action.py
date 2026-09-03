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
import threading
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from typing import Any
from unittest.mock import MagicMock, patch

import pytest

from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.events.tool_event import ToolRequestEvent, ToolResponseEvent
from flink_agents.api.memory_object import MemoryObject
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import Outcome
from flink_agents.api.tools import InjectedArg, ToolExecutionMetadataProvider
from flink_agents.api.tools.tool import ToolType
from flink_agents.api.trace import (
    ExecutionEntityTypes,
    ExecutionProblemCategories,
    ExecutionReporter,
    ToolExecutionMetadataKeys,
)
from flink_agents.plan.actions import tool_call_action
from flink_agents.plan.actions.tool_call_action import process_tool_request
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.function import PythonFunction
from flink_agents.plan.tools.function_tool import FunctionTool
from flink_agents.runtime.durable_execution import durable_identity_for_call


def query_order(order_id: str, tenant_id: str) -> str:
    return f"{tenant_id}:{order_id}"


def _query_order_tool(
    injected_args: dict[str, InjectedArg] | None = None,
) -> FunctionTool:
    return FunctionTool(
        func=PythonFunction.from_callable(query_order),
        injected_args=injected_args
        or {"tenant_id": InjectedArg.from_config("tenant_id")},
    )


def _expected_durable_function_id(
    order_id: str,
    tenant_id: str = "tenant-1",
) -> str:
    tool = _query_order_tool()
    function_id, _ = durable_identity_for_call(
        tool.call,
        (),
        {"order_id": order_id, "tenant_id": tenant_id},
    )
    return function_id


class _Context:
    def __init__(
        self,
        config: Any | None = None,
        injected_args: dict[str, InjectedArg] | None = None,
        sensory_memory: Any | None = None,
        short_term_memory: Any | None = None,
    ) -> None:
        if config is None:
            self.config = AgentConfiguration({"tenant_id": "tenant-1"})
            self.config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, False)
        else:
            self.config = config
        self.injected_args = injected_args or {
            "tenant_id": InjectedArg.from_config("tenant_id")
        }
        self.sensory_memory = sensory_memory
        self.short_term_memory = short_term_memory
        self.sent_events = []
        self.durable_execute_calls = []
        self.durable_execute_async_calls = []
        self.durable_execute_all_async_calls = []
        self.durable_execute_all_async_outcomes = None

    def get_resource(self, name: str, type: ResourceType) -> FunctionTool:
        assert type == ResourceType.TOOL
        if name != "query_order":
            msg = f"Tool `{name}` does not exist."
            raise ValueError(msg)
        return FunctionTool(
            func=PythonFunction.from_callable(query_order),
            injected_args=self.injected_args,
        )

    def durable_execute(self, func: Any, *args: Any, **kwargs: Any) -> Any:
        self.durable_execute_calls.append((func, args, kwargs))
        return func(*args, **kwargs)

    async def durable_execute_async(self, func: Any, *args: Any, **kwargs: Any) -> Any:
        self.durable_execute_async_calls.append((func, args, kwargs))
        return func(*args, **kwargs)

    async def durable_execute_all_async(self, callables: list[Any]) -> list[Outcome]:
        self.durable_execute_all_async_calls.append(callables)
        if self.durable_execute_all_async_outcomes is not None:
            return self.durable_execute_all_async_outcomes
        return [
            Outcome.success(call.func(*call.args, **(call.kwargs or {})))
            for call in callables
        ]

    def send_event(self, event: Any) -> None:
        self.sent_events.append(event)


class _Memory:
    def __init__(self, values: dict[str, Any]) -> None:
        self.values = values

    def is_exist(self, path: str) -> bool:
        return path in self.values

    def get(self, path: str) -> Any:
        return self.values[path]


class _NestedMemoryObject(MemoryObject):
    def get(self, path_or_ref: Any) -> Any:
        return None

    def set(self, path: str, value: Any) -> Any:
        raise NotImplementedError

    def new_object(
        self, path: str, *, overwrite: bool = False
    ) -> "_NestedMemoryObject":
        raise NotImplementedError

    def is_exist(self, path: str) -> bool:
        return False

    def get_field_names(self) -> list[str]:
        return []

    def get_fields(self) -> dict[str, Any]:
        return {}


class _WrongConfig:
    def get(self, option: Any) -> bool:
        assert option in (
            AgentExecutionOptions.TOOL_CALL_ASYNC,
            AgentExecutionOptions.TOOL_CALL_PARALLELISM,
        )
        return False


def test_tool_call_action_injects_args_from_config_without_mutating_request() -> None:
    ctx = _Context()
    arguments = {"order_id": "order-1"}
    event = ToolRequestEvent(
        model="model",
        tool_calls=[
            {
                "id": "call-1",
                "type": "function",
                "function": {"name": "query_order", "arguments": arguments},
            }
        ],
    )

    asyncio.run(process_tool_request(event, ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.responses["call-1"] == "tenant-1:order-1"
    assert response.success["call-1"] is True
    assert response.error == {}
    assert arguments == {"order_id": "order-1"}


def test_tool_call_action_injected_arg_overrides_model_argument() -> None:
    ctx = _Context()
    arguments = {"order_id": "order-1", "tenant_id": "model-tenant"}
    event = ToolRequestEvent(
        model="model",
        tool_calls=[
            {
                "id": "call-1",
                "type": "function",
                "function": {"name": "query_order", "arguments": arguments},
            }
        ],
    )

    asyncio.run(process_tool_request(event, ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.responses["call-1"] == "tenant-1:order-1"
    assert response.success["call-1"] is True


def test_tool_call_action_injects_args_from_sensory_memory() -> None:
    ctx = _Context(
        injected_args={
            "tenant_id": InjectedArg.from_sensory_memory("request.tenant_id")
        },
        sensory_memory=_Memory({"request.tenant_id": "tenant-sensory"}),
    )

    asyncio.run(process_tool_request(tool_request(), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.responses["call-1"] == "tenant-sensory:order-1"
    assert response.success["call-1"] is True


def test_tool_call_action_injects_args_from_short_term_memory() -> None:
    ctx = _Context(
        injected_args={
            "tenant_id": InjectedArg.from_short_term_memory("session.tenant_id")
        },
        short_term_memory=_Memory({"session.tenant_id": "tenant-short"}),
    )

    asyncio.run(process_tool_request(tool_request(), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.responses["call-1"] == "tenant-short:order-1"
    assert response.success["call-1"] is True


def test_tool_call_action_reports_missing_config_injected_arg() -> None:
    ctx = _Context(AgentConfiguration({}))
    event = ToolRequestEvent(
        model="model",
        tool_calls=[
            {
                "id": "call-1",
                "type": "function",
                "function": {
                    "name": "query_order",
                    "arguments": {"order_id": "order-1"},
                },
            }
        ],
    )

    asyncio.run(process_tool_request(event, ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.responses["call-1"] == "Tool `query_order` execute failed."
    assert response.success["call-1"] is False
    assert (
        response.error["call-1"]
        == "Missing config for injected tool parameter: tenant_id"
    )


def test_tool_call_action_reports_missing_memory_path() -> None:
    ctx = _Context(
        injected_args={
            "tenant_id": InjectedArg.from_sensory_memory("request.tenant_id")
        },
        sensory_memory=_Memory({}),
    )

    asyncio.run(process_tool_request(tool_request(), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.responses["call-1"] == "Tool `query_order` execute failed."
    assert response.success["call-1"] is False
    assert (
        response.error["call-1"]
        == "Missing memory path for injected tool parameter: request.tenant_id"
    )


def test_tool_call_action_reports_nested_memory_path() -> None:
    ctx = _Context(
        injected_args={
            "tenant_id": InjectedArg.from_sensory_memory("request.tenant_id")
        },
        sensory_memory=_Memory({"request.tenant_id": _NestedMemoryObject()}),
    )

    asyncio.run(process_tool_request(tool_request(), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.responses["call-1"] == "Tool `query_order` execute failed."
    assert response.success["call-1"] is False
    assert (
        response.error["call-1"]
        == "Memory path for injected tool parameter must reference a value: request.tenant_id"
    )


def test_tool_call_action_reports_uninitialized_memory() -> None:
    ctx = _Context(
        injected_args={
            "tenant_id": InjectedArg.from_sensory_memory("request.tenant_id")
        },
    )

    asyncio.run(process_tool_request(tool_request(), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.responses["call-1"] == "Tool `query_order` execute failed."
    assert response.success["call-1"] is False
    assert (
        response.error["call-1"]
        == "Cannot inject tool parameter from sensory_memory because memory is not initialized."
    )


def test_tool_call_action_exposes_wrong_config_type() -> None:
    ctx = _Context(config=_WrongConfig())

    asyncio.run(process_tool_request(tool_request(), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.responses["call-1"] == "Tool `query_order` execute failed."
    assert response.success["call-1"] is False
    assert (
        response.error["call-1"] == "'_WrongConfig' object has no attribute 'conf_data'"
    )


def test_tool_call_action_uses_sync_execution_in_test_context() -> None:
    ctx = _Context()

    assert ctx.config.get(AgentExecutionOptions.TOOL_CALL_ASYNC) is False


def test_tool_call_action_uses_parallel_batch_for_multiple_tools() -> None:
    config = AgentConfiguration({"tenant_id": "tenant-1"})
    config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, True)
    config.set(AgentExecutionOptions.TOOL_CALL_PARALLELISM, 4)
    ctx = _Context(config=config)

    asyncio.run(process_tool_request(tool_request("call-1", "call-2"), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.responses == {
        "call-1": "tenant-1:order-call-1",
        "call-2": "tenant-1:order-call-2",
    }
    assert response.success == {"call-1": True, "call-2": True}
    assert len(ctx.durable_execute_all_async_calls) == 1
    expected_id = _expected_durable_function_id("order-call-1")
    assert [
        durable_identity_for_call(call.func, call.args, call.kwargs)[0]
        for call in ctx.durable_execute_all_async_calls[0]
    ] == [
        expected_id,
        expected_id,
    ]
    assert ctx.durable_execute_async_calls == []


def test_parallel_tool_calls_report_independent_occurrences() -> None:
    config = AgentConfiguration({"tenant_id": "tenant-1"})
    config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, True)
    config.set(AgentExecutionOptions.TOOL_CALL_PARALLELISM, 4)
    tool = MagicMock()
    tool.tool_type.return_value = ToolType.FUNCTION

    def call_tool(**kwargs: Any) -> str:
        query = kwargs["query"]
        if query != "call-1":
            message = f"{query} failed"
            raise RuntimeError(message)
        return "ok"

    tool.call = MagicMock(side_effect=call_tool)
    ctx = MagicMock(spec=ExecutionReporter)
    ctx.config = config
    ctx.get_resource = MagicMock(return_value=tool)
    sent_events = []
    ctx.send_event = MagicMock(side_effect=sent_events.append)

    async def execute_all(callables: list[Any]) -> list[Outcome]:
        outcomes = []
        for durable_call in callables:
            try:
                outcomes.append(
                    Outcome.success(
                        durable_call.func(
                            *durable_call.args, **(durable_call.kwargs or {})
                        )
                    )
                )
            except Exception as error:  # noqa: PERF203
                outcomes.append(Outcome.failure(error))
        return outcomes

    ctx.durable_execute_all_async = execute_all

    request = ToolRequestEvent(
        model="model-a",
        tool_calls=[
            {
                "id": call_id,
                "function": {
                    "name": "search",
                    "arguments": {"query": call_id},
                },
            }
            for call_id in ("call-1", "call-2", "call-3")
        ],
    )

    asyncio.run(process_tool_request(request, ctx))

    assert ctx.report_execution_started_at.call_count == 3
    assert ctx.report_execution_succeeded_at.call_count == 1
    assert ctx.report_execution_failed_at.call_count == 2
    terminal_call_ids = {
        report.args[2][ToolExecutionMetadataKeys.TOOL_CALL_ID]
        for report in (
            ctx.report_execution_succeeded_at.call_args_list
            + ctx.report_execution_failed_at.call_args_list
        )
    }
    assert terminal_call_ids == {"call-1", "call-2", "call-3"}

    response = ToolResponseEvent.from_event(sent_events[0])
    assert response.success == {
        "call-1": True,
        "call-2": False,
        "call-3": False,
    }
    assert response.error == {
        "call-2": "call-2 failed",
        "call-3": "call-3 failed",
    }


def test_response_processing_failure_does_not_repeat_occurrences() -> None:
    tool = MagicMock()
    tool.tool_type.return_value = ToolType.FUNCTION
    tool.call.return_value = "ok"
    ctx, sent_events = trace_context(tool)
    ctx.config = AgentConfiguration({})
    ctx.config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, True)
    ctx.config.set(AgentExecutionOptions.TOOL_CALL_PARALLELISM, 3)

    async def execute_all(callables: list[Any]) -> list[Outcome]:
        return [
            Outcome.success(call.func(*call.args, **(call.kwargs or {})))
            for call in callables
        ]

    ctx.durable_execute_all_async = execute_all
    with patch(
        "flink_agents.plan.actions.tool_call_action._record_outcome",
        side_effect=[None, RuntimeError("response processing failed")],
    ):
        asyncio.run(process_tool_request(parallel_trace_request(), ctx))

    assert_occurrence_reports(
        ctx, ["call-1", "call-2", "call-3"], ["call-1", "call-2", "call-3"], []
    )
    assert sent_events[0].success == dict.fromkeys(
        ["call-1", "call-2", "call-3"], False
    )


@pytest.mark.parametrize("mode", ["sync", "serial_async", "parallel"])
def test_durable_failure_is_reported_as_tool_failure(mode: str) -> None:
    failure = RuntimeError("persist failed")
    tool = MagicMock()
    tool.tool_type.return_value = ToolType.FUNCTION
    tool.call.return_value = "ok"
    ctx, sent_events = trace_context(tool)
    ctx.config = AgentConfiguration({})
    ctx.config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, mode != "sync")
    ctx.config.set(
        AgentExecutionOptions.TOOL_CALL_PARALLELISM, 3 if mode == "parallel" else 1
    )

    def execute(func: Any, **kwargs: Any) -> Any:
        func(**kwargs)
        raise failure

    async def execute_async(func: Any, **kwargs: Any) -> Any:
        return execute(func, **kwargs)

    async def execute_all(callables: list[Any]) -> list[Outcome]:
        outcomes = []
        for call in callables:
            result = call.func(*call.args, **(call.kwargs or {}))
            outcomes.append(
                Outcome.failure(failure)
                if call.kwargs["query"] == "call-2"
                else Outcome.success(result)
            )
        return outcomes

    ctx.durable_execute = execute
    ctx.durable_execute_async = execute_async
    ctx.durable_execute_all_async = execute_all

    asyncio.run(process_tool_request(parallel_trace_request(), ctx))

    assert_occurrence_reports(
        ctx,
        ["call-1", "call-2", "call-3"],
        ["call-1", "call-3"] if mode == "parallel" else [],
        ["call-2"] if mode == "parallel" else ["call-1", "call-2", "call-3"],
    )
    assert all(
        call.args[3] is failure
        for call in ctx.report_execution_failed_at.call_args_list
    )
    assert sent_events[0].success["call-2"] is False
    assert sent_events[0].error["call-2"] == "persist failed"


def test_timeout_reports_failure_without_repeating_on_late_completion() -> None:
    failure = TimeoutError("request timed out")
    started = threading.Event()
    release = threading.Event()
    tool = MagicMock()
    tool.tool_type.return_value = ToolType.FUNCTION

    def call_tool(**kwargs: Any) -> str:
        started.set()
        assert release.wait(5)
        return "ok"

    tool.call.side_effect = call_tool
    ctx, sent_events = trace_context(tool)
    ctx.config = AgentConfiguration({})
    ctx.config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, True)
    ctx.config.set(AgentExecutionOptions.TOOL_CALL_PARALLELISM, 1)
    worker = ThreadPoolExecutor(max_workers=1)
    pending = []
    reporting_started_at = []
    ctx.report_execution_started_at.side_effect = (
        lambda *args: reporting_started_at.append(datetime.now(timezone.utc))
    )

    async def execute_async(func: Any, **kwargs: Any) -> Any:
        pending.append(worker.submit(func, **kwargs))
        assert started.wait(5)
        raise failure

    ctx.durable_execute_async = execute_async
    try:
        asyncio.run(
            process_tool_request(
                ToolRequestEvent(model="model-a", tool_calls=[trace_tool_call()]), ctx
            )
        )
        assert_occurrence_reports(ctx, ["call-1"], [], ["call-1"])
        assert ctx.report_execution_failed_at.call_args.args[3] is failure
        finished_at = ctx.report_execution_failed_at.call_args.args[-1]
        assert (
            datetime.fromisoformat(finished_at.replace("Z", "+00:00"))
            <= (reporting_started_at[0])
        )
        assert sent_events[0].success["call-1"] is False
    finally:
        release.set()
        worker.shutdown(wait=True)

    assert pending[0].result() == "ok"
    assert_occurrence_reports(ctx, ["call-1"], [], ["call-1"])


@pytest.mark.parametrize("complete_during_reporting", [False, True])
def test_parallel_timeout_timestamp_precedes_response_processing_and_reporting(
    complete_during_reporting: bool,
) -> None:
    failure = TimeoutError("batch timed out")
    release = threading.Event()
    started = {call_id: threading.Event() for call_id in ("call-2", "call-3")}
    base = datetime(2026, 1, 1, tzinfo=timezone.utc)
    clock = [base]
    observed_at = base + timedelta(seconds=1)
    tool = MagicMock()
    tool.tool_type.return_value = ToolType.FUNCTION

    def call_tool(**kwargs: Any) -> str:
        call_id = kwargs["query"]
        if call_id != "call-1":
            started[call_id].set()
            assert release.wait(5)
        return "ok"

    tool.call.side_effect = call_tool
    ctx, sent_events = trace_context(tool)
    ctx.config = AgentConfiguration({})
    ctx.config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, True)
    ctx.config.set(AgentExecutionOptions.TOOL_CALL_PARALLELISM, 3)
    pending = []
    record_outcome = tool_call_action._record_outcome

    def process_response(*args: Any) -> None:
        clock[0] = base + timedelta(seconds=2)
        record_outcome(*args)

    def report_started(*args: Any) -> None:
        clock[0] = base + timedelta(seconds=3)
        if complete_during_reporting:
            release.set()
            for future in pending:
                future.result(timeout=5)

    ctx.report_execution_started_at.side_effect = report_started
    with (
        ThreadPoolExecutor(max_workers=2) as workers,
        patch.object(tool_call_action, "datetime") as datetime_mock,
        patch.object(tool_call_action, "_record_outcome", side_effect=process_response),
    ):
        datetime_mock.now.side_effect = lambda tz: clock[0]

        async def execute_all(callables: list[Any]) -> list[Outcome]:
            first = callables[0]
            result = first.func(*first.args, **(first.kwargs or {}))
            pending.extend(
                workers.submit(call.func, *call.args, **(call.kwargs or {}))
                for call in callables[1:]
            )
            assert all(event.wait(5) for event in started.values())
            clock[0] = observed_at
            return [
                Outcome.success(result),
                Outcome.failure(failure),
                Outcome.failure(failure),
            ]

        ctx.durable_execute_all_async = execute_all
        try:
            asyncio.run(process_tool_request(parallel_trace_request(), ctx))
            timestamps = [
                call.args[-1] for call in ctx.report_execution_failed_at.call_args_list
            ]
            assert timestamps == ["2026-01-01T00:00:01Z"] * 2
            assert sent_events[0].success == {
                "call-1": True,
                "call-2": False,
                "call-3": False,
            }
        finally:
            release.set()
            for future in pending:
                future.result(timeout=5)

    assert_occurrence_reports(
        ctx, ["call-1", "call-2", "call-3"], ["call-1"], ["call-2", "call-3"]
    )


@pytest.mark.parametrize("starts_after_observation", [False, True])
def test_parallel_timeout_omits_starts_after_result_observation(
    starts_after_observation: bool,
) -> None:
    failure = TimeoutError("batch timed out")
    base = datetime(2026, 1, 1, tzinfo=timezone.utc)
    observed_at = base + timedelta(seconds=1)
    delayed_start = (
        observed_at + timedelta(seconds=1) if starts_after_observation else observed_at
    )
    clock = [base]
    delayed = []
    tool = MagicMock()
    tool.tool_type.return_value = ToolType.FUNCTION
    tool.call.return_value = "ok"
    ctx, sent_events = trace_context(tool)
    ctx.config = AgentConfiguration({})
    ctx.config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, True)
    ctx.config.set(AgentExecutionOptions.TOOL_CALL_PARALLELISM, 3)

    async def execute_all(callables: list[Any]) -> list[Outcome]:
        first = callables[0]
        result = first.func(*first.args, **(first.kwargs or {}))
        delayed.extend(callables[1:])
        clock[0] = observed_at
        return [
            Outcome.success(result),
            Outcome.failure(failure),
            Outcome.failure(failure),
        ]

    def report_started(*args: Any) -> None:
        if args[2][ToolExecutionMetadataKeys.TOOL_CALL_ID] == "call-1":
            # The delayed calls enter after the Action has observed the timeout.
            clock[0] = delayed_start
            for call in delayed:
                call.func(*call.args, **(call.kwargs or {}))

    ctx.durable_execute_all_async = execute_all
    ctx.report_execution_started_at.side_effect = report_started
    with patch.object(tool_call_action, "datetime") as datetime_mock:
        datetime_mock.now.side_effect = lambda tz: clock[0]
        asyncio.run(process_tool_request(parallel_trace_request(), ctx))

    starts = ["call-1"] if starts_after_observation else ["call-1", "call-2", "call-3"]
    assert_occurrence_reports(ctx, starts, ["call-1"], ["call-2", "call-3"])
    assert tool.call.call_count == 3
    assert all(
        call.args[3] is failure and call.args[-1] == "2026-01-01T00:00:01Z"
        for call in ctx.report_execution_failed_at.call_args_list
    )
    assert sent_events[0].success == {
        "call-1": True,
        "call-2": False,
        "call-3": False,
    }


def test_partial_cache_replay_only_reports_start_for_invoked_tool() -> None:
    tool = MagicMock()
    tool.tool_type.return_value = ToolType.FUNCTION
    tool.call.return_value = "ok"
    ctx, sent_events = trace_context(tool)
    ctx.config = AgentConfiguration({})
    ctx.config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, True)
    ctx.config.set(AgentExecutionOptions.TOOL_CALL_PARALLELISM, 3)

    async def execute_all(callables: list[Any]) -> list[Outcome]:
        call = callables[1]
        result = call.func(*call.args, **(call.kwargs or {}))
        return [
            Outcome.success("cached"),
            Outcome.success(result),
            Outcome.success("cached"),
        ]

    ctx.durable_execute_all_async = execute_all

    asyncio.run(process_tool_request(parallel_trace_request(), ctx))

    assert_occurrence_reports(ctx, ["call-2"], ["call-1", "call-2", "call-3"], [])
    assert all(sent_events[0].success.values())
    tool.call.assert_called_once()


def parallel_trace_request() -> ToolRequestEvent:
    return ToolRequestEvent(
        model="model-a",
        tool_calls=[
            {
                "id": call_id,
                "function": {"name": "search", "arguments": {"query": call_id}},
            }
            for call_id in ("call-1", "call-2", "call-3")
        ],
    )


def assert_occurrence_reports(
    ctx: MagicMock, started: list[str], succeeded: list[str], failed: list[str]
) -> None:
    for method, expected in (
        (ctx.report_execution_started_at, started),
        (ctx.report_execution_succeeded_at, succeeded),
        (ctx.report_execution_failed_at, failed),
    ):
        actual = [
            call.args[2][ToolExecutionMetadataKeys.TOOL_CALL_ID]
            for call in method.call_args_list
        ]
        assert sorted(actual) == sorted(expected)


def test_tool_call_action_uses_serial_async_when_parallelism_is_one() -> None:
    config = AgentConfiguration({"tenant_id": "tenant-1"})
    config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, True)
    config.set(AgentExecutionOptions.TOOL_CALL_PARALLELISM, 1)
    ctx = _Context(config=config)

    asyncio.run(process_tool_request(tool_request("call-1", "call-2"), ctx))

    assert ctx.durable_execute_all_async_calls == []
    assert len(ctx.durable_execute_async_calls) == 2


def test_tool_call_action_does_not_batch_single_tool() -> None:
    config = AgentConfiguration({"tenant_id": "tenant-1"})
    config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, True)
    config.set(AgentExecutionOptions.TOOL_CALL_PARALLELISM, 4)
    ctx = _Context(config=config)

    asyncio.run(process_tool_request(tool_request("call-1"), ctx))

    assert ctx.durable_execute_all_async_calls == []
    assert len(ctx.durable_execute_async_calls) == 1


def test_tool_call_action_excludes_missing_tool_from_parallel_batch() -> None:
    config = AgentConfiguration({"tenant_id": "tenant-1"})
    config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, True)
    config.set(AgentExecutionOptions.TOOL_CALL_PARALLELISM, 4)
    ctx = _Context(config=config)

    asyncio.run(process_tool_request(tool_request("call-1", "missing"), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.success["call-1"] is True
    assert response.success["missing"] is False
    assert len(ctx.durable_execute_async_calls) == 1
    assert ctx.durable_execute_all_async_calls == []


def test_tool_call_action_records_parallel_outcome_failure() -> None:
    config = AgentConfiguration({"tenant_id": "tenant-1"})
    config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, True)
    config.set(AgentExecutionOptions.TOOL_CALL_PARALLELISM, 4)
    ctx = _Context(config=config)
    ctx.durable_execute_all_async_outcomes = [
        Outcome.success("ok"),
        Outcome.failure(ValueError("boom")),
    ]

    asyncio.run(process_tool_request(tool_request("call-1", "call-2"), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.responses["call-1"] == "ok"
    assert response.success["call-1"] is True
    assert response.responses["call-2"] == "Tool `query_order` execute failed."
    assert response.success["call-2"] is False
    assert response.error["call-2"] == "boom"


def test_tool_call_action_uses_sync_when_async_disabled_multi_tool() -> None:
    config = AgentConfiguration({"tenant_id": "tenant-1"})
    config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, False)
    ctx = _Context(config=config)

    asyncio.run(process_tool_request(tool_request("call-1", "call-2"), ctx))

    assert ctx.durable_execute_all_async_calls == []
    assert ctx.durable_execute_async_calls == []
    assert len(ctx.durable_execute_calls) == 2


def test_tool_call_action_records_tool_execution_exception() -> None:
    config = AgentConfiguration({"tenant_id": "tenant-1"})
    config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, True)
    ctx = _Context(config=config)

    async def failing_async(func: Any, *args: Any, **kwargs: Any) -> Any:
        msg = "boom"
        raise ValueError(msg)

    ctx.durable_execute_async = failing_async  # type: ignore[method-assign]
    asyncio.run(process_tool_request(tool_request("call-1"), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.success["call-1"] is False
    assert response.responses["call-1"] == "Tool `query_order` execute failed."
    assert response.error["call-1"] == "boom"


def test_tool_call_action_records_infrastructure_failure_for_all_parallel_tools() -> (
    None
):
    class _FailingBatchContext(_Context):
        async def durable_execute_all_async(
            self, callables: list[Any]
        ) -> list[Outcome]:
            msg = "persist failed"
            raise RuntimeError(msg)

    config = AgentConfiguration({"tenant_id": "tenant-1"})
    config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, True)
    config.set(AgentExecutionOptions.TOOL_CALL_PARALLELISM, 4)
    ctx = _FailingBatchContext(config=config)

    asyncio.run(process_tool_request(tool_request("call-1", "call-2"), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.success["call-1"] is False
    assert response.success["call-2"] is False
    assert response.error["call-1"] == "persist failed"
    assert response.error["call-2"] == "persist failed"


def tool_request(*call_ids: str) -> ToolRequestEvent:
    if not call_ids:
        call_ids = ("call-1",)
    return ToolRequestEvent(
        model="model",
        tool_calls=[
            {
                "id": call_id,
                "type": "function",
                "function": {
                    "name": "missing_tool" if call_id == "missing" else "query_order",
                    "arguments": {
                        "order_id": "order-1"
                        if len(call_ids) == 1
                        else f"order-{call_id}"
                    },
                },
            }
            for call_id in call_ids
        ],
    )


def test_tool_call_reports_started_and_succeeded() -> None:
    tool = MagicMock()
    tool.tool_type.return_value = ToolType.FUNCTION
    tool.call = MagicMock(return_value="result")
    ctx, sent_events = trace_context(tool)
    request = ToolRequestEvent(model="model-a", tool_calls=[trace_tool_call()])

    asyncio.run(process_tool_request(request, ctx))

    assert len(sent_events) == 1
    metadata = {
        ToolExecutionMetadataKeys.TOOL_REQUEST_EVENT_ID: str(request.id),
        ToolExecutionMetadataKeys.TOOL_CALL_ID: "call-1",
        ToolExecutionMetadataKeys.EXTERNAL_ID: "external-call-1",
        ToolExecutionMetadataKeys.TOOL_TYPE: "function",
    }
    ctx.report_execution_started_at.assert_called_once()
    started_args = ctx.report_execution_started_at.call_args.args
    assert started_args[:3] == (ExecutionEntityTypes.TOOL, "search", metadata)
    ctx.report_execution_succeeded_at.assert_called_once()
    succeeded_args = ctx.report_execution_succeeded_at.call_args.args
    assert succeeded_args[:3] == (ExecutionEntityTypes.TOOL, "search", metadata)
    assert datetime.fromisoformat(succeeded_args[3].replace("Z", "+00:00")) >= (
        datetime.fromisoformat(started_args[3].replace("Z", "+00:00"))
    )
    ctx.report_execution_failed_at.assert_not_called()


def test_tool_call_reports_failed() -> None:
    tool = MagicMock()
    tool.tool_type.return_value = ToolType.FUNCTION
    tool.call = MagicMock(side_effect=RuntimeError("boom"))
    ctx, _ = trace_context(tool)
    request = ToolRequestEvent(model="model-a", tool_calls=[trace_tool_call()])

    asyncio.run(process_tool_request(request, ctx))

    ctx.report_execution_failed_at.assert_called_once()
    args = ctx.report_execution_failed_at.call_args.args
    assert args[0] == ExecutionEntityTypes.TOOL
    assert args[1] == "search"
    assert args[2][ToolExecutionMetadataKeys.TOOL_CALL_ID] == "call-1"
    assert isinstance(args[3], RuntimeError)
    assert args[4] == ExecutionProblemCategories.TOOL_CALL_FAILED
    assert datetime.fromisoformat(args[5].replace("Z", "+00:00"))


def test_tool_call_includes_provider_metadata() -> None:
    class MetadataTool(ToolExecutionMetadataProvider):
        @staticmethod
        def tool_type() -> ToolType:
            return ToolType.MCP

        @staticmethod
        def call(**kwargs: object) -> str:
            return "result"

        def get_tool_execution_metadata(
            self, parameters: dict[str, object]
        ) -> dict[str, object]:
            return {ToolExecutionMetadataKeys.MCP_SERVER: "search_server"}

    ctx, _ = trace_context(MetadataTool())
    request = ToolRequestEvent(model="model-a", tool_calls=[trace_tool_call()])

    asyncio.run(process_tool_request(request, ctx))

    metadata = ctx.report_execution_started_at.call_args.args[2]
    assert metadata[ToolExecutionMetadataKeys.MCP_SERVER] == "search_server"


def test_tool_call_reports_registered_skill_metadata() -> None:
    class SkillTool(ToolExecutionMetadataProvider):
        @staticmethod
        def tool_type() -> ToolType:
            return ToolType.FUNCTION

        @staticmethod
        def call(**kwargs: object) -> str:
            return "skill content"

        def get_tool_execution_metadata(
            self, parameters: dict[str, object]
        ) -> dict[str, object]:
            return {
                ToolExecutionMetadataKeys.SKILL_NAME: "calculator",
                ToolExecutionMetadataKeys.SKILL_REGISTERED: True,
            }

    ctx, _ = trace_context(SkillTool())

    asyncio.run(
        process_tool_request(
            ToolRequestEvent(model="model-a", tool_calls=[trace_tool_call()]), ctx
        )
    )

    metadata = ctx.report_execution_started_at.call_args.args[2]
    assert metadata[ToolExecutionMetadataKeys.SKILL_NAME] == "calculator"
    assert metadata[ToolExecutionMetadataKeys.SKILL_REGISTERED] is True


def test_durable_cache_hit_does_not_record_tool_call_latency() -> None:
    tool = MagicMock()
    tool.tool_type.return_value = ToolType.FUNCTION
    tool.call = MagicMock(return_value="uncached")
    ctx, _ = trace_context(tool)
    ctx.durable_execute = MagicMock(return_value="cached")

    asyncio.run(
        process_tool_request(
            ToolRequestEvent(model="model-a", tool_calls=[trace_tool_call()]), ctx
        )
    )

    tool.call.assert_not_called()
    ctx.report_execution_started_at.assert_not_called()
    ctx.report_execution_succeeded_at.assert_called_once()


def test_tool_execution_metadata_cannot_mutate_call_arguments() -> None:
    class MutatingMetadataTool(ToolExecutionMetadataProvider):
        @staticmethod
        def tool_type() -> ToolType:
            return ToolType.FUNCTION

        @staticmethod
        def call(**kwargs: object) -> object:
            return kwargs["query"]

        def get_tool_execution_metadata(
            self, parameters: dict[str, object]
        ) -> dict[str, object]:
            parameters["query"] = "mutated"
            return {}

    ctx, sent_events = trace_context(MutatingMetadataTool())

    asyncio.run(
        process_tool_request(
            ToolRequestEvent(model="model-a", tool_calls=[trace_tool_call()]), ctx
        )
    )

    response = ToolResponseEvent.from_event(sent_events[0])
    assert response.responses["call-1"] == "flink"


def trace_context(tool: object) -> tuple[MagicMock, list[ToolResponseEvent]]:
    sent_events = []
    config = MagicMock()
    config.get = MagicMock(
        side_effect=lambda option: False
        if option is AgentExecutionOptions.TOOL_CALL_ASYNC
        else option.get_default_value()
    )
    ctx = MagicMock(spec=ExecutionReporter)
    ctx.config = config
    ctx.get_resource = MagicMock(return_value=tool)
    ctx.durable_execute = MagicMock(side_effect=lambda fn, **kwargs: fn(**kwargs))
    ctx.send_event = MagicMock(side_effect=lambda event: sent_events.append(event))
    return ctx, sent_events


def trace_tool_call() -> dict:
    return {
        "id": "call-1",
        "original_id": "external-call-1",
        "function": {"name": "search", "arguments": {"query": "flink"}},
    }
