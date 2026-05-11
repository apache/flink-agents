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
# limitations under the License.
################################################################################
"""Built-in tools for skill loading."""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from flink_agents.runtime.skill.skill_manager import SkillManager

from pydantic import BaseModel, Field

from flink_agents.api.tools.tool import Tool, ToolMetadata, ToolType

logger = logging.getLogger(__name__)


class LoadSkillArgs(BaseModel):
    """Arguments for LoadSkillTool."""

    name: str = Field(
        ..., description="The name of the skill to load (e.g., 'pdf-processing')."
    )
    path: str | None = Field(
        default="SKILL.md",
        description=(
            "Optional path to a specific resource within the skill. "
            "If not provided, returns the full SKILL.md content."
        ),
    )


class LoadSkillTool(Tool):
    """Tool for loading skill content and resources.

    Accesses the SkillManager through the runtime ResourceContext
    (not the public ResourceContext interface).
    """

    metadata: ToolMetadata = Field(exclude=True)

    def __init__(self, **kwargs: Any) -> None:
        """Initialize the load skill tool."""
        super().__init__(
            metadata=ToolMetadata(
                name="load_skill",
                description=(
                    "Load a skill's content or a specific resource. "
                    "Use this to access skill instructions and resources. "
                ),
                args_schema=LoadSkillArgs,
            ),
            **kwargs,
        )

    @classmethod
    def tool_type(cls) -> ToolType:
        """Return tool type of class."""
        return ToolType.FUNCTION

    def call(self, *args: Any, **kwargs: Any) -> str:
        """Call the tool to load a skill."""
        if args:
            parsed_args = LoadSkillArgs(name=args[0], **kwargs)
        else:
            parsed_args = LoadSkillArgs(**kwargs)

        skill_name = parsed_args.name
        resource_path = parsed_args.path
        logger.debug(f"Loading skill resource {resource_path} for {skill_name}.")

        manager = self._get_skill_manager()
        if manager is None:
            return "Skill manager not available. No skills have been registered."

        try:
            skill = manager.get_skill(skill_name)
        except ValueError:
            available = manager.get_all_skill_names()
            available_str = (
                ", ".join(available) if available else "No skills available."
            )
            return f"Skill '{skill_name}' not found. Available skills: {available_str}"

        if resource_path is None or resource_path == "SKILL.md":
            skill_dir = manager.get_skill_dir(skill_name)
            if skill_dir is not None:
                file_lines = [
                    f"<file>{skill_dir / rel}</file>"
                    for rel in sorted(skill.get_resource_paths())
                ]
                files_section = "\n".join(file_lines) if file_lines else ""
                return (
                    f'<skill_content name="{skill_name}">\n'
                    f"# Skill: {skill_name}\n\n"
                    f"{skill.content.strip()}\n\n"
                    f"Base directory for this skill: {skill_dir}\n"
                    f"Relative paths in this skill are relative to this base directory.\n"
                    f"<skill_files>\n"
                    f"{files_section}\n"
                    f"</skill_files>\n"
                    f"</skill_content>"
                )
            return skill.content

        content = skill.get_resource(resource_path)
        if content is None:
            available = sorted(skill.get_resource_paths())
            return f"Resource '{resource_path}' not found in skill '{skill_name}', Available resources: {available}"
        return content

    def _get_skill_manager(self) -> SkillManager | None:
        from flink_agents.runtime.resource_context import ResourceContextImpl

        ctx = self.resource_context
        if isinstance(ctx, ResourceContextImpl):
            return ctx.get_skill_manager()
        return None
