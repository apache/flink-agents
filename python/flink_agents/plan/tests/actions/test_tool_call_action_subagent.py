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
import json
from concurrent.futures import CancelledError
from datetime import datetime
from types import SimpleNamespace
from typing import Any

import pytest
from pydantic import BaseModel, PrivateAttr
from typing_extensions import override

from flink_agents.api.chat_message import ToolCallBlock
from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.events.tool_event import ToolRequestEvent, ToolResponseEvent
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.subagent import (
    SubagentFuture,
    SubagentResult,
    SubagentSetup,
)
from flink_agents.api.trace import ExecutionEntityTypes
from flink_agents.plan.actions.tool_call_action import process_tool_request
from flink_agents.plan.function import PythonFunction
from flink_agents.plan.subagent_constants import TOOL_NAME_PREFIX
from flink_agents.plan.tools.function_tool import FunctionTool
from flink_agents.runtime.subagent_handles import (
    CompletedSubagentFuture,
    SubagentFutureImpl,
)
from flink_agents.runtime.subagent_tool import SubagentTool
from flink_agents.runtime.tests.future_test_utils import (  # noqa: F401
    RuntimeContext,
    close_runtime_contexts,
)
from flink_agents.runtime.tests.test_flink_runner_context_reconcilable import _run_async


def query_order(order_id: str) -> str:
    return f"queried {order_id}"


def _request(model, tool_calls) -> ToolRequestEvent:
    return ToolRequestEvent(
        model,
        [
            ToolCallBlock(
                call_id=c["id"],
                name=c["function"]["name"],
                input=c["function"]["arguments"],
            )
            for c in tool_calls
        ],
    )


_ResolvedSubagentFuture = CompletedSubagentFuture


class _RecordingSubagentSetup(SubagentSetup):
    """Captures every prompt it is handed and resolves to a preset outcome."""

    input_schema: str | None = '{"type":"object"}'
    _outcome: SubagentResult = PrivateAttr(default=None)
    _submit_failure: Exception | None = PrivateAttr(default=None)
    _prompts: list[Any] = PrivateAttr(default_factory=list)

    @classmethod
    def of(
        cls, outcome: SubagentResult, submit_failure: Exception | None = None
    ) -> "_RecordingSubagentSetup":
        setup = cls(description="Reviews a diff.")
        setup._outcome = outcome
        setup._submit_failure = submit_failure
        return setup

    @property
    def prompts(self) -> list[Any]:
        """Every prompt handed to this sub-agent, in call order."""
        return self._prompts

    @override
    async def submit(
        self,
        ctx: RunnerContext,
        prompt: Any,
        session_id: str | None = None,
        call_id: str | None = None,
    ) -> SubagentFuture:
        if self._submit_failure is not None:
            raise self._submit_failure
        self._prompts.append(prompt)
        return _ResolvedSubagentFuture(
            session_id or "session", call_id or "call", self._outcome
        )


class _Verdict(BaseModel):
    """The result the typed double below declares."""

    approved: bool
    note: str = ""


class _TypedRecordingSubagentSetup(_RecordingSubagentSetup):
    """Declares a result type, so its result is read through it."""

    @classmethod
    def result_type(cls) -> type:
        """Return the declared result type."""
        return _Verdict


class _OrderRecordingSubagentSetup(SubagentSetup):
    """Records submit order into a log shared across sub-agents."""

    input_schema: str | None = '{"type":"object"}'
    _label: str = PrivateAttr(default="")
    _ops: list[str] = PrivateAttr(default_factory=list)

    @classmethod
    def of(cls, label: str, ops: list[str]) -> "_OrderRecordingSubagentSetup":
        setup = cls(description="Orders a diff.")
        setup._label = label
        setup._ops = ops
        return setup

    @override
    async def submit(
        self,
        ctx: RunnerContext,
        prompt: Any,
        session_id: str | None = None,
        call_id: str | None = None,
    ) -> SubagentFuture:
        self._ops.append(f"submit:{self._label}")
        return SubagentFutureImpl(
            f"session-{self._label}",
            f"call-{self._label}",
            ctx.execute_async(lambda: self._record_result()),
        )

    def _record_result(self) -> SubagentResult:
        self._ops.append(f"await:{self._label}")
        return SubagentResult.ok(f"{self._label} done")


