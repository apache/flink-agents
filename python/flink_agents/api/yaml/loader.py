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
"""YAML loader: parse a YAML document and register agents on an execution
environment.
"""

from pathlib import Path
from typing import TYPE_CHECKING, Any, Dict, List, Tuple

if TYPE_CHECKING:
    from flink_agents.api.execution_environment import AgentsExecutionEnvironment

import yaml

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.function import Function, JavaFunction, PythonFunction
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import ResourceDescriptor, ResourceType
from flink_agents.api.skills import Skills, SkillSourceSpec
from flink_agents.api.tools.function_tool import FunctionTool
from flink_agents.api.yaml.aliases import (
    JAVA_WRAPPER_CLAZZ,
    resolve_clazz,
    resolve_event_type,
)
from flink_agents.api.yaml.specs import (
    ActionSpec,
    AgentSpec,
    DescriptorSpec,
    Language,
    PromptSpec,
    SkillsSpec,
    ToolSpec,
    YamlAgentsDocument,
)

# Default Java parameter types for an action. Action methods in
# flink-agents always have signature (Event, RunnerContext).
_JAVA_ACTION_PARAMETER_TYPES: list[str] = [
    "org.apache.flink.agents.api.Event",
    "org.apache.flink.agents.api.context.RunnerContext",
]

_DESCRIPTOR_TYPES: Dict[str, ResourceType] = {
    "chat_model_connections": ResourceType.CHAT_MODEL_CONNECTION,
    "chat_model_setups": ResourceType.CHAT_MODEL,
    "embedding_model_connections": ResourceType.EMBEDDING_MODEL_CONNECTION,
    "embedding_model_setups": ResourceType.EMBEDDING_MODEL,
    "vector_stores": ResourceType.VECTOR_STORE,
    "mcp_servers": ResourceType.MCP_SERVER,
}


def resolve_function(
    *,
    name: str,
    function: str | None,
    language: Language | None = None,
    parameter_types: List[str] | None = None,
) -> PythonFunction | JavaFunction:
    """Resolve a YAML function reference to a flink-agents Function.

    Returns a ``PythonFunction`` when ``language`` is ``"python"`` (or
    None — the default). Returns a ``JavaFunction`` when ``language``
    is ``"java"``. Java parameter types must be passed in by the caller
    (actions use a fixed signature; tools vary per method).

    ``function`` must be ``<left>:<right>`` — a colon separates the
    module (or Java class FQN) from the attribute path inside it:

    - Python: ``flink_agents.tools:add`` or
      ``flink_agents.tools:MyTools.add`` (the right side is the
      ``PythonFunction.qualname``, so nested ``Class.method`` is fine).
    - Java: ``com.example.MyClass:method`` (or
      ``com.example.Outer$Inner:method`` for inner classes).

    The colon is what lets a cross-language YAML loader recognise the
    module/class boundary without language-specific import probing.
    """
    if function is None:
        msg = (
            f"Action/tool {name!r}: 'function' is required and must be "
            "of the form '<module-or-class>:<qualname>'."
        )
        raise ValueError(msg)

    parts = function.split(":")
    if len(parts) != 2 or not parts[0] or not parts[1]:
        kind = "java" if language == "java" else "python"
        msg = (
            f"Action/tool {name!r}: {kind} function {function!r} must be "
            "of the form '<module-or-class>:<qualname>' (e.g. "
            "'pkg.tools:add', 'pkg.tools:MyTools.add', "
            "'com.example.MyClass:method')."
        )
        raise ValueError(msg)
    left, right = parts

    if language == "java":
        return JavaFunction(
            qualname=left,
            method_name=right,
            parameter_types=parameter_types or [],
        )
    return PythonFunction(module=left, qualname=right)


def _load_document(path: Path | str) -> YamlAgentsDocument:
    text = Path(path).read_text()
    raw = yaml.load(text, Loader=yaml.SafeLoader)
    if raw is None:
        msg = f"YAML file {path} is empty"
        raise ValueError(msg)
    return YamlAgentsDocument.model_validate(raw)


def _build_descriptor(
    spec: DescriptorSpec, resource_type: ResourceType
) -> ResourceDescriptor:
    kwargs = dict(spec.model_extra or {})
    if spec.type == "java":
        if resource_type not in JAVA_WRAPPER_CLAZZ:
            msg = (
                f"Resource {spec.name!r}: type='java' is not supported "
                f"for {resource_type.value} (no Python-side Java wrapper)."
            )
            raise ValueError(msg)
        java_fqn = resolve_clazz(spec.clazz, resource_type, "java")
        wrapper_clazz = JAVA_WRAPPER_CLAZZ[resource_type]
        return ResourceDescriptor(clazz=wrapper_clazz, java_clazz=java_fqn, **kwargs)
    python_fqn = resolve_clazz(spec.clazz, resource_type, "python")
    return ResourceDescriptor(clazz=python_fqn, **kwargs)


