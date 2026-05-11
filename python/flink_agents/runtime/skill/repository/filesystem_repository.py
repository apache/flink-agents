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
import logging
import os
from pathlib import Path
from typing import Dict, List

from typing_extensions import override

from flink_agents.runtime.skill.agent_skill import AgentSkill
from flink_agents.runtime.skill.skill_parser import SkillParser
from flink_agents.runtime.skill.skill_repository import (
    SkillRepository,
)

logger = logging.getLogger(__name__)


class FileSystemSkillRepository(SkillRepository):
    """File system based implementation of SkillRepository.

    This repository stores skills in a local file system directory structure
    where each skill is stored in its own subdirectory containing a SKILL.md
    file and optional resource files.

    Directory structure:

    baseDir/
    ├── skill-name-1/
    │   ├── SKILL.md          # Required: Entry file with YAML frontmatter
    │   ├── references/       # Optional: Reference documentation
    │   ├── examples/         # Optional: Example files
    │   └── scripts/          # Optional: Script files
    └── skill-name-2/
        └── SKILL.md
    """

    SKILL_MD_FILE = "SKILL.md"

    def __init__(
        self,
        base_dir: Path | str,
    ) -> None:
        """Create a FileSystemSkillRepository.

        Args:
            base_dir: The base directory containing skill subdirectories.
            skip_dirs: Optional set of directory names to skip.
            skip_patterns: Optional set of file patterns to skip.

        Raises:
            ValueError: If base_dir is None, doesn't exist, or is not a directory.
        """
        if base_dir is None:
            msg = "Base directory cannot be None"
            raise ValueError(msg)

        # Convert to Path and normalize
        self._base_dir = Path(base_dir).resolve()

        # Validate directory exists
        if not self._base_dir.exists():
            msg = f"Base directory does not exist: {self._base_dir}"
            raise ValueError(msg)

        # Validate it's a directory
        if not self._base_dir.is_dir():
            msg = f"Base directory is not a directory: {self._base_dir}"
            raise ValueError(msg)

    @property
    def base_dir(self) -> Path:
        """Get the base directory.

        Returns:
            The base directory path.
        """
        return self._base_dir

    @override
    def get_skill(self, name: str) -> AgentSkill | None:
        """Get a skill by name.

        Args:
            name: The skill name.

        Returns:
            The skill, or None if not found.
        """
        skill_dir = self._base_dir / name
        skill_md_path = skill_dir / self.SKILL_MD_FILE

        if not skill_md_path.exists():
            return None

        return self._load_skill(skill_dir)

    @override
    def get_resources(self, name: str) -> Dict[str, str]:
        skill_dir = self._base_dir / name
        return self._load_resources(skill_dir)

    @override
    def get_skills(self) -> List[AgentSkill]:
        """Get all skills in this repository.

        Returns:
            List of all skills.
        """
        skills = []
        for skill_name in self._get_all_skill_names():
            skill = self.get_skill(skill_name)
            if skill is not None:
                skills.append(skill)
        return skills

    def _get_all_skill_names(self) -> List[str]:
        """Get all skill names in this repository.

        Returns:
            List of skill names.
        """
        return sorted(
            [
                entry.name
                for entry in self._base_dir.iterdir()
                if entry.is_dir() and (entry / self.SKILL_MD_FILE).exists()
            ]
        )

    def _load_skill(self, skill_dir: Path) -> AgentSkill | None:
        """Load a skill from a directory.

        Args:
            skill_dir: Path to the skill directory.

        Returns:
            The loaded skill, or None if loading failed.
        """
        skill_md_path = skill_dir / self.SKILL_MD_FILE

        if not skill_md_path.exists():
            return None

        try:
            skill_md_content = skill_md_path.read_text()

            skill = SkillParser.parse_skill(skill_md_content)

            if skill.name != skill_dir.name:
                logger.warning(
                    f"The skill name {skill.name} is different from the base directory {skill_dir.name}."
                )

        except Exception as e:
            err_msg = f"Failed to load skill from {skill_dir}"
            raise ValueError(err_msg) from e
        else:
            return skill

    def _load_resources(self, skill_dir: Path) -> dict[str, str]:
        """Load all resources from a skill directory.

        Args:
            skill_dir: Path to the skill directory.

        Returns:
            Map of relative path to content.
        """
        resources = {}

        for root, _dirs, files in os.walk(skill_dir):
            root_path = Path(root)

            for file_name in files:
                # Skip SKILL.md (handled separately)
                if file_name == self.SKILL_MD_FILE:
                    continue

                file_path = root_path / file_name
                relative_path = str(file_path.relative_to(skill_dir))

                try:
                    # Try to read as text
                    content = file_path.read_text()
                    resources[relative_path] = content
                except UnicodeDecodeError:
                    content = file_path.read_bytes()
                    resources[relative_path] = f"base64: {content}"
                except Exception:
                    logging.warning(
                        f"Failed to read resource file {file_path}", exc_info=True
                    )

        return resources
