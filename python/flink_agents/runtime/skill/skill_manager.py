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
from pathlib import Path
from typing import TYPE_CHECKING, Dict, List

from flink_agents.api.skills import Skills
from flink_agents.runtime.skill.agent_skill import AgentSkill
from flink_agents.runtime.skill.repository.filesystem_repository import (
    FileSystemSkillRepository,
)
from flink_agents.runtime.skill.skill_prompt_provider import SkillPromptProvider

if TYPE_CHECKING:
    from flink_agents.runtime.skill.skill_repository import SkillRepository


class SkillManager:
    """Internal runtime component for loading, parsing, and managing skills.

    Created by the runtime from a :class:`Skills` configuration resource.
    Never exposed to users directly.

    Progressive Disclosure:
    - Discovery: Load only name/description at startup (~100 tokens)
    - Activation: Load full SKILL.md when skill matches task
    - Execution: Load resources/scripts only when needed
    """

    def __init__(self, skills_config: Skills) -> None:
        """Initialize the SkillManager from a Skills configuration."""
        self._skills: Dict[str, AgentSkill] = {}
        self._repos: Dict[str, SkillRepository] = {}
        self._config = skills_config
        self._load_skills_from_paths()

    @property
    def size(self) -> int:
        """Get the number of registered skills."""
        return len(self._skills)

    def get_skill(self, name: str) -> AgentSkill:
        """Get a registered skill by name."""
        if name not in self._skills:
            msg = f"Skill {name} not found, available skill names are: {list(self._skills.keys())}"
            raise ValueError(msg)
        return self._skills[name]

    def get_all_skill_names(self) -> List[str]:
        """Get the names of all registered skills."""
        return list(self._skills.keys())

    def load_skill_resource(self, skill_name: str, resource_path: str) -> str | None:
        """Load a specified resource of a skill."""
        skill = self.get_skill(skill_name)
        return skill.get_resource(resource_path)

    def generate_discovery_prompt(self, *names: str) -> str:
        """Generate a system prompt for skill discovery."""
        if self.size == 0:
            return ""

        skill_list = []
        for name in names:
            skill = self.get_skill(name)
            skill_list.append(
                SkillPromptProvider.AVAILABLE_SKILL_TEMPLATE.format(
                    name=skill.name, description=skill.description
                )
            )

        return (
            SkillPromptProvider.SKILL_DISCOVERY_PROMPT.format()
            + ("".join(skill_list))
            + SkillPromptProvider.AVAILABLE_SKILLS_TAG_END
        )

    def get_skill_dirs(self, *names: str) -> List[str]:
        """Return absolute directory paths for the given skill names.

        If no names are provided, returns directories for all filesystem-backed
        skills. Unknown names and skills not backed by a filesystem repo are
        silently skipped.
        """
        selected = names if names else tuple(self._repos.keys())
        dirs: List[str] = []
        for skill_name in selected:
            repo = self._repos.get(skill_name)
            if isinstance(repo, FileSystemSkillRepository):
                dirs.append(str(repo.base_dir / skill_name))
        return dirs

    def get_skill_dir(self, skill_name: str) -> Path | None:
        """Return absolute directory path for a single skill, if filesystem-backed."""
        repo = self._repos.get(skill_name)
        if isinstance(repo, FileSystemSkillRepository):
            return repo.base_dir / skill_name
        return None

    def resolve_resource_path(self, skill_name: str, resource_path: str) -> Path | None:
        """Resolve a skill resource's relative path to an absolute filesystem path.

        Returns None if the skill's repository doesn't support path resolution.
        """
        repo = self._repos.get(skill_name)
        if isinstance(repo, FileSystemSkillRepository):
            resolved = repo.base_dir / skill_name / resource_path
            if resolved.exists() and resolved.is_file():
                return resolved
        return None

    def _load_skills_from_paths(self) -> None:
        for path in self._config.paths:
            repo = FileSystemSkillRepository(path)
            for skill in repo.get_skills():
                skill.set_resource_loader(
                    lambda name=skill.name, r=repo: r.get_resources(name)
                )
                self._skills[skill.name] = skill
                self._repos[skill.name] = repo
