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

Each :class:`Skills` resource carries a single ordered list of
:class:`SkillSourceSpec` entries. Each entry has a ``scheme`` (e.g.
``"local"``, ``"url"``, ``"package"``) and a scheme-specific ``params`` map.
Use one of the factory methods to construct a :class:`Skills` resource:

* :meth:`Skills.from_local_dir` for local directories or local ``.zip`` files
* :meth:`Skills.from_url` for http(s) URLs pointing to a ``.zip``
* :meth:`Skills.from_package` for resources inside installed packages

Example::

    @skills
    @staticmethod
    def my_skills() -> Skills:
        return Skills.from_local_dir("./skills")


    @skills
    @staticmethod
    def remote_skills() -> Skills:
        return Skills.from_url("https://example.com/skills.zip")


    @skills
    @staticmethod
    def packaged_skills() -> Skills:
        return Skills.from_package(("my_skills_pkg", "skills"))

The ``"classpath"`` scheme is Java-only; a plan written by Java with
``scheme=classpath`` deserializes successfully on Python but
:class:`SkillManager` will fail fast at load time with the registered-scheme
list.

Declare more than one ``@skills`` function on the same agent to combine
sources; the runtime merges them and de-duplicates identical
:class:`SkillSourceSpec` entries.
"""

from __future__ import annotations

from typing import Dict, List, Tuple

from pydantic import BaseModel, ConfigDict, Field, field_validator
from typing_extensions import override

from flink_agents.api.resource import ResourceType, SerializableResource


class SkillSourceSpec(BaseModel):
    """One entry in :attr:`Skills.sources`.

    ``scheme`` identifies the source type; ``params`` carries the
    scheme-specific configuration. The ``scheme`` is normalized to lowercase.
    Unknown schemes deserialize successfully — the registry is the fail point
    at load time.
    """

    scheme: str
    params: Dict[str, str] = Field(default_factory=dict)

    model_config = ConfigDict(frozen=True)

    @field_validator("scheme")
    @classmethod
    def _lower(cls, v: str) -> str:
        return v.lower()

    def __hash__(self) -> int:
        return hash((self.scheme, tuple(sorted(self.params.items()))))


class Skills(SerializableResource):
    """A resource describing where to load agent skills from.

    Use one of the ``from_*`` factory methods to construct — direct field
    construction is reserved for internal serialization and not part of the
    public API.
    """

    sources: List[SkillSourceSpec] = Field(default_factory=list)

    @classmethod
    def from_local_dir(cls, *paths: str) -> Skills:
        """Create a Skills resource from one or more local paths.

        Each path may be a directory or a ``.zip`` file. For a directory, its
        immediate subdirectories must each contain a ``SKILL.md`` file. For
        a zip, its top-level entries are the skill subdirectories.
        """
        return cls(
            sources=[
                SkillSourceSpec(scheme="local", params={"path": p}) for p in paths
            ]
        )

    @classmethod
    def from_url(cls, *urls: str) -> Skills:
        """Create a Skills resource from one or more http(s) URLs.

        Each URL must point to a ``.zip`` whose top level is the baseDir
        (i.e. skill subdirectories sit at the top of the zip).
        """
        return cls(
            sources=[SkillSourceSpec(scheme="url", params={"url": u}) for u in urls]
        )

    @classmethod
    def from_package(cls, *pairs: Tuple[str, str]) -> Skills:
        """Create a Skills resource from resources inside installed packages.

        Args:
            *pairs: One or more ``(package, resource)`` tuples. ``package`` is
                a dotted Python package name (e.g. ``"my_skills_pkg"``);
                ``resource`` is a path inside the package, relative to the
                package root. The resource may refer to a directory or a
                ``.zip`` file.
        """
        return cls(
            sources=[
                SkillSourceSpec(
                    scheme="package", params={"package": pkg, "resource": res}
                )
                for pkg, res in pairs
            ]
        )

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""
        return ResourceType.SKILLS


# name of built-in tools needed by using skills
LOAD_SKILL_TOOL = "load_skill"
BASH_TOOL = "bash"
