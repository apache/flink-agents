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
from typing import Any, Dict, Sequence

from pyflink.datastream import StreamExecutionEnvironment
from typing_extensions import override

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import BaseChatModelSetup
from flink_agents.api.decorators import action, chat_model_setup, tool
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.resource import ResourceDescriptor
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.tools.tool import ToolType


class SlowMockChatModel(BaseChatModelSetup):
    """Mock ChatModel with slow connection."""

    def open(self) -> None:
        """Do nothing."""

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        return {}

    @override
    def chat(self, messages: Sequence[ChatMessage], **kwargs: Any) -> ChatMessage:
        time.sleep(5)  # Simulate network delay
        if "sum" in messages[-1].content:
            input = messages[-1].content
            function = {"name": "add", "arguments": {"a": 1, "b": 2}}
            tool_call = {
                "id": uuid.uuid4(),
                "type": ToolType.FUNCTION,
                "function": function,
            }
            return ChatMessage(
                role=MessageRole.ASSISTANT, content=input, tool_calls=[tool_call]
            )
        else:
            content = "\n".join([message.content for message in messages])
            return ChatMessage(role=MessageRole.ASSISTANT, content=content)


class AsyncTestAgent(Agent):
    """Agent for testing async execution."""

    @chat_model_setup
    @staticmethod
    def slow_chat_model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=f"{SlowMockChatModel.__module__}.{SlowMockChatModel.__name__}",
            connection="placement",
            tools=["add"],
        )

    @tool
    @staticmethod
    def add(a: int, b: int) -> int:
        """Calculate the sum of a and b."""
        time.sleep(5)  # Simulate slow tool execution
        return a + b

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        input_event = InputEvent.from_event(event)
        input = input_event.input
        ctx.send_event(
            ChatRequestEvent(
                model="slow_chat_model",
                messages=[
                    ChatMessage(
                        role=MessageRole.USER, content=input, extra_args={"task": input}
                    )
                ],
            )
        )

    @action(ChatResponseEvent.EVENT_TYPE)
    @staticmethod
    def process_chat_response(event: Event, ctx: RunnerContext) -> None:
        input = ChatResponseEvent.from_event(event).response
        ctx.send_event(OutputEvent(output=input.content))


def test_built_in_actions_async_execution() -> None:
    """Test that built-in actions use async execution correctly.

    This test verifies that chat_model_action and tool_call_action work
    correctly with async execution, ensuring backward compatibility.
    """
    env = StreamExecutionEnvironment.get_execution_environment()
    env.set_parallelism(1)

    input_stream = env.from_collection(
        ["calculate the sum of 1 and 2" for _ in range(10)],
    )

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    output_datastream = (
        agents_env.from_datastream(
            input=input_stream, key_selector=lambda x: uuid.uuid4()
        )
        .apply(AsyncTestAgent())
        .to_datastream()
    )

    output_datastream.print()

    # Measure execution time to verify async doesn't block
    start_time = time.time()
    agents_env.execute()
    execution_time = time.time() - start_time

    assert execution_time < 50
