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
import time
import uuid
from typing import Any, ClassVar, Dict, List, Sequence

import pytest

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
)
from flink_agents.api.decorators import (
    action,
    chat_model_connection,
    chat_model_setup,
    tool,
)
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.resource import ResourceDescriptor, ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.tools.tool import ToolType


class Agent1(Agent):
    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def increment(event: Event, ctx: RunnerContext):  # noqa D102
        input = InputEvent.from_event(event).input
        value = input + 1
        ctx.send_event(OutputEvent(output=value))


class Agent1WithAsync(Agent):
    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    async def increment(event: Event, ctx: RunnerContext):  # noqa D102
        def my_func(value: int) -> int:
            time.sleep(1)
            return value + 1

        input = InputEvent.from_event(event).input
        value = await ctx.durable_execute_async(my_func, input)
        ctx.send_event(OutputEvent(output=value))


class Agent2(Agent):
    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def decrease(event: Event, ctx: RunnerContext):  # noqa D102
        input = InputEvent.from_event(event).input
        value = input - 1
        ctx.send_event(OutputEvent(output=value))


def test_local_execution_environment() -> None:
    env = AgentsExecutionEnvironment.get_execution_environment()

    input_list = []
    agent = Agent1()

    output_list = env.from_list(input_list).apply(agent).to_list()

    input_list.append({"key": "bob", "value": 1})
    input_list.append({"k": "john", "v": 2})

    env.execute()

    assert output_list == [{"bob": 2}, {"john": 3}]


def test_local_execution_environment_with_async() -> None:
    env = AgentsExecutionEnvironment.get_execution_environment()

    input_list = []
    agent = Agent1WithAsync()

    output_list = env.from_list(input_list).apply(agent).to_list()

    input_list.append({"key": "bob", "value": 1})
    input_list.append({"k": "john", "v": 2})

    env.execute()

    assert output_list == [{"bob": 2}, {"john": 3}]


def test_local_execution_environment_apply_multi_agents() -> None:
    env = AgentsExecutionEnvironment.get_execution_environment()

    input_list = []
    agent1 = Agent1()
    agent2 = Agent2()

    with pytest.raises(RuntimeError):
        env.from_list(input_list).apply(agent1).apply(agent2).to_list()


def test_local_execution_environment_execute_multi_times() -> None:
    env = AgentsExecutionEnvironment.get_execution_environment()

    input_list = []
    agent = Agent1()

    env.from_list(input_list).apply(agent).to_list()

    input_list.append({"key": "bob", "value": 1})
    input_list.append({"k": "john", "v": 2})

    env.execute()
    with pytest.raises(RuntimeError):
        env.execute()


def test_local_execution_environment_call_from_list_twice() -> None:
    env = AgentsExecutionEnvironment.get_execution_environment()

    input_list = []

    env.from_list(input_list)
    with pytest.raises(RuntimeError):
        env.from_list(input_list)


# ── Unified event E2E tests ──────────────────────────────────────────────


class UnifiedEventAgent(Agent):
    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def on_input(event: Event, ctx: RunnerContext) -> None:
        ctx.send_event(
            Event(
                type="Intermediate",
                attributes={"msg": InputEvent.from_event(event).input},
            )
        )

    @action("Intermediate")
    @staticmethod
    def on_intermediate(event: Event, ctx: RunnerContext) -> None:
        ctx.send_event(
            OutputEvent(output=f"processed:{event.get_attr('msg')}")
        )


def test_unified_event_workflow() -> None:
    """End-to-end: InputEvent → unified 'Intermediate' → OutputEvent."""
    env = AgentsExecutionEnvironment.get_execution_environment()

    input_list = []
    agent = UnifiedEventAgent()

    output_list = env.from_list(input_list).apply(agent).to_list()

    input_list.append({"key": "alice", "value": "hello"})
    env.execute()

    assert output_list == [{"alice": "processed:hello"}]


class Step1Event(Event):
    """Custom event with a type string."""

    EVENT_TYPE: ClassVar[str] = "_step1_event"

    def __init__(self, data: str) -> None:
        """Create a Step1Event with the given data."""
        super().__init__(
            type=Step1Event.EVENT_TYPE,
            attributes={"data": data},
        )

    @property
    def data(self) -> str:
        """Return the event data."""
        return self.attributes["data"]


class MixedEventAgent(Agent):
    """Agent mixing subclassed and string-based event routing."""

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def start(event: Event, ctx: RunnerContext) -> None:
        ctx.send_event(Step1Event(data=str(InputEvent.from_event(event).input)))

    @action(Step1Event.EVENT_TYPE)
    @staticmethod
    def on_step1(event: Event, ctx: RunnerContext) -> None:
        ctx.send_event(
            Event(type="Step2", attributes={"value": event.get_attr("data")})
        )

    @action("Step2")
    @staticmethod
    def on_step2(event: Event, ctx: RunnerContext) -> None:
        ctx.send_event(
            OutputEvent(output=f"done:{event.get_attr('value')}")
        )


