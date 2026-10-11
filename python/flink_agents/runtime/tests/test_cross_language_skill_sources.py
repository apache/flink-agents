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
"""Cross-language forwarding of the ``skills_sources`` marker.

A Python chat model running inside a Java agent reaches its ``ResourceContext``
through :class:`JavaResourceContextWrapper`, so expanding the marker written by a
Java-side ``Skills`` object has to cross the bridge. Before the forwarding
existed the wrapper inherited the empty default and the marker resolved to no
skills without any error.
"""

from typing import Any

from flink_agents.api.skills import SkillSourceSpec
from flink_agents.plan.resource.java.java_resource_wrapper import (
    JavaResourceContextWrapper,
)
from flink_agents.runtime.java_resource_adapter import JavaResourceAdapterImpl


class _FakeJavaBridge:
    """Stand-in for the Java adapter object reached through pemja."""

    def __init__(self, names: list[str] | None) -> None:
        self._names = names
        self.received: Any = None

    def resolveSkillNamesForSources(self, sources: list[dict]) -> list[str] | None:
        self.received = sources
        return self._names


def _wrapper(bridge: _FakeJavaBridge) -> JavaResourceContextWrapper:
    return JavaResourceContextWrapper(JavaResourceAdapterImpl(bridge))


def test_flattens_specs_to_wire_maps_and_returns_bridge_result():
    bridge = _FakeJavaBridge(["math-calculator", "joke-generator"])

    resolved = _wrapper(bridge).resolve_skill_names_for_sources(
        [SkillSourceSpec(scheme="Local", params={"path": "/data/skills"})]
    )

    assert resolved == ["math-calculator", "joke-generator"]
    # "Local" is normalized before crossing, so the Java SkillSourceSpec
    # constructor receives the same lowercase scheme.
    assert bridge.received == [{"scheme": "local", "params": {"path": "/data/skills"}}]


def test_empty_sources_short_circuit_without_crossing():
    bridge = _FakeJavaBridge(["unused"])

    assert _wrapper(bridge).resolve_skill_names_for_sources([]) == []
    assert bridge.received is None


def test_missing_bridge_result_resolves_to_empty_list():
    bridge = _FakeJavaBridge(None)

    resolved = _wrapper(bridge).resolve_skill_names_for_sources(
        [SkillSourceSpec(scheme="local", params={"path": "/data"})]
    )

    assert resolved == []


def test_adapter_impl_normalizes_missing_bridge_result():
    bridge = _FakeJavaBridge(None)

    assert JavaResourceAdapterImpl(bridge).resolveSkillNamesForSources([]) == []