class _Context(RuntimeContext):
    def __init__(self) -> None:
        super().__init__()
        self.config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, False)
        self.sent_events = []
        self.reports: list[tuple[str, str, str, str | None, str]] = []
        self.tools = {}
        self.agents = {}
        self.durable_executions = 0

    def with_tool(self, name: str, tool: Any) -> "_Context":
        self.tools[name] = tool
        return self

    def with_agent(self, name: str, agent: Any) -> "_Context":
        self.agents[name] = agent
        return self

    def with_parallel_tool_calls(self) -> "_Context":
        """Turn on the batched path: async calls with room for more than one."""
        self.config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, True)
        self.config.set(AgentExecutionOptions.ASYNC_BATCH_PARALLELISM, 2)
        return self

    def get_resource(self, name: str, type: ResourceType) -> Any:
        if type == ResourceType.CHAT_MODEL:
            names = set(self.tools) | {TOOL_NAME_PREFIX + n for n in self.agents}
            names.add(TOOL_NAME_PREFIX + "missing")
            return SimpleNamespace(get_tool_names=lambda: names, resource_context=self)
        registry = self.agents if type == ResourceType.AGENT else self.tools
        if name not in registry:
            msg = f"Resource does not exist: {name}"
            raise ValueError(msg)
        return registry[name]

    def durable_execute(self, func: Any, *args: Any, **kwargs: Any) -> Any:
        kwargs.pop("durable_id", None)
        self.durable_executions += 1
        return func(*args, **kwargs)

    def durable_execute_async(self, func: Any, *args: Any, **kwargs: Any) -> Any:
        def execute() -> Any:
            self.durable_executions += 1
            return func(
                *args,
                **{
                    k: v
                    for k, v in kwargs.items()
                    if k not in ("durable_id", "reconciler")
                },
            )

        return super().durable_execute_async(
            execute, durable_id=kwargs.get("durable_id")
        )

    def get_subagent_tool(self, name: str) -> SubagentTool:
        return SubagentTool.of(name, self.get_resource(name, ResourceType.AGENT))

    def send_event(self, event: Any) -> None:
        self.sent_events.append(event)

    @override
    def report_execution_created(
        self,
        execution_id: str,
        entity_type: str,
        entity_name: str,
        entity_metadata: Any = None,
    ) -> None:
        self.reports.append(("created", entity_type, entity_name, None, execution_id))

    @override
    def report_execution_started(
        self,
        execution_id: str,
        entity_type: str,
        entity_name: str,
        entity_metadata: Any = None,
    ) -> None:
        self.reports.append(("started", entity_type, entity_name, None, execution_id))

    @override
    def report_execution_started_at(
        self,
        execution_id: str,
        entity_type: str,
        entity_name: str,
        entity_metadata: Any,
        timestamp: str,
    ) -> None:
        self.reports.append(
            ("started", entity_type, entity_name, timestamp, execution_id)
        )

    @override
    def report_execution_succeeded(
        self,
        execution_id: str,
        entity_type: str,
        entity_name: str,
        entity_metadata: Any = None,
    ) -> None:
        self.reports.append(("succeeded", entity_type, entity_name, None, execution_id))

    @override
    def report_execution_succeeded_at(
        self,
        execution_id: str,
        entity_type: str,
        entity_name: str,
        entity_metadata: Any,
        timestamp: str,
    ) -> None:
        self.reports.append(
            ("succeeded", entity_type, entity_name, timestamp, execution_id)
        )

    @override
    def report_execution_failed(
        self,
        execution_id: str,
        entity_type: str,
        entity_name: str,
        entity_metadata: Any,
        error: BaseException,
        problem_category: str | None = None,
    ) -> None:
        self.reports.append(("failed", entity_type, entity_name, None, execution_id))

    @override
    def report_execution_failed_at(
        self,
        execution_id: str,
        entity_type: str,
        entity_name: str,
        entity_metadata: Any,
        error: BaseException,
        problem_category: str | None,
        timestamp: str,
    ) -> None:
        self.reports.append(
            ("failed", entity_type, entity_name, timestamp, execution_id)
        )


def _parse_timestamp(timestamp: str) -> datetime:
    return datetime.fromisoformat(timestamp.replace("Z", "+00:00"))


def tool_request(callable_name: str) -> ToolRequestEvent:
    return _request(
        model="model",
        tool_calls=[
            {
                "id": "call-1",
                "type": "function",
                "function": {
                    "name": callable_name,
                    "arguments": {"prompt": "review the diff"},
                },
            }
        ],
    )


def two_subagent_request(first: str, second: str) -> ToolRequestEvent:
    """One request carrying two sub-agent calls, so the batched path has more
    than one to run.
    """
    return _request(
        model="model",
        tool_calls=[
            {
                "id": "call-1",
                "type": "function",
                "function": {
                    "name": TOOL_NAME_PREFIX + first,
                    "arguments": {"prompt": "review the diff"},
                },
            },
            {
                "id": "call-2",
                "type": "function",
                "function": {
                    "name": TOOL_NAME_PREFIX + second,
                    "arguments": {"prompt": "review the diff"},
                },
            },
        ],
    )


