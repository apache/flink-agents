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
    ModelRouter,
    RoutingStrategyType,
)
from flink_agents.api.resource import ResourceType, check_model_name_collision
from flink_agents.plan.resource_provider import PythonResourceProvider, ResourceProvider


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
) -> None:
    """Validate native Python routers after local and shared resources merge."""
    for name, provider in providers.get(ResourceType.MODEL_ROUTER, {}).items():
        check_model_name_collision(name, ResourceType.MODEL_ROUTER, providers)
        if not isinstance(provider, PythonResourceProvider):
            msg = (
                f"Model router '{name}' must use a Python descriptor in a Python agent."
            )
            raise TypeError(msg)
        router = ModelRouter(**provider.descriptor.arguments)
        if router.strategy.type == RoutingStrategyType.CUSTOM:
            custom_executor_class(router.strategy.executor_class)
