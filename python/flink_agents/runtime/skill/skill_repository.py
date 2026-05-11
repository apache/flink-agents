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
    """Abstract interface for skill repositories.

    A SkillRepository is responsible for loading and optionally storing skills
    from a specific source (filesystem, classpath, URL, etc.).

    Each skill is stored in its own subdirectory containing a SKILL.md file
    and optional resource files:

    baseDir/
    ├── skill-name-1/
    │   ├── SKILL.md          # Required: Entry file with YAML frontmatter
    │   ├── references/       # Optional: Reference documentation
    │   ├── examples/         # Optional: Example files
    │   └── scripts/          # Optional: Script files
    └── skill-name-2/
        └── SKILL.md
    """

    @abstractmethod
    def get_skill(self, name: str) -> str:
        """Get a skill by name.

        Args:
            name: The skill name.

        Returns:
            The skill, or None if not found.
        """

    @abstractmethod
    def get_skills(self) -> List[AgentSkill]:
        """Get all skills in this repository.

        Returns:
            List of all skills.
        """

    @abstractmethod
    def get_resources(self, name: str) -> Dict[str, str]:
        """Get resources for the specified skill."""
