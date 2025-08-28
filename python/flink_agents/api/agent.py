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
from abc import ABC
from typing import Any, Callable, Dict, List, Tuple, Type

from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
)
from flink_agents.api.events.event import Event
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import ResourceType


class Agent(ABC):
    """Base class for defining agent logic.


    Example:
        Users have two ways to create an Agent

        * Declare an Agent with decorators
        ::

            class MyAgent(Agent):
                @action(InputEvent)
                @staticmethod
                def my_action(event: Event, ctx: RunnerContext) -> None:
                    action logic

                @chat_model_connection
                @staticmethod
                def my_connection() -> Tuple[Type[BaseChatModelConnection],
                                       Dict[str, Any]]:
                    return OllamaChatModelConnection, {"name": "my_connection",
                                                       "model": "qwen2:7b",
                                                       "base_url": "http://localhost:11434"}

                @chat_model_setup
                @staticmethod
                def my_chat_model() -> Tuple[Type[ChatModel], Dict[str, Any]]:
                    return OllamaChatModel, {"name": "model",
                                             "connection": "my_connection"}
        * Add actions and resources to an Agent instance
        ::

            my_agent = Agent()
            my_agent.add_action(name="my_action",
                                events=[InputEvent],
                                func=action_function)
                    .add_chat_model_connection(name="my_connection",
                                               connection=OllamaChatModelConnection,
                                               arg1=xxx)
                    .add_chat_model_setup(name="my_chat_model",
                                          chat_model=OllamaChatModelSetup,
                                          connection="my_connection")
    """

    _actions: Dict[str, Tuple[List[Type[Event]], Callable]]
    _resources: Dict[ResourceType, Dict[str, Any]]

    def __init__(self) -> None:
        """Init method."""
        self._actions = {}
        self._resources = {}
        for type in ResourceType:
            self._resources[type] = {}

    @property
    def actions(self) -> Dict[str, Tuple[List[Type[Event]], Callable]]:
        """Get added actions."""
        return self._actions

    @property
    def resources(self) -> Dict[ResourceType, Dict[str, Any]]:
        """Get added resources."""
        return self._resources

    def add_action(
        self, name: str, events: List[Type[Event]], func: Callable
    ) -> "Agent":
        """Add action to agent.

        Parameters
        ----------
        name : str
            The name of the action, should be unique in the same Agent.
        events: List[Type[Event]]
            The type of events listened by this action.
        func: Callable
            The function to be executed when receive listened events.

        Returns:
        -------
        Agent
            The modified Agent instance.
        """
        if name in self._actions:
            msg = f"Action {name} already defined"
            raise ValueError(msg)
        self._actions[name] = (events, func)
        return self

    def add_prompt(self, name: str, prompt: Prompt) -> "Agent":
        """Add prompt to agent.

        Parameters
        ----------
        name : str
            The name of the prompt, should be unique in the same Agent.
        prompt: Prompt
            The prompt to be used in the agent.

        Returns:
        -------
        Agent
            The modified Agent instance.
        """
        if ResourceType.PROMPT not in self._resources:
            self._resources[ResourceType.PROMPT] = {}
        if name in self._resources[ResourceType.PROMPT]:
            msg = f"Prompt {name} already defined"
            raise ValueError(msg)
        self._resources[ResourceType.PROMPT][name] = prompt
        return self

    def add_tool(self, name: str, func: Callable) -> "Agent":
        """Add function tool to agent.

        Parameters
        ----------
        name : str
            The name of the tool, should be unique in the same Agent.
        func: Callable
            The execution function of the tool.

        Returns:
        -------
        Agent
            The modified Agent instance.
        """
        if ResourceType.TOOL not in self._resources:
            self._resources[ResourceType.TOOL] = {}
        if name in self._resources[ResourceType.TOOL]:
            msg = f"Function tool {name} already defined"
            raise ValueError(msg)
        self._resources[ResourceType.TOOL][name] = func
        return self

    def add_chat_model_connection(
        self, name: str, connection: Type[BaseChatModelConnection], **kwargs: Any
    ) -> "Agent":
        """Add chat model connection to agent.

        Parameters
        ----------
        name : str
            The name of the chat model connection, should be unique in the same Agent.
        connection: Type[BaseChatModelConnection]
            The type of chat model connection.
        **kwargs: Any
            Initialize keyword arguments passed to the chat model connection.

        Returns:
        -------
        Agent
            The modified Agent instance.
        """
        if ResourceType.CHAT_MODEL_CONNECTION not in self._resources:
            self._resources[ResourceType.CHAT_MODEL_CONNECTION] = {}
        if name in self._resources[ResourceType.CHAT_MODEL_CONNECTION]:
            msg = f"Chat model connection {name} already defined"
            raise ValueError(msg)
        kwargs["name"] = name
        self._resources[ResourceType.CHAT_MODEL_CONNECTION][name] = (connection, kwargs)
        return self

    def add_chat_model_setup(
        self, name: str, chat_model: Type[BaseChatModelSetup], **kwargs: Any
    ) -> "Agent":
        """Add chat model setup to agent.

        Parameters
        ----------
        name : str
            The name of the chat model, should be unique in the same Agent.
        chat_model: Type[BaseChatModel]
            The type of chat model.
        **kwargs: Any
            Initialize keyword arguments passed to the chat model setup.

        Returns:
        -------
        Agent
            The modified Agent instance.
        """
        if ResourceType.CHAT_MODEL not in self._resources:
            self._resources[ResourceType.CHAT_MODEL] = {}
        if name in self._resources[ResourceType.CHAT_MODEL]:
            msg = f"Chat model setup {name} already defined"
            raise ValueError(msg)
        kwargs["name"] = name
        self._resources[ResourceType.CHAT_MODEL][name] = (chat_model, kwargs)
        return self