def order_tool() -> FunctionTool:
    return FunctionTool(func=PythonFunction.from_callable(query_order))


def test_delegates_to_the_subagent_and_reports_its_normalized_result() -> None:
    agent = _RecordingSubagentSetup.of(
        SubagentResult.ok({"verdict": "approved", "findings": ["style"]})
    )
    ctx = _Context().with_agent("reviewer", agent)

    asyncio.run(process_tool_request(tool_request("_subagent_reviewer"), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.success["call-1"] is True
    assert (
        response.responses["call-1"].to_result_block("call").text
        == '{"verdict":"approved","findings":["style"]}'
    )
    assert "call-1" not in response.error


def test_hands_the_model_arguments_to_the_subagent_as_the_prompt() -> None:
    agent = _RecordingSubagentSetup.of(SubagentResult.ok("done"))
    ctx = _Context().with_agent("reviewer", agent)

    asyncio.run(process_tool_request(tool_request("_subagent_reviewer"), ctx))

    assert agent.prompts == [{"prompt": "review the diff"}]
    # A sub-agent call resolves through the setup, which owns its own durable
    # execution.
    assert ctx.durable_executions == 0


@pytest.mark.parametrize("parallel", [False, True])
@pytest.mark.parametrize("field", ["ctx", "call_context", "self"])
def test_subagent_input_preserves_framework_parameter_names(
    parallel: bool, field: str
) -> None:
    agent = _RecordingSubagentSetup.of(SubagentResult.ok("done"))
    agent.input_schema = json.dumps(
        {"type": "object", "properties": {field: {"type": "string"}}}
    )
    ctx = _Context().with_agent("reviewer", agent)
    if parallel:
        ctx.with_parallel_tool_calls()
    inputs = [{field: "review the diff"}, {field: "review the tests"}]
    request = ToolRequestEvent(
        "model",
        [
            ToolCallBlock(
                call_id=f"call-{index}", name="_subagent_reviewer", input=prompt
            )
            for index, prompt in enumerate(inputs)
        ],
    )

    asyncio.run(process_tool_request(request, ctx))

    assert agent.prompts == inputs
    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.success == {"call-0": True, "call-1": True}


def test_reports_a_failed_subagent_result_with_the_detail_exposed() -> None:
    agent = _RecordingSubagentSetup.of(SubagentResult.error("upstream refused"))
    ctx = _Context().with_agent("reviewer", agent)

    asyncio.run(process_tool_request(tool_request("_subagent_reviewer"), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.success["call-1"] is False
    assert response.responses["call-1"].to_result_block("call").text == (
        "Sub-agent `_subagent_reviewer` execute failed: upstream refused"
    )
    assert response.error["call-1"] == "upstream refused"


def test_reports_a_failure_raised_while_submitting() -> None:
    agent = _RecordingSubagentSetup.of(
        SubagentResult.ok("unreachable"), RuntimeError("mailbox is full")
    )
    ctx = _Context().with_agent("reviewer", agent)

    asyncio.run(process_tool_request(tool_request("_subagent_reviewer"), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.success["call-1"] is False
    assert response.responses["call-1"].to_result_block("call").text == (
        "Sub-agent `_subagent_reviewer` execute failed: mailbox is full"
    )
    assert response.error["call-1"] == "mailbox is full"


def test_rejects_a_result_json_cannot_express() -> None:
    agent = _RecordingSubagentSetup.of(SubagentResult.ok({"handle": object()}))
    ctx = _Context().with_agent("reviewer", agent)

    asyncio.run(process_tool_request(tool_request("_subagent_reviewer"), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.success["call-1"] is False
    assert (
        response.responses["call-1"]
        .to_result_block("call")
        .text.startswith("Sub-agent `_subagent_reviewer` execute failed")
    )
    assert "result.handle" in response.responses["call-1"].to_result_block("call").text
    assert "result.handle" in response.error["call-1"]


def test_reads_a_result_through_the_type_the_subagent_declares() -> None:
    """A declared result type is what admits a result JSON cannot express on its
    own.
    """
    agent = _TypedRecordingSubagentSetup.of(
        SubagentResult.ok(_Verdict(approved=True, note="clean"))
    )
    ctx = _Context().with_agent("reviewer", agent)

    asyncio.run(process_tool_request(tool_request("_subagent_reviewer"), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.success["call-1"] is True
    assert (
        response.responses["call-1"].to_result_block("call").text
        == '{"approved":true,"note":"clean"}'
    )


def test_routes_a_tool_and_a_subagent_sharing_a_name_to_their_own_namespace() -> None:
    """The reserved prefix routes each namespace on its own, even under one
    shared name.
    """
    ctx = (
        _Context()
        .with_agent("reviewer", _RecordingSubagentSetup.of(SubagentResult.ok("done")))
        .with_tool("reviewer", order_tool())
    )

    asyncio.run(process_tool_request(tool_request("_subagent_reviewer"), ctx))
    delegated = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert delegated.success["call-1"] is True
    assert delegated.responses["call-1"].to_result_block("call").text == "done"
    # A sub-agent call resolves through the setup, which owns its own durable
    # execution.
    assert ctx.durable_executions == 0

    tool_event = _request(
        model="model",
        tool_calls=[
            {
                "id": "call-1",
                "type": "function",
                "function": {"name": "reviewer", "arguments": {"order_id": "order-1"}},
            }
        ],
    )
    asyncio.run(process_tool_request(tool_event, ctx))
    direct = ToolResponseEvent.from_event(ctx.sent_events[1])
    assert direct.success["call-1"] is True
    assert direct.responses["call-1"].to_result_block("call").text == "queried order-1"
    assert ctx.durable_executions == 1


def test_submits_every_subagent_call_before_awaiting_any_in_parallel() -> None:
    """The batched path runs sub-agent calls concurrently: every call is
    submitted before any is awaited, so the async setups' remote runs overlap
    instead of blocking one behind the next. The serial path interleaves submit
    and await per call, which this order assertion rejects.
    """
    ops: list[str] = []
    ctx = (
        _Context()
        .with_parallel_tool_calls()
        .with_agent("a", _OrderRecordingSubagentSetup.of("a", ops))
        .with_agent("b", _OrderRecordingSubagentSetup.of("b", ops))
    )

    asyncio.run(process_tool_request(two_subagent_request("a", "b"), ctx))

    assert ops[:2] == ["submit:a", "submit:b"]
    assert sorted(ops[2:]) == ["await:a", "await:b"]
    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.success["call-1"] is True
    assert response.success["call-2"] is True
    assert response.responses["call-1"].to_result_block("call").text == "a done"
    assert response.responses["call-2"].to_result_block("call").text == "b done"


def test_reports_a_resolved_subagent_delegation_under_the_subagent_scope() -> None:
    """A resolved delegation is reported under the sub-agent scope keyed by the
    registered agent name -- not the reserved callable name and not the tool
    scope -- and carries a start occurrence, so the runtime attributes a latency
    window to it instead of bucketing it as an unknown tool with no latency.
    """
    agent = _RecordingSubagentSetup.of(SubagentResult.ok("done"))
    ctx = _Context().with_agent("reviewer", agent)

    asyncio.run(process_tool_request(tool_request("_subagent_reviewer"), ctx))

    assert [report[0] for report in ctx.reports] == ["created", "started", "succeeded"]
    for _, entity_type, entity_name, _, _ in ctx.reports:
        assert entity_type == ExecutionEntityTypes.SUBAGENT
        assert entity_name == "reviewer"
    assert len({report[4] for report in ctx.reports}) == 1
    assert ctx.reports[0][4]
    started_timestamp = ctx.reports[1][3]
    succeeded_timestamp = ctx.reports[2][3]
    assert started_timestamp is not None
    assert succeeded_timestamp is not None
    assert _parse_timestamp(succeeded_timestamp) >= _parse_timestamp(started_timestamp)


@pytest.mark.parametrize("parallel", [False, True])
@pytest.mark.parametrize("during_submit", [False, True])
@pytest.mark.parametrize(
    "error_type", [InterruptedError, CancelledError, asyncio.CancelledError]
)
def test_subagent_cancellation_propagates_without_tool_responses(
    parallel: bool, during_submit: bool, error_type: type[BaseException]
) -> None:
    failure = error_type("interrupted")
    ops = []

    class InterruptingSetup(_OrderRecordingSubagentSetup):
        async def submit(self, *args: Any, **kwargs: Any) -> SubagentFuture:
            if during_submit:
                raise failure
            return await super().submit(*args, **kwargs)

        def _record_result(self) -> SubagentResult:
            raise failure

    ctx = _Context().with_agent("first", InterruptingSetup.of("first", ops))
    ctx.with_agent("second", _OrderRecordingSubagentSetup.of("second", ops))
    if parallel:
        ctx.with_parallel_tool_calls()

    # Drive the coroutine as the runtime does. asyncio.run() on Python 3.10
    # replaces CancelledError, hiding the exception propagated by the action.
    with pytest.raises(error_type) as raised:
        _run_async(process_tool_request(two_subagent_request("first", "second"), ctx))

    assert raised.value is failure
    assert ctx.sent_events == []
    if not parallel or during_submit:
        assert "submit:second" not in ops
