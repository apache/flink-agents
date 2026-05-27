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
"""Read-only accessor for an on-disk directory of skills.

Composed by :class:`SkillRepository` implementations to handle the
"parse SKILL.md under base_dir" half of their work, leaving each repo
free to manage its own materialization and ``close()`` story.
"""

from __future__ import annotations

import base64
import logging
import os
from pathlib import Path
from typing import TYPE_CHECKING, Dict, List

from flink_agents.runtime.skill.skill_parser import SkillParser

if TYPE_CHECKING:
    from flink_agents.runtime.skill.agent_skill import AgentSkill

logger = logging.getLogger(__name__)


class SkillDirectoryReader:
    """Reads skills from an already-materialized directory. No lifecycle."""

    SKILL_MD_FILE = "SKILL.md"

    def __init__(self, base_dir: Path) -> None:
        """Wrap ``base_dir`` (must be an existing directory)."""
        if base_dir is None:
            msg = "Base directory cannot be None"
            raise ValueError(msg)
        resolved = Path(base_dir).resolve()
        if not resolved.exists():
            msg = f"Path does not exist: {resolved}"
            raise ValueError(msg)
        if not resolved.is_dir():
            msg = f"Path must be a directory: {resolved}"
            raise ValueError(msg)
        self._base_dir = resolved

    @property
    def base_dir(self) -> Path:
        """Absolute base directory."""
        return self._base_dir

    def get_skill_dir(self, name: str) -> Path:
        """``base_dir / name``; existence not checked."""
        return self._base_dir / name

    def get_skill(self, name: str) -> AgentSkill | None:
        """Parse ``base_dir/name/SKILL.md``; ``None`` if absent."""
        skill_dir = self._base_dir / name
        skill_md_path = skill_dir / self.SKILL_MD_FILE
        if not skill_md_path.exists():
            return None
        return self._load_skill(skill_dir)

    def get_skills(self) -> List[AgentSkill]:
        """All skills under ``base_dir``, sorted by name."""
        skills: List[AgentSkill] = []
        for skill_name in self._list_skill_names():
            skill = self.get_skill(skill_name)
            if skill is not None:
                skills.append(skill)
        return skills

    def get_resources(self, name: str) -> Dict[str, str]:
        """All non-``SKILL.md`` files under the named skill, keyed by relative path."""
        skill_dir = self._base_dir / name
        if not skill_dir.is_dir():
            return {}
        return self._load_resources(skill_dir)

    def _list_skill_names(self) -> List[str]:
        return sorted(
            [
                entry.name
                for entry in self._base_dir.iterdir()
                if entry.is_dir() and (entry / self.SKILL_MD_FILE).exists()
            ]
        )

    def _load_skill(self, skill_dir: Path) -> AgentSkill | None:
        skill_md_path = skill_dir / self.SKILL_MD_FILE
        if not skill_md_path.exists():
            return None
        try:
            content = skill_md_path.read_text()
            skill = SkillParser.parse_skill(content)
            if skill.name != skill_dir.name:
                logger.warning(
                    "The skill name %s is different from the base directory %s.",
                    skill.name,
                    skill_dir.name,
                )
        except Exception as e:
            err_msg = f"Failed to load skill from {skill_dir}"
            raise ValueError(err_msg) from e
        else:
            return skill

    def _load_resources(self, skill_dir: Path) -> Dict[str, str]:
        resources: Dict[str, str] = {}
        for root, _dirs, files in os.walk(skill_dir):
            root_path = Path(root)
            for file_name in files:
                if file_name == self.SKILL_MD_FILE:
                    continue
                file_path = root_path / file_name
                relative_path = str(file_path.relative_to(skill_dir))
                try:
                    resources[relative_path] = file_path.read_text()
                except UnicodeDecodeError:
                    encoded = base64.b64encode(file_path.read_bytes()).decode("ascii")
                    resources[relative_path] = f"base64: {encoded}"
                except Exception:
                    logger.warning(
                        "Failed to read resource file %s", file_path, exc_info=True
                    )
        return resources
