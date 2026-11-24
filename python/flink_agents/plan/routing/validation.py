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
"""Plan-time checks for Python router declarations and custom executors."""

import importlib
import inspect

from flink_agents.api.chat_models.routing import (
    CustomRoutingExecutor,
    RouterDeclaration,
    RoutingStrategyType,
)
from flink_agents.api.resource import ResourceType, check_model_name_collision
from flink_agents.plan.resource_provider import PythonResourceProvider, ResourceProvider
from flink_agents.plan.routing.model_router import ModelRouter


def custom_executor_class(path: str) -> tuple[type[CustomRoutingExecutor], bool]:
    """Resolve the named class and choose its map or no-argument constructor."""
    module, _, name = path.rpartition(".")
    cls = getattr(importlib.import_module(module), name)
    if (
        not isinstance(cls, type)
        or not issubclass(cls, CustomRoutingExecutor)
        or inspect.isabstract(cls)
    ):
        msg = f"Custom executor '{path}' must implement CustomRoutingExecutor."
        raise ValueError(msg)
    signature = inspect.signature(cls)
    try:
        signature.bind({})
    except TypeError:
        try:
            signature.bind()
        except TypeError as error:
            msg = f"Custom executor '{path}' needs a map or no-argument constructor."
            raise ValueError(msg) from error
        return cls, False
    return cls, True


def validate_routing(
    providers: dict[ResourceType, dict[str, ResourceProvider]],
    *,
    allow_java: bool = False,
) -> None:
    """Validate native Python routers and name the router resource they provide.

    Runs when a plan is built and when one is restored, so a descriptor written by
    hand, loaded from YAML or read from a serialized plan meets the same checks as
    one built with ``ModelRouter.of(...)``. Each declaration is then provided as
    ``flink_agents.plan.routing.model_router.ModelRouter``, which only decodes it.
    ``allow_java`` keeps the Java routers of a restored Java plan, which the Java
    plan validates and Python never provides.
    """
    routers = providers.get(ResourceType.MODEL_ROUTER, {})
    for name, provider in list(routers.items()):
        check_model_name_collision(name, ResourceType.MODEL_ROUTER, providers)
        if not isinstance(provider, PythonResourceProvider):
            if allow_java:
                continue
            msg = (
                f"Model router '{name}' must use a Python descriptor in a Python agent."
            )
            raise TypeError(msg)
        declaration = RouterDeclaration(**provider.descriptor.arguments)
        if declaration.strategy.type == RoutingStrategyType.CUSTOM:
            custom_executor_class(declaration.strategy.executor_class)
        descriptor = ModelRouter.resource_descriptor(provider.descriptor)
        if descriptor is not provider.descriptor:
            routers[name] = PythonResourceProvider.get(
                name, descriptor, ResourceType.MODEL_ROUTER
            )
