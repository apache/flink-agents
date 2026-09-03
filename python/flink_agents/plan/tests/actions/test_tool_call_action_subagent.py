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
from typing import Any

from pydantic import BaseModel, PrivateAttr
from typing_extensions import override

from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.events.tool_event import ToolRequestEvent, ToolResponseEvent
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.subagent import SubagentFuture, SubagentResult, SubagentSetup
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


class _Context:
    def __init__(self) -> None:
        self.config = AgentConfiguration({})
        self.config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, False)
        self.sensory_memory = None
        self.short_term_memory = None
        self.sent_events = []
        self.tools = {}
        self.agents = {}
        self.durable_executions = 0

    def with_tool(self, name: str, tool: Any) -> "_Context":
        self.tools[name] = tool
        return self

    def with_agent(self, name: str, agent: Any) -> "_Context":
        self.agents[name] = agent
        return self

    def get_resource(self, name: str, type: ResourceType) -> Any:
        registry = self.agents if type == ResourceType.AGENT else self.tools
        if name not in registry:
            msg = f"Resource does not exist: {name}"
            raise ValueError(msg)
        return registry[name]

    def durable_execute(self, func: Any, **kwargs: Any) -> Any:
        self.durable_executions += 1
        return func(**kwargs)

    async def durable_execute_async(self, func: Any, **kwargs: Any) -> Any:
        self.durable_executions += 1
        return func(**kwargs)

    def send_event(self, event: Any) -> None:
        self.sent_events.append(event)


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


def order_tool() -> FunctionTool:
    return FunctionTool(func=PythonFunction.from_callable(query_order))


def test_delegates_to_the_subagent_and_reports_its_normalized_result() -> None:
    agent = _RecordingSubagentSetup.of(
        SubagentResult.ok({"verdict": "approved", "findings": ["style"]})
    )
    ctx = _Context().with_agent("reviewer", agent)

    asyncio.run(process_tool_request(tool_request("subagent_reviewer"), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.success["call-1"] is True
    assert response.responses["call-1"] == '{"verdict":"approved","findings":["style"]}'
    assert "call-1" not in response.error


def test_hands_the_model_arguments_to_the_subagent_as_the_prompt() -> None:
    agent = _RecordingSubagentSetup.of(SubagentResult.ok("done"))
    ctx = _Context().with_agent("reviewer", agent)

    asyncio.run(process_tool_request(tool_request("subagent_reviewer"), ctx))

    assert agent.prompts == [{"prompt": "review the diff"}]
    # A sub-agent call resolves through the setup, which owns its own durable
    # execution.
    assert ctx.durable_executions == 0


def test_reports_a_failed_subagent_result_with_the_detail_exposed() -> None:
    agent = _RecordingSubagentSetup.of(SubagentResult.error("upstream refused"))
    ctx = _Context().with_agent("reviewer", agent)

    asyncio.run(process_tool_request(tool_request("subagent_reviewer"), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.success["call-1"] is False
    assert response.responses["call-1"] == (
        "Sub-agent `subagent_reviewer` execute failed: upstream refused"
    )
    assert response.error["call-1"] == "upstream refused"


def test_reports_a_failure_raised_while_submitting() -> None:
    agent = _RecordingSubagentSetup.of(
        SubagentResult.ok("unreachable"), RuntimeError("mailbox is full")
    )
    ctx = _Context().with_agent("reviewer", agent)

    asyncio.run(process_tool_request(tool_request("subagent_reviewer"), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.success["call-1"] is False
    assert response.responses["call-1"] == (
        "Sub-agent `subagent_reviewer` execute failed: mailbox is full"
    )
    assert response.error["call-1"] == "mailbox is full"


def test_rejects_a_result_json_cannot_express() -> None:
    agent = _RecordingSubagentSetup.of(SubagentResult.ok({"handle": object()}))
    ctx = _Context().with_agent("reviewer", agent)

    asyncio.run(process_tool_request(tool_request("subagent_reviewer"), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.success["call-1"] is False
    assert response.responses["call-1"].startswith(
        "Sub-agent `subagent_reviewer` execute failed"
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

    asyncio.run(process_tool_request(tool_request("subagent_reviewer"), ctx))

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

    asyncio.run(process_tool_request(tool_request("subagent_reviewer"), ctx))
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

    asyncio.run(process_tool_request(tool_request("subagent_reviewer"), ctx))

    response = ToolResponseEvent.from_event(ctx.sent_events[0])
    assert response.success["call-1"] is False
    assert response.responses["call-1"] == (
        "Sub-agent `subagent_reviewer` execute failed: Sub-agent reviewer must"
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
