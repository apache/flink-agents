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
from abc import ABC, abstractmethod
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List

from flink_agents.runtime.skill.agent_skill import AgentSkill


@dataclass
class SkillRepositoryInfo:
    """Information about a skill repository.

    Attributes:
    ----------
    repo_type : str
        The type of repository (e.g., "filesystem", "classpath", "url").
    location : str
        The location of the repository (e.g., path, URL).
    writeable : bool
        Whether the repository supports write operations.
    """

    repo_type: str
    location: str
    writeable: bool


class SkillRepository(ABC):
    """Source of skills, loaded from filesystem / classpath / URL / package.

    Each skill lives under ``base_dir/<name>/`` with a required ``SKILL.md``
    and optional resource files (``references/``, ``scripts/``, ...).
    """

    @abstractmethod
    def get_skill(self, name: str) -> AgentSkill | None:
        """Return the named skill, or ``None`` if absent."""

    @abstractmethod
    def get_skills(self) -> List[AgentSkill]:
        """Return all skills in this repository."""

    @abstractmethod
    def get_resources(self, name: str) -> Dict[str, str]:
        """Return resources for the named skill, keyed by relative path."""

    def get_skill_dir(self, name: str) -> Path | None:
        """Absolute on-disk directory for the named skill.

        Filesystem-backed implementations return ``base_dir / name``
        without checking existence. Non-filesystem-backed return ``None``.
        """
        return None

    def close(self) -> None:  # noqa: B027
        """Release any owned temp directory. Default no-op; idempotent."""
