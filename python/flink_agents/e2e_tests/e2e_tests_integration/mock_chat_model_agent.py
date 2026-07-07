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
"""Agent definitions exercising a deterministic mock chat model in Flink.

These agents run the built-in ``chat_model_action``/``tool_call_action`` flow and
in-action ``get_resource`` resolution against a mock chat model, so the resulting
content is stable without any external LLM service.
"""

import uuid
from typing import Any, Dict, List, Sequence

from pydantic import BaseModel
from pyflink.datastream import KeySelector

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
    prompt,
    tool,
)
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import ResourceDescriptor, ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.tools.tool import ToolType


class MockChatModelInput(BaseModel):
    """Input record for the mock chat model agents.

    Attributes:
    ----------
    id : int
        Unique identifier used as the partition key.
    content : str
        The user message content fed to the agent.
    """

    id: int
    content: str


class MockChatModelOutput(BaseModel):
    """Output record capturing the deterministic chat model content.

    Attributes:
    ----------
    id : int
        Unique identifier echoed from the input record.
    result : str
        The chat model content produced for the record.
    """

    id: int
    result: str


class MockChatModelKeySelector(KeySelector):
    """KeySelector extracting the partition key from a MockChatModelInput."""

    def get_key(self, value: MockChatModelInput) -> int:
        """Extract key from MockChatModelInput."""
        return value.id


class MockChatModelConnection(BaseChatModelConnection):
    """Mock chat model connection integrating prompt and tool."""

    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
        """Generate a tool call or a response according to input."""
        if "sum" in messages[-1].content:
            input = messages[-1].content
            # Validate the tool was bound before the model was invoked.
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
        content = "\n".join([message.content for message in messages])
        return ChatMessage(role=MessageRole.ASSISTANT, content=content)


class MockChatModel(BaseChatModelSetup):
    """Mock chat model setup integrating prompt and tool."""

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
        """Execute chat conversation."""
        server = self.resource_context.get_resource(
            self.connection, ResourceType.CHAT_MODEL_CONNECTION
        )

        if self.prompt is not None:
            if isinstance(self.prompt, str):
                prompt = self.resource_context.get_resource(
                    self.prompt, ResourceType.PROMPT
                )
            else:
                prompt = self.prompt

            if "sum" in messages[-1].content:
                str_prompt_args = (
                    {k: str(v) for k, v in prompt_args.items()} if prompt_args else {}
                )
                messages = prompt.format_messages(**str_prompt_args)

        tools = None
        if self.tools is not None:
            tools = [
                self.resource_context.get_resource(tool_name, ResourceType.TOOL)
                for tool_name in self.tools
            ]

        return server.chat(messages, tools=tools, **kwargs)


class BuiltInActionAgent(Agent):
    """Agent driving the built-in chat/tool actions with a mock chat model."""

    @prompt
    @staticmethod
    def prompt() -> Prompt:
        """Prompt used by the mock chat model."""
        return Prompt.from_text(
            text="Please call the appropriate tool to do the following task: {task}",
        )

    @chat_model_connection
    @staticmethod
    def mock_connection() -> ResourceDescriptor:
        """Chat model connection used by the mock chat model."""
        return ResourceDescriptor(
            clazz=f"{MockChatModelConnection.__module__}."
            f"{MockChatModelConnection.__name__}"
        )

    @chat_model_setup
    @staticmethod
    def mock_chat_model() -> ResourceDescriptor:
        """Chat model referenced by the ChatRequestEvent."""
        return ResourceDescriptor(
            clazz=f"{MockChatModel.__module__}.{MockChatModel.__name__}",
            connection="mock_connection",
            model="mock-model",
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

    @action(EventType.InputEvent)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        """Send a ChatRequestEvent to trigger the built-in actions."""
        input_data = MockChatModelInput.model_validate(
            InputEvent.from_event(event).input
        )
        # Carry the record id across the chat round-trip via per-key memory,
        # since ChatResponseEvent does not echo the original input.
        ctx.short_term_memory.set("input_id", input_data.id)
        ctx.send_event(
            ChatRequestEvent(
                model="mock_chat_model",
                messages=[
                    ChatMessage(role=MessageRole.USER, content=input_data.content)
                ],
                prompt_args={"task": input_data.content},
            )
        )

    @action(EventType.ChatResponseEvent)
    @staticmethod
    def process_chat_response(event: Event, ctx: RunnerContext) -> None:
        """Emit the chat model content, keyed by the original record id."""
        response = ChatResponseEvent.from_event(event).response
        input_id = ctx.short_term_memory.get("input_id")
        ctx.send_event(
            OutputEvent(
                output=MockChatModelOutput(id=input_id, result=response.content)
            )
        )


class GetResourceChatModel(BaseChatModelSetup):
    """Mock chat model setup carrying custom descriptor fields."""

    host: str
    desc: str

    def open(self) -> None:
        """Do nothing."""

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return model kwargs."""
        return {}

    def chat(self, messages: Sequence[ChatMessage], **kwargs: Any) -> ChatMessage:
        """Echo the input alongside the custom descriptor fields."""
        return ChatMessage(
            role=MessageRole.ASSISTANT,
            content=f"{messages[0].content} {self.host} {self.desc}",
        )


class GetResourceAgent(Agent):
    """Agent resolving a chat model via get_resource inside an action."""

    @chat_model_setup
    @staticmethod
    def mock_chat_model() -> ResourceDescriptor:
        """Chat model carrying custom descriptor fields."""
        return ResourceDescriptor(
            clazz=f"{GetResourceChatModel.__module__}."
            f"{GetResourceChatModel.__name__}",
            host="8.8.8.8",
            desc="mock chat model just for testing.",
            connection="mock",
            model="mock-model",
        )

    @action(EventType.InputEvent)
    @staticmethod
    def mock_action(event: Event, ctx: RunnerContext) -> None:
        """Resolve the chat model and emit its content, keyed by record id."""
        input_data = MockChatModelInput.model_validate(
            InputEvent.from_event(event).input
        )
        mock_chat_model = ctx.get_resource(
            name="mock_chat_model", type=ResourceType.CHAT_MODEL
        )
        content = mock_chat_model.chat(
            messages=[ChatMessage(role=MessageRole.USER, content=input_data.content)]
        ).content
        ctx.send_event(
            OutputEvent(
                output=MockChatModelOutput(id=input_data.id, result=content)
            )
        )
