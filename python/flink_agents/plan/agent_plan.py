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
from typing import TYPE_CHECKING, Any, Dict, List, cast

from pydantic import BaseModel, field_serializer, model_validator

from flink_agents.api.agents.agent import Agent
from flink_agents.api.resource import (
    ResourceDescriptor,
    ResourceType,
)
from flink_agents.api.resource_context import ResourceContext
from flink_agents.api.skills import (
    BASH_TOOL,
    LOAD_SKILL_TOOL,
    Skills,
)
from flink_agents.plan.actions.action import Action
from flink_agents.plan.actions.chat_model_action import CHAT_MODEL_ACTION
from flink_agents.plan.actions.context_retrieval_action import CONTEXT_RETRIEVAL_ACTION
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

if TYPE_CHECKING:
    from flink_agents.api.resource import (
        Resource,
    )
    from flink_agents.integrations.mcp.mcp import MCPServer

BUILT_IN_ACTIONS = [CHAT_MODEL_ACTION, TOOL_CALL_ACTION, CONTEXT_RETRIEVAL_ACTION]


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
    resource_providers: Dict[ResourceType, Dict[str, ResourceProvider]] | None = None
    config: AgentConfiguration | None = None

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
        for provider in _get_resource_providers(agent, config):
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

    def get_action_config(self, action_name: str) -> Dict[str, Any]:
        """Get config of the action.

        Parameters
        ----------
        action_name : str
            The name of the action.

        Returns:
        -------
        Dict[str, Any]
            The config of action.
        """
        return self.actions[action_name].config

    def get_action_config_value(self, action_name: str, key: str) -> Any:
        """Get config of the action.

        Parameters
        ----------
        action_name : str
            The name of the action.
        key : str
            The name of the option.

        Returns:
        -------
        Dict[str, Any]
            The option value of the action config.
        """
        return self.actions[action_name].config.get(key, None)


def _resolve_event_type(evt: Any) -> str:
    """Convert a listen-event entry to a routing-key string.

    Only string type identifiers are accepted.
    """
    if isinstance(evt, str):
        return evt
    msg = f"Event type must be a string identifier, got {evt!r}"
    raise ValueError(msg)


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
                        _resolve_event_type(et)
                        for et in value._listen_events
                    ],
                )
            )
        elif callable(value) and hasattr(value, "_listen_events"):
            actions.append(
                Action(
                    name=name,
                    exec=PythonFunction.from_callable(value),
                    listen_event_types=[
                        _resolve_event_type(et)
                        for et in value._listen_events
                    ],
                )
            )
    for name, action_tuple in agent.actions.items():
        actions.append(
            Action(
                name=name,
                exec=PythonFunction.from_callable(action_tuple[1]),
                listen_event_types=[
                    _resolve_event_type(et)
                    for et in action_tuple[0]
                ],
                config=action_tuple[2],
            )
        )
    return actions


def _get_resource_providers(
    agent: Agent, config: AgentConfiguration
) -> List[ResourceProvider]:
    resource_providers = []
    skills_descriptors = {}
    # retrieve resource declared by decorator
    for name, value in agent.__class__.__dict__.items():
        if (
            hasattr(value, "_is_chat_model_setup")
            or hasattr(value, "_is_chat_model_connection")
            or hasattr(value, "_is_embedding_model_setup")
            or hasattr(value, "_is_embedding_model_connection")
            or hasattr(value, "_is_vector_store")
        ):
            if isinstance(value, staticmethod):
                value = value.__func__

            if callable(value):
                descriptor = value()
                if hasattr(descriptor.clazz, "_is_java_resource"):
                    resource_providers.append(
                        JavaResourceProvider.get(name=name, descriptor=value())
                    )
                else:
                    resource_providers.append(
                        PythonResourceProvider.get(name=name, descriptor=value())
                    )

        elif hasattr(value, "_is_tool"):
            if isinstance(value, staticmethod):
                value = value.__func__

            if callable(value):
                # TODO: support other tool type.
                tool = from_callable(func=value)
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
        elif hasattr(value, "_is_mcp_server"):
            if isinstance(value, staticmethod):
                value = value.__func__

            descriptor = value()
            _add_mcp_server(name, resource_providers, descriptor, config)
        elif hasattr(value, "_is_skills"):
            if isinstance(value, staticmethod):
                value = value.__func__
            skills_descriptors[name] = value()

    # retrieve resource declared by add interface
    for name, prompt in agent.resources[ResourceType.PROMPT].items():
        resource_providers.append(
            PythonSerializableResourceProvider.from_resource(name=name, resource=prompt)
        )

    for name, tool in agent.resources[ResourceType.TOOL].items():
        resource_providers.append(
            PythonSerializableResourceProvider.from_resource(
                name=name, resource=from_callable(tool.func)
            )
        )

    for name, descriptor in agent.resources[ResourceType.MCP_SERVER].items():
        _add_mcp_server(name, resource_providers, descriptor, config)

    # Merge decorator-based and programmatic skills
    all_skills: Dict[str, Skills] = dict(
        {**skills_descriptors, **agent.resources[ResourceType.SKILLS]}.items()
    )
    _add_skills(all_skills, resource_providers)

    for resource_type in [
        ResourceType.CHAT_MODEL,
        ResourceType.CHAT_MODEL_CONNECTION,
        ResourceType.EMBEDDING_MODEL,
        ResourceType.EMBEDDING_MODEL_CONNECTION,
        ResourceType.VECTOR_STORE,
    ]:
        for name, descriptor in agent.resources[resource_type].items():
            if hasattr(descriptor.clazz, "_is_java_resource"):
                resource_providers.append(
                    JavaResourceProvider.get(name=name, descriptor=descriptor)
                )
            else:
                resource_providers.append(
                    PythonResourceProvider.get(name=name, descriptor=descriptor)
                )

    return resource_providers


