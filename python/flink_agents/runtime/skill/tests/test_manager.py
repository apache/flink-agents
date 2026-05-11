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
"""Unit tests for SkillManager and skill tools."""

from pathlib import Path

import pytest

from flink_agents.api.skills import Skills
from flink_agents.runtime.skill.skill_manager import SkillManager

base_dir = Path(__file__).parent


class TestSkillManager:
    """Tests for SkillManager class."""

    @pytest.fixture
    def skills_dir(self) -> Path:
        """Create a temporary directory with test skills."""
        return base_dir / "resources" / "skills"

    def test_add_skills_from_path(self, skills_dir: Path) -> None:
        """Test adding skills from path."""
        manager = SkillManager(Skills.from_local_dir(str(skills_dir)))
        skill = manager.get_skill("github")
        assert skill.name == "github"
        assert skill.description == (
            "Interact with GitHub using the `gh` CLI. "
            "Use `gh issue`, `gh pr`, `gh run`, and `gh api` "
            "for issues, PRs, CI runs, and advanced queries."
        )

    def test_generate_discovery_prompt(self, skills_dir: Path) -> None:
        """Test generating discovery prompt."""
        manager = SkillManager(Skills.from_local_dir(str(skills_dir)))

        prompt = manager.generate_discovery_prompt("github", "nano-banana-pro")
        with Path.open(base_dir / "resources" / "skill_discovery_prompt.txt") as f:
            content = f.read()
            assert prompt == content

    def test_get_skill(self, skills_dir: Path) -> None:
        """Test getting a skill."""
        manager = SkillManager(Skills.from_local_dir(str(skills_dir)))

        skill = manager.get_skill("github")
        assert skill is not None
        assert skill.name == "github"
        assert (
            skill.description
            == "Interact with GitHub using the `gh` CLI. Use `gh issue`, `gh pr`, `gh run`, and `gh api` for issues, PRs, CI runs, and advanced queries."
        )

        skill2 = manager.get_skill("nano-banana-pro")
        assert skill2 is not None
        assert skill2.name == "nano-banana-pro"
        assert (
            skill2.description
            == "Generate/edit images with Nano Banana Pro (Gemini 3 Pro Image). Use for image create/modify requests incl. edits. Supports text-to-image + image-to-image; 1K/2K/4K; use --input-image."
        )

    def test_load_skill_resource(self, skills_dir: Path) -> None:
        """Test loading a skill resource."""
        manager = SkillManager(Skills.from_local_dir(str(skills_dir)))

        skill = manager.get_skill("nano-banana-pro")
        content = skill.get_resource("scripts/generate_image.py")
        assert content is not None
        assert "get_api_key" in content

        nonexistent = skill.get_resource("nonexistent")
        assert nonexistent is None
