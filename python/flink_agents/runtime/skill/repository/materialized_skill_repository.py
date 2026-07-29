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
"""Base for SkillRepository implementations whose skills live on disk under a
single :class:`Materialized` directory. Subclasses contribute only the
source-specific I/O that produces that handle in their constructor; reading
and close lifecycle are handled here.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Dict, List

from typing_extensions import override

from flink_agents.runtime.skill.repository.skill_directory_reader import (
    SkillDirectoryReader,
)
from flink_agents.runtime.skill.skill_repository import SkillRepository

if TYPE_CHECKING:
    from pathlib import Path

    from flink_agents.runtime.skill.agent_skill import AgentSkill
    from flink_agents.runtime.skill.repository._materialize import Materialized


class MaterializedSkillRepository(SkillRepository):
    """SkillRepository whose backing data is a single :class:`Materialized`
    directory — borrowed (already on disk) or owned (extracted from a zip /
    download). :meth:`close` is unconditional because ``Materialized.close``
    collapses both cases.
    """

    def __init__(self, materialization: Materialized) -> None:
        """Initialize a :class:`MaterializedSkillRepository`."""
        self._materialization = materialization
        self._reader = SkillDirectoryReader(materialization.dir)

    @override
    def get_skill(self, name: str) -> AgentSkill | None:
        return self._reader.get_skill(name)

    @override
    def get_skills(self) -> List[AgentSkill]:
        return self._reader.get_skills()

    @override
    def get_resources(self, name: str) -> Dict[str, str]:
        return self._reader.get_resources(name)

    @override
    def get_skill_dir(self, name: str) -> Path:
        return self._reader.get_skill_dir(name)

    @override
    def close(self) -> None:
        self._materialization.close()
