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
#  limitations under the License.
################################################################################
import uuid
from typing import Any, Dict, Generator, List

import chromadb
from chromadb import ClientAPI as ChromaClient
from chromadb import CloudClient
from chromadb.config import Settings
from pydantic import Field
from typing_extensions import override

from flink_agents.api.vector_stores.vector_store import (
    CollectionManageableVectorStore,
    Document,
)

DEFAULT_COLLECTION = "flink_agents_chroma_collection"

# ChromaDB's collection.add() has a hard per-call record-count ceiling;
# anything larger must be split.
_MAX_CHUNK_SIZE = 41665


class ChromaVectorStore(CollectionManageableVectorStore):
    """ChromaDB vector store that handles connection and semantic search.

    Visit https://docs.trychroma.com/ for ChromaDB documentation.

    Supports multiple client modes:
    - In-memory: No configuration needed (default)
    - Persistent: Provide persist_directory
    - Server: Provide host and port
    - Cloud: Provide api_key for Chroma Cloud

    Attributes:
    ----------
    persist_directory : Optional[str]
        Directory for persistent storage. If None, uses in-memory client.
    host : Optional[str]
        Host for ChromaDB server connection.
    port : Optional[int]
        Port for ChromaDB server connection.
    api_key : Optional[str]
        API key for Chroma Cloud connection.
    client_settings : Optional[Settings]
        ChromaDB client settings for advanced configuration.
    tenant : str
        ChromaDB tenant for multi-tenancy support (default: "default_tenant").
    database : str
        ChromaDB database name (default: "default_database").
    collection : str
        Name of the ChromaDB collection to use (default: flink_agents_collection).
    collection_metadata : Dict[str, Any]
        Metadata for the collection (optional).
    auto_create_collection : bool
        Whether read / write paths auto-create the collection when it's
        missing (default: True).
    """

    # Connection configuration
    persist_directory: str | None = Field(
        default=None,
        description="Directory for persistent storage. If None, uses in-memory client.",
    )
    host: str | None = Field(
        default=None,
        description="Host for ChromaDB server connection.",
    )
    port: int | None = Field(
        default=8000,
        description="Port for ChromaDB server connection.",
    )
    api_key: str | None = Field(
        default=None,
        description="API key for Chroma Cloud connection.",
    )
    client_settings: Settings | None = Field(
        default=None,
        description="ChromaDB client settings for advanced configuration.",
    )
    tenant: str = Field(
        default="default_tenant",
        description="ChromaDB tenant for multi-tenancy support.",
    )
    database: str = Field(
        default="default_database",
        description="ChromaDB database name.",
    )

    # Collection configuration
    collection: str = Field(
        default=DEFAULT_COLLECTION,
        description="Name of the ChromaDB collection to use.",
    )
    collection_metadata: Dict[str, Any] = Field(
        default_factory=dict,
        description="Metadata for the collection.",
    )
    auto_create_collection: bool = Field(
        default=True,
        description=(
            "Whether read / write paths auto-create the collection when it's missing."
        ),
    )

    __client: ChromaClient | None = None

    def __init__(
        self,
        *,
        embedding_model: str,
        persist_directory: str | None = None,
        host: str | None = None,
        port: int | None = 8000,
        api_key: str | None = None,
        client_settings: Settings | None = None,
        tenant: str = "default_tenant",
        database: str = "default_database",
        collection: str = DEFAULT_COLLECTION,
        collection_metadata: Dict[str, Any] | None = None,
        auto_create_collection: bool = True,
        **kwargs: Any,
    ) -> None:
        """Initialize the ChromaDB vector store."""
        super().__init__(
            embedding_model=embedding_model,
            persist_directory=persist_directory,
            host=host,
            port=port,
            api_key=api_key,
            client_settings=client_settings,
            tenant=tenant,
            database=database,
            collection=collection,
            collection_metadata=collection_metadata or {},
            auto_create_collection=auto_create_collection,
            **kwargs,
        )

    # -------------------------------------------------------------------------
    # Client / setup kwargs
    # -------------------------------------------------------------------------
    @property
    def client(self) -> ChromaClient:
        """Return the ChromaDB client, creating it lazily.

        The client mode is picked in priority order: cloud → server →
        persistent → in-memory.
        """
        if self.__client is not None:
            return self.__client

        if self.api_key is not None:
            self.__client = CloudClient(
                tenant=self.tenant,
                database=self.database,
                api_key=self.api_key,
            )
        elif self.host is not None:
            self.__client = chromadb.HttpClient(
                host=self.host,
                port=self.port,
                settings=self.client_settings,
                tenant=self.tenant,
                database=self.database,
            )
        elif self.persist_directory is not None:
            self.__client = chromadb.PersistentClient(
                path=self.persist_directory,
                settings=self.client_settings,
                tenant=self.tenant,
                database=self.database,
            )
        else:
            self.__client = chromadb.EphemeralClient(
                settings=self.client_settings,
                tenant=self.tenant,
                database=self.database,
            )
        return self.__client

    @property
    @override
    def store_kwargs(self) -> Dict[str, Any]:
        return {
            "collection": self.collection,
            "collection_metadata": self.collection_metadata,
            "auto_create_collection": self.auto_create_collection,
        }

    # -------------------------------------------------------------------------
    # Collection management
    # -------------------------------------------------------------------------
    @override
    def create_collection_if_not_exists(self, name: str, **kwargs: Any) -> None:
        """Chroma accepts ``metadata`` (``Dict[str, Any] | None``)."""
        metadata = kwargs.get("metadata")
        self.client.get_or_create_collection(name=name, metadata=metadata)

    @override
    def delete_collection(self, name: str) -> None:
        self.client.delete_collection(name=name)

    # -------------------------------------------------------------------------
    # Public CRUD
    # -------------------------------------------------------------------------
    @override
    def get(
        self,
        ids: str | List[str] | None = None,
        collection_name: str | None = None,
        filters: Dict[str, Any] | None = None,
        limit: int | None = 100,
        **kwargs: Any,
    ) -> List[Document]:
        results = self.client.get_collection(collection_name or self.collection).get(
            ids=ids,
            where=self._resolve_where(filters, kwargs),
            limit=limit,
            offset=kwargs.get("offset"),
            where_document=kwargs.get("where_document"),
        )
        return [
            Document(id=rid, content=content, metadata=dict(metadata))
            for rid, content, metadata in zip(
                results["ids"],
                results["documents"],
                results["metadatas"],
                strict=False,
            )
        ]

    @override
    def delete(
        self,
        ids: str | List[str] | None = None,
        collection_name: str | None = None,
        filters: Dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> None:
        where = self._resolve_where(filters, kwargs)
        where_document = kwargs.get("where_document")
        collection = self.client.get_collection(collection_name or self.collection)

        if ids is None and where is None and where_document is None:
            # Explicit delete-all: ChromaDB forbids passing no selector.
            ids = collection.get(include=[]).get("ids")
            if not ids:
                return

        collection.delete(ids=ids, where=where, where_document=where_document)

    # -------------------------------------------------------------------------
    # Protected embedding hooks (pre-computed-embedding paths)
    # -------------------------------------------------------------------------
    @override
    def _add_embedding(
        self,
        *,
        documents: List[Document],
        collection_name: str | None = None,
        **kwargs: Any,
    ) -> List[str]:
        collection = self._resolve_collection(collection_name, kwargs)
        all_ids: List[str] = []
        for chunk in _chunk_documents(documents, _MAX_CHUNK_SIZE):
            ids = [doc.id or str(uuid.uuid4()) for doc in chunk]
            collection.add(
                ids=ids,
                documents=[doc.content for doc in chunk],
                embeddings=[doc.embedding for doc in chunk],
                metadatas=[doc.metadata for doc in chunk],
            )
            all_ids.extend(ids)
        return all_ids

    @override
    def _update_embedding(
        self,
        *,
        documents: List[Document],
        collection_name: str | None = None,
        **kwargs: Any,
    ) -> None:
        collection = self.client.get_collection(collection_name or self.collection)
        collection.update(
            ids=[doc.id for doc in documents],
            documents=[doc.content for doc in documents],
            embeddings=[doc.embedding for doc in documents],
            metadatas=[doc.metadata for doc in documents],
        )

    @override
    def _query_embedding(
        self,
        embedding: List[float],
        limit: int = 10,
        collection_name: str | None = None,
        filters: Dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> List[Document]:
        collection = self._resolve_collection(collection_name, kwargs)
        results = collection.query(
            query_embeddings=[embedding],
            n_results=limit,
            where=self._resolve_where(filters, kwargs),
            include=["documents", "metadatas", "distances"],
        )
        return _parse_query_results(results)

    # -------------------------------------------------------------------------
    # Internal helpers
    # -------------------------------------------------------------------------
    def _resolve_where(
        self, filters: Dict[str, Any] | None, kwargs: Dict[str, Any]
    ) -> Dict[str, Any] | None:
        """Pick the final ChromaDB ``where`` clause.

        Preference: the unified-DSL ``filters`` argument (translated), falling
        back to ``kwargs["where"]`` for callers already speaking ChromaDB's
        native dialect.
        """
        if filters is not None:
            return _translate_filters_to_chroma_where(filters)
        return kwargs.get("where")

    def _resolve_collection(
        self, collection_name: str | None, kwargs: Dict[str, Any]
    ) -> Any:
        """Return the ChromaDB collection object for an add / query path.

        Honours the ``auto_create_collection`` flag (either on this store or
        overridden via kwargs) and attaches ``collection_metadata`` only when
        creating.
        """
        name = collection_name or self.collection
        create = kwargs.get("auto_create_collection", self.auto_create_collection)
        if not create:
            return self.client.get_collection(name=name)
        # ChromaDB rejects empty metadata dicts — pass None instead.
        metadata = kwargs.get("collection_metadata", self.collection_metadata) or None
        return self.client.get_or_create_collection(name=name, metadata=metadata)


# =============================================================================
# Module-level helpers
# =============================================================================
def _chunk_documents(
    docs: List[Document], max_chunk_size: int
) -> Generator[List[Document], None, None]:
    """Yield successive ``max_chunk_size``-sized slices of ``docs``."""
    for i in range(0, len(docs), max_chunk_size):
        yield docs[i : i + max_chunk_size]


def _parse_query_results(results: Dict[str, Any]) -> List[Document]:
    r"""Flatten ChromaDB's per-query-lists result shape into ``Document``\\s.

    ChromaDB returns each field as ``List[List[...]]`` — one inner list per
    query embedding. We always query with a single embedding, so we take
    index ``[0]``. ``score`` carries ChromaDB's distance (smaller = closer).
    """
    if not (results.get("documents") and results["documents"][0]):
        return []

    contents = results["documents"][0]
    ids = results["ids"][0] if results.get("ids") else [None] * len(contents)
    metadatas = (
        results["metadatas"][0]
        if results.get("metadatas") and results["metadatas"][0]
        else [{}] * len(contents)
    )
    distances = (
        results["distances"][0]
        if results.get("distances") and results["distances"][0]
        else [None] * len(contents)
    )
    return [
        Document(id=rid, content=content, metadata=meta or {}, score=score)
        for rid, content, meta, score in zip(
            ids,
            contents,
            metadatas,
            distances,
            strict=False,
        )
    ]


def _translate_filters_to_chroma_where(
    filters: Dict[str, Any] | None,
) -> Dict[str, Any] | None:
    """Translate the flink-agents unified filter DSL to ChromaDB's ``where``.

    The unified DSL is equality-only (see :class:`BaseVectorStore`). The
    only rewrite needed is wrapping multi-key dicts in ``$and`` —
    ChromaDB rejects ``{"k1": v1, "k2": v2}`` as a single ``where``
    clause. Anything that looks like an operator form (``$``-prefixed
    key or a dict value) is out-of-spec and rejected up front; callers
    needing ChromaDB's native operators should pass a native ``where``
    dict through ``**kwargs`` instead.
    """
    if not filters:
        return None
    for key, value in filters.items():
        if key.startswith("$") or isinstance(value, dict):
            err_msg = (
                "The unified filter DSL only supports equality shorthand "
                '(e.g. {"field": value}). For ChromaDB-native operators '
                "such as $and/$or/$gt/$in, pass a raw `where` dict via "
                "**kwargs instead of `filters`."
            )
            raise NotImplementedError(err_msg)
    if len(filters) > 1:
        return {"$and": [{k: v} for k, v in filters.items()]}
    return filters
