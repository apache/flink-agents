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
    collection_name : str
        The collection to apply the query. Optional.
    extra_args : Dict[str, Any]
        Vector store-specific parameters such as filters, distance metrics,
        namespaces, or other search configurations.
    """

    mode: VectorStoreQueryMode = Field(
        default=VectorStoreQueryMode.SEMANTIC,
        description="The type of query operation to perform.",
    )
    query_text: str = Field(
        description="Text query to be converted to embedding for semantic search."
    )
    limit: int = Field(default=10, description="Maximum number of results to return.")
    collection_name: str | None = Field(
        default=None, description="The collection to apply the query."
    )
    extra_args: Dict[str, Any] = Field(
        default_factory=dict, description="Vector store-specific parameters."
    )

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
    metadata: Dict[str, Any] = Field(
        default_factory=dict,
        description="Document metadata such as source, author, timestamp, etc.",
    )
    id: str | None = Field(
        default=None, description="Unique identifier of the document."
    )
    embedding: List[float] | None = Field(
        default=None, description="Embedding vector for content."
    )

    def __str__(self) -> str:
        content_preview = (
            self.content[:50] + "..." if len(self.content) > 50 else self.content
        )
        return f"Document: {content_preview}"


class VectorStoreQueryResult(BaseModel):
    """Result from a vector store query operation.

    Contains the retrieved documents from the search.

    Attributes:
    ----------
    documents : List[Document]
        List of documents retrieved from the vector store.
    """

    documents: List[Document] = Field(
        description="List of documents retrieved from the vector store."
    )

    def __str__(self) -> str:
        return f"QueryResult: {len(self.documents)} documents"


class BaseVectorStore(Resource, ABC):
    """Base abstract class for vector store.

    Provides vector store functionality that integrates embedding models
    for text-based semantic search. Handles both connection management and
    embedding generation internally.
    """

    embedding_model: str = Field(
        description="Name of the embedding model resource to use."
    )

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

    def add(
        self,
        documents: Document | List[Document],
        collection_name: str | None = None,
        **kwargs: Any,
    ) -> List[str]:
        """Add documents to the vector store.

        Converts document content to embeddings and stores them in the vector store.
        The implementation may generate IDs for documents that don't have one.

        Args:
            documents: Single Document or list of Documents to add.
            collection_name: The collection name of the documents to add to. Optional.
            **kwargs: Vector store specific parameters.

        Returns:
            List of document IDs that were added to the vector store
        """
        # Normalize to list
        documents = _maybe_cast_to_list(documents)

        # Generate embeddings for all documents
        embedding_model = self.get_resource(
            self.embedding_model, ResourceType.EMBEDDING_MODEL
        )

        # Generate embeddings for each document
        for doc in documents:
            if doc.embedding is None:
                doc.embedding = embedding_model.embed(doc.content)

        # Merge setup kwargs with add-specific args
        merged_kwargs = self.store_kwargs.copy()
        merged_kwargs.update(kwargs)

        # Perform add operation using the abstract method
        return self._add_embedding(
            documents=documents, collection_name=collection_name, **merged_kwargs
        )

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
        documents = self._query_embedding(
            query_embedding, query.limit, query.collection_name, **merged_kwargs
        )

        # Return structured result
        return VectorStoreQueryResult(documents=documents)

    @abstractmethod
    def size(self, collection_name: str | None = None) -> int:
        """Get the size of the collection in vector store.

        Args:
            collection_name: The target collection. Optional.
        """

    @abstractmethod
    def get(
        self,
        ids: str | List[str] | None = None,
        collection_name: str | None = None,
        **kwargs: Any,
    ) -> List[Document]:
        """Retrieve a document from the vector store by its ID.

        Args:
            ids: Unique identifier of the documents to retrieve
            collection_name: The collection name of the documents to retrieve. Optional.
            **kwargs: Vector store specific parameters (offset, limit, filter etc.)

        Returns:
            Document object if found, None otherwise
        """

    @abstractmethod
    def delete(
        self,
        ids: str | List[str] | None = None,
        collection_name: str | None = None,
        **kwargs: Any,
    ) -> None:
        """Delete documents in the vector store by its IDs.

        Args:
            ids: Unique identifier of the documents to delete
            collection_name: The collection name of the documents belong to. Optional.
            **kwargs: Vector store specific parameters (filter etc.)
        """

    @abstractmethod
    def _query_embedding(
        self,
        embedding: List[float],
        limit: int = 10,
        collection_name: str | None = None,
        **kwargs: Any,
    ) -> List[Document]:
        """Perform vector search using pre-computed embedding.

        Args:
            embedding: Pre-computed embedding vector for semantic search
            limit: Maximum number of results to return (default: 10)
            collection_name: The collection to apply the query. Optional.
            **kwargs: Vector store-specific parameters (filters, distance metrics, etc.)

        Returns:
            List of documents matching the search criteria
        """

    @abstractmethod
    def _add_embedding(
        self,
        *,
        documents: List[Document],
        collection_name: str | None = None,
        **kwargs: Any,
    ) -> List[str]:
        """Add documents with pre-computed embeddings to the vector store.

        Args:
            documents: Documents with embeddings to add to the vector store
            collection_name: The collection name of the documents to add. Optional.
            **kwargs: Vector store-specific parameters (collection, namespace, etc.)

        Returns:
            List of document IDs that were added to the vector store
        """


class Collection(BaseModel):
    """Represents a collection of documents."""

    name: str
    metadata: Dict[str, Any] | None = None


class CollectionManageableVectorStore(BaseVectorStore, ABC):
    """Base abstract class for vector store which support collection management."""

    @abstractmethod
    def get_or_create_collection(
        self, name: str, metadata: Dict[str, Any] | None = None
    ) -> Collection:
        """Get a collection, or create it if it doesn't exist.

        Args:
            name: Name of the collection
            metadata: Metadata of the collection
        Returns:
            The retrieved or created collection
        """

    @abstractmethod
    def get_collection(self, name: str) -> Collection:
        """Get a collection, raise an exception if it doesn't exist.

        Args:
            name: Name of the collection
        Returns:
            The retrieved collection
        """

    @abstractmethod
    def delete_collection(self, name: str) -> Collection:
        """Delete a collection.

        Args:
            name: Name of the collection
        Returns:
            The deleted collection
        """


def _maybe_cast_to_list(value: Any | List[Any]) -> List[Any] | None:
    """Cast T to List[T] if T is not None."""
    if value is None:
        return None
    return [value] if not isinstance(value, list) else value
