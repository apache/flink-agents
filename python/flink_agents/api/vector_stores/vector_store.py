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
################################################################################
from abc import ABC, abstractmethod
from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field
from typing_extensions import override

from flink_agents.api.resource import Resource, ResourceType


class VectorStoreQueryMode(str, Enum):
    """Vector store query mode.

    Attributes:
    ----------
    SEMANTIC : str
        Perform semantic search using embedding vectors to find
        the most semantically similar documents in the vector store.
    """

    SEMANTIC = "semantic"  # embedding-based retrieval
    # KEYWORD = "keyword"  # TODO: term-based retrieval
    # HYBRID = "hybrid"  # TODO: semantic + keyword retrieval


class VectorStoreQuery(BaseModel):
    """Structured query object for vector store operations.

    Provides a unified interface for text-based semantic search queries
    while supporting vector store-specific parameters and configurations.

    Attributes:
    ----------
    mode : VectorStoreQueryMode
        The type of query operation to perform (default: SEMANTIC).
    query_text : str
        Text query to be converted to embedding for semantic search.
    limit : int
        Maximum number of results to return (default: 10).
    extra_args : Dict[str, Any]
        Vector store-specific parameters such as filters, distance metrics,
        namespaces, or other search configurations.
    """

    mode: VectorStoreQueryMode = Field(default=VectorStoreQueryMode.SEMANTIC, description="The type of query "
                                                                                          "operation to perform.")
    query_text: str = Field(description="Text query to be converted to embedding for semantic search.")
    limit: int = Field(default=10, description="Maximum number of results to return.")
    extra_args: Dict[str, Any] = Field(default_factory=dict, description="Vector store-specific parameters.")

    def __str__(self) -> str:
        return f"{self.mode.value} query: '{self.query_text}' (limit={self.limit})"


class Document(BaseModel):
    """A document retrieved from vector store search.

    Represents a single piece of content with associated metadata.

    Attributes:
    ----------
    content : str
        The actual text content of the document.
    metadata : Dict[str, Any]
        Document metadata such as source, author, timestamp, etc.
    id : Optional[str]
        Unique identifier of the document (if available).
    """

    content: str = Field(description="The actual text content of the document.")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="Document metadata such as source, author, timestamp, etc.")
    id: Optional[str] = Field(default=None, description="Unique identifier of the document.")

    def __str__(self) -> str:
        content_preview = self.content[:50] + "..." if len(self.content) > 50 else self.content
        return f"Document: {content_preview}"


class VectorStoreQueryResult(BaseModel):
    """Result from a vector store query operation.

    Contains the retrieved documents from the search.

    Attributes:
    ----------
    documents : List[Document]
        List of documents retrieved from the vector store.
    """

    documents: List[Document] = Field(description="List of documents retrieved from the vector store.")

    def __str__(self) -> str:
        return f"QueryResult: {len(self.documents)} documents"


class BaseVectorStoreConnection(Resource, ABC):
    """Base abstract class for vector store connection.

    Manages connection configuration and provides raw vector search operations
    using pre-computed embeddings. One connection can be shared across multiple
    vector store setups.
    """

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""
        return ResourceType.VECTOR_STORE_CONNECTION

    @abstractmethod
    def query(self, embedding: List[float], limit: int = 10, **kwargs: Any) -> List[Document]:
        """Perform vector search using pre-computed embedding.

        Args:
            embedding: Pre-computed embedding vector for semantic search
            limit: Maximum number of results to return (default: 10)
            **kwargs: Vector store-specific parameters (filters, distance metrics, etc.)

        Returns:
            List of documents matching the search criteria
        """


class BaseVectorStoreSetup(Resource, ABC):
    """Base abstract class for vector store setup.

    Coordinates between vector store connections and embedding models to provide
    text-based semantic search. Automatically converts text queries to embeddings
    before delegating to the connection layer.
    """

    connection: str = Field(description="Name of the referenced connection.")
    embedding_model: str = Field(description="Name of the embedding model resource to use.")

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""
        return ResourceType.VECTOR_STORE

    def query(self, query: VectorStoreQuery) -> VectorStoreQueryResult:
        """Perform vector search using structured query object.

        Converts text query to embeddings and returns structured query result.

        Args:
            query: VectorStoreQuery object containing query parameters

        Returns:
            VectorStoreQueryResult containing the retrieved documents
        """
        # Generate embedding from the query text
        embedding_model = self.get_resource(
            self.embedding_model, ResourceType.EMBEDDING_MODEL
        )
        query_embedding = embedding_model.embed(query.query_text)

        # Get vector store connection resource
        connection = self.get_resource(
            self.connection, ResourceType.VECTOR_STORE_CONNECTION
        )

        # Perform vector search
        documents = connection.query(query_embedding, query.limit, **query.extra_args)

        # Return structured result
        return VectorStoreQueryResult(
            documents=documents
        )
