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
from typing import TYPE_CHECKING, Any, ClassVar, Dict, Literal, Type

from pydantic import BaseModel, ConfigDict, Field, PrivateAttr, model_validator

from flink_agents.api.resource_context import ResourceContext

if TYPE_CHECKING:
    from flink_agents.api.metric_group import MetricGroup


class ResourceType(Enum):
    """Type enum of resource.

    Currently, support chat_model, chat_model_server, tool, embedding_model,
    vector_store, prompt, mcp_server, skills, model_router, agent.
    """

    CHAT_MODEL = "chat_model"
    CHAT_MODEL_CONNECTION = "chat_model_connection"
    TOOL = "tool"
    EMBEDDING_MODEL = "embedding_model"
    EMBEDDING_MODEL_CONNECTION = "embedding_model_connection"
    VECTOR_STORE = "vector_store"
    PROMPT = "prompt"
    MCP_SERVER = "mcp_server"
    SKILLS = "skills"
    # Java-side in-chat model routing (FLIP; full Python routing is a follow-up).
    # Present so a Java plan containing a MODEL_ROUTER resource deserializes on the
    # Python side: mixed jobs (Java router + Python actions) must not fail at
    # operator open with a ValidationError.
    MODEL_ROUTER = "model_router"
    AGENT = "agent"


def check_registrable_from_python(resource_type: "ResourceType") -> None:
    """Reject resource types that cannot be registered from Python.

    MODEL_ROUTER exists in the enum only so Java plans containing routers
    deserialize on the Python side; Python-side routing is a planned
    follow-up. Shared by every registration entry point
    (``Agent.add_resource`` and ``AgentsExecutionEnvironment.add_resource``)
    so the guard and its message cannot drift apart.
    """
    if resource_type == ResourceType.MODEL_ROUTER:
        msg = (
            "MODEL_ROUTER resources cannot be registered from Python yet: "
            "model routing currently executes on the Java side only (the "
            "enum member exists so Java plans containing routers "
            "deserialize). Python-side routing is a planned follow-up."
        )
        raise NotImplementedError(msg)


class Resource(BaseModel, ABC):
    """Base abstract class of all kinds of resources, includes chat model,
    prompt, tools and so on.

    Resource extends BaseModel only for decreasing the complexity of attribute
    declaration of subclasses, this not represents Resource object is serializable.

    Attributes:
    ----------
    resource_context : ResourceContext
        Get other resource object declared in the same Agent. The first argument is
        resource name and the second argument is resource type.
    """

    model_config = ConfigDict(arbitrary_types_allowed=True)
    resource_context: ResourceContext | None = Field(exclude=True, default=None)

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

    def open(self) -> None:
        """Open the resource."""

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
    language: Literal["python", "java"] = "python"
    _default_language: ClassVar[str] = "python"
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
        language: str | None = None,
        **kwargs: Any,
    ) -> None:
        """Initialize ResourceDescriptor.

        Args:
            clazz: The fully qualified name of the resource implementation, including
                    module and class.
            target_module: The module name of the resource class.
            target_clazz: The class name of the resource.
            arguments: Dictionary containing resource initialization parameters.
            language: Serialized implementation language; must match this descriptor.
            **kwargs: Additional keywords arguments for resource initialization,
            will merge into arguments.

        Usage:
            descriptor = ResourceDescriptor(clazz="flink_agents.integrations.chat_models
                                            .ollama_chat_model.OllamaChatModelConnection",
                                            param1="value1",
                                            param2="value2")
        """
        language = language or self._default_language
        if language != self._default_language:
            msg = "Use the descriptor class matching the resource language"
            raise ValueError(msg)
        if clazz is not None:
            if language == "java":
                target_module, target_clazz = "", clazz
            else:
                target_module, separator, target_clazz = clazz.rpartition(".")
                if not separator or not target_module or not target_clazz:
                    msg = "Expected a Python module.ClassName"
                    raise ValueError(msg)

        if target_clazz is None or target_module is None:
            msg = "The fully qualified name of the resource must be specified"
            raise ValueError(msg)

        args = {}
        if arguments is not None:
            args.update(arguments)
        args.update(kwargs)

        super().__init__(
            language=language,
            target_module=target_module,
            target_clazz=target_clazz,
            arguments=args,
        )

    @model_validator(mode="wrap")
    @classmethod
    def _restore_language(cls, value: Any, handler: Any) -> "ResourceDescriptor":
        if (
            cls is ResourceDescriptor
            and isinstance(value, dict)
            and value.get("language") == "java"
        ):
            return JavaResourceDescriptor.model_validate(value)
        return handler(value)

    @model_validator(mode="after")
    def _validate_target(self) -> "ResourceDescriptor":
        if not self.target_clazz.strip():
            msg = "Resource class must not be empty"
            raise ValueError(msg)
        if self.language == "java" and self.target_module:
            msg = "Java resources require a full class name and an empty module"
            raise ValueError(msg)
        if self.language == "python" and not self.target_module.strip():
            msg = "Python resources require a module"
            raise ValueError(msg)
        return self

    @property
    def clazz(self) -> Type[Resource]:
        """Get the class of the resource."""
        if self.language != "python":
            msg = "A Java resource class can only be loaded by the Java provider"
            raise TypeError(msg)
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
            self.language == other.language
            and self.target_module == other.target_module
            and self.target_clazz == other.target_clazz
            and self.arguments == other.arguments
        )

    def __hash__(self) -> int:
        """Generate hash for ResourceDescriptor."""
        return hash(
            (
                self.language,
                self.target_module,
                self.target_clazz,
                tuple(sorted(self.arguments.items())),
            )
        )


