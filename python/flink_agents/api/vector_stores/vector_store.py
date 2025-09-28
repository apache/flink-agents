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
from typing import Any, Dict, List

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
    id: str | None = Field(default=None, description="Unique identifier of the document.")

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


class BaseVectorStore(Resource, ABC):
    """Base abstract class for vector store.

    Provides vector store functionality that integrates embedding models
    for text-based semantic search. Handles both connection management and
    embedding generation internally.
    """

    embedding_model: str = Field(description="Name of the embedding model resource to use.")

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""
        return ResourceType.VECTOR_STORE

    @property
    @abstractmethod
    def store_kwargs(self) -> Dict[str, Any]:
        """Return vector store setup settings passed to connection.

        These parameters are merged with query-specific parameters
        when performing vector search operations.
        """

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

        # Merge setup kwargs with query-specific args
        merged_kwargs = self.store_kwargs.copy()
        merged_kwargs.update(query.extra_args)

        # Perform vector search using the abstract method
        documents = self.query_embedding(query_embedding, query.limit, **merged_kwargs)

        # Return structured result
        return VectorStoreQueryResult(
            documents=documents
        )

    @abstractmethod
    def query_embedding(self, embedding: List[float], limit: int = 10, **kwargs: Any) -> List[Document]:
        """Perform vector search using pre-computed embedding.

        Args:
            embedding: Pre-computed embedding vector for semantic search
            limit: Maximum number of results to return (default: 10)
            **kwargs: Vector store-specific parameters (filters, distance metrics, etc.)

        Returns:
            List of documents matching the search criteria
        """
