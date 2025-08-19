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
from typing import Dict, List, Optional

from pydantic import BaseModel, field_serializer, model_validator

from flink_agents.api.agent import Agent
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.plan.actions.action import Action
from flink_agents.plan.actions.chat_model_action import CHAT_MODEL_ACTION
from flink_agents.plan.actions.tool_call_action import TOOL_CALL_ACTION
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.function import PythonFunction
from flink_agents.plan.resource_provider import (
    JavaResourceProvider,
    JavaSerializableResourceProvider,
    PythonResourceProvider,
    PythonSerializableResourceProvider,
    ResourceProvider,
)
from flink_agents.plan.tools.function_tool import from_callable

BUILT_IN_ACTIONS = [CHAT_MODEL_ACTION, TOOL_CALL_ACTION]


class AgentPlan(BaseModel):
    """Agent plan compiled from user defined agent.

    Attributes:
    ----------
    actions: Dict[str, Action]
        Mapping of action names to actions
    actions_by_event : Dict[Type[Event], str]
        Mapping of event types to the list of actions name that listen to them.
    resource_providers: ResourceProvider
        Two level mapping of resource type to resource name to resource provider.
    """

    actions: Dict[str, Action]
    actions_by_event: Dict[str, List[str]]
    resource_providers: Optional[Dict[ResourceType, Dict[str, ResourceProvider]]] = None
    config: Optional[AgentConfiguration] = None
    __resources: Dict[ResourceType, Dict[str, Resource]] = {}

    @field_serializer("resource_providers")
    def __serialize_resource_providers(
        self, providers: Dict[ResourceType, Dict[str, ResourceProvider]]
    ) -> dict:
        # append meta info to help deserialize resource providers
        data = {}
        for type in providers:
            data[type] = {}
            for name, provider in providers[type].items():
                data[type][name] = provider.model_dump()
                if isinstance(provider, PythonResourceProvider):
                    data[type][name]["__resource_provider_type__"] = (
                        "PythonResourceProvider"
                    )
                elif isinstance(provider, PythonSerializableResourceProvider):
                    data[type][name]["__resource_provider_type__"] = (
                        "PythonSerializableResourceProvider"
                    )
                elif isinstance(provider, JavaResourceProvider):
                    data[type][name]["__resource_provider_type__"] = (
                        "JavaResourceProvider"
                    )
                elif isinstance(provider, JavaSerializableResourceProvider):
                    data[type][name]["__resource_provider_type__"] = (
                        "JavaSerializableResourceProvider"
                    )
        return data

    @model_validator(mode="before")
    def __custom_deserialize(self) -> "AgentPlan":
        if "resource_providers" in self:
            providers = self["resource_providers"]
            # restore exec from serialized json.
            if isinstance(providers, dict):
                for type in providers:
                    for name, provider in providers[type].items():
                        if isinstance(provider, dict):
                            provider_type = provider["__resource_provider_type__"]
                            if provider_type == "PythonResourceProvider":
                                self["resource_providers"][type][name] = (
                                    PythonResourceProvider.model_validate(provider)
                                )
                            elif provider_type == "PythonSerializableResourceProvider":
                                self["resource_providers"][type][name] = (
                                    PythonSerializableResourceProvider.model_validate(
                                        provider
                                    )
                                )
                            elif provider_type == "JavaResourceProvider":
                                self["resource_providers"][type][name] = (
                                    JavaResourceProvider.model_validate(provider)
                                )
                            elif provider_type == "JavaSerializableResourceProvider":
                                self["resource_providers"][type][name] = (
                                    JavaSerializableResourceProvider.model_validate(
                                        provider
                                    )
                                )
        return self

    @staticmethod
    def from_agent(agent: Agent, config: AgentConfiguration) -> "AgentPlan":
        """Build a AgentPlan from user defined agent."""
        actions = {}
        actions_by_event = {}
        for action in _get_actions(agent) + BUILT_IN_ACTIONS:
            assert action.name not in actions, f"Duplicate action name: {action.name}"
            actions[action.name] = action
            for event_type in action.listen_event_types:
                if event_type not in actions_by_event:
                    actions_by_event[event_type] = []
                actions_by_event[event_type].append(action.name)

        resource_providers = {}
        for provider in _get_resource_providers(agent):
            type = provider.type
            if type not in resource_providers:
                resource_providers[type] = {}
            name = provider.name
            assert name not in resource_providers[type], (
                f"Duplicate resource name: {name}"
            )
            resource_providers[type][name] = provider
        return AgentPlan(
            actions=actions,
            actions_by_event=actions_by_event,
            resource_providers=resource_providers,
            config=config,
        )

    def get_actions(self, event_type: str) -> List[Action]:
        """Get actions that listen to the specified event type.

        Parameters
        ----------
        event_type : Type[Event]
            The event type to query.

        Returns:
        -------
        list[Action]
            List of Actions that will respond to this event type.
        """
        return [self.actions[name] for name in self.actions_by_event[event_type]]

    def get_resource(self, name: str, type: ResourceType) -> Resource:
        """Get resource from agent plan.

        Parameters
        ----------
        name : str
            The name of the resource.
        type : ResourceType
            The type of the resource.
        """
        if type not in self.__resources:
            self.__resources[type] = {}
        if name not in self.__resources[type]:
            resource_provider = self.resource_providers[type][name]
            resource = resource_provider.provide(get_resource=self.get_resource, config=self.config)
            self.__resources[type][name] = resource
        return self.__resources[type][name]


