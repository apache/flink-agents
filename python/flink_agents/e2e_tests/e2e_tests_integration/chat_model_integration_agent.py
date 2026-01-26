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
    tool,
)
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import InputEvent, OutputEvent
from flink_agents.api.resource import (
    ResourceDescriptor,
    ResourceName,
)
from flink_agents.api.runner_context import RunnerContext


class ChatModelTestAgent(Agent):
    """Example agent demonstrating the new ChatModel architecture."""

    @chat_model_connection
    @staticmethod
    def openai_connection() -> ResourceDescriptor:
        """ChatModelConnection responsible for openai model service connection."""
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OPENAI_CONNECTION, api_key=os.environ.get("OPENAI_API_KEY")
        )

    @chat_model_connection
    @staticmethod
    def azure_openai_connection() -> ResourceDescriptor:
        """ChatModelConnection responsible for openai model service connection."""
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.AZURE_OPENAI_CONNECTION,
            api_key=os.environ.get("AZURE_OPENAI_API_KEY"),
            api_version=os.environ.get("AZURE_OPENAI_API_VERSION"),
            azure_endpoint=os.environ.get("AZURE_OPENAI_ENDPOINT"),
        )

    @chat_model_connection
    @staticmethod
    def tongyi_connection() -> ResourceDescriptor:
        """ChatModelConnection responsible for tongyi model service connection."""
        return ResourceDescriptor(clazz=ResourceName.ChatModel.TONGYI_CONNECTION)

    @chat_model_connection
    @staticmethod
    def ollama_connection() -> ResourceDescriptor:
        """ChatModelConnection responsible for ollama model service connection."""
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_CONNECTION, request_timeout=240.0
        )

    @chat_model_setup
    @staticmethod
    def math_chat_model() -> ResourceDescriptor:
        """ChatModel which focus on math, and reuse ChatModelConnection."""
        model_provider = os.environ.get("MODEL_PROVIDER")
        if model_provider == "Tongyi":
            return ResourceDescriptor(
                clazz=ResourceName.ChatModel.TONGYI_SETUP,
                connection="tongyi_connection",
                model=os.environ.get("TONGYI_CHAT_MODEL", "qwen-plus"),
                tools=["add"],
            )
        elif model_provider == "Ollama":
            return ResourceDescriptor(
                clazz=ResourceName.ChatModel.OLLAMA_SETUP,
                connection="ollama_connection",
                model=os.environ.get("OLLAMA_CHAT_MODEL", "qwen3:1.7b"),
                tools=["add"],
                extract_reasoning=True,
            )
        elif model_provider == "OpenAI":
            return ResourceDescriptor(
                clazz=ResourceName.ChatModel.OPENAI_SETUP,
                connection="openai_connection",
                model=os.environ.get("OPENAI_CHAT_MODEL", "gpt-3.5-turbo"),
                tools=["add"],
            )
        elif model_provider == "AzureOpenAI":
            return ResourceDescriptor(
                clazz=ResourceName.ChatModel.AZURE_OPENAI_SETUP,
                connection="azure_openai_connection",
                model=os.environ.get("AZURE_OPENAI_CHAT_MODEL", "gpt-5"),
                tools=["add"],
            )
        else:
            err_msg = f"Unknown model_provider {model_provider}"
            raise RuntimeError(err_msg)

    @chat_model_setup
    @staticmethod
    def creative_chat_model() -> ResourceDescriptor:
        """ChatModel which focus on text generate, and reuse ChatModelConnection."""
        model_provider = os.environ.get("MODEL_PROVIDER")
        if model_provider == "Tongyi":
            return ResourceDescriptor(
                clazz=ResourceName.ChatModel.TONGYI_SETUP,
                connection="tongyi_connection",
                model=os.environ.get("TONGYI_CHAT_MODEL", "qwen-plus"),
            )
        elif model_provider == "Ollama":
            return ResourceDescriptor(
                clazz=ResourceName.ChatModel.OLLAMA_SETUP,
                connection="ollama_connection",
                model=os.environ.get("OLLAMA_CHAT_MODEL", "qwen3:1.7b"),
                extract_reasoning=True,
            )
        elif model_provider == "OpenAI":
            return ResourceDescriptor(
                clazz=ResourceName.ChatModel.OPENAI_SETUP,
                connection="openai_connection",
                model=os.environ.get("OPENAI_CHAT_MODEL", "gpt-3.5-turbo"),
            )
        elif model_provider == "AzureOpenAI":
            return ResourceDescriptor(
                clazz=ResourceName.ChatModel.AZURE_OPENAI_SETUP,
                connection="azure_openai_connection",
                model=os.environ.get("AZURE_OPENAI_CHAT_MODEL", "gpt-5"),
            )
        else:
            err_msg = f"Unknown model_provider {model_provider}"
            raise RuntimeError(err_msg)

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
