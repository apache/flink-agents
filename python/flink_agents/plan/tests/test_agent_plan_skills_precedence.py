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
#  limitations under the License.
#################################################################################
"""Skill source precedence when one name is declared at more than one level.

``AgentPlan`` flattens every ``Skills`` declaration into one ordered source list,
and later entries win on duplicate skill names. Decorator declarations are the
lowest-precedence level, so a programmatic declaration reusing a decorator
method's name has to move to the end of that list instead of keeping the
decorator's slot.
"""

import pytest

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import skills, tool
from flink_agents.api.resource import (
    JavaResourceDescriptor,
    ResourceDescriptor,
    ResourceType,
)
from flink_agents.api.skills import Skills
from flink_agents.plan.agent_plan import SKILLS_CONFIG, AgentPlan
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.resource_provider import PythonSerializableResourceProvider


class _SkillsAgent(Agent):
    @skills
    @staticmethod
    def alpha() -> Skills:
        return Skills.from_local_dir("/tmp/ann-alpha")

    @skills
    @staticmethod
    def beta() -> Skills:
        return Skills.from_local_dir("/tmp/ann-beta")


def _merged_source_paths(agent: Agent) -> list[str]:
    plan = AgentPlan.from_agent(agent, AgentConfiguration())
    provider = plan.resource_providers[ResourceType.SKILLS][SKILLS_CONFIG]
    merged = provider.provide(None, AgentConfiguration())
    return [spec.params["path"] for spec in merged.sources]


def test_decorator_skills_are_merged_in_declaration_order() -> None:
    assert _merged_source_paths(_SkillsAgent()) == ["/tmp/ann-alpha", "/tmp/ann-beta"]


def test_skills_register_only_load_skill() -> None:
    plan = AgentPlan.from_agent(_SkillsAgent(), AgentConfiguration())
    assert set(plan.resource_providers[ResourceType.TOOL]) == {"load_skill"}


@pytest.mark.parametrize("with_skills", [False, True])
@pytest.mark.parametrize("tools", [None, [], ["bash"]])
@pytest.mark.parametrize(
    "descriptor_class", [ResourceDescriptor, JavaResourceDescriptor]
)
def test_builtin_bash_requires_explicit_model_declaration(
    with_skills, tools, descriptor_class
) -> None:
    agent = _SkillsAgent() if with_skills else Agent()
    agent.add_resource(
        "model",
        ResourceType.CHAT_MODEL,
        descriptor_class(clazz="unavailable.Model", tools=tools),
    )
    plan = AgentPlan.from_agent(agent, AgentConfiguration())
    assert ("bash" in plan.resource_providers.get(ResourceType.TOOL, {})) == (
        tools == ["bash"]
    )


class CustomBashAgent(Agent):
    @tool
    @staticmethod
    def bash(command: str) -> str:
        """Return the command without executing it."""
        return command


@pytest.mark.parametrize("enabled", [False, True])
def test_explicit_bash_registration_is_preserved(enabled) -> None:
    agent = CustomBashAgent()
    agent.add_resource(
        "model",
        ResourceType.CHAT_MODEL,
        ResourceDescriptor(
            clazz="unavailable.Model", tools=["bash"] if enabled else []
        ),
    )
    plan = AgentPlan.from_agent(agent, AgentConfiguration())
    assert isinstance(
        plan.resource_providers[ResourceType.TOOL]["bash"],
        PythonSerializableResourceProvider,
    )


def test_programmatic_skills_override_decorator_skills_by_name_and_move_last() -> None:
    agent = _SkillsAgent()
    agent.add_resource(
        "alpha", ResourceType.SKILLS, Skills.from_local_dir("/tmp/prog-alpha")
    )

    # "alpha" resolves to the programmatic declaration, and that declaration is
    # flattened behind "beta" so it stays the winning entry.
    assert _merged_source_paths(agent) == ["/tmp/ann-beta", "/tmp/prog-alpha"]
