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
import contextlib
import logging
from pathlib import Path
from typing import TYPE_CHECKING, Dict, List

from flink_agents.api.skills import Skills, SkillSourceSpec
from flink_agents.runtime.skill import skill_source_registry
from flink_agents.runtime.skill.agent_skill import AgentSkill, SkillOrigin
from flink_agents.runtime.skill.skill_prompt_provider import SkillPromptProvider

if TYPE_CHECKING:
    from flink_agents.runtime.skill.skill_repository import SkillRepository

logger = logging.getLogger(__name__)


def _origin_of(spec: SkillSourceSpec) -> SkillOrigin:
    """Build a SkillOrigin from a spec for diagnostics.

    Delegates location description to the handler so a new scheme is one
    ``register()`` call, not a parallel switch here.
    """
    handler = skill_source_registry.get(spec.scheme)
    return SkillOrigin(
        scheme=spec.scheme,
        location=handler.describe_location(spec.params),
    )


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
        # Every opened repo in load order, kept separately from `_repos` because that
        # map is keyed by skill name — duplicate names overwrite the earlier repo's
        # reference, so close() iterates this list (id-deduped) instead.
        self._opened_repos: List[SkillRepository] = []
        self._config = skills_config
        self._load_skills()

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
        skills. Skills not backed by a filesystem repo are silently skipped.

        Raises:
            ValueError: If any provided name is not a registered skill.
        """
        selected = names if names else tuple(self._repos.keys())
        dirs: List[str] = []
        for skill_name in selected:
            repo = self._repos.get(skill_name)
            if repo is None:
                msg = (
                    f"Skill {skill_name} not found, "
                    f"available skill names are: {list(self._repos.keys())}"
                )
                raise ValueError(msg)
            dir_path = repo.get_skill_dir(skill_name)
            if dir_path is not None:
                dirs.append(str(dir_path))
        return dirs

    def get_skill_dir(self, skill_name: str) -> Path | None:
        """Return absolute directory path for a single skill, if filesystem-backed."""
        repo = self._repos.get(skill_name)
        return None if repo is None else repo.get_skill_dir(skill_name)

    def resolve_resource_path(self, skill_name: str, resource_path: str) -> Path | None:
        """Resolve a skill resource's relative path to an absolute filesystem path.

        Returns None if the skill's repository doesn't support path resolution.
        """
        repo = self._repos.get(skill_name)
        if repo is None:
            return None
        dir_path = repo.get_skill_dir(skill_name)
        if dir_path is None:
            return None
        resolved = dir_path / resource_path
        return resolved if resolved.is_file() else None

    def _load_skills(self) -> None:
        for spec in self._config.sources:
            try:
                handler = skill_source_registry.get(spec.scheme)
                repo = handler.open(spec.params)
                self._opened_repos.append(repo)
            except (OSError, ValueError) as e:
                # Release repos opened by earlier iterations — the caller never
                # receives a SkillManager reference to clean them up via close()
                # itself, so without this their temp dirs / atexit handlers leak
                # until interpreter exit.
                self.close()
                msg = (
                    f"Failed to load skills from {spec.scheme}:{spec.params}"
                )
                raise RuntimeError(msg) from e
            self._register_repo(repo, _origin_of(spec))

    def _register_repo(self, repo: "SkillRepository", origin: SkillOrigin) -> None:
        for skill in repo.get_skills():
            skill.set_resource_loader(
                lambda name=skill.name, r=repo: r.get_resources(name)
            )
            skill.set_origin(origin)
            previous = self._skills.get(skill.name)
            if previous is not None:
                logger.warning(
                    "Skill '%s' from %s overrides earlier registration from %s",
                    skill.name,
                    origin,
                    previous.origin if previous.origin is not None else "<unknown>",
                )
            self._skills[skill.name] = skill
            self._repos[skill.name] = repo

    def close(self) -> None:
        """Close every opened :class:`SkillRepository`, releasing any temp directory
        materialized for URL / classpath-zip / package sources. Idempotent.

        Iterates ``_opened_repos`` rather than ``_repos.values()``: duplicate skill
        names overwrite the earlier repo's reference in ``_repos``, but the displaced
        repo is still owned and must be closed. Dedup by identity in case the same
        repo contributes multiple skills.
        """
        seen: set[int] = set()
        for repo in self._opened_repos:
            if id(repo) in seen:
                continue
            seen.add(id(repo))
            with contextlib.suppress(Exception):
                repo.close()

    def __enter__(self) -> "SkillManager":
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()
