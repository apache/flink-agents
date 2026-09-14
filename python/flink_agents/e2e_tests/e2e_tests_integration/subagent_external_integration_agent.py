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
"""Agents exercising the Python external sub-agent modes.

An async (durable pub/sub) setup and a deferred setup, each driven by a Python
action running on the Java runtime over pemja. The backend is an in-memory run
store held on the setup instance (pemja's Python runs in the MiniCluster JVM,
not the test process), so the test needs no external service while still
exercising the submit / poll / fetch sequence of each mode. The backend
completes on the first probe, so the multi-probe pacing of the poll loop is
covered at unit level rather than here.
"""

from typing import Any

from pydantic import PrivateAttr
from typing_extensions import override

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.subagent import SubagentResult
from flink_agents.runtime.async_subagent import BaseAsyncSubagentSetup, RunStatus
from flink_agents.runtime.deferred_subagent import (
    DeferredSubagentSetup,
    PreparedTriple,
)


def _outcome(result: SubagentResult) -> str:
    """Render a sub-agent result as the string emitted downstream."""
    return result.result if result.success else f"ERR:{result.error_message}"


class InMemoryAsyncSubagentSetup(BaseAsyncSubagentSetup):
    """External async setup backed by an in-memory run store.

    A prompt containing ``fail`` produces a failed run; any other prompt
    completes and echoes back, tagged with the injected sub-agent name.
    """

    _runs: dict = PrivateAttr(default_factory=dict)

    @override
    def call_submit_request(self, session_id: str, call_id: str, prompt: Any) -> None:
        """Record the run under its (session_id, call_id) identity."""
        self._runs[(session_id, call_id)] = prompt

    @override
    def call_query_status(self, session_id: str, call_id: str) -> RunStatus:
        """Report the run as terminal immediately (completed or failed)."""
        if (session_id, call_id) not in self._runs:
            return RunStatus.not_started()
        prompt = self._runs[(session_id, call_id)]
        if "fail" in str(prompt):
            return RunStatus.failed("async run failed on demand")
        return RunStatus.completed()

    @override
    def call_fetch_result(self, session_id: str, call_id: str) -> SubagentResult:
        """Fetch the completed run's echoed answer."""
        prompt = self._runs[(session_id, call_id)]
        return SubagentResult.ok(f"async[{self.subagent_name}]:{prompt}")


class InMemoryDeferredSubagentSetup(DeferredSubagentSetup):
    """External deferred setup that runs the whole invocation on resolve."""

    @override
    def prepare(
        self,
        ctx: RunnerContext,
        prompt: Any,
        session_id: str,
        call_id: str,
    ) -> PreparedTriple:
        """Return a durable call echoing the prompt (or failing on demand)."""
        name = self.subagent_name

        def call() -> SubagentResult:
            if "fail" in str(prompt):
                return SubagentResult.error("deferred run failed on demand")
            return SubagentResult.ok(f"deferred[{name}]:{prompt}")

        return (f"{session_id}#{call_id}", call, None)


class AsyncExternalAgent(Agent):
    """Agent whose action submits to the async external sub-agent and awaits it."""

    @action(EventType.InputEvent)
    @staticmethod
    async def process(event: Event, ctx: RunnerContext) -> None:
        """Submit, await, and emit the async sub-agent outcome."""
        prompt = InputEvent.from_event(event).input
        reviewer = ctx.get_resource("reviewer", ResourceType.AGENT)
        # Awaiting the submit hands back the handle once the durable POST has
        # landed.
        future = await reviewer.submit(ctx, prompt)
        result = await future
        ctx.send_event(OutputEvent(output=_outcome(result)))


class DeferredExternalAgent(Agent):
    """Agent whose action submits to the deferred external sub-agent and awaits."""

    @action(EventType.InputEvent)
    @staticmethod
    async def process(event: Event, ctx: RunnerContext) -> None:
        """Submit, await, and emit the deferred sub-agent outcome."""
        prompt = InputEvent.from_event(event).input
        reviewer = ctx.get_resource("reviewer", ResourceType.AGENT)
        # This mode sends nothing until the handle is awaited.
        future = await reviewer.submit(ctx, prompt)
        result = await future
        ctx.send_event(OutputEvent(output=_outcome(result)))


def build_async_agent() -> Agent:
    """Build the agent registering the async external sub-agent."""
    agent = AsyncExternalAgent()
    agent.add_resource("reviewer", ResourceType.AGENT, InMemoryAsyncSubagentSetup())
    return agent


def build_deferred_agent() -> Agent:
    """Build the agent registering the deferred external sub-agent."""
    agent = DeferredExternalAgent()
    agent.add_resource("reviewer", ResourceType.AGENT, InMemoryDeferredSubagentSetup())
    return agent
