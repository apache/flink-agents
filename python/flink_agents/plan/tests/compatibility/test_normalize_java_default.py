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
"""Default comparison for Java values, without a PyFlink gateway.

Py4J makes every attribute of a Java object look callable. These stand-ins
reproduce that, including ``Duration.name()`` raising ``Py4JError``.
"""

from datetime import timedelta
from typing import Any

import pytest
from py4j.protocol import Py4JError

from flink_agents.api.core_options import ShortTermMemoryTtlUpdate
from flink_agents.plan.tests.compatibility.check_java_python_config_options_parity import (
    normalize_java_default,
)


class _JavaClass:
    def __init__(self, is_enum: bool) -> None:
        self._is_enum = is_enum

    def isEnum(self) -> bool:
        return self._is_enum


class _GatewayObject:
    """Attribute lookup succeeds for every name, matching a Py4J JavaObject."""

    def __init__(self, methods: dict[str, Any]) -> None:
        self._methods = methods
        self.called: list[str] = []

    def __getattr__(self, name: str) -> Any:
        def invoke(*_args: Any, **_kwargs: Any) -> Any:
            self.called.append(name)
            if name not in self._methods:
                message = f"Method {name}([]) does not exist"
                raise Py4JError(message)
            value = self._methods[name]
            return value() if callable(value) else value

        return invoke


def _duration(seconds: int, nanos: int) -> _GatewayObject:
    return _GatewayObject(
        {
            "getSeconds": lambda: seconds,
            "getNano": lambda: nanos,
            "getClass": lambda: _JavaClass(is_enum=False),
            "name": lambda: "NOT_A_DURATION",
        }
    )


@pytest.mark.parametrize(
    ("seconds", "nanos", "expected"),
    [
        (1, 0, timedelta(seconds=1)),
        (-1, 999_000_000, timedelta(milliseconds=-1)),
        (0, 0, timedelta(0)),
        (1, 500_000_000, timedelta(milliseconds=1500)),
    ],
    ids=["1s", "-1ms", "zero", "1.5s"],
)
def test_duration_defaults_do_not_call_enum_name(
    seconds: int, nanos: int, expected: timedelta
) -> None:
    value = _duration(seconds, nanos)
    assert normalize_java_default(value, "java.time.Duration", timedelta) == expected
    assert "name" not in value.called
    assert "getClass" not in value.called


def test_duration_name_error_is_not_reached_for_missing_method() -> None:
    """The CI failure: Py4J ``name`` exists as an attribute but the call fails."""
    value = _GatewayObject(
        {
            "getSeconds": lambda: -1,
            "getNano": lambda: 999_000_000,
            "getClass": lambda: _JavaClass(is_enum=False),
        }
    )
    assert normalize_java_default(value, "java.time.Duration", timedelta) == timedelta(
        milliseconds=-1
    )
    assert "name" not in value.called


def test_non_enum_java_value_does_not_call_name() -> None:
    value = _GatewayObject(
        {
            "getClass": lambda: _JavaClass(is_enum=False),
            "name": lambda: "NOT_AN_ENUM",
        }
    )
    assert normalize_java_default(value, "java.lang.String", str) is value
    assert "name" not in value.called


def test_java_enum_default_maps_to_python_enum() -> None:
    value = _GatewayObject(
        {
            "getClass": lambda: _JavaClass(is_enum=True),
            "name": lambda: "ON_READ_AND_WRITE",
        }
    )
    assert (
        normalize_java_default(
            value,
            "org.apache.flink.agents.api.agents.ShortTermMemoryTtlUpdate",
            ShortTermMemoryTtlUpdate,
        )
        is ShortTermMemoryTtlUpdate.ON_READ_AND_WRITE
    )
    assert value.called == ["getClass", "name"]


def test_java_enum_name_is_returned_when_python_type_is_not_an_enum() -> None:
    value = _GatewayObject(
        {
            "getClass": lambda: _JavaClass(is_enum=True),
            "name": lambda: "SLF4J",
        }
    )
    assert normalize_java_default(value, "java.lang.String", str) == "SLF4J"


def test_py4j_error_from_enum_probe_falls_through() -> None:
    def raise_py4j_error() -> Any:
        message = "Method getClass([]) does not exist"
        raise Py4JError(message)

    value = _GatewayObject({"getClass": raise_py4j_error})
    assert normalize_java_default(value, "java.lang.Object", object) is value


def test_python_numbers_and_none_skip_enum_name() -> None:
    assert normalize_java_default(None, "java.time.Duration", timedelta) is None
    assert normalize_java_default(2.0, "java.lang.Integer", int) == 2
    assert normalize_java_default(4, "java.lang.Long", int) == 4
