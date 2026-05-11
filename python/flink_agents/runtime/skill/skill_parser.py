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
import re
from dataclasses import dataclass
from typing import Dict

import yaml

from flink_agents.runtime.skill.agent_skill import AgentSkill


@dataclass
class ParsedMarkdown:
    """Result of parsing markdown with frontmatter.

    Contains both the extracted metadata and the markdown content.

    Attributes:
    ----------
    metadata : Dict[str, str]
        YAML metadata extracted from frontmatter.
    content : str
        Markdown content (without frontmatter).
    """

    metadata: Dict[str, str]
    content: str


class MarkdownSkillParser:
    """Utility for parsing Markdown files with YAML frontmatter."""

    # Pattern to match frontmatter: starts with ---, ends with ---
    # Group 1: frontmatter content (non-greedy)
    # Group 2: remaining content
    FRONTMATTER_PATTERN = re.compile(
        r"^---\s*[\r\n]+(.*?)[\r\n]*---(?:\s*[\r\n]+)?(.*)", re.DOTALL
    )

    @classmethod
    def parse(cls, markdown: str) -> ParsedMarkdown:
        """Parse markdown content with YAML frontmatter.

        Extracts both the YAML metadata and the markdown content.

        Args:
            markdown: Markdown content.

        Returns:
            ParsedMarkdown containing metadata and content.

        Raises:
            ValueError: If YAML syntax is invalid.
        """
        if not markdown:
            return ParsedMarkdown(metadata={}, content="")

        matcher = cls.FRONTMATTER_PATTERN.match(markdown)

        if not matcher:
            # No frontmatter found, treat entire content as markdown
            return ParsedMarkdown(metadata={}, content=markdown)

        yaml_content = matcher.group(1).strip()
        markdown_content = matcher.group(2)

        if not yaml_content:
            return ParsedMarkdown(metadata={}, content=markdown_content)

        try:
            metadata = yaml.safe_load(yaml_content)
            return ParsedMarkdown(metadata=metadata, content=markdown_content)
        except ValueError:
            raise
        except Exception as e:
            msg = f"Invalid YAML frontmatter syntax: {e}"
            raise ValueError(msg) from e


class SkillParser:
    """Parser for creating AgentSkill from SKILL.md content.

    This class provides high-level parsing functionality to create
    AgentSkill instances from raw SKILL.md file content and resources.
    """

    @classmethod
    def parse_skill(
        cls,
        skill_md_content: str,
    ) -> "AgentSkill":
        """Parse SKILL.md content and create an AgentSkill.

        Args:
            skill_md_content: The raw content of SKILL.md file.

        Returns:
            The parsed AgentSkill.

        Raises:
            ValueError: If required fields (name, description) are missing.
        """
        parsed = MarkdownSkillParser.parse(skill_md_content)
        metadata = parsed.metadata

        # Required fields
        name = metadata.get("name")
        description = metadata.get("description")

        if not name:
            msg = "The SKILL.md must have a YAML frontmatter including 'name' field."
            raise ValueError(msg)
        if not description:
            msg = "The SKILL.md must have a YAML frontmatter including 'description' field."
            raise ValueError(msg)

        if not parsed.content:
            msg = "The SKILL.md must have a markdown content after YAML frontmatter."
            raise ValueError(msg)

        return AgentSkill(
            name=name.strip(),
            description=description.strip(),
            license=metadata.get("license"),
            compatibility=metadata.get("compatibility"),
            metadata=metadata.get("metadata"),
            content=parsed.content,
        )