def _add_mcp_server(
    name: str,
    resource_providers: List[ResourceProvider],
    descriptor: ResourceDescriptor,
    config: AgentConfiguration,
) -> None:
    provider = PythonResourceProvider.get(name=name, descriptor=descriptor)

    resource_providers.append(provider)

    class ResourceContextPlaceholder(ResourceContext):
        """Placeholder - MCP server construction doesn't need resource resolution."""

        def generate_available_skills_prompt(self, *skill_names: str) -> str:
            pass

        def get_resource(self, name: str, resource_type: "ResourceType") -> "Resource":
            pass

        def get_skill_dirs(self, *skill_names: str) -> List[str]:
            return []

    mcp_server = cast(
        "MCPServer",
        provider.provide(resource_context=ResourceContextPlaceholder(), config=config),
    )

    resource_providers.extend(
        [
            PythonSerializableResourceProvider.from_resource(
                name=prompt.name, resource=prompt
            )
            for prompt in mcp_server.list_prompts()
        ]
    )

    resource_providers.extend(
        [
            PythonSerializableResourceProvider.from_resource(
                name=tool.name, resource=tool
            )
            for tool in mcp_server.list_tools()
        ]
    )

    mcp_server.close()


SKILLS_CONFIG = "_skills_config"


def _add_skills(
    skills_objects: Dict[str, Skills],
    resource_providers: List[ResourceProvider],
) -> None:
    """Register skill configuration and skill tools.

    Merges all Skills objects into a single Skills config resource,
    and registers built-in skill tools (load_skill, bash).


    """
    if len(skills_objects) == 0:
        return

    # Register skill tools via descriptor (no runtime import needed).
    # The tool classes live in flink_agents.runtime.skill_tools and will
    # be instantiated at runtime by PythonResourceProvider.

    resource_providers.extend(
        [
            PythonResourceProvider.get(
                name=LOAD_SKILL_TOOL,
                descriptor=ResourceDescriptor(
                    clazz="flink_agents.runtime.skill.skill_tools.LoadSkillTool",
                ),
            ),
            PythonResourceProvider.get(
                name=BASH_TOOL,
                descriptor=ResourceDescriptor(
                    clazz="flink_agents.plan.tools.bash.bash_tool.BashTool",
                ),
            ),
        ]
    )

    # TODO: Currently, we construct a global agent skill manager for all skill
    #  resource descriptors. In the future, we can support crate individual
    #  agent skill manager for each resource descriptor, and support specifying
    #  skill names and which skill manager they belong to when declaring a chat
    #  model setup. MCP prompts and tools face the same situation, we can refactor
    #  them as a whole.
    paths: List[str] = []
    for skills_obj in skills_objects.values():
        paths.extend(skills_obj.paths)

    merged = Skills.from_local_dir(*dict.fromkeys(paths))

    resource_providers.append(
        PythonSerializableResourceProvider.from_resource(
            name=SKILLS_CONFIG, resource=merged
        )
    )
