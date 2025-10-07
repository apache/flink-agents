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
import importlib
from abc import ABC, abstractmethod
from collections.abc import Callable
from typing import Any, Dict

from pydantic import BaseModel, Field

from flink_agents.api.resource import (
    Resource,
    ResourceDescriptor,
    ResourceType,
    SerializableResource,
)
from flink_agents.plan.configuration import AgentConfiguration


class ResourceProvider(BaseModel, ABC):
    """Resource provider that carries resource meta to crate
     Resource object in runtime.

    Attributes:
    ----------
    name : str
        The name of the resource
    type : ResourceType
        The type of the resource
    """

    name: str
    type: ResourceType

    @abstractmethod
    def provide(self, get_resource: Callable, config: AgentConfiguration) -> Resource:
        """Create resource in runtime.

        Parameters
        ----------
        get_resource : Callable
            The helper function to get other resource declared in the same Agent.

        config : AgentConfiguration
            Configuration for Flink Agents.
        """


class SerializableResourceProvider(ResourceProvider, ABC):
    """Resource Provider that carries Resource object or serialized object.

    Attributes:
    ----------
    module : str
        The module name of the resource.
    clazz : str
        The class name of the resource.
    """

    module: str
    clazz: str


class PythonResourceProvider(ResourceProvider):
    """Python Resource provider that carries resource meta to crate
     Resource object in runtime.

    Attributes:
    ----------
    module : str
        The module name of the resource.
    clazz : str
        The class name of the resource.
    kwargs : Dict[str, Any]
        The initialization arguments of the resource.
    """

    module: str
    clazz: str
    kwargs: Dict[str, Any]

    @staticmethod
    def get(name: str, descriptor: ResourceDescriptor) -> "PythonResourceProvider":
        """Create PythonResourceProvider instance."""
        clazz = descriptor.clazz
        return PythonResourceProvider(
                    name=name,
                    type=clazz.resource_type(),
                    module=clazz.__module__,
                    clazz=clazz.__name__,
                    kwargs=descriptor.arguments,
                )


    def provide(self, get_resource: Callable, config: AgentConfiguration) -> Resource:
        """Create resource in runtime."""
        module = importlib.import_module(self.module)
        cls = getattr(module, self.clazz)

        final_kwargs = {}

        resource_class_config = config.get_config_data_by_prefix(self.clazz)

        final_kwargs.update(resource_class_config)
        final_kwargs.update(self.kwargs)

        resource = cls(**final_kwargs, get_resource=get_resource)
        return resource


class PythonSerializableResourceProvider(SerializableResourceProvider):
    """Resource Provider that carries Resource object or serialized object.

    Attributes:
    ----------
    serialized : Dict[str, Any]
        serialized resource object
    resource : Optional[SerializableResource]
        SerializableResource object
    """

    serialized: Dict[str, Any]
    resource: SerializableResource | None = Field(exclude=True, default=None)

    @staticmethod
    def from_resource(
        name: str, resource: SerializableResource
    ) -> "PythonSerializableResourceProvider":
        """Create PythonSerializableResourceProvider from SerializableResource."""
        return PythonSerializableResourceProvider(
            name=name,
            type=resource.resource_type(),
            serialized=resource.model_dump(),
            module=resource.__module__,
            clazz=resource.__class__.__name__,
            resource=resource,
        )

    def provide(self, get_resource: Callable, config: AgentConfiguration) -> Resource:
        """Get or deserialize resource in runtime."""
        if self.resource is None:
            module = importlib.import_module(self.module)
            clazz = getattr(module, self.clazz)
            self.resource = clazz.model_validate(self.serialized)
        return self.resource


# TODO: implementation
class JavaResourceProvider(ResourceProvider):
    """Represent Resource Provider declared by Java.

    Currently, this class only used for deserializing Java agent plan json
    """

    def provide(self, get_resource: Callable, config: AgentConfiguration) -> Resource:
        """Create resource in runtime."""
        err_msg = (
            "Currently, flink-agents doesn't support create resource "
            "by JavaResourceProvider in python."
        )
        raise NotImplementedError(err_msg)


# TODO: implementation
class JavaSerializableResourceProvider(SerializableResourceProvider):
    """Represent Serializable Resource Provider declared by Java.

    Currently, this class only used for deserializing Java agent plan json
    """

    def provide(self, get_resource: Callable, config: AgentConfiguration) -> Resource:
        """Get or deserialize resource in runtime."""
        err_msg = (
            "Currently, flink-agents doesn't support create resource "
            "by JavaSerializableResourceProvider in python."
        )
        raise NotImplementedError(err_msg)
