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
import threading
from typing import Any, List, Sequence

from pydantic import PrivateAttr
from typing_extensions import override

from flink_agents.api.agents.agent import Agent
from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import BaseChatModelConnection
from flink_agents.api.decorators import action, chat_model_connection, chat_model_setup
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.resource import ResourceDescriptor, ResourceName
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.tools.tool import Tool


class OverlappingPythonChatModelConnection(BaseChatModelConnection):
    """Python connection that only returns after two chat requests overlap."""

    _concurrent_calls: threading.Barrier = PrivateAttr(
        default_factory=lambda: threading.Barrier(2)
    )

    @override
    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List[Tool] | None = None,
        output_schema: OutputSchema | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
        """Echo the request after observing another in-flight call."""
        self._reject_unsupported_output_schema(output_schema)
        try:
            self._concurrent_calls.wait(timeout=30)
        except threading.BrokenBarrierError as error:
            message = "Timed out waiting for concurrent cross-language chat request."
            raise RuntimeError(message) from error

        return ChatMessage(
            role=MessageRole.ASSISTANT,
            content=f"python-connection:{messages[-1].content}",
        )


class ConcurrentChatModelCrossLanguageAgent(Agent):
    """Python agent exercising concurrent Python-to-Java-to-Python chat calls."""

    @chat_model_connection
    @staticmethod
    def overlapping_python_connection() -> ResourceDescriptor:
        """Declare the Python connection used behind the Java setup."""
        return ResourceDescriptor(
            clazz=(
                f"{OverlappingPythonChatModelConnection.__module__}."
                f"{OverlappingPythonChatModelConnection.__name__}"
            )
        )

    @chat_model_setup
    @staticmethod
    def java_chat_model() -> ResourceDescriptor:
        """Declare a Java setup backed by the Python connection."""
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.JAVA_WRAPPER_SETUP,
            java_clazz=ResourceName.ChatModel.Java.OLLAMA_SETUP,
            connection="overlapping_python_connection",
            model="mock-model",
            extract_reasoning=False,
        )

    @action(EventType.InputEvent)
    @staticmethod
    def request_chat(event: Event, ctx: RunnerContext) -> None:
        """Send one chat request per input key."""
        input_value = str(InputEvent.from_event(event).input)
        ctx.send_event(
            ChatRequestEvent(
                model="java_chat_model",
                messages=[ChatMessage(role=MessageRole.USER, content=input_value)],
            )
        )

    @action(EventType.ChatResponseEvent)
    @staticmethod
    def emit_response(event: Event, ctx: RunnerContext) -> None:
        """Emit the cross-language chat response."""
        response = ChatResponseEvent.from_event(event).response
        ctx.send_event(OutputEvent(output=response.content))
