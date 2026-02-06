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
import os

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.decorators import (
    action,
    chat_model_connection,
    chat_model_setup,
    prompt,
    tool,
)
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import InputEvent, OutputEvent
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import (
    ResourceDescriptor,
    ResourceName,
)
from flink_agents.api.runner_context import RunnerContext


class ChatModelCrossLanguageAgent(Agent):
    """Example agent demonstrating cross-language integration testing.

    This test includes:
    - Python scheduling Java ChatModel
    - Java ChatModel scheduling Python Prompt
    - Java ChatModel scheduling Python Tool
    """

    @prompt
    @staticmethod
    def from_text_prompt() -> Prompt:
        """Prompt for instruction."""
        return Prompt.from_text("Please answer the user's question.")

    @prompt
    @staticmethod
    def from_messages_prompt() -> Prompt:
        """Prompt for instruction."""
        return Prompt.from_messages(
            messages=[
                ChatMessage(
                    role=MessageRole.SYSTEM,
                    content="Please answer the user's question.",
                ),
            ],
        )

    @chat_model_connection
    @staticmethod
    def ollama_connection_python() -> ResourceDescriptor:
        """ChatModelConnection responsible for ollama model service connection."""
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_CONNECTION, request_timeout=240.0
        )

    @chat_model_connection
    @staticmethod
    def ollama_connection_java() -> ResourceDescriptor:
        """ChatModelConnection responsible for ollama model service connection."""
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.JAVA_WRAPPER_CONNECTION,
            java_clazz=ResourceName.ChatModel.Java.OLLAMA_CONNECTION,
            endpoint="http://localhost:11434",
            requestTimeout=120,
        )

    @chat_model_setup
    @staticmethod
    def math_chat_model() -> ResourceDescriptor:
        """ChatModel which focus on math, and reuse ChatModelConnection."""
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.JAVA_WRAPPER_SETUP,
            java_clazz=ResourceName.ChatModel.Java.OLLAMA_SETUP,
            connection="ollama_connection_python",
            model=os.environ.get("OLLAMA_CHAT_MODEL", "qwen3:1.7b"),
            prompt="from_messages_prompt",
            tools=["add"],
            extract_reasoning=True,
        )

    @chat_model_setup
    @staticmethod
    def creative_chat_model() -> ResourceDescriptor:
        """ChatModel which focus on text generate, and reuse ChatModelConnection."""
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.JAVA_WRAPPER_SETUP,
            java_clazz=ResourceName.ChatModel.Java.OLLAMA_SETUP,
            connection="ollama_connection_java",
            model=os.environ.get("OLLAMA_CHAT_MODEL", "qwen3:1.7b"),
            prompt="from_text_prompt",
            extract_reasoning=True,
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
        input_text = event.input.lower()
        model_name = (
            "math_chat_model"
            if ("calculate" in input_text or "sum" in input_text)
            else "creative_chat_model"
        )
        ctx.send_event(
            ChatRequestEvent(
                model=model_name,
                messages=[ChatMessage(role=MessageRole.USER, content=event.input)],
            )
        )

    @action(ChatResponseEvent)
    @staticmethod
    def process_chat_response(event: ChatResponseEvent, ctx: RunnerContext) -> None:
        """User defined action for processing chat model response."""
        input = event.response
        if event.response and input.content:
            ctx.send_event(OutputEvent(output=input.content))
