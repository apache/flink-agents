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

from flink_agents.api.chat_message import (
    DataContentBlock,
    MediaBlock,
    TextBlock,
    UnsupportedContentBlockError,
)
from flink_agents.api.resource import Resource, ResourceType

EmbeddingContent = str | DataContentBlock | Sequence[str | DataContentBlock]
EmbeddingValue = TypeVar("EmbeddingValue", list[float], list[list[float]])


def _text_embedding_input(
    content: EmbeddingContent, provider: str
) -> str | Sequence[str]:
    """Project text blocks onto a text-only provider's existing input contract."""

    def require_text(item: str | DataContentBlock) -> str:
        if isinstance(item, TextBlock):
            return item.text
        if isinstance(item, MediaBlock):
            raise UnsupportedContentBlockError.for_block(
                provider, item, "the embedding model accepts only text"
            )
        if isinstance(item, str):
            return item
        msg = "Each embedding input must be a string or a data content block"
        raise TypeError(msg)

    if isinstance(content, str | TextBlock | MediaBlock):
        return require_text(content)
    if isinstance(content, Sequence) and any(
        isinstance(item, TextBlock | MediaBlock) for item in content
    ):
        return [require_text(item) for item in content]
    return cast("Sequence[str]", content)


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
    """Base abstract class for an embedding model connection.

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
        self, content: EmbeddingContent, **kwargs: Any
    ) -> list[float] | list[list[float]]:
        """Generate a vector per input.

        Args:
            content: A string or data content block, or a batch of independent inputs.
                The selected model determines which media it accepts.
            **kwargs: Additional parameters passed to the embedding model.

        Returns:
            One vector for a single input, or a list of vectors in batch input order.
        """

    def embed_with_usage(
        self, content: EmbeddingContent, **kwargs: Any
    ) -> EmbeddingResult[list[float] | list[list[float]]]:
        """Generate embeddings and return provider token usage when available."""
        return EmbeddingResult(embeddings=self.embed(content, **kwargs))


class BaseEmbeddingModelSetup(Resource, ABC):
    """Base abstract class for an embedding model setup.

    Responsible for managing embedding model configurations, such as:
    - Connection to embedding model service (connection)
    - Model name (model)

    Provides the embedding interface with setup defaults.
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
        self, content: EmbeddingContent, **kwargs: Any
    ) -> list[float] | list[list[float]]:
        """Generate a vector per input using this setup.

        Args:
            content: A string or data content block, or a batch of independent inputs.
            **kwargs: Additional parameters passed to the embedding model.

        Returns:
            One vector for a single input, or a list of vectors in batch input order.
        """
        merged_kwargs = self.model_kwargs.copy()
        merged_kwargs.update(kwargs)
        return self._get_connection().embed(content, **merged_kwargs)

    def embed_with_usage(
        self, content: EmbeddingContent, **kwargs: Any
    ) -> EmbeddingResult[list[float] | list[list[float]]]:
        """Generate embeddings and return provider token usage when available."""
        merged_kwargs = self.model_kwargs.copy()
        merged_kwargs.update(kwargs)
        return self._get_connection().embed_with_usage(content, **merged_kwargs)
