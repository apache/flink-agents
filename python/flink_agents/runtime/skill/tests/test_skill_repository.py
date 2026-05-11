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
"""Unit tests for Skill Repository components."""

from pathlib import Path

import pytest

from flink_agents.runtime.skill.repository.filesystem_repository import (
    FileSystemSkillRepository,
)


class TestFileSystemSkillRepository:
    """Tests for FileSystemSkillRepository class."""

    @pytest.fixture
    def skills_dir(self) -> Path:
        """Get the skills' directory."""
        base_dir = Path(__file__).parent
        return base_dir / "resources" / "skills"

    def test_create_repository(self, skills_dir: Path) -> None:
        """Test creating a repository."""
        repo = FileSystemSkillRepository(skills_dir)
        # Path is resolved, so compare resolved paths
        assert repo.base_dir == skills_dir.resolve()

    def test_create_repository_invalid_path(self) -> None:
        """Test creating repository with invalid path."""
        with pytest.raises(ValueError):
            FileSystemSkillRepository("/nonexistent/path")

    def test_get_all_skill_names(self, skills_dir: Path) -> None:
        """Test getting all skill names."""
        repo = FileSystemSkillRepository(skills_dir)
        names = repo._get_all_skill_names()
        assert len(names) == 2
        assert "github" in names
        assert "nano-banana-pro" in names

    def test_get_skill(self, skills_dir: Path) -> None:
        """Test getting a specific skill."""
        repo = FileSystemSkillRepository(skills_dir)
        skill = repo.get_skill("github")

        assert skill is not None
        assert skill.name == "github"
        assert (
            skill.description
            == "Interact with GitHub using the `gh` CLI. Use `gh issue`, `gh pr`, `gh run`, and `gh api` for issues, PRs, CI runs, and advanced queries."
        )
        assert "## JSON Output" in skill.content

    def test_get_resources(self, skills_dir: Path) -> None:
        """Test getting a skill with resources."""
        repo = FileSystemSkillRepository(skills_dir)
        skill = repo.get_skill("nano-banana-pro")

        assert skill is not None
        skill.resources = repo.get_resources("nano-banana-pro")
        assert "_meta.json" in skill.resources

        resource = skill.get_resource("scripts/generate_image.py")
        assert resource is not None
        assert "get_api_key" in resource

    def test_get_nonexistent_skill(self, skills_dir: Path) -> None:
        """Test getting a nonexistent skill."""
        repo = FileSystemSkillRepository(skills_dir)
        skill = repo.get_skill("nonexistent")
        assert skill is None

    def test_get_all_skills(self, skills_dir: Path) -> None:
        """Test getting all skills."""
        repo = FileSystemSkillRepository(skills_dir)
        skills = repo.get_skills()
        assert len(skills) == 2
        names = {s.name for s in skills}
        assert "github" in names
        assert "nano-banana-pro" in names
