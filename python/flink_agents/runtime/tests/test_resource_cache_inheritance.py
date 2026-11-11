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
from flink_agents.plan.configuration import AgentConfiguration
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


def test_has_resource_checks_inherited_declarations_without_construction():
    provider = _provider(Mock(spec=Resource))
    root = ResourceCache({ResourceType.MODEL_ROUTER: {"router": provider}})
    child = ResourceCache({}, parent=root)
    assert child.has_resource("router", ResourceType.MODEL_ROUTER)
    assert not child.has_resource("router", ResourceType.CHAT_MODEL)
    assert not root.has_resource("missing", ResourceType.MODEL_ROUTER)
    provider.provide.assert_not_called()


@pytest.mark.parametrize(
    ("local_type", "parent_type"),
    [
        (ResourceType.CHAT_MODEL, ResourceType.MODEL_ROUTER),
        (ResourceType.MODEL_ROUTER, ResourceType.CHAT_MODEL),
    ],
)
def test_nearest_model_declaration_shadows_both_types(local_type, parent_type):
    parent_resource, local_resource = [Mock(spec=Resource) for _ in range(2)]
    parent_provider = _provider(parent_resource)
    local_provider = _provider(local_resource)
    root = ResourceCache({parent_type: {"shared": parent_provider}})
    child = ResourceCache({local_type: {"shared": local_provider}}, parent=root)
    grandchild = ResourceCache({}, parent=child)
    # The parent's already opened resource must not affect scoped name resolution.
    assert root.get_resource("shared", parent_type) is parent_resource
    for scope in (child, grandchild):
        assert scope.has_resource("shared", local_type)
        assert not scope.has_resource("shared", parent_type)
        local_provider.provide.assert_not_called()
        with pytest.raises(KeyError, match="Resource not found"):
            scope.get_resource("shared", parent_type)
    assert grandchild.get_resource("shared", local_type) is local_resource
    local_provider.provide.assert_called_once()
    grandchild.close()
    child.close()
    root.close()


def test_unrelated_resource_type_does_not_shadow_inherited_router():
    router, tool = [Mock(spec=Resource) for _ in range(2)]
    root = ResourceCache({ResourceType.MODEL_ROUTER: {"shared": _provider(router)}})
    child = ResourceCache({ResourceType.TOOL: {"shared": _provider(tool)}}, parent=root)
    assert child.has_resource("shared", ResourceType.MODEL_ROUTER)
    assert child.get_resource("shared", ResourceType.MODEL_ROUTER) is router
    assert child.get_resource("shared", ResourceType.TOOL) is tool
    child.close()
    root.close()


def test_get_config_returns_own_config_else_parents() -> None:
    root_config = AgentConfiguration({"k": "root"})
    child_config = AgentConfiguration({"k": "child"})
    root = ResourceCache({}, root_config)
    assert root.get_config() is root_config
    assert ResourceCache({}, parent=root).get_config() is root_config
    assert ResourceCache({}, child_config, parent=root).get_config() is child_config
    grandchild = ResourceCache({}, parent=ResourceCache({}, parent=root))
    assert grandchild.get_config() is root_config
    assert ResourceCache({}).get_config() is None
