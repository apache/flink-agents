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

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import skills
from flink_agents.api.resource import ResourceType
from flink_agents.api.skills import Skills
from flink_agents.plan.agent_plan import SKILLS_CONFIG, AgentPlan
from flink_agents.plan.configuration import AgentConfiguration


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


def test_programmatic_skills_override_decorator_skills_by_name_and_move_last() -> None:
    agent = _SkillsAgent()
    agent.add_resource(
        "alpha", ResourceType.SKILLS, Skills.from_local_dir("/tmp/prog-alpha")
    )

    # "alpha" resolves to the programmatic declaration, and that declaration is
    # flattened behind "beta" so it stays the winning entry.
    assert _merged_source_paths(agent) == ["/tmp/ann-beta", "/tmp/prog-alpha"]
