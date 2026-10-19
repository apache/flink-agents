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
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any


class TraceLogLevel(Enum):
    """Recording level for matched trace log records.

    OFF suppresses matching records. STANDARD and VERBOSE preserve identities,
    relationships, timestamps, and statuses in full. Mirrors the Java
    ``TraceLogLevel`` enum.
    """

    OFF = "OFF"
    STANDARD = "STANDARD"
    VERBOSE = "VERBOSE"


class TraceLogScope(Enum):
    """Preset scopes for trace log recording targets.

    EVENT_ONLY is equivalent to ``entityType: event`` without ``entityName``.
    Mirrors the Java ``TraceLogScope`` enum.
    """

    EVENT_ONLY = "EVENT_ONLY"
    ALL = "ALL"


@dataclass(frozen=True)
class TraceLogTarget:
    """An immutable scope and optional recording level.

    Use the factories to select all records, a category, or a named entity.
    Omitted levels inherit from the next matching broader scope, falling back to
    STANDARD when no matching scope specifies a level. Names match exactly, or
    match a namespace and its descendants when ending in ``.*``.
    """

    _entity_type: str | None
    _entity_name: str | None = None
    _level: TraceLogLevel | None = None

    def __post_init__(self) -> None:
        if self._entity_type is not None:
            _required_string(self._entity_type, "entityType")
        if self._entity_name is not None:
            if self._entity_type is None:
                msg = "entityName requires entityType"
                raise _invalid(msg)
            _required_string(self._entity_name, "entityName")
            name = self._entity_name
            if "*" in name and (
                not name.endswith(".*") or len(name) == 2 or "*" in name[:-2]
            ):
                msg = (
                    "entityName must be an exact name or a namespace ending in .*; "
                    "omit entityName to select the whole type"
                )
                raise _invalid(msg)
        object.__setattr__(self, "_level", _parse_level(self._level))

    @staticmethod
    def all(level: TraceLogLevel | None = None) -> TraceLogTarget:
        """Select all entity types."""
        return TraceLogTarget(None, _level=level)

    @staticmethod
    def events(level: TraceLogLevel | None = None) -> TraceLogTarget:
        """Select all Events."""
        return TraceLogTarget.entity("event", level=level)

    @staticmethod
    def actions(level: TraceLogLevel | None = None) -> TraceLogTarget:
        """Select all Actions."""
        return TraceLogTarget.entity("action", level=level)

    @staticmethod
    def tools(level: TraceLogLevel | None = None) -> TraceLogTarget:
        """Select all tools."""
        return TraceLogTarget.entity("tool", level=level)

    @staticmethod
    def llms(level: TraceLogLevel | None = None) -> TraceLogTarget:
        """Select all chat models."""
        return TraceLogTarget.entity("llm", level=level)

    @staticmethod
    def parsers(level: TraceLogLevel | None = None) -> TraceLogTarget:
        """Select all output parsers."""
        return TraceLogTarget.entity("parser", level=level)

    @staticmethod
    def subagents(level: TraceLogLevel | None = None) -> TraceLogTarget:
        """Select all subagents."""
        return TraceLogTarget.entity("subagent", level=level)

    @staticmethod
    def event(name: str, level: TraceLogLevel | None = None) -> TraceLogTarget:
        """Select Events by exact name or namespace."""
        return TraceLogTarget.entity(
            "event", _required_string(name, "entityName"), level
        )

    @staticmethod
    def action(name: str, level: TraceLogLevel | None = None) -> TraceLogTarget:
        """Select Actions by exact name or namespace."""
        return TraceLogTarget.entity(
            "action", _required_string(name, "entityName"), level
        )

    @staticmethod
    def tool(name: str, level: TraceLogLevel | None = None) -> TraceLogTarget:
        """Select tools by exact name or namespace."""
        return TraceLogTarget.entity(
            "tool", _required_string(name, "entityName"), level
        )

    @staticmethod
    def llm(name: str, level: TraceLogLevel | None = None) -> TraceLogTarget:
        """Select chat models by exact name or namespace."""
        return TraceLogTarget.entity("llm", _required_string(name, "entityName"), level)

    @staticmethod
    def parser(name: str, level: TraceLogLevel | None = None) -> TraceLogTarget:
        """Select output parsers by exact name or namespace."""
        return TraceLogTarget.entity(
            "parser", _required_string(name, "entityName"), level
        )

    @staticmethod
    def subagent(name: str, level: TraceLogLevel | None = None) -> TraceLogTarget:
        """Select subagents by exact name or namespace."""
        return TraceLogTarget.entity(
            "subagent", _required_string(name, "entityName"), level
        )

    @staticmethod
    def entity(
        entity_type: str,
        entity_name: str | None = None,
        level: TraceLogLevel | None = None,
    ) -> TraceLogTarget:
        """Select any built-in or custom entity type, optionally by name."""
        _required_string(entity_type, "entityType")
        return TraceLogTarget(entity_type, entity_name, level)


