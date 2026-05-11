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
"""Unit tests for Agent Skill components."""

from pathlib import Path

import pytest

from flink_agents.runtime.skill.skill_parser import (
    MarkdownSkillParser,
    SkillParser,
)

base_dir = Path(__file__).parent


class TestMarkdownSkillParser:
    """Tests for MarkdownSkillParser class."""

    def test_parse_with_frontmatter(self) -> None:
        """Test parsing markdown with frontmatter."""
        markdown = """---
name: my-skill
description: A test skill
---
# My Skill

This is the skill content.
"""
        parsed = MarkdownSkillParser.parse(markdown)
        assert parsed.metadata["name"] == "my-skill"
        assert parsed.metadata["description"] == "A test skill"
        assert parsed.content == "# My Skill\n\nThis is the skill content.\n"

    def test_parse_without_frontmatter(self) -> None:
        """Test parsing markdown without frontmatter."""
        markdown = "# My Skill\n\nThis is just markdown."
        parsed = MarkdownSkillParser.parse(markdown)
        assert parsed.metadata == {}
        assert markdown in parsed.content

    def test_parse_empty(self) -> None:
        """Test parsing empty content."""
        parsed = MarkdownSkillParser.parse("")
        assert parsed.metadata == {}
        assert parsed.content == ""

    def test_parse_with_optional_fields(self) -> None:
        """Test parsing with optional frontmatter fields."""
        markdown = """---
name: pdf-processing
description: PDF skill
license: Apache-2.0
compatibility: Requires python3
metadata:
  author: example-org
  version: "1.0"
allowed-tools: Bash(git:*) Read
---
Content here.
"""
        parsed = MarkdownSkillParser.parse(markdown)
        assert parsed.metadata["name"] == "pdf-processing"
        assert parsed.metadata["license"] == "Apache-2.0"
        assert parsed.metadata["compatibility"] == "Requires python3"
        assert parsed.metadata["metadata"] == {
            "author": "example-org",
            "version": "1.0",
        }
        assert parsed.metadata["allowed-tools"] == "Bash(git:*) Read"
        assert parsed.content == "Content here.\n"

    def test_parse_quoted_values(self) -> None:
        """Test parsing quoted YAML values."""
        markdown = """---
name: test
description: "A description with: colons"
---
Content
"""
        parsed = MarkdownSkillParser.parse(markdown)
        assert parsed.metadata["description"] == "A description with: colons"

    def test_parse_skill(self) -> None:
        with Path.open(base_dir / "resources" / "skills" / "github" / "SKILL.md") as f:
            markdown = f.read()
        parsed = MarkdownSkillParser.parse(markdown)
        assert parsed.metadata["name"] == "github"
        assert (
            parsed.metadata["description"]
            == "Interact with GitHub using the `gh` CLI. Use `gh issue`, "
            "`gh pr`, `gh run`, and `gh api` for issues, PRs, CI runs, "
            "and advanced queries."
        )


class TestSkillParser:
    """Tests for SkillParser class."""

    def test_parse_skill(self) -> None:
        """Test parsing a complete skill."""
        skill_md = """---
name: my-skill
description: A test skill
license: Apache-2.0
---
# My Skill

Instructions here.
"""
        skill = SkillParser.parse_skill(skill_md)

        assert skill.name == "my-skill"
        assert skill.description == "A test skill"
        assert skill.license == "Apache-2.0"
        assert "Instructions here" in skill.content

    def test_parse_skill_missing_name(self) -> None:
        """Test parsing skill without name."""
        skill_md = """---
description: A test skill
---
Content
"""
        with pytest.raises(ValueError):
            SkillParser.parse_skill(skill_md)

    def test_parse_skill_missing_description(self) -> None:
        """Test parsing skill without description."""
        skill_md = """---
name: test
---
Content
"""
        with pytest.raises(ValueError):
            SkillParser.parse_skill(skill_md)

    def test_parse_skill_missing_frontmatter(self) -> None:
        skill_md = "# My Skill\n\nThis is just markdown."
        with pytest.raises(ValueError):
            SkillParser.parse_skill(skill_md)

    def test_parse_skill_missing_content(self) -> None:
        skill_md = """---
name: my-skill
description: A test skill
license: Apache-2.0
---
"""
        with pytest.raises(ValueError):
            SkillParser.parse_skill(skill_md)
