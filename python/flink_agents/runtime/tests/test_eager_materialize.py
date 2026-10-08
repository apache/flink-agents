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
    """Resource cache recording every resolution and close, returning a marker."""

    def __init__(self) -> None:
        self.resolved: list = []
        self.closed = False

    def get_resource(self, name: str, type: ResourceType) -> Any:
        self.resolved.append((name, type))
        return f"resource:{name}"

    def close(self) -> None:
        self.closed = True


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


def test_scoped_materialization_uses_the_child_plan_and_its_own_cache() -> None:
    """A sub-agent scope materializes against the child plan, not the root.

    An internal child that declares a Python-owned resource must have it built
    in the child scope's cache -- the same one a child action resolves through
    at call time -- keyed by the child plan JSON, so the resource is built once
    and shared instead of being looked up in the root plan where it is absent.
    """
    ctx, root_cache = _context(
        {ResourceType.CHAT_MODEL: {"root": _python_provider("root")}}
    )
    child_plan_json = '{"agent_name": "child"}'
    child_cache = _StubResourceCache()
    child_plan = _StubAgentPlan(
        {ResourceType.CHAT_MODEL: {"child_model": _python_provider("child_model")}}
    )
    ctx._FlinkRunnerContext__scoped_resource_caches = {
        child_plan_json: (child_plan, child_cache)
    }

    materialized = ctx.eager_materialize(ResourceType.CHAT_MODEL.value, child_plan_json)

    assert materialized == {"child_model": "resource:child_model"}
    assert child_cache.resolved == [("child_model", ResourceType.CHAT_MODEL)]
    # The root plan is untouched: the child's resource is not looked up there.
    assert root_cache.resolved == []


def test_scoped_materialization_leaves_java_owned_child_resources() -> None:
    """Only the child scope's Python-owned resources are built in Python."""
    ctx, _ = _context({})
    child_plan_json = '{"agent_name": "child"}'
    child_cache = _StubResourceCache()
    child_plan = _StubAgentPlan(
        {
            ResourceType.CHAT_MODEL: {
                "python": _python_provider("python"),
                "java": _java_provider("java"),
            }
        }
    )
    ctx._FlinkRunnerContext__scoped_resource_caches = {
        child_plan_json: (child_plan, child_cache)
    }

    materialized = ctx.eager_materialize(ResourceType.CHAT_MODEL.value, child_plan_json)

    assert materialized == {"python": "resource:python"}
    assert child_cache.resolved == [("python", ResourceType.CHAT_MODEL)]


def test_close_closes_the_root_and_every_scoped_cache() -> None:
    """Closing the context releases the sub-agent scope caches too.

    Each scope cache holds the Python-owned resources of its scope, built
    eagerly at open or lazily at call time; leaving them unclosed leaks those
    resources when the operator closes.
    """
    ctx, root_cache = _context({})
    scoped_a = _StubResourceCache()
    scoped_b = _StubResourceCache()
    ctx._FlinkRunnerContext__scoped_resource_caches = {
        '{"agent_name": "a"}': (_StubAgentPlan({}), scoped_a),
        '{"agent_name": "b"}': (_StubAgentPlan({}), scoped_b),
    }

    ctx.close()

    assert root_cache.closed is True
    assert scoped_a.closed is True
    assert scoped_b.closed is True
    assert ctx._FlinkRunnerContext__scoped_resource_caches == {}
