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
from flink_agents.plan.function import JavaFunction
from flink_agents.plan.resource_provider import JavaResourceProvider, ResourceProvider
from flink_agents.plan.tools.function_tool import FunctionTool
from flink_agents.runtime.close_utils import failure_of, first_or_logged
from flink_agents.runtime.resource_context import ResourceContextImpl


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
        parent: "ResourceCache | None" = None,
    ) -> None:
        """Create a ResourceCache from the given resource providers and config.

        Parameters
        ----------
        resource_providers : Dict[ResourceType, Dict[str, ResourceProvider]]
            Two-level mapping of resource type to resource name to provider.
        config : AgentConfiguration | None
            Agent configuration passed to providers during resource creation.
        parent : ResourceCache | None
            Fallback cache for resources not declared in this scope. Resources
            resolved there remain owned by that cache, including their lifecycle.
        """
        self._resource_providers = resource_providers or {}
        self._config = (
            config
            if config is not None
            else parent.get_config()
            if parent is not None
            else AgentConfiguration()
        )
        self._parent = parent
        self._cache: Dict[ResourceType, Dict[str, Resource]] = {}
        self._j_resource_adapter: Any = None
        self._resource_context = ResourceContextImpl(self)

    def get_java_resource_adapter(self) -> Any:
        """Return this operator's Java bridge, or None in a standalone Python runner."""
        return self._j_resource_adapter

    def get_config(self) -> AgentConfiguration:
        """Return the agent configuration supplying the skill download limits.

        A scope without its own config uses its parent's, so one job uses one
        set of ``SkillOptions`` limits.
        """
        return self._config

    def get_resource_context(self) -> ResourceContextImpl:
        """Return the long-lived ResourceContext owned by this cache."""
        return self._resource_context

    def set_java_resource_adapter(self, j_resource_adapter: Any) -> None:
        """Set Java resource adapter for Java resource providers."""
        from flink_agents.runtime.java_resource_adapter import JavaResourceAdapterImpl

        self._j_resource_adapter = JavaResourceAdapterImpl(j_resource_adapter)

    def get_resource_providers(self, type: ResourceType) -> Dict[str, ResourceProvider]:
        """Return the providers declared for ``type``, keyed by resource name."""
        return self._resource_providers.get(type, {})

    def has_resource(self, name: str, type: ResourceType) -> bool:
        """Check visible resources without opening one, matching ``get_resource``.

        Chat models and routers share a namespace: either local declaration
        shadows both types in parent scopes. Other resource types inherit by type.
        """
        return self._has_local_resource(name, type) or (
            self._parent is not None
            and self._can_inherit_resource(name, type)
            and self._parent.has_resource(name, type)
        )

    def _has_local_resource(self, name: str, type: ResourceType) -> bool:
        """Include declared providers and resources inserted through ``put``."""
        cached = self._cache.get(type, {})
        declared = self._resource_providers.get(type, {})
        return name in cached or name in declared

    def _can_inherit_resource(self, name: str, type: ResourceType) -> bool:
        model_types = (ResourceType.CHAT_MODEL, ResourceType.MODEL_ROUTER)
        return type not in model_types or not any(
            self._has_local_resource(name, kind) for kind in model_types
        )

    def put(self, name: str, type: ResourceType, resource: Resource) -> None:
        """Cache an already built resource, so ``close()`` releases it."""
        self._cache.setdefault(type, {})[name] = resource

    def get_resource(self, name: str, type: ResourceType) -> Resource:
        """Get resource by name and type, creating it from its provider if not cached.

        Local chat-model/router names shadow both model types in parent scopes,
        matching ``has_resource``. Other resource types fall back by type.

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
            if self._parent is not None and self._can_inherit_resource(name, type):
                return self._parent.get_resource(name, type)
            msg = f"Resource not found: '{name}' of type {type}"
            raise KeyError(msg)
        resource_provider = providers[name]
        if isinstance(resource_provider, JavaResourceProvider):
            from flink_agents.runtime.java_resource_adapter import (
                JavaResourceAdapterImpl,
            )

            resource_provider.set_java_resource_adapter(
                self._j_resource_adapter or JavaResourceAdapterImpl(None)
            )
        resource = resource_provider.provide(
            resource_context=self._resource_context, config=self._config
        )
        if isinstance(resource, FunctionTool) and isinstance(
            resource.func, JavaFunction
        ):
            resource.set_java_resource_adapter(self._j_resource_adapter)
        # Local import avoids pulling sub-agent machinery for non-sub-agent usage.
        from flink_agents.runtime.base_subagent import BaseSubagentSetup

        if isinstance(resource, BaseSubagentSetup):
            # The framework owns the setup's identity: inject the resource name
            # as its sub-agent name, mirroring the Java ResourceCache.
            resource.set_subagent_name(name)
        resource.open()
        self._cache.setdefault(type, {})[name] = resource
        return resource

    def close(self) -> None:
        """Clean up all cached resources and close the injected ResourceContext.

        Cascades to ``ResourceContextImpl.close()`` which in turn closes the
        cached ``SkillManager`` (releasing materialized skill temp dirs). This
        is what releases skill resources on operator close, including Flink
        failover when the JVM stays up.

        Every resource is closed even when an earlier one fails, so a single bad
        resource cannot strand the ones behind it, the cache clear, or the
        resource context. The first failure is re-raised; later ones are logged.

        This mirrors the Java ``ResourceCache.close()`` contract. Two differences
        are deliberate. Java catches ``Throwable`` to keep a non-``Exception``
        failure from stopping the loop; the Python equivalent is ``Exception``,
        since it already covers what Java calls ``Error`` (``MemoryError`` and
        friends) while ``BaseException`` would also swallow ``KeyboardInterrupt``
        and ``SystemExit``, which cleanup must not do. And Java attaches later
        failures with ``addSuppressed``; ``ExceptionGroup`` needs 3.11 and this
        package supports 3.10, so later failures are logged instead of attached.
        """
        first_failure: Exception | None = None
        for typed in self._cache.values():
            for resource in typed.values():
                first_failure = first_or_logged(
                    failure_of(resource.close), first_failure, "resource"
                )
        self._cache.clear()
        first_failure = first_or_logged(
            failure_of(self._resource_context.close), first_failure, "resource context"
        )
        if first_failure is not None:
            raise first_failure
