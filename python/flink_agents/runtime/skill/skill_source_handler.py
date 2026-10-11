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
"""Repository factory and source description for an individual SkillManager."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Callable, Mapping

if TYPE_CHECKING:
    from flink_agents.runtime.skill.skill_repository import SkillRepository


@dataclass(frozen=True)
class SkillSourceHandler:
    """Pair of (open, describe_location) bound to a scheme.

    ``describe_location`` returns the human-readable source location for
    :class:`SkillOrigin`. The default falls back to the raw params dict;
    built-ins override it to point at the relevant param (e.g. ``path`` for
    local). Keeping description on the handler removes the parallel scheme
    ladder ``SkillManager`` would otherwise need.
    """

    open: Callable[[Mapping[str, str]], SkillRepository]
    describe_location: Callable[[Mapping[str, str]], str] = field(
        default=lambda params: str(dict(params)),
    )
