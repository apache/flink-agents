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
from typing import Any, Dict

from pydantic import Field
from typing_extensions import override

from flink_agents.api.resource import Resource, ResourceType


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
    def embed(self, text: str, **kwargs: Any) -> list[float]:
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


class BaseEmbeddingModelSetup(Resource, ABC):
    """Base abstract class for text embedding model setup.

    Responsible for managing embedding model configurations, such as:
    - Connection to embedding model service (connection)
    - Model name (model)

    Provides the basic embedding interface for generating embeddings from text inputs.
    """

    connection: str = Field(description="Name of the referenced connection.")
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

    def embed(self, text: str, **kwargs: Any) -> list[float]:
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
        connection = self.get_resource(
            self.connection, ResourceType.EMBEDDING_MODEL_CONNECTION
        )
        merged_kwargs = self.model_kwargs.copy()
        merged_kwargs.update(kwargs)
        return connection.embed(text, **merged_kwargs)