def _add_descriptors_to_agent(
    agent: Agent, attr_name: str, descriptors: list[DescriptorSpec]
) -> None:
    resource_type = _DESCRIPTOR_TYPES[attr_name]
    for spec in descriptors:
        agent.add_resource(
            spec.name, resource_type, _build_descriptor(spec, resource_type)
        )


def _resolve_action_function(action: ActionSpec) -> Function:
    parameter_types = _JAVA_ACTION_PARAMETER_TYPES if action.type == "java" else None
    return resolve_function(
        name=action.name,
        function=action.function,
        language=action.type,
        parameter_types=parameter_types,
    )


def _add_action_to_agent(agent: Agent, action: ActionSpec) -> None:
    func = _resolve_action_function(action)
    trigger_conditions = [
        resolve_event_type(e) for e in action.trigger_conditions
    ]
    config = action.config or {}
    agent.add_action(action.name, trigger_conditions, func, **config)


def _build_tool(spec: ToolSpec) -> FunctionTool:
    if spec.type == "java" and spec.parameter_types is None:
        msg = f"Tool {spec.name!r}: java tools must declare 'parameter_types' in YAML."
        raise ValueError(msg)
    func = resolve_function(
        name=spec.name,
        function=spec.function,
        language=spec.type,
        parameter_types=spec.parameter_types,
    )
    return FunctionTool(func=func)


def _build_prompt(spec: PromptSpec) -> Prompt:
    if spec.text is not None:
        return Prompt.from_text(spec.text)
    messages = [
        ChatMessage(role=MessageRole(m.role.value), content=m.content)
        for m in (spec.messages or [])
    ]
    return Prompt.from_messages(messages)


def _build_skills(spec: SkillsSpec) -> Skills:
    sources: List[SkillSourceSpec] = [
        SkillSourceSpec(scheme="local", params={"path": p}) for p in spec.paths
    ]
    sources.extend(
        SkillSourceSpec(scheme="url", params={"url": u}) for u in spec.urls
    )
    sources.extend(
        SkillSourceSpec(scheme="classpath", params={"resource": r})
        for r in spec.classpath
    )
    sources.extend(
        SkillSourceSpec(
            scheme="package",
            params={"package": entry.package, "resource": entry.resource},
        )
        for entry in spec.package
    )
    return Skills(sources=sources)


def _build_agent(agent_spec: AgentSpec) -> Agent:
    agent = Agent()
    for attr in _DESCRIPTOR_TYPES:
        descriptors = getattr(agent_spec, attr)
        _add_descriptors_to_agent(agent, attr, descriptors)
    for tool_spec in agent_spec.tools:
        agent.add_resource(
            tool_spec.name,
            ResourceType.TOOL,
            _build_tool(tool_spec),
        )
    for prompt_spec in agent_spec.prompts:
        agent.add_resource(
            prompt_spec.name, ResourceType.PROMPT, _build_prompt(prompt_spec)
        )
    for skills_spec in agent_spec.skills:
        agent.add_resource(
            skills_spec.name, ResourceType.SKILLS, _build_skills(skills_spec)
        )
    for action in agent_spec.actions:
        if isinstance(action, str):
            continue  # shared-action references handled by caller
        _add_action_to_agent(agent, action)
    return agent


