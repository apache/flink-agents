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
"""The model router resource the engine instantiates for a router declaration."""

from typing import Any

from pydantic import Field, PrivateAttr, model_validator

from flink_agents.api.chat_models.routing import RouterDeclaration, RoutingStrategy
from flink_agents.api.resource import (
    Resource,
    ResourceDescriptor,
    ResourceType,
)


class ModelRouter(Resource):
    """The ``MODEL_ROUTER`` resource: a router declaration decoded for the engine.

    It selects nothing by itself; ``flink_agents.plan.routing.resolver`` asks the
    strategy for the first model. The declaration rules run when the agent plan is
    built or restored (``validate_routing``), and again here on the effective
    arguments, because the provider merges ``ModelRouter.*`` configuration into the
    descriptor arguments when it creates this resource.
    """

    _routing_executor: Any = PrivateAttr(default=None)

    candidates: list[str]
    candidate_descriptions: dict[str, str] = Field(default_factory=dict)
    default_model: str | None = None
    fallback: bool = False
    strategy_type: str
    strategy_args: dict[str, Any] = Field(default_factory=dict)
    strategy_executor_class: str | None = None

    @model_validator(mode="after")
    def _validate_effective_arguments(self) -> "ModelRouter":
        RouterDeclaration(
            **self.model_dump(include=set(RouterDeclaration.model_fields))
        )
        return self

    @classmethod
    def resource_type(cls) -> ResourceType:
        """Return the router resource type."""
        return ResourceType.MODEL_ROUTER

    @property
    def strategy(self) -> RoutingStrategy:
        """Return the strategy declaration."""
        return RoutingStrategy(
            self.strategy_type, self.strategy_args, self.strategy_executor_class
        )

    @staticmethod
    def resource_descriptor(declaration: ResourceDescriptor) -> ResourceDescriptor:
        """Return ``declaration`` with this class named as the resource to provide.

        The router resource is framework-owned, so a Python declaration does not
        choose the class it is provided as. The builder and the YAML ``router``
        alias write ``ResourceName.ModelRouter.MODEL_ROUTER``, and whatever class a
        Python descriptor names is replaced, as the Java plan does for Java
        descriptors. A Java descriptor is returned unchanged.
        """
        if declaration.language != "python" or (
            declaration.target_module == ModelRouter.__module__
            and declaration.target_clazz == ModelRouter.__name__
        ):
            return declaration
        return ResourceDescriptor(
            target_module=ModelRouter.__module__,
            target_clazz=ModelRouter.__name__,
            arguments=declaration.arguments,
        )