def test_mixed_event_workflow() -> None:
    """E2E: InputEvent → class Step1Event → unified 'Step2' → OutputEvent."""
    env = AgentsExecutionEnvironment.get_execution_environment()

    input_list = []
    agent = MixedEventAgent()

    output_list = env.from_list(input_list).apply(agent).to_list()

    input_list.append({"key": "bob", "value": 42})
    env.execute()

    assert output_list == [{"bob": "done:42"}]


# ── Tool-request capture hook (Track B) ──────────────────────────────────


class _ToolConnection(BaseChatModelConnection):
    """Mock connection emitting a single ``add`` tool call."""

    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
        """Emit an ``add`` tool call, then echo the tool result as content."""
        last = messages[-1]
        if last.role == MessageRole.TOOL:
            return ChatMessage(role=MessageRole.ASSISTANT, content=str(last.content))
        tool_call = {
            "id": str(uuid.uuid4()),
            "type": ToolType.FUNCTION,
            "function": {"name": "add", "arguments": {"a": 1, "b": 2}},
        }
        return ChatMessage(
            role=MessageRole.ASSISTANT, content="", tool_calls=[tool_call]
        )


class _ToolChatModel(BaseChatModelSetup):
    """Mock setup binding the ``add`` tool to the connection."""

    def open(self) -> None:
        """Do nothing."""

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return model kwargs."""
        return {}

    def chat(
        self,
        messages: Sequence[ChatMessage],
        prompt_args: Dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
        """Bind tools and delegate to the connection."""
        server = self.resource_context.get_resource(
            self.connection, ResourceType.CHAT_MODEL_CONNECTION
        )
        tools = [
            self.resource_context.get_resource(name, ResourceType.TOOL)
            for name in (self.tools or [])
        ]
        return server.chat(messages, tools=tools, **kwargs)


class ToolRequestAgent(Agent):
    """Agent whose chat model emits a ToolRequestEvent dispatched to ``add``.

    The InputEvent action sends a ChatRequestEvent; the mock chat model returns
    an ``add`` tool call, which the built-in chat/tool actions turn into a real
    ToolRequestEvent flowing through the runner. The ToolRequestEvent is captured
    by the runner AND still dispatched to ``tool_call_action`` — the final output
    (the tool result) proves capture did not swallow the event.
    """

    @chat_model_connection
    @staticmethod
    def conn() -> ResourceDescriptor:
        """Mock chat model connection."""
        return ResourceDescriptor(
            clazz=f"{_ToolConnection.__module__}.{_ToolConnection.__name__}"
        )

    @chat_model_setup
    @staticmethod
    def model() -> ResourceDescriptor:
        """Mock chat model bound to the ``add`` tool."""
        return ResourceDescriptor(
            clazz=f"{_ToolChatModel.__module__}.{_ToolChatModel.__name__}",
            connection="conn",
            model="mock-model",
            tools=["add"],
        )

    @tool
    @staticmethod
    def add(a: int, b: int) -> int:
        """Return the sum of a and b.

        Parameters
        ----------
        a : int
            The first operand.
        b : int
            The second operand.

        Returns:
        -------
        int:
            The sum of a and b.
        """
        return a + b

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        """Send a ChatRequestEvent to drive the tool-calling flow."""
        input = InputEvent.from_event(event).input
        ctx.send_event(
            ChatRequestEvent(
                model="model",
                messages=[ChatMessage(role=MessageRole.USER, content=input)],
            )
        )

    @action(ChatResponseEvent.EVENT_TYPE)
    @staticmethod
    def process_response(event: Event, ctx: RunnerContext) -> None:
        """Emit the final assistant content as output."""
        response = ChatResponseEvent.from_event(event).response
        ctx.send_event(OutputEvent(output=response.content))


def test_local_runner_captures_tool_request_events() -> None:
    """A ToolRequestEvent is captured AND still dispatched to its action."""
    env = AgentsExecutionEnvironment.get_execution_environment()

    input_list = []
    agent = ToolRequestAgent()

    output_list = env.from_list(input_list).apply(agent).to_list()

    input_list.append({"key": "0001", "value": "add 1 and 2"})
    env.execute()

    captured = env.get_tool_request_events()
    assert len(captured) == 1
    assert captured[0].tool_calls[0]["function"] == {
        "name": "add",
        "arguments": {"a": 1, "b": 2},
    }
    # Dispatch was not swallowed: tool_call_action ran, producing the tool result
    # that the model echoed back as the final output.
    assert output_list == [{"0001": "3"}]
