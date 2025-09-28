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
from typing import Any, Dict, List

import chromadb
from chromadb import ClientAPI as ChromaClient
from chromadb import CloudClient
from chromadb.config import Settings
from pydantic import Field

from flink_agents.api.vector_stores.vector_store import (
    BaseVectorStore,
    Document,
)

DEFAULT_COLLECTION = "flink_agents_chroma_collection"


class ChromaVectorStore(BaseVectorStore):
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
    create_collection_if_not_exists : bool
        Whether to create the collection if it doesn't exist (default: True).
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
    create_collection_if_not_exists: bool = Field(
        default=True,
        description="Whether to create the collection if it doesn't exist.",
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
        create_collection_if_not_exists: bool = True,
        **kwargs: Any,
    ) -> None:
        """Init method."""
        if collection_metadata is None:
            collection_metadata = {}
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
            collection_metadata=collection_metadata,
            create_collection_if_not_exists=create_collection_if_not_exists,
            **kwargs,
        )

    @property
    def client(self) -> ChromaClient:
        """Return ChromaDB client, creating it if necessary."""
        if self.__client is None:
            # Choose client type based on configuration
            if self.api_key is not None:
                # Cloud mode
                self.__client = CloudClient(
                    tenant=self.tenant,
                    database=self.database,
                    api_key=self.api_key,
                )
            elif self.host is not None:
                # Client-Server Mode
                self.__client = chromadb.HttpClient(
                    host=self.host,
                    port=self.port,
                    settings=self.client_settings,
                    tenant=self.tenant,
                    database=self.database,
                )
            elif self.persist_directory is not None:
                # Persistent mode
                self.__client = chromadb.PersistentClient(
                    path=self.persist_directory,
                    settings=self.client_settings,
                    tenant=self.tenant,
                    database=self.database,
                )
            else:
                # In-memory mode
                self.__client = chromadb.EphemeralClient(
                    settings=self.client_settings,
                    tenant=self.tenant,
                    database=self.database,
                )

        return self.__client

    @property
    def store_kwargs(self) -> Dict[str, Any]:
        """Return ChromaDB-specific setup settings."""
        return {
            "collection": self.collection,
            "collection_metadata": self.collection_metadata,
            "create_collection_if_not_exists": self.create_collection_if_not_exists,
        }

    def query_embedding(self, embedding: List[float], limit: int = 10, **kwargs: Any) -> List[Document]:
        """Perform vector search using pre-computed embedding.

        Args:
            embedding: Pre-computed embedding vector for semantic search
            limit: Maximum number of results to return (default: 10)
            **kwargs: ChromaDB-specific parameters (collection, where, etc.)

        Returns:
            List of documents matching the search criteria
        """
        # Extract ChromaDB-specific parameters
        collection_name = kwargs.get("collection", self.collection)
        collection_metadata = kwargs.get("collection_metadata", self.collection_metadata)
        create_collection_if_not_exists = kwargs.get("create_collection_if_not_exists", self.create_collection_if_not_exists)
        where = kwargs.get("where")  # Metadata filters

        # Get or create collection based on configuration
        if create_collection_if_not_exists:
            # ChromaDB doesn't accept empty metadata, pass None instead
            metadata = collection_metadata if collection_metadata else None
            collection = self.client.get_or_create_collection(
                name=collection_name,
                metadata=metadata,
            )
        else:
            collection = self.client.get_collection(name=collection_name)

        # Perform query
        results = collection.query(
            query_embeddings=[embedding],
            n_results=limit,
            where=where,
            include=["documents", "metadatas"],
        )

        # Convert to Document objects
        documents = []
        if results["documents"] and results["documents"][0]:
            for i, doc_content in enumerate(results["documents"][0]):
                doc_id = results["ids"][0][i] if results["ids"] else None
                metadata = results["metadatas"][0][i] if results["metadatas"] and results["metadatas"][0] else {}

                documents.append(Document(
                    content=doc_content,
                    id=doc_id,
                    metadata=metadata,
                ))

        return documents

