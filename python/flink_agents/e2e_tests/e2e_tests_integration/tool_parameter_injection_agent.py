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
from pyflink.datastream import KeySelector

from flink_agents.api.agents.agent import Agent
from flink_agents.api.agents.types import OutputSchema
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
from flink_agents.api.resource import ResourceDescriptor
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.tools import InjectedArg
from flink_agents.api.tools.tool import Tool as BaseTool


class OrderKeySelector(KeySelector):
    """Key orders by their identifier."""

    def get_key(self, value: str) -> str:
        """Return the order id as key."""
        return value


class ToolParameterInjectionAgent(Agent):
    """E2E agent covering tool parameter injection in a real Flink job."""

    @chat_model_connection
    @staticmethod
    def mock_connection() -> ResourceDescriptor:
        """Declare the mock chat connection."""
        return ResourceDescriptor(
            clazz=f"{MockToolChatConnection.__module__}.{MockToolChatConnection.__name__}"
        )

    @chat_model_setup
    @staticmethod
    def mock_model() -> ResourceDescriptor:
        """Declare the mock chat model bound to query_order."""
        return ResourceDescriptor(
            clazz=f"{MockToolChatModel.__module__}.{MockToolChatModel.__name__}",
            connection="mock_connection",
            model="mock",
            tools=["query_order"],
        )

    @tool(injected_args={"tenant_id": InjectedArg.from_config("tenant_id")})
    @staticmethod
    def query_order(order_id: str, tenant_id: str) -> str:
        """Query order in the current tenant.

        Parameters
        ----------
        order_id : str
            The order id requested by the model.
        tenant_id : str
            The tenant id injected by runtime.

        Returns:
        -------
        str:
            The tenant-scoped order identifier.
        """
        return f"checked:{tenant_id}:{order_id}"

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def request_chat(event: Event, ctx: RunnerContext) -> None:
        """Send a chat request that lets the mock model call the tool."""
        order_id = str(InputEvent.from_event(event).input)
        ctx.send_event(
            ChatRequestEvent(
                model="mock_model",
                messages=[ChatMessage(role=MessageRole.USER, content=order_id)],
            )
        )

    @action(ChatResponseEvent.EVENT_TYPE)
    @staticmethod
    def emit_result(event: Event, ctx: RunnerContext) -> None:
        """Emit the final assistant response as output."""
        response_event = ChatResponseEvent.from_event(event)
        ctx.send_event(OutputEvent(output=response_event.response.content))


class MockToolChatConnection(BaseChatModelConnection):
    """Mock model connection that emits a tool call, then echoes tool output."""

    def chat(
        self,
        messages: list[ChatMessage],
        tools: list[BaseTool] | None = None,
        output_schema: OutputSchema | None = None,
        **kwargs: object,
    ) -> ChatMessage:
        """Return a tool call for user input, or echo the tool response.

        A non-``None`` ``output_schema`` is rejected: this connection has no native
        structured-output translation. Declaring the parameter keeps a caller-supplied
        schema out of ``**kwargs``.
        """
        self._reject_unsupported_output_schema(output_schema)
        last_message = messages[-1]
        if last_message.role == MessageRole.TOOL:
            return ChatMessage(role=MessageRole.ASSISTANT, content=last_message.content)

        for candidate_tool in tools or []:
            if "tenant_id" in str(candidate_tool.metadata.get_parameters_dict()):
                msg = "Injected argument leaked into tool schema."
                raise RuntimeError(msg)

        order_id = str(last_message.content)
        return ChatMessage(
            role=MessageRole.ASSISTANT,
            content="",
            tool_calls=[
                {
                    "id": f"call-{order_id}",
                    "type": "function",
                    "function": {
                        "name": "query_order",
                        "arguments": {"order_id": order_id},
                    },
                }
            ],
        )


class MockToolChatModel(BaseChatModelSetup):
    """Mock chat model setup."""

    @property
    def model_kwargs(self):
        """Return model kwargs."""
        return {}
