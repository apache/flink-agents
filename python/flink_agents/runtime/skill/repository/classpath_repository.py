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
"""Read Java classpath resources from a Python-owned materialized directory."""

from __future__ import annotations

import tempfile
from pathlib import Path
from typing import Any

from flink_agents.runtime.skill.repository._materialize import Materialized
from flink_agents.runtime.skill.repository.materialized_skill_repository import (
    MaterializedSkillRepository,
)


class ClasspathSkillRepository(MaterializedSkillRepository):
    """Resolve resources through the current operator's Java bridge once at open."""

    def __init__(self, resource: str, java_bridge: Any = None) -> None:
        """Copy the classpath resource using the Flink user-code class loader."""
        if java_bridge is None:
            msg = (
                f"Classpath skill source {resource!r} requires the Flink Java runtime "
                "bridge. Run the agent in Flink and add its resource JARs to the job."
            )
            raise ValueError(msg)
        materialization = Materialized(
            Path(tempfile.mkdtemp(prefix="flink-agents-skills-"))
        )
        try:
            java_bridge.extractClasspathSkills(resource, str(materialization.dir))
            super().__init__(materialization)
        except BaseException:
            materialization.close()
            raise
