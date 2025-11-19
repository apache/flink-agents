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
import uuid
from typing import Any, Dict, List, Sequence

from flink_agents.api.agent import Agent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
)
from flink_agents.api.decorators import (
    action,
    chat_model_connection,
    chat_model_setup,
    prompt,
    tool,
)
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import InputEvent, OutputEvent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import ResourceDescriptor, ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.tools.tool import ToolType


class MockChatModelConnection(BaseChatModelConnection):
    """Mock ChatModel for testing integrating prompt and tool."""

    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
        """Generate tool call or response according to input."""
        # Generate tool call
        if "sum" in messages[-1].content:
            input = messages[-1].content
            # Validate bind_tools
            assert tools[0].name == "add"
            function = {"name": "add", "arguments": {"a": 1, "b": 2}}
            tool_call = {
                "id": uuid.uuid4(),
                "type": ToolType.FUNCTION,
                "function": function,
            }
            return ChatMessage(
                role=MessageRole.ASSISTANT, content=input, tool_calls=[tool_call]
            )
        # Generate response including tool call context
        else:
            content = "\n".join([message.content for message in messages])
            return ChatMessage(role=MessageRole.ASSISTANT, content=content)


class MockChatModel(BaseChatModelSetup):
    """Mock ChatModel for testing integrating prompt and tool."""

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return model kwargs."""
        return {}

    def chat(self, messages: Sequence[ChatMessage], **kwargs: Any) -> ChatMessage:
        """Execute chat conversation."""
        # Get model connection
        server = self.get_resource(self.connection, ResourceType.CHAT_MODEL_CONNECTION)

        # Apply prompt template
        if self.prompt is not None:
            if isinstance(self.prompt, str):
                # Get prompt resource if it's a string
                prompt = self.get_resource(self.prompt, ResourceType.PROMPT)
            else:
                prompt = self.prompt

            if "sum" in messages[-1].content:
                input_variable = {}
                for msg in messages:
                    # Convert Any values to str to match format_messages signature
                    str_extra_args = {k: str(v) for k, v in msg.extra_args.items()}
                    input_variable.update(str_extra_args)
                messages = prompt.format_messages(**input_variable)

        # Bind tools
        tools = None
        if self.tools is not None:
            tools = [
                self.get_resource(tool_name, ResourceType.TOOL)
                for tool_name in self.tools
            ]

        # Call server to execute chat
        return server.chat(messages, tools=tools, **kwargs)


class MyAgent(Agent):
    """Mock agent for testing built-in actions."""

    @prompt
    @staticmethod
    def prompt() -> Prompt:
        """Prompt can be used in action or chat model."""
        return Prompt.from_text(
            text="Please call the appropriate tool to do the following task: {task}",
        )

    @chat_model_connection
    @staticmethod
    def mock_connection() -> ResourceDescriptor:
        """Chat model server can be used by ChatModel."""
        return ResourceDescriptor(clazz=MockChatModelConnection)

    @chat_model_setup
    @staticmethod
    def mock_chat_model() -> ResourceDescriptor:
        """Chat model can be used in action."""
        return ResourceDescriptor(
            clazz=MockChatModel,
            connection="mock_connection",
            prompt="prompt",
            tools=["add"],
        )

    @tool
    @staticmethod
    def add(a: int, b: int) -> int:
        """Calculate the sum of a and b.

        Parameters
        ----------
        a : int
            The first operand
        b : int
            The second operand

        Returns:
        -------
        int:
            The sum of a and b
        """
        return a + b

    @action(InputEvent)
    @staticmethod
    def process_input(event: InputEvent, ctx: RunnerContext) -> None:
        """User defined action for processing input.

        In this action, we will send ChatRequestEvent to trigger built-in actions.
        """
        input = event.input
        ctx.send_event(
            ChatRequestEvent(
                model="mock_chat_model",
                messages=[
                    ChatMessage(
                        role=MessageRole.USER, content=input, extra_args={"task": input}
                    )
                ],
            )
        )

    @action(ChatResponseEvent)
    @staticmethod
    def process_chat_response(event: ChatResponseEvent, ctx: RunnerContext) -> None:
        """User defined action for processing chat model response."""
        input = event.response
        ctx.send_event(OutputEvent(output=input.content))


def test_built_in_actions() -> None:  # noqa: D103
    env = AgentsExecutionEnvironment.get_execution_environment()

    input_list = []
    agent = MyAgent()

    output_list = env.from_list(input_list).apply(agent).to_list()

    input_list.append({"key": "0001", "value": "calculate the sum of 1 and 2."})

    env.execute()

    assert output_list == [
        {
            "0001": "calculate the sum of 1 and 2.\n"
            "Please call the appropriate tool to do the following task: "
            "calculate the sum of 1 and 2.\n"
            "3"
        }
    ]