def _build_in_file_state(
    path: Path | str,
) -> Tuple[
    Dict[str, Agent],
    Dict[ResourceType, Dict[str, Any]],
    Dict[str, ActionSpec],
    Dict[str, AgentSpec],
    YamlAgentsDocument,
]:
    """Parse one YAML file, perform in-file duplicate detection, and build
    the in-memory state without touching any execution environment.

    Returns:
        agents: name -> Agent
        shared_resources: resource_type -> name -> descriptor/resource
        shared_actions: name -> ActionSpec (file-level, for cross-agent reference)
        agent_specs: name -> AgentSpec (kept so callers can resolve string
            action references back to the originating spec).

    Both :func:`build_agents` and :func:`load_yaml` go through this helper
    so the in-file rules (duplicate detection, build order) are defined in
    exactly one place.
    """
    doc = _load_document(path)
    agent_specs: Dict[str, AgentSpec] = {}
    agents: Dict[str, Agent] = {}
    for spec in doc.agents:
        if spec.name in agents:
            msg = f"Duplicate agent name {spec.name!r} in {path}"
            raise ValueError(msg)
        agent_specs[spec.name] = spec
        agents[spec.name] = _build_agent(spec)

    shared_resources: Dict[ResourceType, Dict[str, Any]] = {t: {} for t in ResourceType}
    for attr, resource_type in _DESCRIPTOR_TYPES.items():
        for spec in getattr(doc, attr):
            if spec.name in shared_resources[resource_type]:
                msg = f"Duplicate shared resource name {spec.name!r} in {path}"
                raise ValueError(msg)
            shared_resources[resource_type][spec.name] = _build_descriptor(
                spec, resource_type
            )
    for tool_spec in doc.tools:
        if tool_spec.name in shared_resources[ResourceType.TOOL]:
            msg = f"Duplicate shared tool name {tool_spec.name!r} in {path}"
            raise ValueError(msg)
        shared_resources[ResourceType.TOOL][tool_spec.name] = _build_tool(tool_spec)
    for prompt_spec in doc.prompts:
        if prompt_spec.name in shared_resources[ResourceType.PROMPT]:
            msg = f"Duplicate shared prompt name {prompt_spec.name!r} in {path}"
            raise ValueError(msg)
        shared_resources[ResourceType.PROMPT][prompt_spec.name] = _build_prompt(
            prompt_spec
        )
    for skills_spec in doc.skills:
        if skills_spec.name in shared_resources[ResourceType.SKILLS]:
            msg = f"Duplicate shared skills name {skills_spec.name!r} in {path}"
            raise ValueError(msg)
        shared_resources[ResourceType.SKILLS][skills_spec.name] = _build_skills(
            skills_spec
        )

    shared_actions: Dict[str, ActionSpec] = {}
    for action_spec in doc.actions:
        if action_spec.name in shared_actions:
            msg = f"Duplicate shared action name {action_spec.name!r} in {path}"
            raise ValueError(msg)
        shared_actions[action_spec.name] = action_spec

    return agents, shared_resources, shared_actions, agent_specs, doc


def build_agents(
    path: Path | str,
) -> Tuple[Dict[str, Agent], Dict[ResourceType, Dict[str, Any]], Dict[str, ActionSpec]]:
    """Parse one YAML file and build the agents it declares.

    Returns:
        agents: name -> Agent
        shared_resources: resource_type -> name -> descriptor/resource
        shared_actions: name -> ActionSpec (file-level, for cross-agent reference)

    This function only handles in-file structure. It does NOT enforce
    cross-file duplicate detection — that's the caller's job.
    """
    agents, shared_resources, shared_actions, _, _ = _build_in_file_state(path)
    return agents, shared_resources, shared_actions


def _resolve_shared_action_refs(
    agents: Dict[str, "Agent"],
    agent_specs: Dict[str, AgentSpec],
    shared_actions: Dict[str, ActionSpec],
    path: "Path | str",
) -> None:
    """For each agent, replace any string action reference with a copy of
    the shared action.
    """
    for agent_name, agent in agents.items():
        spec = agent_specs[agent_name]
        for item in spec.actions:
            if not isinstance(item, str):
                continue
            if item not in shared_actions:
                msg = (
                    f"Agent {agent_name!r} references shared action "
                    f"{item!r} in {path}, but no shared action with that "
                    "name is defined at the file level."
                )
                raise ValueError(msg)
            shared = shared_actions[item]
            _add_action_to_agent(agent, shared)


def load_yaml(
    env: "AgentsExecutionEnvironment",
    paths: Path | str | List[Path | str],
) -> None:
    """Load one or more YAML files and register their agents and shared
    resources on the environment.

    Multiple calls accumulate. Duplicate names — both within a single file
    and across the current environment — raise ``ValueError``. In-file
    duplicate detection is delegated to :func:`_build_in_file_state` so
    that ``load_yaml`` and :func:`build_agents` share the same rules.
    """
    if isinstance(paths, str | Path):
        paths = [paths]

    for path in paths:
        agents, shared_resources, shared_actions, agent_specs, _ = (
            _build_in_file_state(path)
        )

        # Cross-environment duplicate checks. In-file duplicates were
        # already caught inside ``_build_in_file_state``.
        for name in agents:
            if name in env._agents:
                msg = f"Duplicate agent name {name!r} (loading {path})"
                raise ValueError(msg)
        for resource_type, name_to_resource in shared_resources.items():
            for name in name_to_resource:
                if name in env.resources[resource_type]:
                    msg = (
                        f"Duplicate shared {resource_type.value} {name!r} "
                        f"(loading {path})"
                    )
                    raise ValueError(msg)

        # Resolve string action refs (raises ValueError on unknown ref).
        _resolve_shared_action_refs(agents, agent_specs, shared_actions, path)

        # Commit: write resources then agents to env.
        for resource_type, name_to_resource in shared_resources.items():
            for name, resource in name_to_resource.items():
                env.add_resource(name, resource_type, resource)
        env._agents.update(agents)
