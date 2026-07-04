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
from dataclasses import dataclass
from typing import Any, Dict, Generic, Sequence, TypeVar, cast

from pydantic import Field
from typing_extensions import override

from flink_agents.api.resource import Resource, ResourceType

EmbeddingValue = TypeVar("EmbeddingValue", list[float], list[list[float]])


@dataclass(frozen=True)
class EmbeddingTokenUsage:
    """Token usage reported by an embedding provider."""

    prompt_tokens: int = 0
    total_tokens: int = 0


@dataclass(frozen=True)
class EmbeddingResult(Generic[EmbeddingValue]):
    """Embedding provider result with optional token usage metadata."""

    embeddings: EmbeddingValue
    token_usage: EmbeddingTokenUsage | None = None


class BaseEmbeddingModelConnection(Resource, ABC):
    """Base abstract class for text embedding model connection.

    Responsible for managing model service connection configurations.
    Specific implementations can add their own connection parameters like:
    - Service address (base_url) for remote services
    - API key (api_key) for authenticated services
    - Authentication information, timeouts, etc.

    Provides the basic embedding interface for direct communication with model services.

    One connection can be shared in multiple embedding model setup.
    """

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""
        return ResourceType.EMBEDDING_MODEL_CONNECTION

    @abstractmethod
    def embed(
        self, text: str | Sequence[str], **kwargs: Any
    ) -> list[float] | list[list[float]]:
        """Generate embedding vector for a single text input.

        Converts the input text into a high-dimensional vector representation
        suitable for semantic similarity search and retrieval operations.

        Args:
            text: The text string to convert into an embedding vector.
            **kwargs: Additional parameters passed to the embedding model.

        Returns:
            A list of floating-point numbers representing the embedding vector.
            The dimension of the vector depends on the specific embedding model used.
        """

    def embed_with_usage(
        self, text: str | Sequence[str], **kwargs: Any
    ) -> EmbeddingResult[list[float] | list[list[float]]]:
        """Generate embeddings and return provider token usage when available."""
        return EmbeddingResult(embeddings=self.embed(text, **kwargs))


class BaseEmbeddingModelSetup(Resource, ABC):
    """Base abstract class for text embedding model setup.

    Responsible for managing embedding model configurations, such as:
    - Connection to embedding model service (connection)
    - Model name (model)

    Provides the basic embedding interface for generating embeddings from text inputs.
    """

    connection: str | BaseEmbeddingModelConnection = Field(
        description="The referenced connection."
    )
    model: str = Field(description="Name of the embedding model to use.")

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""
        return ResourceType.EMBEDDING_MODEL

    @property
    @abstractmethod
    def model_kwargs(self) -> Dict[str, Any]:
        """Return embedding model settings."""

    @override
    def open(self) -> None:
        self.connection = cast(
            "BaseEmbeddingModelConnection",
            self.resource_context.get_resource(
                self.connection, ResourceType.EMBEDDING_MODEL_CONNECTION
            ),
        )

    def _get_connection(self) -> BaseEmbeddingModelConnection:
        if not isinstance(self.connection, BaseEmbeddingModelConnection):
            err_msg = f"Expect BaseEmbeddingModelConnection, but is {self.connection.__class__.__name__}"
            raise TypeError(err_msg)
        return self.connection

    def embed(
        self, text: str | Sequence[str], **kwargs: Any
    ) -> list[float] | list[list[float]]:
        """Generate embedding vector for a single text query.

        Converts the input text into a high-dimensional vector representation
        suitable for semantic similarity search and retrieval operations.

        Args:
            text: The text string to convert into an embedding vector.
            **kwargs: Additional parameters passed to the embedding model.

        Returns:
            A list of floating-point numbers representing the embedding vector.
            The dimension of the vector depends on the specific embedding model used.
        """
        merged_kwargs = self.model_kwargs.copy()
        merged_kwargs.update(kwargs)
        result = self._get_connection().embed_with_usage(text, **merged_kwargs)
        self._record_token_metrics(result.token_usage)
        return result.embeddings

    def _record_token_metrics(self, usage: EmbeddingTokenUsage | None) -> None:
        """Record embedding token metrics under the current model metric group."""
        if usage is None:
            return
        metric_group = self.metric_group
        if metric_group is None:
            return

        model_group = metric_group.get_sub_group("model", self.model)
        model_group.get_counter("promptTokens").inc(usage.prompt_tokens)
        model_group.get_counter("totalTokens").inc(usage.total_tokens)
