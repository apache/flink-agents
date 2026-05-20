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
"""Filesystem-backed :class:`SkillRepository`.

Accepts either a directory whose immediate subdirectories each contain a
``SKILL.md``, or a ``.zip`` file that expands into such a layout.
"""

from __future__ import annotations

from pathlib import Path

from flink_agents.runtime.skill.repository._materialize import (
    Materialized,
    extract_zip_safely,
)
from flink_agents.runtime.skill.repository.materialized_skill_repository import (
    MaterializedSkillRepository,
)


class FileSystemSkillRepository(MaterializedSkillRepository):
    """Filesystem-backed :class:`SkillRepository`.

    Accepts a directory whose immediate subdirectories each contain a
    ``SKILL.md``, or a ``.zip`` that extracts into such a layout.
    """

    def __init__(self, base_dir: Path | str) -> None:
        """Open a directory or ``.zip`` of skills.

        Raises:
            ValueError: If ``base_dir`` is None, doesn't exist, or is neither
                a directory nor a ``.zip`` file.
        """
        if base_dir is None:
            msg = "Base directory cannot be None"
            raise ValueError(msg)

        path = Path(base_dir).resolve()

        if not path.exists():
            msg = f"Path does not exist: {path}"
            raise ValueError(msg)

        if path.is_dir():
            materialization = Materialized.borrowed(path)
        elif path.is_file() and path.suffix.lower() == ".zip":
            materialization = extract_zip_safely(path)
        else:
            msg = f"Path must be a directory or a .zip file: {path}"
            raise ValueError(msg)

        super().__init__(materialization)

    @property
    def base_dir(self) -> Path:
        """Absolute base directory."""
        return self._materialization.dir
