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
"""Public ResourceContext interface.

Defines the capabilities available to a Resource during execution.
The concrete implementation lives in :mod:`flink_agents.runtime.resource_context`.
"""

from abc import ABC, abstractmethod
from typing import TYPE_CHECKING, List

if TYPE_CHECKING:
    from flink_agents.api.resource import Resource, ResourceType


class ResourceContext(ABC):
    """Base abstract class for Resource Context."""

    @abstractmethod
    def get_resource(self, name: str, resource_type: "ResourceType") -> "Resource":
        """Get another resource declared in the same Agent."""

    @abstractmethod
    def generate_available_skills_prompt(self, *skill_names: str) -> str:
        """Generate the available skills prompt for the given skill names.

        Returns empty string if no skills are configured.
        """

    @abstractmethod
    def get_skill_dirs(self, *skill_names: str) -> List[str]:
        """Return absolute directory paths for the given skill names.

        Returns an empty list if no skills are configured or none of the
        requested skills are filesystem-backed.
        """