def _get_actions(agent: Agent) -> List[Action]:
    """Extract all registered agent actions from an agent.

    Parameters
    ----------
    agent : Agent
        The agent to be analyzed.

    Returns:
    -------
    List[Action]
        List of Action defined in the agent.
    """
    actions = []
    for name, value in agent.__class__.__dict__.items():
        if isinstance(value, staticmethod) and hasattr(value, "_listen_events"):
            actions.append(
                Action(
                    name=name,
                    exec=PythonFunction.from_callable(value.__func__),
                    listen_event_types=[
                        f"{event_type.__module__}.{event_type.__name__}"
                        for event_type in value._listen_events
                    ],
                )
            )
        elif callable(value) and hasattr(value, "_listen_events"):
            actions.append(
                Action(
                    name=name,
                    exec=PythonFunction.from_callable(value),
                    listen_event_types=[
                        f"{event_type.__module__}.{event_type.__name__}"
                        for event_type in value._listen_events
                    ],
                )
            )
    for name, action in agent._actions.items():
        actions.append(
            Action(
                name=name,
                exec=PythonFunction.from_callable(action[1]),
                listen_event_types=[
                    f"{event_type.__module__}.{event_type.__name__}"
                    for event_type in action[0]
                ],
            )
        )
    return actions


def _get_resource_providers(agent: Agent) -> List[ResourceProvider]:
    resource_providers = []
    for name, value in agent.__class__.__dict__.items():
        if hasattr(value, "_is_chat_model"):
            if isinstance(value, staticmethod):
                value = value.__func__

            if callable(value):
                clazz, kwargs = value()
                provider = PythonResourceProvider(
                    name=name,
                    type=clazz.resource_type(),
                    module=clazz.__module__,
                    clazz=clazz.__name__,
                    kwargs=kwargs,
                )
                resource_providers.append(provider)
        elif hasattr(value, "_is_chat_model_server"):
            if isinstance(value, staticmethod):
                value = value.__func__

            if callable(value):
                clazz, kwargs = value()
                provider = PythonResourceProvider(
                    name=name,
                    type=clazz.resource_type(),
                    module=clazz.__module__,
                    clazz=clazz.__name__,
                    kwargs=kwargs,
                )
                resource_providers.append(provider)
        elif hasattr(value, "_is_tool"):
            if isinstance(value, staticmethod):
                value = value.__func__

            if callable(value):
                # TODO: support other tool type.
                tool = from_callable(name=name, func=value)
                resource_providers.append(
                    PythonSerializableResourceProvider.from_resource(
                        name=name, resource=tool
                    )
                )
        elif hasattr(value, "_is_prompt"):
            if isinstance(value, staticmethod):
                value = value.__func__
            prompt = value()
            resource_providers.append(
                PythonSerializableResourceProvider.from_resource(
                    name=name, resource=prompt
                )
            )

    for name, prompt in agent._prompts.items():
        resource_providers.append(
            PythonSerializableResourceProvider.from_resource(name=name, resource=prompt)
        )

    for name, func in agent._tools.items():
        tool = from_callable(name=name, func=func)
        resource_providers.append(
            PythonSerializableResourceProvider.from_resource(name=name, resource=tool)
        )

    for name, chat_model in agent._chat_models.items():
        clazz, kwargs = chat_model
        provider = PythonResourceProvider(
            name=name,
            type=clazz.resource_type(),
            module=clazz.__module__,
            clazz=clazz.__name__,
            kwargs=kwargs,
        )
        resource_providers.append(provider)

    for name, connection in agent._chat_model_servers.items():
        clazz, kwargs = connection
        provider = PythonResourceProvider(
            name=name,
            type=clazz.resource_type(),
            module=clazz.__module__,
            clazz=clazz.__name__,
            kwargs=kwargs,
        )
        resource_providers.append(provider)

    return resource_providers
