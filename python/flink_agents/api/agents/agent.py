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

from flink_agents.api.events.event import Event
from flink_agents.api.resource import (
    ResourceDescriptor,
    ResourceType,
    SerializableResource,
)
from flink_agents.api.tools.mcp import MCPServer


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
                def my_connection() -> ResourceDescriptor:
                    return ResourceDescriptor(clazz=OllamaChatModelConnection,
                                              model="qwen2:7b",
                                              base_url="http://localhost:11434")

                @chat_model_setup
                @staticmethod
                def my_chat_model() -> ResourceDescriptor:
                    return ResourceDescriptor(clazz=OllamaChatModel,
                                              connection="my_connection")

        * Add actions and resources to an Agent instance
        ::

            my_agent = Agent()
            my_agent.add_action(name="my_action",
                                events=[InputEvent],
                                func=action_function)
                    .add_resource(name="my_connection",
                                  instance=ResourceDescriptor(
                                        clazz=OllamaChatModelConnection,
                                        arg1=xxx
                                )
                    .add_resource(
                        name="my_connection",
                        instance=ResourceDescriptor(
                            clazz=OllamaChatModelConnection,
                            arg1=xxx
                        )
                    )
                    .add_resource(
                        name="my_chat_model",
                        instance=ResourceDescriptor(
                            clazz=OllamaChatModelSetup,
                            connection="my_connection"
                        )
                    )
    """

    _actions: Dict[str, Tuple[List[Type[Event]], Callable, Dict[str, Any]]]
    _resources: Dict[ResourceType, Dict[str, Any]]
    _mcp_servers: Dict[str, MCPServer]

    def __init__(self) -> None:
        """Init method."""
        self._actions = {}
        self._resources = {}
        for type in ResourceType:
            self._resources[type] = {}

    @property
    def actions(self) -> Dict[str, Tuple[List[Type[Event]], Callable, Dict[str, Any]]]:
        """Get added actions."""
        return self._actions

    @property
    def resources(self) -> Dict[ResourceType, Dict[str, Any]]:
        """Get added resources."""
        return self._resources

    def add_action(
        self, name: str, events: List[Type[Event]], func: Callable, **config: Any
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
        **config: Any
            Key named arguments can be used by this action in runtime.

        Returns:
        -------
        Agent
            The modified Agent instance.
        """
        if name in self._actions:
            msg = f"Action {name} already defined"
            raise ValueError(msg)
        self._actions[name] = (events, func, config if config else None)
        return self

    def add_resource(
        self, name: str, instance: SerializableResource | ResourceDescriptor
    ) -> "Agent":
        """Add resource to agent instance.

        Parameters
        ----------
        name : str
            The name of the prompt, should be unique in the same Agent.
        instance: SerializableResource | ResourceDescriptor
            The serializable resource instance, or the descriptor of resource.

        Returns:
        -------
        Agent
            The agent to add the resource.
        """
        if isinstance(instance, SerializableResource):
            resource_type = instance.resource_type()
        elif isinstance(instance, ResourceDescriptor):
            resource_type = instance.clazz.resource_type()
        else:
            err_msg = f"Unexpected resource {instance}"
            raise TypeError(err_msg)

        if name in self._resources[resource_type]:
            msg = f"{resource_type.value} {name} already defined"
            raise ValueError(msg)

        self._resources[resource_type][name] = instance
        return self
