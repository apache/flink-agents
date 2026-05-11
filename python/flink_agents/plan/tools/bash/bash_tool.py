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
"""Standalone bash execution tool.

``BashTool`` is a general-purpose shell tool — it has no knowledge of skills.
The framework (e.g. ``chat_model_action``) injects ``allowed_commands`` and
``allowed_script_dirs`` at call time; the model only sees ``command``,
``timeout`` and ``cwd``.
"""

from __future__ import annotations

import logging
import subprocess
from typing import Any, List

from pydantic import BaseModel, Field
from typing_extensions import override

from flink_agents.api.tools.tool import Tool, ToolMetadata, ToolType
from flink_agents.plan.tools.bash.bash_validator import (
    is_under_allowed_dirs,
    validate_command,
)

logger = logging.getLogger(__name__)


class BashArgs(BaseModel):
    """Arguments for BashTool that are visible to the LLM."""

    command: str = Field(
        ...,
        description="The shell command to execute.",
    )
    timeout: int = Field(
        default=60,
        description="Timeout in seconds. Defaults to 60.",
    )
    cwd: str | None = Field(
        default=None,
        description=(
            "The working directory to run the command in. Defaults to the "
            "current directory. Use this instead of `cd` commands."
        ),
    )


class BashTool(Tool):
    """Standalone bash execution tool.

    Safety:
    - The first token of each sub-command must be in ``allowed_commands``, or
      resolve to a file under one of ``allowed_script_dirs``.
    - ``allowed_commands`` and ``allowed_script_dirs`` are injected at call
      time by the framework (not visible to the LLM through ``args_schema``).
    """

    metadata: ToolMetadata = Field(exclude=True)

    def __init__(self, **kwargs: Any) -> None:
        """Initialize the tool."""
        super().__init__(
            metadata=ToolMetadata(
                name="bash",
                description=(
                    "Execute a shell command. Only commands on the allowed "
                    "list or scripts under the allowed directories may run."
                ),
                args_schema=BashArgs,
            ),
            **kwargs,
        )

    @classmethod
    @override
    def tool_type(cls) -> ToolType:
        """Return tool type of class."""
        return ToolType.FUNCTION

    @override
    def call(self, *args: Any, **kwargs: Any) -> str:
        """Execute the command after validation.

        Accepts ``command``, ``timeout`` and ``cwd`` from the LLM, plus
        framework-injected ``allowed_commands`` and ``allowed_script_dirs``.
        """
        allowed_commands: List[str] = kwargs.pop("allowed_commands", None) or []
        allowed_script_dirs: List[str] = kwargs.pop("allowed_script_dirs", None) or []

        if args:
            parsed_args = BashArgs(command=args[0], **kwargs)
        else:
            parsed_args = BashArgs(**kwargs)

        command = parsed_args.command
        timeout = parsed_args.timeout
        cwd = parsed_args.cwd

        if cwd is not None and not is_under_allowed_dirs(cwd, allowed_script_dirs):
            return (
                f"Command rejected: cwd '{cwd}' is not under any allowed script dir. "
                f"Allowed script dirs: {sorted(allowed_script_dirs)}."
            )

        error = validate_command(command, allowed_commands, allowed_script_dirs, cwd)
        if error is not None:
            return f"Command rejected: {error}"

        logger.debug(
            f"Executing bash command: {command} (timeout={timeout}s, cwd={cwd})"
        )
        try:
            result = subprocess.run(
                command,
                shell=True,
                capture_output=True,
                text=True,
                timeout=timeout,
                cwd=cwd,
                executable="/bin/bash",
            )
            if result.returncode == 0:
                stdout = result.stdout.strip()
                return stdout if stdout else "Success"
            return f"Error (exit code {result.returncode}): {result.stderr.strip()}"
        except subprocess.TimeoutExpired:
            return f"Error: Command timed out after {timeout} seconds"
        except Exception as e:
            return f"Error: {e!s}"
