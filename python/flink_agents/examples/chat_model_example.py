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
from typing import Any, Dict, List, Tuple, Type

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
    tool,
)
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
from flink_agents.integrations.chat_models.tongyi_chat_model import (
    TongyiChatModelConnection,
    TongyiChatModelSetup,
)

TONGYI_MODEL = os.environ.get("TONGYI_CHAT_MODEL", "qwen-plus")
OLLAMA_MODEL = os.environ.get("OLLAMA_CHAT_MODEL", "qwen3:0.6b")
BACKENDS_TO_RUN: List[str] = ["Tongyi", "Ollama"]

class MyAgent(Agent):
    """Example agent demonstrating the new ChatModel architecture."""

    @chat_model_connection
    @staticmethod
    def tongyi_connection() -> Tuple[Type[BaseChatModelConnection], Dict[str, Any]]:
        """ChatModelConnection responsible for tongyi model service connection."""
        if not os.environ.get("DASHSCOPE_API_KEY"):
            msg = "Please set the 'DASHSCOPE_API_KEY' environment variable."
            raise ValueError(msg)
        return TongyiChatModelConnection, {
            "model": TONGYI_MODEL,
        }

    @chat_model_connection
    @staticmethod
    def ollama_connection() -> Tuple[Type[BaseChatModelConnection], Dict[str, Any]]:
        """ChatModelConnection responsible for ollama model service connection."""
        return OllamaChatModelConnection, {
            "model": OLLAMA_MODEL,
        }

    @chat_model_setup
    @staticmethod
    def math_chat_model() -> Tuple[Type[BaseChatModelSetup], Dict[str, Any]]:
        """ChatModel which focus on math, and reuse ChatModelConnection."""
        if CURRENT_BACKEND == "Tongyi":
            return TongyiChatModelSetup, {
                "connection": "tongyi_connection",
                "tools": ["add"],
            }
        else:
            return OllamaChatModelSetup, {
                "connection": "ollama_connection",
                "tools": ["add"],
                "extract_reasoning": True,
            }

    @chat_model_setup
    @staticmethod
    def creative_chat_model() -> Tuple[Type[BaseChatModelSetup], Dict[str, Any]]:
        """ChatModel which focus on text generate, and reuse ChatModelConnection."""
        if CURRENT_BACKEND == "Tongyi":
            return TongyiChatModelSetup, {
                "connection": "tongyi_connection",
            }
        else:
            return OllamaChatModelSetup, {
                "connection": "ollama_connection",
                "extract_reasoning": True,
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
        input_text = event.input.lower()
        model_name = "math_chat_model" if ("calculate" in input_text or "sum" in input_text) else "creative_chat_model"
        ctx.send_event(ChatRequestEvent(model=model_name, messages=[ChatMessage(role=MessageRole.USER, content=event.input)]))

    @action(ChatResponseEvent)
    @staticmethod
    def process_chat_response(event: ChatResponseEvent, ctx: RunnerContext) -> None:
        """User defined action for processing chat model response."""
        input = event.response
        if event.response and input.content:
            ctx.send_event(OutputEvent(output=input.content))


if __name__ == "__main__":
    for backend in BACKENDS_TO_RUN:
        CURRENT_BACKEND = backend
        CURRENT_MODEL = TONGYI_MODEL if backend == "Tongyi" else OLLAMA_MODEL

        if backend == "Tongyi" and not os.environ.get("DASHSCOPE_API_KEY"):
            print("[SKIP] TongyiChatModel because DASHSCOPE_API_KEY is not set.")
            continue

        print(f"\nRunning {backend}ChatModel while the using model is {CURRENT_MODEL}...")

        env = AgentsExecutionEnvironment.get_execution_environment()
        input_list = []
        agent = MyAgent()

        output_list = env.from_list(input_list).apply(agent).to_list()

        input_list.append({"key": "0001", "value": "calculate the sum of 1 and 2."})
        input_list.append({"key": "0002", "value": "Tell me a joke about cats."})

        env.execute()

        for output in output_list:
            for key, value in output.items():
                print(f"{key}: {value}")

