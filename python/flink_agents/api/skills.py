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
"""Skills configuration resource for agent skills discovery.

Use :meth:`Skills.from_local_dir` to construct a :class:`Skills` resource.

Example::

    @skills
    @staticmethod
    def my_skills() -> Skills:
        return Skills.from_local_dir("./skills")

Declare more than one ``@skills`` function on the same agent to combine
sources; the runtime merges them.
"""

from __future__ import annotations

from typing import List

from pydantic import Field
from typing_extensions import override

from flink_agents.api.resource import ResourceType, SerializableResource


class Skills(SerializableResource):
    """A resource describing where to load agent skills from.

    Use :meth:`from_local_dir` to construct — direct field construction is
    reserved for internal serialization and not part of the public API.
    """

    # Internal location list populated through the factory methods; kept
    # as a public pydantic field so it can be serialized/deserialized.
    paths: List[str] = Field(default_factory=list)

    @classmethod
    def from_local_dir(cls, *paths: str) -> Skills:
        """Create a Skills resource from one or more local filesystem directories.

        Each path points to a directory whose immediate subdirectories each
        contain a ``SKILL.md`` file.
        """
        return cls(paths=list(paths))

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""
        return ResourceType.SKILLS


# name of built-in tools needed by using skills
LOAD_SKILL_TOOL = "load_skill"
BASH_TOOL = "bash"
