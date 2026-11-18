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

The declared Python type selects the conversion. These stand-ins only implement
the methods that conversion calls.
"""

from datetime import timedelta
from typing import Any

import pytest
from py4j.protocol import Py4JError

from flink_agents.api.core_options import ShortTermMemoryTtlUpdate
from flink_agents.plan.tests.compatibility.check_java_python_config_options_parity import (
    normalize_java_default,
)


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
def test_duration_defaults_use_seconds_and_nanos(
    seconds: int, nanos: int, expected: timedelta
) -> None:
    value = _duration(seconds, nanos)
    assert normalize_java_default(value, "java.time.Duration", timedelta) == expected
    assert value.called == ["getSeconds", "getNano"]


def test_non_enum_python_type_does_not_read_a_name() -> None:
    value = _GatewayObject({"name": lambda: "SLF4J"})
    assert normalize_java_default(value, "java.lang.String", str) is value
    assert value.called == []


def test_java_enum_default_maps_to_the_declared_python_enum() -> None:
    value = _GatewayObject({"name": lambda: "ON_READ_AND_WRITE"})
    assert (
        normalize_java_default(
            value,
            "org.apache.flink.agents.api.agents.ShortTermMemoryTtlUpdate",
            ShortTermMemoryTtlUpdate,
        )
        is ShortTermMemoryTtlUpdate.ON_READ_AND_WRITE
    )
    assert value.called == ["name"]


def test_enum_gateway_errors_surface() -> None:
    value = _GatewayObject({})
    with pytest.raises(Py4JError, match=r"Method name"):
        normalize_java_default(
            value,
            "org.apache.flink.agents.api.agents.ShortTermMemoryTtlUpdate",
            ShortTermMemoryTtlUpdate,
        )


def test_python_numbers_and_none_are_converted_without_gateway_calls() -> None:
    assert normalize_java_default(None, "java.time.Duration", timedelta) is None
    assert normalize_java_default(2.0, "java.lang.Integer", int) == 2
    assert normalize_java_default(4, "java.lang.Long", int) == 4
