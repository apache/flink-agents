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
from datetime import datetime
from typing import Any

from pydantic import BaseModel, PrivateAttr
from typing_extensions import override

from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.events.tool_event import ToolRequestEvent, ToolResponseEvent
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import Outcome, RunnerContext
from flink_agents.api.subagent import (
    CALLABLE_NAME_PREFIX,
    SubagentFuture,
    SubagentResult,
    SubagentSetup,
)
from flink_agents.api.trace import ExecutionEntityTypes, ExecutionReporter
from flink_agents.plan.actions.tool_call_action import process_tool_request
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.function import PythonFunction
from flink_agents.plan.tools.function_tool import FunctionTool


def query_order(order_id: str) -> str:
    return f"queried {order_id}"


class _ResolvedSubagentFuture(SubagentFuture):
    """Handle that is already resolved to a preset outcome."""

    def __init__(self, session_id: str, call_id: str, outcome: SubagentResult) -> None:
        super().__init__(session_id, call_id)
        self._outcome = outcome

    @override
    def done(self) -> bool:
        return True

    @override
    def combine(self, *others: SubagentFuture) -> Any:
        raise NotImplementedError

    @override
    def __await__(self) -> Any:
        async def resolve() -> SubagentResult:
            return self._outcome

        return resolve().__await__()


class _RecordingSubagentSetup(SubagentSetup):
    """Captures every prompt it is handed and resolves to a preset outcome."""

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


class _OrderRecordingFuture(SubagentFuture):
    """Records its await into a shared log, then resolves to a fixed success."""

    def __init__(self, label: str, ops: list[str]) -> None:
        super().__init__(f"session-{label}", f"call-{label}")
        self._label = label
        self._ops = ops

    @override
    def done(self) -> bool:
        return True

    @override
    def combine(self, *others: SubagentFuture) -> Any:
        raise NotImplementedError

    @override
    def __await__(self) -> Any:
        async def resolve() -> SubagentResult:
            self._ops.append(f"await:{self._label}")
            return SubagentResult.ok(f"{self._label} done")

        return resolve().__await__()


class _OrderRecordingSubagentSetup(SubagentSetup):
    """Records submit order into a log shared across sub-agents."""

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
        return _OrderRecordingFuture(self._label, self._ops)


class _Context(ExecutionReporter):
    def __init__(self) -> None:
        self.config = AgentConfiguration({})
        self.config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, False)
        self.sensory_memory = None
        self.short_term_memory = None
        self.sent_events = []
        self.reports: list[tuple[str, str, str, str | None]] = []
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
        self.config.set(AgentExecutionOptions.TOOL_CALL_PARALLELISM, 2)
        return self

    def get_resource(self, name: str, type: ResourceType) -> Any:
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
        kwargs.pop("durable_id", None)

        # Deferred: the count reflects executions (when the future is awaited,
        # directly or via gather), not handle creations, matching the lazy
        # durable-future contract.
        async def execute() -> Any:
            self.durable_executions += 1
            return func(*args, **kwargs)

        return execute()

    async def gather(self, *futures: Any) -> list[Outcome]:
        outcomes = []
        for future in futures:
            try:
                outcomes.append(Outcome.success(await future))
            except Exception as error:  # noqa: PERF203
                outcomes.append(Outcome.failure(error))
        return outcomes

    def send_event(self, event: Any) -> None:
        self.sent_events.append(event)

    @override
    def report_execution_created(
        self, entity_type: str, entity_name: str, entity_metadata: Any = None
    ) -> None:
        self.reports.append(("created", entity_type, entity_name, None))

    @override
    def report_execution_started(
        self, entity_type: str, entity_name: str, entity_metadata: Any = None
    ) -> None:
        self.reports.append(("started", entity_type, entity_name, None))

    @override
    def report_execution_started_at(
        self, entity_type: str, entity_name: str, entity_metadata: Any, timestamp: str
    ) -> None:
        self.reports.append(("started", entity_type, entity_name, timestamp))

    @override
    def report_execution_succeeded(
        self, entity_type: str, entity_name: str, entity_metadata: Any = None
    ) -> None:
        self.reports.append(("succeeded", entity_type, entity_name, None))

    @override
    def report_execution_succeeded_at(
        self, entity_type: str, entity_name: str, entity_metadata: Any, timestamp: str
    ) -> None:
        self.reports.append(("succeeded", entity_type, entity_name, timestamp))

    @override
    def report_execution_failed(
        self,
        entity_type: str,
        entity_name: str,
        entity_metadata: Any,
        error: BaseException,
        problem_category: str | None = None,
    ) -> None:
        self.reports.append(("failed", entity_type, entity_name, None))

    @override
    def report_execution_failed_at(
        self,
        entity_type: str,
        entity_name: str,
        entity_metadata: Any,
        error: BaseException,
        problem_category: str | None,
        timestamp: str,
    ) -> None:
        self.reports.append(("failed", entity_type, entity_name, timestamp))


