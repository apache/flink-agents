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
from typing import TYPE_CHECKING, Any, Callable, Dict, Type

from pydantic import BaseModel, Field, PrivateAttr, model_validator

if TYPE_CHECKING:
    from flink_agents.api.metric_group import MetricGroup


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

    # The metric group bound to this resource, injected in RunnerContext#get_resource
    _metric_group: "MetricGroup | None" = PrivateAttr(default=None)

    @classmethod
    @abstractmethod
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""

    def set_metric_group(self, metric_group: "MetricGroup") -> None:
        """Set the metric group for this resource.

        Parameters
        ----------
        metric_group : MetricGroup
            The metric group to bind.
        """
        self._metric_group = metric_group

    @property
    def metric_group(self) -> "MetricGroup | None":
        """Get the bound metric group.

        Returns:
        -------
        MetricGroup | None
            The bound metric group, or None if not set.
        """
        return self._metric_group

    def close(self) -> None:
        """Close the resource."""


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
        target_module: The module name of the resource class.
        target_clazz: The class name of the resource.
        arguments: Dictionary containing resource initialization parameters.
    """

    _clazz: Type[Resource] = None
    target_module: str
    target_clazz: str
    arguments: Dict[str, Any]

    def __init__(
        self,
        /,
        *,
        clazz: str | None = None,
        target_module: str | None = None,
        target_clazz: str | None = None,
        arguments: Dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> None:
        """Initialize ResourceDescriptor.

        Args:
            clazz: The fully qualified name of the resource implementation, including
                    module and class.
            target_module: The module name of the resource class.
            target_clazz: The class name of the resource.
            arguments: Dictionary containing resource initialization parameters.
            **kwargs: Additional keywords arguments for resource initialization,
            will merge into arguments.

        Usage:
            descriptor = ResourceDescriptor(clazz="flink_agents.integrations.chat_models
                                            .ollama_chat_model.OllamaChatModelConnection",
                                            param1="value1",
                                            param2="value2")
        """
        if clazz is not None:
            parts = clazz.split(".")
            target_module = ".".join(parts[:-1])
            target_clazz = parts[-1]

        if target_clazz is None or target_module is None:
            msg = "The fully qualified name of the resource must be specified"
            raise ValueError(msg)

        args = {}
        if arguments is not None:
            args.update(arguments)
        args.update(kwargs)

        super().__init__(
            target_module=target_module, target_clazz=target_clazz, arguments=args
        )

    @property
    def clazz(self) -> Type[Resource]:
        """Get the class of the resource."""
        if self._clazz is None:
            module = importlib.import_module(self.target_module)
            self._clazz = getattr(module, self.target_clazz)
        return self._clazz

    def __eq__(self, other: object) -> bool:
        """Compare ResourceDescriptor objects, ignoring private _clazz field.

        This ensures that deserialized objects (with _clazz=None) can be compared
        equal to runtime objects (with _clazz set) as long as their serializable
        fields match.
        """
        if not isinstance(other, ResourceDescriptor):
            return False
        return (
            self.target_module == other.target_module
            and self.target_clazz == other.target_clazz
            and self.arguments == other.arguments
        )

    def __hash__(self) -> int:
        """Generate hash for ResourceDescriptor."""
        return hash(
            (
                self.target_module,
                self.target_clazz,
                tuple(sorted(self.arguments.items())),
            )
        )


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


class Constant:
    """Constant strings for pointing a built-in resource implementation."""

    # Built-in ChatModel
    # java wrapper
    JAVA_CHAT_MODEL_CONNECTION = (
        "flink_agents.api.chat_models.java_chat_model.JavaChatModelConnection"
    )
    JAVA_CHAT_MODEL_SETUP = (
        "flink_agents.api.chat_models.java_chat_model.JavaChatModelSetup"
    )
    # ollama
    OLLAMA_CHAT_MODEL_CONNECTION = "flink_agents.integrations.chat_models.ollama_chat_model.OllamaChatModelConnection"
    OLLAMA_CHAT_MODEL_SETUP = (
        "flink_agents.integrations.chat_models.ollama_chat_model.OllamaChatModelSetup"
    )
    # anthropic
    ANTHROPIC_CHAT_MODEL_CONNECTION = "flink_agents.integrations.chat_models.anthropic.anthropic_chat_model.AnthropicChatModelConnection"
    ANTHROPIC_CHAT_MODEL_SETUP = "flink_agents.integrations.chat_models.anthropic.anthropic_chat_model.AnthropicChatModelSetup"
    # Azure
    TONGYI_CHAT_MODEL_CONNECTION = "flink_agents.integrations.chat_models.tongyi_chat_model.TongyiChatModelConnection"
    TONGYI_CHAT_MODEL_SETUP = (
        "flink_agents.integrations.chat_models.tongyi_chat_model.TongyiChatModelSetup"
    )
    # OpenAI
    OPENAI_CHAT_MODEL_CONNECTION = "flink_agents.integrations.chat_models.openai.openai_chat_model.OpenAIChatModelConnection"
    OPENAI_CHAT_MODEL_SETUP = "flink_agents.integrations.chat_models.openai.openai_chat_model.OpenAIChatModelSetup"

    # Built-in EmbeddingModel
    # java wrapper
    JAVA_EMBEDDING_MODEL_CONNECTION = "flink_agents.api.embedding_models.java_embedding_model.JavaEmbeddingModelConnection"
    JAVA_EMBEDDING_MODEL_SETUP = (
        "flink_agents.api.embedding_models.java_embedding_model.JavaEmbeddingModelSetup"
    )
    # ollama
    OLLAMA_EMBEDDING_MODEL_CONNECTION = "flink_agents.integrations.embedding_models.local.ollama_embedding_model.OllamaEmbeddingModelConnection"
    OLLAMA_EMBEDDING_MODEL_SETUP = "flink_agents.integrations.embedding_models.local.ollama_embedding_model.OllamaEmbeddingModelSetup"

    # OpenAI
    OPENAI_EMBEDDING_MODEL_CONNECTION = "flink_agents.integrations.embedding_models.openai_embedding_model.OpenAIEmbeddingModelConnection"
    OPENAI_EMBEDDING_MODEL_SETUP = "flink_agents.integrations.embedding_models.openai_embedding_model.OpenAIEmbeddingModelSetup"

    # Built-in VectorStore
    # java wrapper
    JAVA_VECTOR_STORE = "flink_agents.api.vector_stores.java_vector_store.JavaVectorStore"
    JAVA_COLLECTION_MANAGEABLE_VECTOR_STORE = "flink_agents.api.vector_stores.java_vector_store.JavaCollectionManageableVectorStore"
    # chroma
    CHROMA_VECTOR_STORE = "flink_agents.integrations.vector_stores.chroma.chroma_vector_store.ChromaVectorStore"

    # MCP
    MCP_SERVER = "flink_agents.integrations.mcp.mcp.MCPServer"
