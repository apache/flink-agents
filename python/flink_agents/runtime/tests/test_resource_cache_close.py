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
"""Close-path contract tests for the Python ResourceCache.

These mirror the Java ``ResourceCacheTest`` close tests: a failing resource must
not strand the resources behind it, the cache clear, or the resource context.
"""

import pytest

from flink_agents.api.resource import Resource, ResourceType
from flink_agents.runtime.resource_cache import ResourceCache


class RecordingResource(Resource):
    """Records whether ``close()`` ran, and optionally fails it."""

    closed: bool = False
    failure: Exception | None = None

    @classmethod
    def resource_type(cls) -> ResourceType:
        return ResourceType.TOOL

    def close(self) -> None:
        """Record the call, then fail if this resource was configured to."""
        self.closed = True
        if self.failure is not None:
            raise self.failure


class RecordingContext:
    """Stands in for the resource context so its close is observable."""

    def __init__(self) -> None:
        self.closed = False

    def close(self) -> None:
        """Record that the cache reached the resource context."""
        self.closed = True


def _cache_with(*resources: RecordingResource) -> tuple[ResourceCache, RecordingContext]:
    cache = ResourceCache({})
    context = RecordingContext()
    cache._resource_context = context
    for i, resource in enumerate(resources):
        cache._cache.setdefault(ResourceType.TOOL, {})[f"r{i}"] = resource
    return cache, context


def test_close_closes_every_resource_when_an_earlier_one_fails() -> None:
    """A failing resource must not strand the ones behind it.

    The cache clear and the resource context close are on the same straight
    line behind the failure, so both are asserted rather than assumed.
    """
    failure = RuntimeError("resource close failed")
    failing = RecordingResource(failure=failure)
    surviving = RecordingResource()
    cache, context = _cache_with(failing, surviving)

    with pytest.raises(RuntimeError) as excinfo:
        cache.close()

    # The failure reaches the caller unchanged in identity, not wrapped.
    assert excinfo.value is failure
    assert failing.closed
    assert surviving.closed
    assert cache._cache == {}
    assert context.closed


def test_close_reports_first_failure_when_several_fail() -> None:
    """The first failure is the one raised; later ones do not replace it."""
    first = RuntimeError("first")
    second = RuntimeError("second")
    cache, context = _cache_with(
        RecordingResource(failure=first), RecordingResource(failure=second)
    )

    with pytest.raises(RuntimeError) as excinfo:
        cache.close()

    assert excinfo.value is first
    assert context.closed


def test_close_reports_resource_context_failure() -> None:
    """A resource context failure surfaces when no resource failed before it."""
    cache, context = _cache_with(RecordingResource())
    failure = RuntimeError("resource context close failed")

    def failing_close() -> None:
        context.closed = True
        raise failure

    context.close = failing_close  # type: ignore[method-assign]

    with pytest.raises(RuntimeError) as excinfo:
        cache.close()

    assert excinfo.value is failure


def test_close_returns_normally_when_nothing_fails() -> None:
    """The healthy path stays a plain no-raise close."""
    surviving = RecordingResource()
    cache, context = _cache_with(surviving)

    cache.close()

    assert surviving.closed
    assert cache._cache == {}
    assert context.closed
