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
"""External sub-agent e2e agent: mock setup, agent, and shared actions."""
import time
from typing import Any

from pyflink.datastream import KeySelector

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.subagent import (
    BaseSubagentCallable,
    DurableCallable,
    Result,
    SubagentSetup,
)

SUBAGENT_NAME = "ext-agent"


class MockExternalSubagentSetup(SubagentSetup):
    """Mock external sub-agent simulating an HTTP-based external agent system.

    Constructible directly or from a resource descriptor (the YAML shape).
    Internal failures are captured into a ``Result`` rather than raised.
    """

    endpoint_url: str
    fail_on_call: bool = False

    def as_async_callable(
        self,
        ctx: RunnerContext,
        prompt: Any,
        session_id: str | None = None,
        call_id: str | None = None,
    ) -> DurableCallable[Result]:
        """Return a callable performing one simulated HTTP call."""
        if session_id is None:
            session_id = ctx.next_session_id()
        if call_id is None:
            call_id = ctx.next_call_id(session_id)
        setup = self

        class _HttpCall(BaseSubagentCallable):
            def call_internal(self) -> Any:
                return setup._simulate_http_call(prompt)

        return _HttpCall(session_id, call_id)

    def _simulate_http_call(self, prompt: Any) -> list:
        # Token latency standing in for a network round trip.
        time.sleep(0.05)
        if self.fail_on_call:
            msg = f"endpoint {self.endpoint_url} is down"
            raise RuntimeError(msg)
        return [f"HTTP response for: {prompt} from {self.endpoint_url}"]


def call_external(event: Event, ctx: RunnerContext) -> None:
    """Call the sub-agent with an explicit session id and emit its result."""
    setup = ctx.get_resource(SUBAGENT_NAME, ResourceType.AGENT)
    prompt = InputEvent.from_event(event).input
    result = setup.call(ctx, prompt, f"session-{prompt}")
    if result.success:
        ctx.send_event(OutputEvent(output=result.result[0]))
    else:
        ctx.send_event(OutputEvent(output=f"error:{result.error_message}"))


class ExternalSubagentAgent(Agent):
    """Agent whose input action delegates to the external sub-agent."""

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def call_external(event: Event, ctx: RunnerContext) -> None:
        call_external(event, ctx)


class InputKeySelector(KeySelector):
    """Keys every element by itself."""

    def get_key(self, value: Any) -> Any:
        """Return the element itself as key."""
        return value
