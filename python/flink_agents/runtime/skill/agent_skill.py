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
#################################################################################
from typing import Callable, Dict, List

from pydantic import BaseModel, Field, PrivateAttr


class AgentSkill(BaseModel):
    """Represents an agent skill that can be loaded and used by agents.

    Attributes:
    ----------
    name : str
        Skill name (1-64 characters, lowercase letters, numbers, hyphens only).
        Must not start or end with a hyphen.
    description : str
        Skill description (1-1024 characters). Describes what the skill does
        and when to use it.
    license : Optional[str]
        License name or reference to a bundled license file.
    compatibility : Optional[str]
        Indicates environment requirements (intended product, system packages,
        network access, etc.) (max 500 characters).
    metadata : Optional[Dict[str, str]]
        Arbitrary key-value mapping for additional metadata.
    content : str
        The skill implementation or instructions (the markdown body after
        frontmatter in SKILL.md).
    resources : Dict[str, str]
        Supporting resources referenced by the skill. Keys are relative paths
        from the skill root, values are file contents.
    """

    name: str = Field(..., min_length=1, max_length=64)
    description: str = Field(..., min_length=1, max_length=1024)
    content: str = Field(..., min_length=1)
    license: str | None = Field(default=None)
    compatibility: str | None = Field(default=None, max_length=500)
    metadata: Dict[str, str] | None = Field(default=None)
    resources: Dict[str, str] | None = None

    _resource_loader: Callable[[], Dict[str, str]] | None = PrivateAttr(default=None)
    _activated: bool = PrivateAttr(default=False)

    def set_resource_loader(self, loader: Callable[[], Dict[str, str]]) -> None:
        """Set a lazy resource loader for this skill."""
        self._resource_loader = loader

    def _activate(self) -> None:
        """Load resources lazily on first access."""
        if not self._activated and self._resource_loader is not None:
            self.resources = self._resource_loader()
            self._activated = True

    def get_resource(self, resource_path: str) -> str | None:
        """Get resource content by path.

        Args:
            resource_path: The relative path of the resource from skill root.

        Returns:
            The resource content, or None if not found.
        """
        self._activate()
        return self.resources.get(resource_path) if self.resources else None

    def get_resource_paths(self) -> List[str]:
        """Get all the resource relative paths of the skill."""
        self._activate()
        if self.resources is None:
            return []
        return list(self.resources.keys())
