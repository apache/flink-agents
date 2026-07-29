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
"""Scheme-keyed registry mapping a skill source scheme (e.g. ``"local"``,
``"url"``, ``"package"``) to a handler that opens a :class:`SkillRepository`.
Built-ins are registered at import time; external code may add custom schemes
via :func:`register`.

Adding a new source = one :func:`register` call plus one ``SkillRepository``
implementation. ``SkillManager`` need not change.

The Java side has an analogous ``SkillSourceRegistry`` registering ``local``
/ ``url`` / ``classpath``. Cross-language unsupported schemes (Java's
``classpath`` on Python, Python's ``package`` on Java) hit the registry's
fail-loud path at load time.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Callable, Dict, Mapping

from flink_agents.runtime.skill.repository.filesystem_repository import (
    FileSystemSkillRepository,
)
from flink_agents.runtime.skill.repository.package_repository import (
    PackageSkillRepository,
)
from flink_agents.runtime.skill.repository.url_repository import URLSkillRepository

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


_HANDLERS: Dict[str, SkillSourceHandler] = {}


def register(
    scheme: str,
    open_fn: Callable[[Mapping[str, str]], SkillRepository],
    describe_location: Callable[[Mapping[str, str]], str] | None = None,
) -> None:
    """Register a handler under ``scheme``. Scheme is normalized to lowercase.

    ``describe_location`` defaults to the raw params dict; pass a tighter
    function (e.g. ``lambda p: p.get("path", "")``) to produce a clean origin.
    """
    handler = (
        SkillSourceHandler(open=open_fn)
        if describe_location is None
        else SkillSourceHandler(open=open_fn, describe_location=describe_location)
    )
    _HANDLERS[scheme.lower()] = handler


def get(scheme: str) -> SkillSourceHandler:
    """Return the handler for ``scheme``, or raise if unknown.

    The error message lists the currently registered schemes to aid debugging
    cross-language plan mismatches.
    """
    handler = _HANDLERS.get(scheme.lower())
    if handler is None:
        msg = (
            f"Unknown skill source scheme: {scheme}. "
            f"Registered schemes: {sorted(_HANDLERS.keys())}"
        )
        raise ValueError(msg)
    return handler


def _require(params: Mapping[str, str], scheme: str, key: str) -> str:
    value = params.get(key)
    if value is None:
        msg = (
            f"Missing required param '{key}' for skill source scheme "
            f"'{scheme}'. Got: {dict(params)}"
        )
        raise ValueError(msg)
    return value


register(
    "local",
    lambda params: FileSystemSkillRepository(_require(params, "local", "path")),
    lambda params: params.get("path", ""),
)
register(
    "url",
    lambda params: URLSkillRepository(_require(params, "url", "url")),
    lambda params: params.get("url", ""),
)
register(
    "package",
    lambda params: PackageSkillRepository(
        _require(params, "package", "package"),
        _require(params, "package", "resource"),
    ),
    lambda params: f"{params.get('package', '')}/{params.get('resource', '')}",
)