def _parse_timestamp(timestamp: str) -> datetime:
    return datetime.fromisoformat(timestamp.replace("Z", "+00:00"))


def tool_request(callable_name: str) -> ToolRequestEvent:
    return ToolRequestEvent(
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
    return ToolRequestEvent(
        model="model",
        tool_calls=[
            {
                "id": "call-1",
                "type": "function",
                "function": {
                    "name": CALLABLE_NAME_PREFIX + first,
                    "arguments": {"prompt": "review the diff"},
                },
            },
            {
                "id": "call-2",
                "type": "function",
                "function": {
                    "name": CALLABLE_NAME_PREFIX + second,
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
    assert response.responses["call-1"] == '{"verdict":"approved","findings":["style"]}'
    assert "call-1" not in response.error


def test_hands_the_model_arguments_to_the_subagent_as_the_prompt() -> None:
    agent = _RecordingSubagentSetup.of(SubagentResult.ok("done"))
    ctx = _Context().with_agent("reviewer", agent)

    asyncio.run(process_tool_request(tool_request("_subagent_reviewer"), ctx))

    assert agent.prompts == [{"prompt": "review the diff"}]
    # A sub-agent call resolves through the setup, which owns its own durable
    # execution.
    assert ctx.durable_executions == 0


def test_reports_a_failed_subagent_result_with_the_detail_exposed() -> None:
    agent = _RecordingSubagentSetup.of(SubagentResult.error("upstream refused"))
    ctx = _Context().with_agent("reviewer", agent)

    asyncio.run(process_tool_request(tool_request("_subagent_reviewer"), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.success["call-1"] is False
    assert response.responses["call-1"] == (
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
    assert response.responses["call-1"] == (
        "Sub-agent `_subagent_reviewer` execute failed: mailbox is full"
    )
    assert response.error["call-1"] == "mailbox is full"


def test_rejects_a_result_json_cannot_express() -> None:
    agent = _RecordingSubagentSetup.of(SubagentResult.ok({"handle": object()}))
    ctx = _Context().with_agent("reviewer", agent)

    asyncio.run(process_tool_request(tool_request("_subagent_reviewer"), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.success["call-1"] is False
    assert response.responses["call-1"].startswith(
        "Sub-agent `_subagent_reviewer` execute failed"
    )
    assert "result.handle" in response.responses["call-1"]
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
    assert response.responses["call-1"] == '{"approved":true,"note":"clean"}'


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
    assert delegated.responses["call-1"] == "done"
    # A sub-agent call resolves through the setup, which owns its own durable
    # execution.
    assert ctx.durable_executions == 0

    tool_event = ToolRequestEvent(
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
    assert direct.responses["call-1"] == "queried order-1"
    assert ctx.durable_executions == 1


def test_refuses_an_agent_resource_that_carries_no_callable_setup() -> None:
    ctx = _Context().with_agent("reviewer", order_tool())

    asyncio.run(process_tool_request(tool_request("_subagent_reviewer"), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.success["call-1"] is False
    assert response.responses["call-1"] == (
        "Sub-agent `_subagent_reviewer` execute failed: Sub-agent reviewer must"
        " resolve to a SubagentSetup, but was FunctionTool."
    )
    assert response.error["call-1"] == (
        "Sub-agent reviewer must resolve to a SubagentSetup, but was FunctionTool."
    )


def test_still_dispatches_a_tool_when_both_kinds_are_registered() -> None:
    ctx = (
        _Context()
        .with_agent("reviewer", _RecordingSubagentSetup.of(SubagentResult.ok("done")))
        .with_tool("query_order", order_tool())
    )
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
    assert response.success["call-1"] is True
    assert response.responses["call-1"] == "queried order-1"
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

    assert ops == ["submit:a", "submit:b", "await:a", "await:b"]
    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.success["call-1"] is True
    assert response.success["call-2"] is True
    assert response.responses["call-1"] == "a done"
    assert response.responses["call-2"] == "b done"


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
    for _, entity_type, entity_name, _ in ctx.reports:
        assert entity_type == ExecutionEntityTypes.SUBAGENT
        assert entity_name == "reviewer"
    started_timestamp = ctx.reports[1][3]
    succeeded_timestamp = ctx.reports[2][3]
    assert started_timestamp is not None
    assert succeeded_timestamp is not None
    assert _parse_timestamp(succeeded_timestamp) >= _parse_timestamp(started_timestamp)
