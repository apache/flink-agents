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
from typing import Any, Dict

from pydantic import BaseModel, Field, model_validator

from flink_agents.api.resource import (
    Resource,
    ResourceDescriptor,
    ResourceType,
    SerializableResource,
    get_resource_class,
)
from flink_agents.api.resource_context import ResourceContext
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
    def provide(
        self, resource_context: ResourceContext, config: AgentConfiguration
    ) -> Resource:
        """Create resource in runtime.

        Parameters
        ----------
        resource_context : ResourceContext
            The context for the resource.

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

    descriptor: ResourceDescriptor

    @model_validator(mode="after")
    def _validate_language(self) -> "PythonResourceProvider":
        if self.descriptor.language != "python":
            msg = "PythonResourceProvider requires a Python descriptor"
            raise ValueError(msg)
        return self

    @staticmethod
    def get(
        name: str,
        descriptor: ResourceDescriptor,
        resource_type: ResourceType | None = None,
    ) -> "PythonResourceProvider":
        """Create PythonResourceProvider instance."""
        if descriptor.language != "python":
            msg = "PythonResourceProvider requires a Python descriptor"
            raise ValueError(msg)
        return PythonResourceProvider(
            name=name,
            type=resource_type or descriptor.clazz.resource_type(),
            descriptor=descriptor,
        )

    def provide(
        self, resource_context: ResourceContext, config: AgentConfiguration
    ) -> Resource:
        """Create resource in runtime."""
        cls = self.descriptor.clazz

        final_kwargs = {}

        resource_class_config = config.get_config_data_by_prefix(cls.__name__)

        final_kwargs.update(resource_class_config)
        final_kwargs.update(self.descriptor.arguments)

        resource = cls(**final_kwargs, resource_context=resource_context)
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

    def provide(
        self, resource_context: ResourceContext, config: AgentConfiguration
    ) -> Resource:
        """Get or deserialize resource in runtime."""
        if self.resource is None:
            module = importlib.import_module(self.module)
            clazz = getattr(module, self.clazz)
            self.serialized["resource_context"] = resource_context
            self.resource = clazz.model_validate(self.serialized)
        return self.resource


JAVA_RESOURCE_MAPPING: dict[ResourceType, str] = {
    ResourceType.CHAT_MODEL: "flink_agents.plan.resource.java.java_chat_model.JavaChatModelSetup",
    ResourceType.CHAT_MODEL_CONNECTION: "flink_agents.plan.resource.java.java_chat_model.JavaChatModelConnection",
    ResourceType.EMBEDDING_MODEL: "flink_agents.plan.resource.java.java_embedding_model.JavaEmbeddingModelSetup",
    ResourceType.EMBEDDING_MODEL_CONNECTION: "flink_agents.plan.resource.java.java_embedding_model.JavaEmbeddingModelConnection",
    ResourceType.VECTOR_STORE: "flink_agents.plan.resource.java.java_vector_store.JavaVectorStore",
}


class JavaResourceProvider(ResourceProvider):
    """Carry a Java implementation declaration and select its Plan wrapper."""

    descriptor: ResourceDescriptor
    _j_resource_adapter: Any = None

    @model_validator(mode="after")
    def _validate_language(self) -> "JavaResourceProvider":
        if self.descriptor.language != "java":
            msg = "JavaResourceProvider requires a Java descriptor"
            raise ValueError(msg)
        return self

    @staticmethod
    def get(
        name: str, descriptor: ResourceDescriptor, resource_type: ResourceType
    ) -> "JavaResourceProvider":
        """Create a Java provider without loading the target class in Python."""
        if descriptor.language != "java":
            msg = "JavaResourceProvider requires a Java descriptor"
            raise ValueError(msg)
        if resource_type not in JAVA_RESOURCE_MAPPING:
            msg = f"Unsupported Java resource type: {resource_type}"
            raise ValueError(msg)
        return JavaResourceProvider(
            name=name, type=resource_type, descriptor=descriptor
        )

    def provide(
        self, resource_context: ResourceContext, config: AgentConfiguration
    ) -> Resource:
        """Create resource in runtime."""
        if not self._j_resource_adapter:
            err_msg = "java resource adapter is not set"
            raise RuntimeError(err_msg)
        j_resource = self._j_resource_adapter.getResource(self.name, self.type.value)

        class_path = JAVA_RESOURCE_MAPPING.get(self.type)
        if not class_path:
            err_msg = f"No Java resource mapping found for {self.type.value}"
            raise ValueError(err_msg)
        module_path, class_name = class_path.rsplit(".", 1)
        cls = get_resource_class(module_path, class_name)
        kwargs = self.descriptor.arguments

        return cls(
            **kwargs,
            resource_context=resource_context,
            j_resource=j_resource,
            j_resource_adapter=self._j_resource_adapter,
        )

    def set_java_resource_adapter(self, j_resource_adapter: Any) -> None:
        """Set java resource adapter for java resource initialization."""
        self._j_resource_adapter = j_resource_adapter


# TODO: implementation
class JavaSerializableResourceProvider(SerializableResourceProvider):
    """Represent Serializable Resource Provider declared by Java.

    Currently, this class only used for deserializing Java agent plan json
    """

    def provide(
        self, resource_context: ResourceContext, config: AgentConfiguration
    ) -> Resource:
        """Get or deserialize resource in runtime."""
        err_msg = (
            "Currently, flink-agents doesn't support create resource "
            "by JavaSerializableResourceProvider in python."
        )
        raise NotImplementedError(err_msg)


def is_python_owned(provider: ResourceProvider) -> bool:
    """Whether the provider materializes a resource owned by the Python runtime.

    The runtime must ask that runtime to build such a resource instead of
    resolving it on the Java side. Mirrors Java ``ResourceProvider.isPythonOwned``.
    """
    return isinstance(
        provider, PythonResourceProvider | PythonSerializableResourceProvider
    )
