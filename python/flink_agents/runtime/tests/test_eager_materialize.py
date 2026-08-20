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
"""Tests for materializing the resources the Python runtime owns.

The Java resource cache cannot build a resource declared by a Python provider,
so it asks the Python runtime to materialize its own resources and keeps a
handle to each. These tests exercise that Python-side entry with stub providers,
without a live interpreter.
"""

from typing import Any

from flink_agents.api.resource import ResourceType
from flink_agents.plan.resource_provider import (
    JavaResourceProvider,
    PythonResourceProvider,
    PythonSerializableResourceProvider,
)
from flink_agents.runtime.flink_runner_context import FlinkRunnerContext


class _StubResourceCache:
    """Resource cache recording every resolution and returning a marker."""

    def __init__(self) -> None:
        self.resolved: list = []

    def get_resource(self, name: str, type: ResourceType) -> Any:
        self.resolved.append((name, type))
        return f"resource:{name}"


class _StubAgentPlan:
    """Agent plan exposing only the resource providers."""

    def __init__(self, resource_providers: dict) -> None:
        self.resource_providers = resource_providers


def _context(resource_providers: dict) -> tuple[FlinkRunnerContext, _StubResourceCache]:
    """Build a FlinkRunnerContext over the given providers.

    Bypasses ``__init__`` (which needs a Java runner context) and injects the
    plan and cache the materialization reads.
    """
    ctx = FlinkRunnerContext.__new__(FlinkRunnerContext)
    cache = _StubResourceCache()
    ctx._FlinkRunnerContext__agent_plan = _StubAgentPlan(resource_providers)
    ctx._FlinkRunnerContext__resource_cache = cache
    return ctx, cache


def _python_provider(name: str) -> PythonSerializableResourceProvider:
    return PythonSerializableResourceProvider.model_construct(
        name=name, type=ResourceType.CHAT_MODEL
    )


def _python_descriptor_provider(name: str) -> PythonResourceProvider:
    return PythonResourceProvider.model_construct(
        name=name, type=ResourceType.CHAT_MODEL
    )


def _java_provider(name: str) -> JavaResourceProvider:
    return JavaResourceProvider.model_construct(name=name, type=ResourceType.CHAT_MODEL)


def test_python_owned_resources_are_materialized_and_keyed_by_name() -> None:
    """Both Python provider kinds are materialized through the resource cache."""
    ctx, cache = _context(
        {
            ResourceType.CHAT_MODEL: {
                "declared": _python_provider("declared"),
                "from_yaml": _python_descriptor_provider("from_yaml"),
            }
        }
    )

    materialized = ctx.eager_materialize(ResourceType.CHAT_MODEL.value)

    assert materialized == {
        "declared": "resource:declared",
        "from_yaml": "resource:from_yaml",
    }
    assert cache.resolved == [
        ("declared", ResourceType.CHAT_MODEL),
        ("from_yaml", ResourceType.CHAT_MODEL),
    ]


def test_java_owned_resources_are_left_to_the_java_cache() -> None:
    """A Java-owned resource is not built a second time in the Python runtime."""
    ctx, cache = _context(
        {
            ResourceType.CHAT_MODEL: {
                "python": _python_provider("python"),
                "java": _java_provider("java"),
            }
        }
    )

    materialized = ctx.eager_materialize(ResourceType.CHAT_MODEL.value)

    assert materialized == {"python": "resource:python"}
    assert cache.resolved == [("python", ResourceType.CHAT_MODEL)]


def test_a_type_without_providers_materializes_nothing() -> None:
    """The type the operator asks for may not exist in the plan at all."""
    ctx, cache = _context({})

    assert ctx.eager_materialize(ResourceType.CHAT_MODEL.value) == {}
    assert cache.resolved == []
