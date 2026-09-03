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
"""Python integration agent that uses a Python sub-agent.

A Python action submits to a Python deferred sub-agent and awaits it. As for
any Python agent, the action runs on the Java runtime (the operator drives it
over pemja); the sub-agent's id allocation and unresolved-handle enforcement
follow from that.
"""

from typing import Any

from typing_extensions import override

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.subagent import SubagentResult
from flink_agents.runtime.deferred_subagent import (
    DeferredSubagentSetup,
    PreparedTriple,
)


class EchoSubagentSetup(DeferredSubagentSetup):
    """In-process deferred sub-agent that echoes the prompt back.

    The subagent name it reports must be the resource name the framework
    injects; the action asserts on it to prove name injection at runtime.
    """

    @override
    def prepare(
        self,
        ctx: RunnerContext,
        prompt: Any,
        session_id: str,
        call_id: str,
    ) -> PreparedTriple:
        """Return a durable call echoing the prompt, keyed by the identity."""
        name = self.subagent_name

        def call() -> SubagentResult:
            return SubagentResult.ok(f"reviewed[{name}]:{prompt}")

        return (f"{session_id}#{call_id}", call, None)


class SubagentIntegrationAgent(Agent):
    """Python agent whose action calls a Python sub-agent and awaits it."""

    @action(EventType.InputEvent)
    @staticmethod
    async def process(event: Event, ctx: RunnerContext) -> None:
        """Submit the input to the sub-agent, await it, and emit its result."""
        prompt = InputEvent.from_event(event).input
        reviewer = ctx.get_resource("reviewer", ResourceType.AGENT)
        # Short-form submit: ids are allocated from the executing task, which
        # only works when the operator forwarded on_action_prepared over pemja.
        future = await reviewer.submit(ctx, prompt)
        result = await future
        ctx.send_event(OutputEvent(output=result.result))


def build_agent() -> Agent:
    """Build the agent with the sub-agent registered as an AGENT resource."""
    agent = SubagentIntegrationAgent()
    agent.add_resource("reviewer", ResourceType.AGENT, EchoSubagentSetup())
    return agent
