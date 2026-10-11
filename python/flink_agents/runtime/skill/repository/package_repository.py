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
"""Package-resource based :class:`SkillRepository`.

Resolves a resource (directory or zip) inside an installed Python package via
:mod:`importlib.resources` and materializes it once at construction time into
a process-owned temp directory.
"""

from __future__ import annotations

import tempfile
from pathlib import Path

from flink_agents.runtime.skill.repository._materialize import (
    Materialized,
)
from flink_agents.runtime.skill.repository.materialized_skill_repository import (
    MaterializedSkillRepository,
)
from flink_agents.runtime.skill.repository.package_materializer import (
    materialize_package,
)


class PackageSkillRepository(MaterializedSkillRepository):
    """Skill repository backed by a resource inside an installed Python package.

    The resource (directory or ``.zip``) is copied / extracted into a
    process-owned temp directory at construction time. The
    Package resources are resolved only during construction. Close releases
    the resulting owned directory without retaining package resource handles.
    """

    def __init__(self, package: str, resource: str) -> None:
        """Open ``resource`` inside ``package``.

        Args:
            package: Dotted package name.
            resource: Path inside the package; directory or ``.zip``.

        Raises:
            ValueError: If the resource is missing, or is neither a directory
                nor a ``.zip`` file.
        """
        self._package = package
        self._resource = resource
        materialization = Materialized(
            Path(tempfile.mkdtemp(prefix="flink-agents-skills-"))
        )
        try:
            materialize_package(package, resource, str(materialization.dir))
            super().__init__(materialization)
        except BaseException:
            materialization.close()
            raise

    @property
    def package(self) -> str:
        """Source package this repo was loaded from."""
        return self._package

    @property
    def resource(self) -> str:
        """Resource path inside :attr:`package`."""
        return self._resource
