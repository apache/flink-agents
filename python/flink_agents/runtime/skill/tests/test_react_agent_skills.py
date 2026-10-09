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
################################################################################
"""Skills passed to the ReActAgent constructor, as seen by its chat model.

Checking the agent's descriptor alone would not show that the opened setup
discovers the skills, exposes ``load_skill`` and ``bash``, or grants the skill
directories to ``bash``. These tests take the path a job takes: ``AgentPlan``
registers the providers and ``ResourceCache`` builds and opens the setup.
"""

from collections.abc import Iterator, Sequence
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Dict, List, cast

from flink_agents.api.agents.react_agent import ReActAgent
from flink_agents.api.chat_message import ChatMessage
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
)
from flink_agents.api.resource import ResourceDescriptor, ResourceType
from flink_agents.api.skills import BASH_TOOL, LOAD_SKILL_TOOL, Skills
from flink_agents.api.tools.tool import Tool
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.runtime.resource_cache import ResourceCache

_CHAT_MODEL = "_default_chat_model"
_CONNECTION = "connection"
_SKILLS_DIR = Path(__file__).parent / "resources" / "skills"


class StubConnection(BaseChatModelConnection):
    """Resolved by the setup on open; never asked to chat."""

    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List[Tool] | None = None,
        output_schema: Any = None,
        **kwargs: Any,
    ) -> ChatMessage:
        """Refuse: these tests only open the setup."""
        msg = "These tests only open the setup."
        raise NotImplementedError(msg)


class StubChatModelSetup(BaseChatModelSetup):
    """Built by the plan's provider from its import path."""

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """No model settings."""
        return {}


def _import_path(clazz: type) -> str:
    return f"{clazz.__module__}.{clazz.__name__}"


def _chat_model(**arguments: Any) -> ResourceDescriptor:
    return ResourceDescriptor(
        clazz=_import_path(StubChatModelSetup),
        connection=_CONNECTION,
        model="stub-model",
        **arguments,
    )


@contextmanager
def _opened_chat_model(agent: ReActAgent) -> Iterator[BaseChatModelSetup]:
    agent.add_resource(
        name=_CONNECTION,
        resource_type=ResourceType.CHAT_MODEL_CONNECTION,
        instance=ResourceDescriptor(clazz=_import_path(StubConnection)),
    )
    agent.add_resource(
        name="test_skills",
        resource_type=ResourceType.SKILLS,
        instance=Skills.from_local_dir(str(_SKILLS_DIR)),
    )
    plan = AgentPlan.from_agent(agent, AgentConfiguration())
    cache = ResourceCache(plan.resource_providers, plan.config)
    try:
        yield cast(
            "BaseChatModelSetup",
            cache.get_resource(_CHAT_MODEL, ResourceType.CHAT_MODEL),
        )
    finally:
        cache.close()


def test_constructor_skills_alone_are_loadable_by_the_chat_model() -> None:
    agent = ReActAgent(chat_model=_chat_model(), skills=["github"])

    with _opened_chat_model(agent) as setup:
        assert setup.skills == ["github"]
        assert "<name>github</name>" in setup.skill_discovery_prompt
        assert "<name>nano-banana-pro</name>" not in setup.skill_discovery_prompt
        assert [tool.name for tool in setup.tools] == [LOAD_SKILL_TOOL, BASH_TOOL]
        load_skill = next(tool for tool in setup.tools if tool.name == LOAD_SKILL_TOOL)
        assert load_skill.call(name="github").startswith(
            '<skill_content name="github">'
        )


def test_descriptor_and_constructor_skills_share_the_descriptor_command_policy() -> (
    None
):
    agent = ReActAgent(
        chat_model=_chat_model(skills=["github"], allowed_commands=["gh"]),
        skills=["nano-banana-pro", "github"],
    )

    with _opened_chat_model(agent) as setup:
        assert setup.skills == ["github", "nano-banana-pro"]
        assert "<name>github</name>" in setup.skill_discovery_prompt
        assert "<name>nano-banana-pro</name>" in setup.skill_discovery_prompt
        assert setup.allowed_commands == ["gh"]
        # The chat model action grants bash these directories as allowed script
        # dirs, so a skill added only through the constructor can still run its
        # own scripts.
        skill_dirs = setup.resource_context.get_skill_dirs(*setup.skills)
        assert len(skill_dirs) == 2
        assert any(
            Path(skill_dir).name == "nano-banana-pro" for skill_dir in skill_dirs
        )


def test_empty_constructor_skills_leave_the_chat_model_without_skills() -> None:
    # Skill sources are registered, so load_skill and bash exist in the plan; the
    # chat model must still not pick them up when no skill is named.
    agent = ReActAgent(chat_model=_chat_model(), skills=[])

    with _opened_chat_model(agent) as setup:
        assert setup.skills is None
        assert setup.skill_discovery_prompt is None
        assert setup.tools == []
