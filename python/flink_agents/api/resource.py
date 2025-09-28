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
from abc import ABC, abstractmethod
from enum import Enum
from typing import Any, Callable, Dict, Type

from pydantic import BaseModel, Field, model_validator


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


class ResourceDescriptor:
    """Descriptor of resource, includes the class and the initialize arguments."""

    _clazz: Type[Resource]
    _arguments: Dict[str, Any]

    def __init__(self, *, clazz: Type[Resource], **arguments: Any) -> None:
        """Init method."""
        self._clazz = clazz
        self._arguments = arguments

    @property
    def clazz(self) -> Type[Resource]:
        """Get the class of the resource."""
        return self._clazz

    @property
    def arguments(self) -> Dict[str, Any]:
        """Get the initialize arguments of the resource."""
        return self._arguments
