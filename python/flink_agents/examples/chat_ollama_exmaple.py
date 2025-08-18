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
from typing import Any, Dict, Tuple, Type

from flink_agents.api.agent import Agent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
)
from flink_agents.api.decorators import action, chat_model, chat_model_server, tool
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import (
    InputEvent,
    OutputEvent,
)
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.runner_context import RunnerContext
from flink_agents.integrations.chat_models.ollama_chat_model import (
    OllamaChatModelConnection,
    OllamaChatModelSetup,
)

model = os.environ.get("OLLAMA_CHAT_MODEL", "qwen3:8b")


class MyAgent(Agent):
    """Example agent demonstrating the new ChatModel architecture."""

    @chat_model_server
    @staticmethod
    def ollama_server() -> Tuple[Type[BaseChatModelConnection], Dict[str, Any]]:
        """ChatModelServer responsible for model service connection."""
        return OllamaChatModelConnection, {
            "name": "ollama_server",
            "model": model,
        }

    @chat_model
    @staticmethod
    def math_chat_model() -> Tuple[Type[BaseChatModelSetup], Dict[str, Any]]:
        """ChatModel which focus on math, and reuse ChatModelServer."""
        return OllamaChatModelSetup, {
            "name": "math_chat_model",
            "connection": "ollama_server",
            "tools": ["add"],
        }

    @chat_model
    @staticmethod
    def creative_chat_model() -> Tuple[Type[BaseChatModelSetup], Dict[str, Any]]:
        """ChatModel which focus on text generate, and reuse ChatModelServer."""
        return OllamaChatModelSetup, {
            "name": "creative_chat_model",
            "connection": "ollama_server",
        }

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
        """Choose different sessions based on input content."""
        input_text = event.input.lower()

        if "calculate" in input_text or "sum" in input_text:
            # Use math_session for calculations
            model_name = "math_chat_model"
        else:
            # Use creative_session for other tasks
            model_name = "creative_chat_model"

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
        ctx.send_event(OutputEvent(output=input.content))


# Should manually start ollama server before run this example.
if __name__ == "__main__":
    env = AgentsExecutionEnvironment.get_execution_environment()

    input_list = []
    agent = MyAgent()  # Or use NewArchitectureAgent() to test new architecture

    output_list = env.from_list(input_list).apply(agent).to_list()

    input_list.append({"key": "0001", "value": "calculate the sum of 1 and 2."})
    input_list.append({"key": "0002", "value": "Can you tell a joke about cat."})

    env.execute()

    for output in output_list:
        for key, value in output.items():
            print(f"{key}: {value}")
