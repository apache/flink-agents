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
"""Tests for LoadSkillTool."""

from pathlib import Path
from unittest.mock import MagicMock

import pytest

from flink_agents.api.resource_context import ResourceContext
from flink_agents.api.skills import Skills
from flink_agents.runtime.skill.skill_manager import SkillManager
from flink_agents.runtime.skill.skill_tools import LoadSkillTool

base_dir = Path(__file__).parent
skills_dir = base_dir / "resources" / "skills"


@pytest.fixture
def manager() -> SkillManager:
    """SkillManager loaded with test skills."""
    return SkillManager(Skills.from_local_dir(str(skills_dir)))


@pytest.fixture
def tool(manager: SkillManager) -> LoadSkillTool:
    """LoadSkillTool wired to a real SkillManager."""
    from flink_agents.runtime.resource_context import ResourceContextImpl

    runtime_ctx = MagicMock(spec=ResourceContextImpl)
    runtime_ctx.get_skill_manager.return_value = manager
    return LoadSkillTool(resource_context=runtime_ctx)


class TestLoadSkillTool:
    """Tests for LoadSkillTool.call."""

    # -- load SKILL.md content -----------------------------------------------

    def test_load_skill_default_path(self, tool: LoadSkillTool) -> None:
        """Default path (SKILL.md) returns the skill content with base path and file list."""
        result = tool.call(name="nano-banana-pro")
        assert "<skill_content" in result
        # Base directory is included.
        expected_dir = str((skills_dir / "nano-banana-pro").resolve())
        assert f"Base directory for this skill: {expected_dir}" in result
        # Absolute paths to resources are listed.
        assert "<skill_files>" in result
        assert f"{expected_dir}/scripts/generate_image.py" in result

    def test_load_skill_explicit_skill_md(self, tool: LoadSkillTool) -> None:
        """Explicitly passing path='SKILL.md' returns the same content."""
        result = tool.call(name="github", path="SKILL.md")
        assert "gh" in result

    def test_load_skill_path_none(self, tool: LoadSkillTool) -> None:
        """path=None returns the skill content."""
        result = tool.call(name="github", path=None)
        assert "gh" in result

    # -- load specific resource ----------------------------------------------

    def test_load_resource(self, tool: LoadSkillTool) -> None:
        """Loading a specific resource returns its content."""
        result = tool.call(name="nano-banana-pro", path="scripts/generate_image.py")
        assert "get_api_key" in result

    def test_load_resource_not_found(self, tool: LoadSkillTool) -> None:
        """Loading a nonexistent resource returns an error with available list."""
        result = tool.call(name="nano-banana-pro", path="nonexistent.txt")
        assert "not found" in result.lower()
        assert "scripts/generate_image.py" in result

    # -- skill not found -----------------------------------------------------

    def test_skill_not_found(self, tool: LoadSkillTool) -> None:
        """A nonexistent skill returns an error listing available skills."""
        result = tool.call(name="nonexistent-skill")
        assert "not found" in result.lower()
        assert "github" in result
        assert "nano-banana-pro" in result

    # -- no skill manager ----------------------------------------------------

    def test_no_skill_manager(self) -> None:
        """When no SkillManager is available, return error message."""
        mock_ctx = MagicMock(spec=ResourceContext)
        tool = LoadSkillTool(resource_context=mock_ctx)
        result = tool.call(name="github")
        assert "not available" in result

    # -- positional args -----------------------------------------------------

    def test_call_with_positional_args(self, tool: LoadSkillTool) -> None:
        """call() should accept positional arg for skill name."""
        result = tool.call("github")
        assert "gh" in result
