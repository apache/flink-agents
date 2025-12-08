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
from enum import Enum
from typing import Any, Callable, Dict, Type

from pydantic import BaseModel, Field, model_serializer, model_validator


class ResourceType(Enum):
    """Type enum of resource.

    Currently, support chat_model, chat_model_server, tool, embedding_model,
    vector_store and prompt.
    """

    CHAT_MODEL = "chat_model"
    CHAT_MODEL_CONNECTION = "chat_model_connection"
    TOOL = "tool"
    EMBEDDING_MODEL = "embedding_model"
    EMBEDDING_MODEL_CONNECTION = "embedding_model_connection"
    VECTOR_STORE = "vector_store"
    PROMPT = "prompt"
    MCP_SERVER = "mcp_server"


class Resource(BaseModel, ABC):
    """Base abstract class of all kinds of resources, includes chat model,
    prompt, tools and so on.

    Resource extends BaseModel only for decreasing the complexity of attribute
    declaration of subclasses, this not represents Resource object is serializable.

    Attributes:
    ----------
    get_resource : Callable[[str, ResourceType], "Resource"]
        Get other resource object declared in the same Agent. The first argument is
        resource name and the second argument is resource type.
    """

    get_resource: Callable[[str, ResourceType], "Resource"] = Field(
        exclude=True, default=None
    )

    @classmethod
    @abstractmethod
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""


class SerializableResource(Resource, ABC):
    """Resource which is serializable."""

    @model_validator(mode="after")
    def validate_serializable(self) -> "SerializableResource":
        """Ensure resource is serializable."""
        self.model_dump_json()
        return self


class ResourceDescriptor(BaseModel):
    """Descriptor for Resource instances, storing metadata for serialization and
    instantiation.

    Attributes:
        clazz: The Python Resource class name.
        java_clazz: The Java class full path (e.g., 'com.example.YourJavaClass').
                   Empty string for Python-only resources.
        arguments: Dictionary containing resource initialization parameters.
    """
    clazz: Type[Resource] | None = None
    java_clazz: str
    arguments: Dict[str, Any]

    def __init__(self, /,
                 *,
                 clazz: Type[Resource] | None = None,
                 java_clazz: str = "",
                 **arguments: Any) -> None:
        """Initialize ResourceDescriptor.

        Args:
            clazz: The Resource class type to create a descriptor for.
            java_clazz: The Java class full path for cross-platform compatibility.
                       **REQUIRED when declaring Java resources in Python.**
                       Defaults to empty string for Python-only resources.
                       Example: "com.example.YourJavaClass"
            **arguments: Additional arguments for resource initialization.

        Usage:
            descriptor = ResourceDescriptor(clazz=YourResourceClass,
                                            param1="value1",
                                            param2="value2")
        """
        super().__init__(clazz=clazz, java_clazz=java_clazz, arguments=arguments)

    @model_serializer
    def __custom_serializer(self) -> dict[str, Any]:
        """Serialize ResourceDescriptor to dictionary.

        Returns:
            Dictionary containing python_clazz, python_module, java_clazz, and
            arguments.
        """
        return {
            "python_clazz": self.clazz.__name__,
            "python_module": self.clazz.__module__,
            "java_clazz": self.java_clazz,
            "arguments": self.arguments,
        }

    @model_validator(mode="before")
    @classmethod
    def __custom_deserialize(cls, data: dict[str, Any]) -> dict[str, Any]:
        """Deserialize data to ResourceDescriptor fields.

        Handles both new format (with python_module) and legacy format
        (full path in python_clazz).

        Args:
            data: Dictionary or other data to deserialize.

        Returns:
            Dictionary with normalized field structure.
        """
        if "clazz" in data and data["clazz"] is not None:
            return data

        args = data["arguments"]
        python_clazz = args.pop("python_clazz")
        python_module = args.pop("python_module")
        data["clazz"] = get_resource_class(python_module, python_clazz)
        data["arguments"] = args["arguments"]
        return data

    def __eq__(self, other: object) -> bool:
        """Compare ResourceDescriptor objects, ignoring private _clazz field.

        This ensures that deserialized objects (with _clazz=None) can be compared
        equal to runtime objects (with _clazz set) as long as their serializable
        fields match.
        """
        if not isinstance(other, ResourceDescriptor):
            return False
        return (
            self.clazz == other.clazz
            and self.java_clazz == other.java_clazz
            and self.arguments == other.arguments
        )

    def __hash__(self) -> int:
        """Generate hash for ResourceDescriptor, ignoring private _clazz field."""
        return hash((self.clazz, self.java_clazz,
                     tuple(sorted(self.arguments.items()))))


def get_resource_class(module_path: str, class_name: str) -> Type[Resource]:
    """Get Resource class from separate module path and class name.

    Args:
        module_path: Python module path (e.g., 'your.module.path').
        class_name: Class name (e.g., 'YourResourceClass').

    Returns:
        The Resource class type.
    """
    module = importlib.import_module(module_path)
    return getattr(module, class_name)