class JavaResourceDescriptor(ResourceDescriptor):
    """Declare a Java implementation without importing a Python wrapper class."""

    language: Literal["java"] = "java"
    _default_language: ClassVar[str] = "java"


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


class ResourceName:
    """Hierarchical resource class names for pointing a resource implementation in
    ResourceDescriptor.

    Structure:
        - Python implementation: ResourceType.PROVIDER_RESOURCEKIND
        - Java implementation: ResourceType.Java.PROVIDER_RESOURCEKIND

    Example usage:
        # Python implementation
        ResourceName.ChatModel.OLLAMA_CONNECTION
        ResourceName.ChatModel.OPENAI_COMPLETIONS_SETUP

        # Java implementation
        ResourceName.ChatModel.Java.OLLAMA_CONNECTION
    """

    class ChatModel:
        """ChatModel resource names."""

        # Anthropic
        ANTHROPIC_CONNECTION = "flink_agents.integrations.chat_models.anthropic.anthropic_chat_model.AnthropicChatModelConnection"
        ANTHROPIC_SETUP = "flink_agents.integrations.chat_models.anthropic.anthropic_chat_model.AnthropicChatModelSetup"

        # Azure OpenAI
        AZURE_OPENAI_CONNECTION = "flink_agents.integrations.chat_models.azure.azure_openai_chat_model.AzureOpenAIChatModelConnection"
        AZURE_OPENAI_SETUP = "flink_agents.integrations.chat_models.azure.azure_openai_chat_model.AzureOpenAIChatModelSetup"

        # Ollama
        OLLAMA_CONNECTION = "flink_agents.integrations.chat_models.ollama_chat_model.OllamaChatModelConnection"
        OLLAMA_SETUP = "flink_agents.integrations.chat_models.ollama_chat_model.OllamaChatModelSetup"

        # OpenAI
        OPENAI_COMPLETIONS_CONNECTION = "flink_agents.integrations.chat_models.openai.openai_chat_model.OpenAIChatModelConnection"
        OPENAI_COMPLETIONS_SETUP = "flink_agents.integrations.chat_models.openai.openai_chat_model.OpenAIChatModelSetup"

        # DashScope
        DASHSCOPE_CONNECTION = "flink_agents.integrations.chat_models.dashscope_chat_model.DashScopeChatModelConnection"
        DASHSCOPE_SETUP = "flink_agents.integrations.chat_models.dashscope_chat_model.DashScopeChatModelSetup"

        # vLLM (OpenAI-compatible)
        VLLM_CONNECTION = "flink_agents.integrations.chat_models.vllm.vllm_chat_model.VLLMChatModelConnection"
        VLLM_SETUP = "flink_agents.integrations.chat_models.vllm.vllm_chat_model.VLLMChatModelSetup"

        # Watsonx
        WATSONX_CONNECTION = "flink_agents.integrations.chat_models.watsonx.watsonx_chat_model.WatsonxChatModelConnection"
        WATSONX_SETUP = "flink_agents.integrations.chat_models.watsonx.watsonx_chat_model.WatsonxChatModelSetup"

        class Java:
            """Java implementations of ChatModel."""

            # Anthropic
            ANTHROPIC_CONNECTION = "org.apache.flink.agents.integrations.chatmodels.anthropic.AnthropicChatModelConnection"
            ANTHROPIC_SETUP = "org.apache.flink.agents.integrations.chatmodels.anthropic.AnthropicChatModelSetup"

            # Bedrock
            BEDROCK_CONNECTION = "org.apache.flink.agents.integrations.chatmodels.bedrock.BedrockChatModelConnection"
            BEDROCK_SETUP = "org.apache.flink.agents.integrations.chatmodels.bedrock.BedrockChatModelSetup"

            # Gemini
            GEMINI_CONNECTION = "org.apache.flink.agents.integrations.chatmodels.gemini.GeminiChatModelConnection"
            GEMINI_SETUP = "org.apache.flink.agents.integrations.chatmodels.gemini.GeminiChatModelSetup"

            # Ollama
            OLLAMA_CONNECTION = "org.apache.flink.agents.integrations.chatmodels.ollama.OllamaChatModelConnection"
            OLLAMA_SETUP = "org.apache.flink.agents.integrations.chatmodels.ollama.OllamaChatModelSetup"

            # OpenAI Completions
            OPENAI_COMPLETIONS_CONNECTION = "org.apache.flink.agents.integrations.chatmodels.openai.OpenAICompletionsConnection"
            OPENAI_COMPLETIONS_SETUP = "org.apache.flink.agents.integrations.chatmodels.openai.OpenAICompletionsSetup"

            OPENAI_RESPONSES_CONNECTION = "org.apache.flink.agents.integrations.chatmodels.openai.OpenAIResponsesModelConnection"
            OPENAI_RESPONSES_SETUP = "org.apache.flink.agents.integrations.chatmodels.openai.OpenAIResponsesModelSetup"

            # Azure OpenAI
            AZURE_OPENAI_CONNECTION = "org.apache.flink.agents.integrations.chatmodels.openai.AzureOpenAIChatModelConnection"
            AZURE_OPENAI_SETUP = "org.apache.flink.agents.integrations.chatmodels.openai.AzureOpenAIChatModelSetup"

            # vLLM (OpenAI-compatible)
            VLLM_CONNECTION = "org.apache.flink.agents.integrations.chatmodels.openai.VLLMChatModelConnection"
            VLLM_SETUP = "org.apache.flink.agents.integrations.chatmodels.openai.VLLMChatModelSetup"

            # IBM watsonx.ai
            WATSONX_CONNECTION = "org.apache.flink.agents.integrations.chatmodels.watsonx.WatsonxChatModelConnection"
            WATSONX_SETUP = "org.apache.flink.agents.integrations.chatmodels.watsonx.WatsonxChatModelSetup"

    class EmbeddingModel:
        """EmbeddingModel resource names."""

        # Ollama
        OLLAMA_CONNECTION = "flink_agents.integrations.embedding_models.local.ollama_embedding_model.OllamaEmbeddingModelConnection"
        OLLAMA_SETUP = "flink_agents.integrations.embedding_models.local.ollama_embedding_model.OllamaEmbeddingModelSetup"

        # OpenAI
        OPENAI_CONNECTION = "flink_agents.integrations.embedding_models.openai_embedding_model.OpenAIEmbeddingModelConnection"
        OPENAI_SETUP = "flink_agents.integrations.embedding_models.openai_embedding_model.OpenAIEmbeddingModelSetup"

        # DashScope
        DASHSCOPE_CONNECTION = "flink_agents.integrations.embedding_models.dashscope_embedding_model.DashScopeEmbeddingModelConnection"
        DASHSCOPE_SETUP = "flink_agents.integrations.embedding_models.dashscope_embedding_model.DashScopeEmbeddingModelSetup"

        class Java:
            """Java implementations of EmbeddingModel."""

            # Ollama
            OLLAMA_CONNECTION = "org.apache.flink.agents.integrations.embeddingmodels.ollama.OllamaEmbeddingModelConnection"
            OLLAMA_SETUP = "org.apache.flink.agents.integrations.embeddingmodels.ollama.OllamaEmbeddingModelSetup"

            # Bedrock
            BEDROCK_CONNECTION = "org.apache.flink.agents.integrations.embeddingmodels.bedrock.BedrockEmbeddingModelConnection"
            BEDROCK_SETUP = "org.apache.flink.agents.integrations.embeddingmodels.bedrock.BedrockEmbeddingModelSetup"

            # OpenAI
            OPENAI_CONNECTION = "org.apache.flink.agents.integrations.embeddingmodels.openai.OpenAIEmbeddingModelConnection"
            OPENAI_SETUP = "org.apache.flink.agents.integrations.embeddingmodels.openai.OpenAIEmbeddingModelSetup"

    class VectorStore:
        """VectorStore resource names."""

        # Chroma
        CHROMA_VECTOR_STORE = "flink_agents.integrations.vector_stores.chroma.chroma_vector_store.ChromaVectorStore"

        # Mem0 (gateway to Mem0's native vector stores: pgvector, milvus, qdrant,
        # redis, ...)
        MEM0_VECTOR_STORE = "flink_agents.integrations.vector_stores.mem0.mem0_vector_store.Mem0VectorStore"

        class Java:
            """Java implementations of VectorStore."""

            # Elasticsearch
            ELASTICSEARCH_VECTOR_STORE = "org.apache.flink.agents.integrations.vectorstores.elasticsearch.ElasticsearchVectorStore"

            # Amazon OpenSearch (Serverless or Service domains)
            OPENSEARCH_VECTOR_STORE = "org.apache.flink.agents.integrations.vectorstores.opensearch.OpenSearchVectorStore"

            # Amazon S3 Vectors
            S3_VECTORS_VECTOR_STORE = "org.apache.flink.agents.integrations.vectorstores.s3vectors.S3VectorsVectorStore"

            # Milvus
            MILVUS_VECTOR_STORE = "org.apache.flink.agents.integrations.vectorstores.milvus.MilvusVectorStore"

    # MCP resource names
    MCP_SERVER = "flink_agents.integrations.mcp.mcp.MCPServer"
