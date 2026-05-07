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
from typing import Any, Dict, Sequence

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import BaseChatModelSetup
from flink_agents.api.decorators import action, chat_model_setup, tool
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.resource import ResourceDescriptor, ResourceType
from flink_agents.api.runner_context import RunnerContext


class MockChatModelImpl(BaseChatModelSetup):
    host: str
    desc: str

    def open(self) -> None:
        """Do nothing."""

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        return {}

    def chat(self, messages: Sequence[ChatMessage], **kwargs: Any) -> ChatMessage:
        return ChatMessage(
            role=MessageRole.ASSISTANT,
            content=f"{messages[0].content} {self.host} {self.desc}",
        )


class MyAgent(Agent):
    @chat_model_setup
    @staticmethod
    def mock_chat_model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=f"{MockChatModelImpl.__module__}.{MockChatModelImpl.__name__}",
            host="8.8.8.8",
            desc="mock chat model just for testing.",
            connection="mock",
        )

    @tool
    @staticmethod
    def mock_tool(input: str) -> str:
        """Mock tool.

        Parameters
        ----------
        input : str
            The input message.

        Returns:
        -------
        strs
            Response string value.
        """
        return input + " mock tools just for testing."

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def mock_action(event: Event, ctx: RunnerContext) -> None:
        input = InputEvent.from_event(event).input
        mock_chat_model = ctx.get_resource(
            type=ResourceType.CHAT_MODEL, name="mock_chat_model"
        )
        mock_tool = ctx.get_resource(type=ResourceType.TOOL, name="mock_tool")
        ctx.send_event(
            OutputEvent(
                output=mock_chat_model.chat(
                    messages=[ChatMessage(role=MessageRole.USER, content=input)]
                ).content
                + " "
                + mock_tool.call("call")
            )
        )


def test_get_resource_in_action() -> None:
    env = AgentsExecutionEnvironment.get_execution_environment()

    input_list = []
    agent = MyAgent()

    output_list = env.from_list(input_list).apply(agent).to_list()

    input_list.append({"key": "bob", "value": "the first message."})

    env.execute()

    assert output_list == [
        {
            "bob": "the first message. 8.8.8.8 mock chat model "
            "just for testing. call mock tools just for testing."
        }
    ]
