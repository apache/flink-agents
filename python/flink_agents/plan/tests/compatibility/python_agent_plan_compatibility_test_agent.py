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
from typing import Any, Dict, Sequence, Tuple, Type

from flink_agents.api.agent import Agent
from flink_agents.api.chat_message import ChatMessage
from flink_agents.api.chat_models.chat_model import BaseChatModelSetup
from flink_agents.api.decorators import action, chat_model_setup, tool
from flink_agents.api.events.event import Event, InputEvent
from flink_agents.api.runner_context import RunnerContext


class MyEvent(Event):
    """Test event."""


class MockChatModel(BaseChatModelSetup):
    """Mock ChatModel for testing integrating prompt and tool."""

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Only for testing."""
        return {}

    def chat(self, messages: Sequence[ChatMessage], **kwargs: Any) -> ChatMessage:
        """Only for test plan compatibility."""


class PythonAgentPlanCompatibilityTestAgent(Agent):
    """Agent for generating python agent plan json."""

    @action(InputEvent)
    @staticmethod
    def first_action(event: InputEvent, ctx: RunnerContext) -> None:
        """Test implementation."""

    @action(InputEvent, MyEvent)
    @staticmethod
    def second_action(event: InputEvent, ctx: RunnerContext) -> None:
        """Test implementation."""

    @chat_model_setup
    @staticmethod
    def chat_model() -> Tuple[Type[BaseChatModelSetup], Dict[str, Any]]:
        """ChatModel can be used in action."""
        return MockChatModel, {
            "name": "chat_model",
            "prompt": "prompt",
            "tools": ["add"],
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
