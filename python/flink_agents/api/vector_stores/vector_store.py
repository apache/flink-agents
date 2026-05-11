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
from typing import Any, Dict, List, TypeVar, cast

from pydantic import BaseModel, Field
from typing_extensions import override

from flink_agents.api.embedding_models.embedding_model import BaseEmbeddingModelSetup
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
    filters: Dict[str, Any] | None = Field(
        default=None,
        description=(
            "Metadata filter in the flink-agents unified filter DSL — "
            "equality shorthand only. See :class:`BaseVectorStore` for "
            "the DSL grammar."
        ),
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
    score: float | None = Field(
        default=None,
        description=(
            "Similarity / distance score for this document against a query. "
            "Populated by vector search results; ``None`` for documents returned "
            "from non-query operations (e.g. ``get``) or from stores that do "
            "not surface scores. Semantics (distance vs. similarity, metric) "
            "are implementation-specific — each vector store documents its own."
        ),
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

    Filter DSL
    ----------
    Every vector store operation that takes ``filters`` accepts the same
    unified dialect. The dialect intentionally covers only the subset
    that every supported backend can honour — equality matching — so
    callers writing to ``BaseVectorStore`` never have to know which
    native operators a given store supports.

    Grammar (values must be JSON-compatible scalars)::

        # Equality — "field equals value":
        {"field": value}

        # Multiple top-level keys are implicitly AND-ed:
        {"user_id": "u1", "run_id": "r1"}

    ``None`` means "no filter". Richer operators (ranges, set membership,
    OR, NOT, etc.) are deliberately out of scope here because most
    vector-store backends do not support them uniformly. Callers who need
    backend-specific filters should pass them through ``**kwargs`` /
    ``VectorStoreQuery.extra_args`` (e.g. ChromaDB's ``where`` dict);
    implementations that receive an unsupported operator via ``filters``
    should raise ``NotImplementedError``.
    """

    embedding_model: str | BaseEmbeddingModelSetup | None = Field(
        default=None,
        description=(
            "The embedding model to use for auto-embedding inside "
            ":meth:`add` / :meth:`update` (when a document has no "
            "``embedding``) and :meth:`query` (when the query has no "
            "``query_embedding``). Optional — leave unset when callers "
            "always provide pre-computed vectors (e.g. the Mem0 "
            "integration)."
        ),
    )
    collection: str | None = Field(
        default=None,
        description=(
            "Default collection this store operates on when a caller does "
            "not specify one. Implementations may override the default "
            "value; ``None`` means the backend's own fallback applies."
        ),
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

    @override
    def open(self) -> None:
        if self.embedding_model is not None:
            self.embedding_model = cast(
                "BaseEmbeddingModelSetup",
                self.resource_context.get_resource(
                    self.embedding_model, ResourceType.EMBEDDING_MODEL
                ),
            )

    def _get_embedding_model(self) -> BaseEmbeddingModelSetup:
        if not isinstance(self.embedding_model, BaseEmbeddingModelSetup):
            if self.embedding_model is None:
                err_msg = (
                    "No embedding model configured on this vector store. "
                    "Pass ``embedding_model=`` at construction or supply "
                    "pre-computed vectors in the Documents / "
                    "VectorStoreQuery before calling this method."
                )
            else:
                err_msg = (
                    "Expected BaseEmbeddingModelSetup, got "
                    f"{self.embedding_model.__class__.__name__}."
                )
            raise TypeError(err_msg)
        return self.embedding_model

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
        documents = _maybe_cast_to_list(documents)
        self._ensure_embeddings(documents)

        merged_kwargs = self.store_kwargs.copy()
        merged_kwargs.update(kwargs)

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
        query_embedding = self._get_embedding_model().embed(query.query_text)

        # Merge setup kwargs with query-specific args
        merged_kwargs = self.store_kwargs.copy()
        merged_kwargs.update(query.extra_args)

        # Perform vector search using the abstract method
        documents = self._query_embedding(
            embedding=query_embedding,
            limit=query.limit,
            collection_name=query.collection_name,
            filters=query.filters,
            **merged_kwargs,
        )

        # Return structured result
        return VectorStoreQueryResult(documents=documents)

    def update(
        self,
        documents: Document | List[Document],
        collection_name: str | None = None,
        **kwargs: Any,
    ) -> None:
        """Update existing documents in the vector store.

        Mirrors :meth:`add`'s shape — identity lives on the document itself
        (``Document.id``). Each document must already have its ``id`` set;
        unlike :meth:`add`, update does not generate ids.

        Args:
            documents: Document(s) carrying ``id`` + the new content /
                metadata / embedding.
            collection_name: Target collection. Optional.
            **kwargs: Vector store specific parameters.
        """
        documents = _maybe_cast_to_list(documents)
        if not documents:
            err_msg = "`documents` must be non-empty."
            raise ValueError(err_msg)
        for doc in documents:
            if doc.id is None:
                err_msg = "Every document passed to `update` must have `id` set."
                raise ValueError(err_msg)
        self._ensure_embeddings(documents)

        merged_kwargs = self.store_kwargs.copy()
        merged_kwargs.update(kwargs)

        self._update_embedding(
            documents=documents,
            collection_name=collection_name,
            **merged_kwargs,
        )

    def _ensure_embeddings(self, documents: List[Document]) -> None:
        """Auto-embed any documents whose ``embedding`` field is ``None``."""
        for doc in documents:
            if doc.embedding is None:
                doc.embedding = self._get_embedding_model().embed(doc.content)

    @abstractmethod
    def get(
        self,
        ids: str | List[str] | None = None,
        collection_name: str | None = None,
        filters: Dict[str, Any] | None = None,
        limit: int | None = 100,
        **kwargs: Any,
    ) -> List[Document]:
        """Retrieve documents from the vector store.

        When ``ids`` is provided, the ``ids`` list itself bounds the result
        size and ``limit`` is effectively ignored. Without ``ids``, up to
        ``limit`` documents matching ``filters`` (or all, when no filter is
        set) are returned — callers who genuinely need the full collection
        should pass ``limit=None`` explicitly; the bounded default exists to
        avoid silently loading a whole collection into memory.

        Args:
            ids: Unique identifier(s) of documents to retrieve.
            collection_name: The collection to retrieve from. Optional.
            filters: Metadata filter in the unified DSL (see
                :class:`BaseVectorStore`). ``None`` = no filter.
            limit: Maximum number of documents to return. Defaults to 100;
                pass ``None`` for unbounded.
            **kwargs: Vector store specific parameters (offset, etc.)

        Returns:
            List of matching documents (possibly empty).
        """

    @abstractmethod
    def delete(
        self,
        ids: str | List[str] | None = None,
        collection_name: str | None = None,
        filters: Dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> None:
        """Delete documents in the vector store by its IDs.

        Args:
            ids: Unique identifier of the documents to delete
            collection_name: The collection name of the documents belong to. Optional.
            filters: Metadata filter in the unified DSL (see
                :class:`BaseVectorStore`). ``None`` = no filter.
            **kwargs: Vector store specific parameters.
        """

    @abstractmethod
    def _query_embedding(
        self,
        embedding: List[float],
        limit: int = 10,
        collection_name: str | None = None,
        filters: Dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> List[Document]:
        """Perform vector search using pre-computed embedding.

        Args:
            embedding: Pre-computed embedding vector for semantic search
            limit: Maximum number of results to return (default: 10)
            collection_name: The collection to apply the query. Optional.
            filters: Metadata filter in the unified DSL (see
                :class:`BaseVectorStore`). ``None`` = no filter.
            **kwargs: Vector store-specific parameters (distance metrics, etc.)

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

    @abstractmethod
    def _update_embedding(
        self,
        *,
        documents: List[Document],
        collection_name: str | None = None,
        **kwargs: Any,
    ) -> None:
        """Update documents with pre-computed embeddings. Identity is read
        from ``Document.id``.

        Args:
            documents: Documents carrying id + new content/metadata/embedding.
            collection_name: Target collection. Optional.
            **kwargs: Vector store-specific parameters.
        """


class CollectionManageableVectorStore(BaseVectorStore, ABC):
    """Base abstract class for vector store which support collection management."""

    @abstractmethod
    def create_collection_if_not_exists(self, name: str, **kwargs: Any) -> None:
        """Create the collection if it doesn't already exist; no-op otherwise.

        Args:
            name: Name of the collection
            **kwargs: Backend-specific options applied only when the collection
                is created (e.g. Chroma's ``metadata`` dict, Elasticsearch's
                ``settings`` / ``mappings``, Pinecone's ``dimension`` /
                ``metric``). Implementations should document which keys they
                recognize; unknown keys are ignored.
        """

    @abstractmethod
    def delete_collection(self, name: str) -> None:
        """Delete a collection.

        Args:
            name: Name of the collection
        """


_T = TypeVar("_T")


def _maybe_cast_to_list(value: _T | List[_T] | None) -> List[_T] | None:
    """Wrap a scalar into a single-element list; pass list / None through."""
    if value is None:
        return None
    return [value] if not isinstance(value, list) else value