_TARGET_FIELDS = {"scope", "level"}
_SCOPE_FIELDS = {"entityType", "entityName"}
_CATEGORIES = {"event", "action", "tool", "llm", "parser", "subagent"}


def _invalid(message: str) -> ValueError:
    return ValueError(f"Invalid trace-log.targets: {message}")


def _required_string(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        msg = f"{field} must be a non-empty string"
        raise _invalid(msg)
    return value


def _parse_level(value: Any) -> TraceLogLevel | None:
    if value is None or isinstance(value, TraceLogLevel):
        return value
    if isinstance(value, str):
        try:
            return TraceLogLevel(value.upper())
        except ValueError:
            pass
    msg = f"Invalid TraceLogLevel: {value!r}; use OFF, STANDARD, or VERBOSE"
    raise _invalid(msg)


def _check_fields(data: dict, allowed: set[str]) -> None:
    for key in data:
        if key not in allowed:
            msg = f"unknown field {key!r}"
            raise _invalid(msg)


def _parse_target(value: Any) -> TraceLogTarget:
    if isinstance(value, TraceLogTarget):
        return value
    if not isinstance(value, dict):
        msg = "each target must be an object"
        raise _invalid(msg)
    _check_fields(value, _TARGET_FIELDS)
    level = _parse_level(value.get("level"))
    scope = value.get("scope")
    if isinstance(scope, TraceLogScope):
        scope = scope.value
    if isinstance(scope, str):
        if scope.upper() == TraceLogScope.ALL.value:
            return TraceLogTarget.all(level)
        if scope.upper() == TraceLogScope.EVENT_ONLY.value:
            return TraceLogTarget.events(level)
        msg = f"Invalid TraceLogScope: {scope!r}; use EVENT_ONLY or ALL"
        raise _invalid(msg)
    if not isinstance(scope, dict):
        msg = "scope must be EVENT_ONLY, ALL, or an object"
        raise _invalid(msg)
    selectors = _CATEGORIES.intersection(scope)
    if selectors:
        if len(scope) != 1:
            msg = "scope must contain exactly one category selector"
            raise _invalid(msg)
        entity_type = next(iter(selectors))
        entity_name = _required_string(scope[entity_type], entity_type)
        return TraceLogTarget.entity(
            entity_type, None if entity_name == "*" else entity_name, level
        )
    _check_fields(scope, _SCOPE_FIELDS)
    return TraceLogTarget.entity(
        _required_string(scope.get("entityType"), "entityType"),
        scope.get("entityName"),
        level,
    )


def _parse_targets(value: Any) -> list[TraceLogTarget]:
    if not isinstance(value, list):
        msg = "must be a list of targets"
        raise _invalid(msg)
    targets = []
    for index, target in enumerate(value):
        try:
            targets.append(_parse_target(target))
        except ValueError as error:  # noqa: PERF203
            detail = str(error).removeprefix("Invalid trace-log.targets: ")
            message = f"target {index}: {detail}"
            raise _invalid(message) from error
    return targets


def _serialize_targets(value: Any) -> list[dict[str, Any]]:
    result = []
    for target in _parse_targets(value):
        scope: str | dict[str, str]
        if target._entity_type is None:
            scope = TraceLogScope.ALL.value
        elif target._entity_type == "event" and target._entity_name is None:
            scope = TraceLogScope.EVENT_ONLY.value
        else:
            scope = {"entityType": target._entity_type}
            if target._entity_name is not None:
                scope["entityName"] = target._entity_name
        serialized = {"scope": scope}
        if target._level is not None:
            serialized["level"] = target._level.value
        result.append(serialized)
    return result
