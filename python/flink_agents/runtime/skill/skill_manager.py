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
import logging
from pathlib import Path
from typing import TYPE_CHECKING, Dict, List, Mapping

from flink_agents.api.skills import Skills, SkillSourceSpec, redact_skill_url
from flink_agents.runtime.close_utils import failure_of, first_or_logged
from flink_agents.runtime.skill.agent_skill import AgentSkill, SkillOrigin
from flink_agents.runtime.skill.repository.classpath_repository import (
    ClasspathSkillRepository,
)
from flink_agents.runtime.skill.repository.filesystem_repository import (
    FileSystemSkillRepository,
)
from flink_agents.runtime.skill.repository.package_repository import (
    PackageSkillRepository,
)
from flink_agents.runtime.skill.repository.url_repository import URLSkillRepository
from flink_agents.runtime.skill.skill_prompt_provider import SkillPromptProvider
from flink_agents.runtime.skill.skill_source_handler import SkillSourceHandler

if TYPE_CHECKING:
    from flink_agents.runtime.skill.skill_repository import SkillRepository

logger = logging.getLogger(__name__)


class SkillManager:
    """Internal runtime component for loading, parsing, and managing skills.

    Created by the runtime from a :class:`Skills` configuration resource.
    Never exposed to users directly.

    Progressive Disclosure:
    - Discovery: Load only name/description at startup (~100 tokens)
    - Activation: Load full SKILL.md when skill matches task
    - Execution: Load resources/scripts only when needed
    """

    def __init__(self, skills_config: Skills, java_bridge: object = None) -> None:
        """Initialize the SkillManager from a Skills configuration."""
        self._skills: Dict[str, AgentSkill] = {}
        self._repos: Dict[str, SkillRepository] = {}
        # Every opened repo in load order, kept separately from `_repos` because that
        # map is keyed by skill name — duplicate names overwrite the earlier repo's
        # reference, so close() iterates this list (id-deduped) instead.
        self._opened_repos: List[SkillRepository] = []
        self._config = skills_config
        self._handlers = self._create_handlers(java_bridge)
        self._load_skills()

    def _create_handlers(self, java_bridge: object) -> Dict[str, SkillSourceHandler]:
        """Create instance factories bound to the operator's Java bridge."""
        return {
            "local": SkillSourceHandler(
                lambda p: FileSystemSkillRepository(_require(p, "local", "path")),
                lambda p: p.get("path", ""),
            ),
            "url": SkillSourceHandler(
                lambda p: URLSkillRepository(
                    _require(p, "url", "url"),
                    sha256=p.get("sha256"),
                    allow_insecure_http=p.get("allow_insecure_http", "false").lower()
                    == "true",
                ),
                lambda p: redact_skill_url(p.get("url", "")),
            ),
            "package": SkillSourceHandler(
                lambda p: PackageSkillRepository(
                    _require(p, "package", "package"), _require(p, "package", "resource")
                ),
                lambda p: f"{p.get('package', '')}/{p.get('resource', '')}",
            ),
            "classpath": SkillSourceHandler(
                lambda p: ClasspathSkillRepository(
                    _require(p, "classpath", "resource"), java_bridge
                ),
                lambda p: p.get("resource", ""),
            ),
        }

    def _get_handler(self, scheme: str) -> SkillSourceHandler:
        handler = self._handlers.get(scheme.lower())
        if handler is None:
            msg = (
                f"Unknown skill source scheme: {scheme}. "
                f"Registered schemes: {sorted(self._handlers)}"
            )
            raise ValueError(msg)
        return handler

    def _origin_of(self, spec: SkillSourceSpec) -> SkillOrigin:
        handler = self._get_handler(spec.scheme)
        return SkillOrigin(
            scheme=spec.scheme, location=handler.describe_location(spec.params)
        )

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

    def _load_skills(self) -> None:
        try:
            for spec in self._config.sources:
                origin = None
                try:
                    origin = self._origin_of(spec)
                    handler = self._get_handler(spec.scheme)
                    repo = handler.open(spec.params)
                    self._opened_repos.append(repo)
                except (OSError, ValueError) as e:
                    source_identity = (
                        f"{spec.scheme} source" if origin is None else str(origin)
                    )
                    msg = f"Failed to load skills from {source_identity}"
                    raise RuntimeError(msg) from e
                assert origin is not None
                self._register_repo(repo, origin)
        except BaseException:
            # Release every repo opened so far — the caller never receives a
            # SkillManager reference to clean them up via close() itself, so
            # without this their temp dirs / atexit handlers leak until
            # interpreter exit.
            try:
                self.close()
            except Exception as close_failure:
                # close() surfaces repository failures now, so it can raise here
                # and would otherwise replace the load failure this cleanup is
                # running for. Java attaches it with addSuppressed; ExceptionGroup
                # needs Python 3.11 and this package supports 3.10, so log it.
                logger.warning(
                    "Suppressed failure closing skill repositories after a failed load.",
                    exc_info=close_failure,
                )
            raise

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
        materialized for URL / classpath-zip / package sources.

        Iterates ``_opened_repos`` rather than ``_repos.values()``: duplicate skill
        names overwrite the earlier repo's reference in ``_repos``, but the displaced
        repo is still owned and must be closed. Dedup by identity in case the same
        repo contributes multiple skills.

        Mirrors the Java ``SkillManager.closeRepos()`` contract from #987 and the
        Python ``ResourceCache.close()``: every repository is attempted even when an
        earlier one fails, and the first failure is re-raised after the loop, so a
        real shutdown bug (locked file, permission denied, disk full) reaches the
        caller instead of being swallowed. Later failures are logged rather than
        attached, because ``ExceptionGroup`` needs Python 3.11 and this package
        supports 3.10.

        Idempotent to the extent repository ``close()`` implementations are: one
        that fails does so again on a second call, exactly as in Java.

        Only ``Exception`` is caught, matching ``ResourceCache.close()``: cleanup
        must not swallow ``KeyboardInterrupt`` or ``SystemExit``.
        """
        seen: set[int] = set()
        first_failure: Exception | None = None
        for repo in self._opened_repos:
            if id(repo) in seen:
                continue
            seen.add(id(repo))
            first_failure = first_or_logged(
                failure_of(repo.close), first_failure, "skill repository"
            )
        if first_failure is not None:
            raise first_failure

    def __enter__(self) -> "SkillManager":
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()


def _require(params: Mapping[str, str], scheme: str, key: str) -> str:
    value = params.get(key)
    if value is None:
        msg = f"Missing required param '{key}' for skill source scheme '{scheme}'"
        raise ValueError(msg)
    return value
