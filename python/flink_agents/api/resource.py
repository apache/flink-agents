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

from pydantic import BaseModel, model_validator


class ResourceType(Enum):
    """Type enum of resource.

    Currently, only support chat_models and tools.
    """

    CHAT_MODEL = "chat_model"
    TOOL = "tool"
    # EMBEDDING_MODEL = "embedding_model"
    # PROMPT = "prompt"
    # VECTOR_STORE = "vector_store"
    # MCP_SERVER = "mcp_server"


class Resource(BaseModel, ABC):
    """Base abstract class of all kinds of resources, includes chat model,
    prompt, tools and so on.

    Resource extends BaseModel only for decreasing the complexity of attribute
    declaration of subclasses, this not represents Resource object is serializable.

    Attributes:
    ----------
    name : str
        The name of the resource.
    type : ResourceType
        The type of the resource.
    """

    name: str

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
