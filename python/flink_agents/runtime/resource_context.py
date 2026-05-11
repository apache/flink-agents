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
"""Runtime implementation of ResourceContext."""

from __future__ import annotations

from typing import TYPE_CHECKING, List, cast

from flink_agents.api.resource import ResourceType
from flink_agents.api.resource_context import ResourceContext
from flink_agents.plan.agent_plan import SKILLS_CONFIG
from flink_agents.runtime.skill.skill_manager import SkillManager

if TYPE_CHECKING:
    from flink_agents.api.resource import Resource
    from flink_agents.api.skills import Skills
    from flink_agents.runtime.resource_cache import ResourceCache


class ResourceContextImpl(ResourceContext):
    """Concrete ResourceContext backed by AgentPlan.

    Creates the SkillManager lazily from the Skills config resource.
    ``get_skill_manager()`` is only visible to runtime-internal code
    (e.g., skill tools); it is NOT part of the public
    :class:`ResourceContext` interface.
    """

    def __init__(self, resource_cache: ResourceCache) -> None:
        """Initialize with the backing AgentPlan."""
        self._resource_cache = resource_cache
        self._skill_manager: SkillManager | None = None
        self._skill_manager_initialized = False

    def get_resource(self, name: str, resource_type: ResourceType) -> Resource:
        """Get another resource declared in the same Agent."""
        return self._resource_cache.get_resource(name, resource_type)

    def generate_available_skills_prompt(self, *skill_names: str) -> str:
        """Generate the skill discovery prompt for the given skill names."""
        manager = self.get_skill_manager()
        if manager is None:
            return ""
        return manager.generate_discovery_prompt(*skill_names)

    def get_skill_dirs(self, *skill_names: str) -> List[str]:
        """Return absolute directory paths for the given skill names."""
        manager = self.get_skill_manager()
        if manager is None:
            return []
        return manager.get_skill_dirs(*skill_names)

    def get_skill_manager(self) -> SkillManager | None:
        """Get the SkillManager (runtime-internal only).

        NOT part of the public ResourceContext interface.
        """
        if not self._skill_manager_initialized:
            self._skill_manager_initialized = True
            self._skill_manager = self._create_skill_manager()
        return self._skill_manager

    def _create_skill_manager(self) -> SkillManager | None:
        try:
            skills_config = cast(
                "Skills",
                self._resource_cache.get_resource(SKILLS_CONFIG, ResourceType.SKILLS),
            )
        except KeyError:
            return None
        return SkillManager(skills_config)
