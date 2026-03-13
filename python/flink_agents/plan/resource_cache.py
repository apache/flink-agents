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
from typing import Any, Dict

from flink_agents.api.resource import Resource, ResourceType
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.resource_provider import JavaResourceProvider, ResourceProvider


class ResourceCache:
    """Lazily resolves and caches Resource instances from ResourceProviders.

    Resources are created on first access via their provider's ``provide()`` method
    and cached for subsequent lookups. Supports recursive dependency resolution — a
    resource can depend on other resources.

    This class is designed for single-threaded access within Flink's mailbox
    execution model.
    """

    def __init__(
        self,
        resource_providers: Dict[ResourceType, Dict[str, ResourceProvider]],
        config: AgentConfiguration | None = None,
    ) -> None:
        """Create a ResourceCache from the given resource providers and config.

        Parameters
        ----------
        resource_providers : Dict[ResourceType, Dict[str, ResourceProvider]]
            Two-level mapping of resource type to resource name to provider.
        config : AgentConfiguration | None
            Agent configuration passed to providers during resource creation.
        """
        self._resource_providers = resource_providers or {}
        self._config = config
        self._cache: Dict[ResourceType, Dict[str, Resource]] = {}
        self._j_resource_adapter: Any = None

    def set_java_resource_adapter(self, j_resource_adapter: Any) -> None:
        """Set Java resource adapter for Java resource providers."""
        self._j_resource_adapter = j_resource_adapter

    def get_resource(self, name: str, type: ResourceType) -> Resource:
        """Get resource by name and type, creating it from its provider if not cached.

        Parameters
        ----------
        name : str
            The name of the resource.
        type : ResourceType
            The type of the resource.
        """
        cached = self._cache.get(type, {}).get(name)
        if cached is not None:
            return cached
        providers = self._resource_providers.get(type)
        if providers is None or name not in providers:
            msg = f"Resource not found: '{name}' of type {type}"
            raise KeyError(msg)
        resource_provider = providers[name]
        if isinstance(resource_provider, JavaResourceProvider):
            resource_provider.set_java_resource_adapter(self._j_resource_adapter)
        resource = resource_provider.provide(
            get_resource=self.get_resource, config=self._config
        )
        self._cache.setdefault(type, {})[name] = resource
        return resource

    def close(self) -> None:
        """Clean up all cached resources."""
        for typed in self._cache.values():
            for resource in typed.values():
                resource.close()
        self._cache.clear()
