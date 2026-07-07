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
"""Agent definitions exercising an MCP server directly, without any LLM.

The actions fetch the MCP ``add`` tool and (for the prompt-enabled server) the
``ask_sum`` prompt via ``ctx.get_resource`` and invoke them in-process, so the
test round-trips to a real MCP server without a chat model in the loop.
"""

from pydantic import BaseModel
from pyflink.datastream import KeySelector

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import action, mcp_server
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.resource import ResourceDescriptor, ResourceName, ResourceType
from flink_agents.api.runner_context import RunnerContext

MCP_SERVER_ENDPOINT_WITH_PROMPTS = "http://127.0.0.1:8000/mcp"
MCP_SERVER_ENDPOINT_WITHOUT_PROMPTS = "http://127.0.0.1:8001/mcp"


class MCPCalcInput(BaseModel):
    """Input for an MCP calculation request."""

    key: str
    a: int
    b: int


class MCPCalcKeySelector(KeySelector):
    """KeySelector extracting the routing key from an MCPCalcInput."""

    def get_key(self, value: MCPCalcInput) -> str:
        """Extract key from MCPCalcInput."""
        return value.key


def _call_add(ctx: RunnerContext, a: int, b: int) -> int:
    """Fetch the MCP ``add`` tool and invoke it, returning the numeric sum."""
    add = ctx.get_resource("add", ResourceType.TOOL)
    result = add.call({"a": a, "b": b})
    # MCPTool.call returns a list of extracted content items; the add tool
    # yields a single text item holding the sum.
    return int(result[0])


class MCPRemoteWithPromptsAgent(Agent):
    """Agent that calls the MCP ``add`` tool and reads the ``ask_sum`` prompt."""

    @mcp_server
    @staticmethod
    def math_mcp_server() -> ResourceDescriptor:
        """MCP server exposing both the add tool and the ask_sum prompt."""
        return ResourceDescriptor(
            clazz=ResourceName.MCP_SERVER,
            endpoint=MCP_SERVER_ENDPOINT_WITH_PROMPTS,
        )

    @action(EventType.InputEvent)
    @staticmethod
    def process(event: Event, ctx: RunnerContext) -> None:
        """Invoke the MCP add tool and ask_sum prompt directly."""
        data = MCPCalcInput.model_validate(InputEvent.from_event(event).input)
        total = _call_add(ctx, data.a, data.b)

        prompt = ctx.get_resource("ask_sum", ResourceType.PROMPT)
        messages = prompt.format_messages(a=str(data.a), b=str(data.b))
        prompt_text = messages[0].content

        ctx.send_event(
            OutputEvent(
                output={"key": data.key, "sum": total, "prompt": prompt_text}
            )
        )


class MCPRemoteWithoutPromptsAgent(Agent):
    """Agent that calls the MCP ``add`` tool on a server without prompts."""

    @mcp_server
    @staticmethod
    def math_mcp_server() -> ResourceDescriptor:
        """MCP server exposing only the add tool."""
        return ResourceDescriptor(
            clazz=ResourceName.MCP_SERVER,
            endpoint=MCP_SERVER_ENDPOINT_WITHOUT_PROMPTS,
        )

    @action(EventType.InputEvent)
    @staticmethod
    def process(event: Event, ctx: RunnerContext) -> None:
        """Invoke the MCP add tool directly."""
        data = MCPCalcInput.model_validate(InputEvent.from_event(event).input)
        total = _call_add(ctx, data.a, data.b)
        ctx.send_event(OutputEvent(output={"key": data.key, "sum": total}))
