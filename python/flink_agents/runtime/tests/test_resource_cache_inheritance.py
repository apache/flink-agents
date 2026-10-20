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
"""Shared resource lookup, overriding, and lifecycle ownership."""

from unittest.mock import Mock

import pytest

from flink_agents.api.resource import Resource, ResourceType
from flink_agents.plan.resource_provider import ResourceProvider
from flink_agents.runtime.resource_cache import ResourceCache


def _provider(resource: Resource) -> Mock:
    provider = Mock(spec=ResourceProvider)
    provider.provide.return_value = resource
    return provider


def test_siblings_share_one_parent_instance_without_owning_it() -> None:
    resource = Mock(spec=Resource)
    provider = _provider(resource)
    root = ResourceCache({ResourceType.TOOL: {"shared": provider}})
    children = [ResourceCache({}, parent=root) for _ in range(2)]

    for child in children:
        assert child.get_resource("shared", ResourceType.TOOL) is resource
        assert child.get_resource("shared", ResourceType.TOOL) is resource
        child.close()

    provider.provide.assert_called_once()
    resource.open.assert_called_once()
    resource.close.assert_not_called()
    assert root.get_resource("shared", ResourceType.TOOL) is resource
    root.close()
    resource.close.assert_called_once()


def test_child_override_and_dependency_lookup_use_child_scope() -> None:
    root_resource, child_resource, dependent = [Mock(spec=Resource) for _ in range(3)]
    root_provider = _provider(root_resource)
    child_provider = _provider(child_resource)
    dependent_provider = _provider(dependent)

    def build_dependent(*, resource_context, config) -> Resource:
        assert (
            resource_context.get_resource("shared", ResourceType.TOOL) is child_resource
        )
        return dependent

    dependent_provider.provide.side_effect = build_dependent
    root = ResourceCache({ResourceType.TOOL: {"shared": root_provider}})
    child = ResourceCache(
        {
            ResourceType.TOOL: {
                "shared": child_provider,
                "dependent": dependent_provider,
            }
        },
        parent=root,
    )
    # Even an already cached parent instance must not hide a child's provider.
    assert root.get_resource("shared", ResourceType.TOOL) is root_resource
    assert child.get_resource("dependent", ResourceType.TOOL) is dependent
    assert child.get_resource("shared", ResourceType.TOOL) is child_resource
    child.close()
    child_resource.close.assert_called_once()
    dependent.close.assert_called_once()
    root_resource.close.assert_not_called()
    root.close()


def test_missing_name_in_existing_type_falls_back_but_wrong_type_does_not() -> None:
    resource = Mock(spec=Resource)
    root = ResourceCache({ResourceType.TOOL: {"shared": _provider(resource)}})
    child = ResourceCache(
        {ResourceType.TOOL: {"other": _provider(Mock())}}, parent=root
    )
    assert child.get_resource("shared", ResourceType.TOOL) is resource
    with pytest.raises(KeyError, match="Resource not found"):
        child.get_resource("shared", ResourceType.CHAT_MODEL)
    with pytest.raises(KeyError, match="Resource not found"):
        root.get_resource("other", ResourceType.TOOL)
    child.close()
    root.close()


def test_shared_resource_dependencies_remain_in_the_root_scope() -> None:
    root_dependency, child_dependency, shared = [Mock(spec=Resource) for _ in range(3)]
    provider = _provider(shared)

    def build_shared(*, resource_context, config) -> Resource:
        assert (
            resource_context.get_resource("dependency", ResourceType.TOOL)
            is root_dependency
        )
        return shared

    provider.provide.side_effect = build_shared
    root = ResourceCache(
        {
            ResourceType.TOOL: {
                "shared": provider,
                "dependency": _provider(root_dependency),
            }
        }
    )
    child = ResourceCache(
        {ResourceType.TOOL: {"dependency": _provider(child_dependency)}}, parent=root
    )
    assert child.get_resource("shared", ResourceType.TOOL) is shared
    child_dependency.open.assert_not_called()
    root.close()
    child.close()
    shared.close.assert_called_once()
    root_dependency.close.assert_called_once()


def test_child_provider_failure_does_not_silently_fall_back() -> None:
    root_provider = _provider(Mock(spec=Resource))
    broken_provider = Mock(spec=ResourceProvider)
    broken_provider.provide.side_effect = RuntimeError("broken child resource")
    root = ResourceCache({ResourceType.TOOL: {"shared": root_provider}})
    child = ResourceCache({ResourceType.TOOL: {"shared": broken_provider}}, parent=root)
    with pytest.raises(RuntimeError, match="broken child resource"):
        child.get_resource("shared", ResourceType.TOOL)
    root_provider.provide.assert_not_called()
    child.close()
    root.close()
