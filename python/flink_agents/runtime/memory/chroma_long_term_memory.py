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
import uuid
from datetime import datetime, timezone
from typing import TYPE_CHECKING, Any, Dict, List, Type, cast

import chromadb
from chromadb import ClientAPI as ChromaClient
from chromadb import CloudClient, GetResult, Metadata, Settings
from chromadb.api.types import Document
from chromadb.errors import NotFoundError
from pydantic import BaseModel, ConfigDict, Field
from typing_extensions import override

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.embedding_models.embedding_model import BaseEmbeddingModelSetup
from flink_agents.api.memory.long_term_memory import (
    BaseLongTermMemory,
    DatetimeRange,
    MemorySet,
    MemorySetItem,
    ReduceSetup,
    ReduceStrategy,
)
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.runtime.memory.reduce_functions import summarize

if TYPE_CHECKING:
    from chromadb import GetResult


class ChromaLongTermMemory(BaseLongTermMemory):
    """Long-Term Memory based on ChromaDB."""

    model_config = ConfigDict(arbitrary_types_allowed=True)
    embedding_model: str | BaseEmbeddingModelSetup | None = Field(
        default=None,
        description="Custom embedding model used to generate item embedding. "
        "If not provided, will use the default embedding function of chroma.",
    )
    runner_context: RunnerContext = Field(
        default="The runner context for this long term memory.",
    )

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

    __client: ChromaClient | None = None

    def __init__(
        self,
        *,
        runner_context: RunnerContext,
        embedding_model: str | None = None,
        persist_directory: str | None = None,
        host: str | None = None,
        port: int | None = 8000,
        api_key: str | None = None,
        client_settings: Settings | None = None,
        tenant: str = "default_tenant",
        database: str,
        **kwargs: Any,
    ) -> None:
        """Init method."""
        super().__init__(
            runner_context=runner_context,
            embedding_model=embedding_model,
            persist_directory=persist_directory,
            host=host,
            port=port,
            api_key=api_key,
            client_settings=client_settings,
            tenant=tenant,
            database=database,
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
                # create database if needed
                admin_client = chromadb.AdminClient(
                    Settings(
                        chroma_api_impl="chromadb.api.fastapi.FastAPI",
                        chroma_server_host=self.host,
                        chroma_server_http_port=self.port,
                    )
                )
                try:
                    admin_client.get_database(name=self.database, tenant=self.tenant)
                except Exception:
                    admin_client.create_database(name=self.database, tenant=self.tenant)
                self.__client = chromadb.HttpClient(
                    host=self.host,
                    port=self.port,
                    settings=self.client_settings,
                    tenant=self.tenant,
                    database=self.database,
                )
            else:
                err_msg = (
                    "As long-term memory backend, chromadb must run in cloud mode or client-server mode, "
                    "user should provide cloud api-key or server address."
                )
                raise RuntimeError(err_msg)

        return self.__client

    @override
    def create_memory_set(
        self,
        name: str,
        item_type: type[str] | Type[ChatMessage],
        capacity: int,
        reduce_setup: ReduceSetup,
    ) -> MemorySet:
        try:
            return self.get_memory_set(name=name)
        except NotFoundError:
            memory_set = MemorySet(
                name=name,
                item_type=item_type,
                size=0,
                capacity=capacity,
                reduce_setup=reduce_setup,
                ltm=self,
            )
            self.client.get_or_create_collection(
                name=name, metadata={"serialization": memory_set.model_dump_json()}
            )
            return memory_set

    @override
    def get_memory_set(self, name: str) -> MemorySet:
        metadata = self.client.get_collection(name=name).metadata
        memory_set = MemorySet.model_validate_json(metadata["serialization"])
        memory_set.ltm = self
        return memory_set

    @override
    def delete_memory_set(self, name: str) -> None:
        self.client.delete_collection(name=name)

    @override
    def add(self, memory_set: MemorySet, memory_item: str | ChatMessage) -> None:
        if memory_set.size >= memory_set.capacity:
            # trigger reduce operation to manage memory set size.
            self._reduce(memory_set)

        if issubclass(memory_set.item_type, BaseModel):
            memory_item = memory_item.model_dump_json()

        embedding = self._generate_embedding(text=memory_item)

        item_id = str(uuid.uuid4())
        timestamp = datetime.now(timezone.utc).isoformat()
        self.client.get_collection(name=memory_set.name).add(
            ids=item_id,
            embeddings=embedding,
            documents=memory_item,
            metadatas={
                "compacted": False,
                "created_time_start": timestamp,
                "created_time_end": timestamp,
                "last_accessed_time": timestamp,
            },
        )
        memory_set.size = memory_set.size + 1
        memory_set.item_ids.append(item_id)
        self._update_set_metadata(memory_set)

    @override
    def get(self, memory_set: MemorySet) -> List[MemorySetItem]:
        return self.slice(memory_set=memory_set)

    @override
    def get_recent(self, memory_set: MemorySet, n: int) -> List[MemorySetItem]:
        offset = memory_set.size - n if memory_set.size > n else 0
        return self.slice(memory_set=memory_set, offset=offset, n=n)

    @override
    def search(
        self, memory_set: MemorySet, query: str, limit: int, **kwargs: Any
    ) -> List[MemorySetItem]:
        embedding = self._generate_embedding(text=query)
        result = self.client.get_collection(name=memory_set.name).query(
            query_embeddings=[embedding] if embedding else None,
            query_texts=[query],
            n_results=limit,
            where=kwargs.get("where"),
            include=["documents", "metadatas"],
        )

        ids = result["ids"][0]
        documents = result["documents"][0]
        metadatas = result["metadatas"][0]

        self._update_items_metadata(memory_set=memory_set, ids=ids, metadatas=metadatas)

        return self._convert_to_items(
            memory_set=memory_set, ids=ids, documents=documents, metadatas=metadatas
        )

    def slice(
        self,
        *,
        memory_set: MemorySet,
        offset: int | None = None,
        n: int | None = None,
        update_metadata: bool = True,
    ) -> List[MemorySetItem]:
        """Retrieve memory items up to limit starting at offset.

        Args:
            memory_set: The memory set to be retrieved from.
            offset: The offset to start retrieving from.
            n: The number of items to retrieve.
            update_metadata: If True, update the items metadata in chroma store.

        Returns:
            Retrieved memory items.
        """
        result: GetResult = self.client.get_collection(name=memory_set.name).get(
            offset=offset, limit=n
        )

        ids = result["ids"]
        metadatas = result["metadatas"]
        documents = result["documents"]

        if update_metadata:
            self._update_items_metadata(
                memory_set=memory_set, ids=ids, metadatas=metadatas
            )

        return self._convert_to_items(
            memory_set=memory_set, ids=ids, documents=documents, metadatas=metadatas
        )

    def delete(self, memory_set: MemorySet, offset: int, n: int) -> None:
        """Delete memory items.

        Args:
            memory_set: The memory set to be deleted from.
            offset: The offset to start delete from.
            n: The number of items to delete.
        """
        self.client.get_collection(name=memory_set.name).delete(
            memory_set.item_ids[offset : offset + n]
        )
        del memory_set.item_ids[offset : offset + n]
        memory_set.size = memory_set.size - n

        self.client.get_collection(name=memory_set.name).modify(
            metadata={"serialization": memory_set.model_dump_json()}
        )

    def update(
        self, memory_set: MemorySet, item_id: str, text: str, metadata: Dict[str, Any]
    ) -> None:
        """Update memory item.

        Args:
            memory_set: The memory set item belongs to.
            item_id: The memory item to be updated.
            text: The updated text.
            metadata: The updated metadata.
        """
        embedding = self._generate_embedding(text=text)
        self.client.get_collection(name=memory_set.name).update(
            ids=item_id,
            embeddings=embedding,
            documents=text,
            metadatas=metadata,
        )

    @staticmethod
    def _convert_to_items(
        memory_set: MemorySet,
        ids: List[str],
        documents: List[Document],
        metadatas: List[Metadata],
    ) -> List[MemorySetItem]:
        """Convert retrival result to memory items."""
        return [
            MemorySetItem(
                memory_set_name=memory_set.name,
                id=item_id,
                value=document
                if memory_set.item_type is str
                else memory_set.item_type.model_validate_json(document),
                compacted=metadata["compacted"],
                created_time=DatetimeRange(
                    start=datetime.fromisoformat(
                        cast("str", metadata["created_time_start"])
                    ),
                    end=datetime.fromisoformat(cast("str", metadata["created_time_end"])),
                ),
                last_accessed_time=datetime.fromisoformat(metadata["last_accessed_time"]),
            )
            for item_id, document, metadata in zip(
                ids, documents, metadatas, strict=False
            )
        ]

    def _generate_embedding(self, text: str) -> list[float] | None:
        """Generate embedding for text.

        If no embedding model configured, return None to use the default embedding
        generated by chroma.
        """
        if self.embedding_model is not None:
            if isinstance(self.embedding_model, str):
                self.embedding_model = cast(
                    "BaseEmbeddingModelSetup",
                    self.runner_context.get_resource(
                        self.embedding_model, ResourceType.EMBEDDING_MODEL
                    ),
                )
            return self.embedding_model.embed(text=text)
        else:
            return None

    def _update_set_metadata(self, memory_set: MemorySet) -> None:
        """Update metadata for memory set."""
        self.client.get_collection(name=memory_set.name).modify(
            metadata={"serialization": memory_set.model_dump_json()}
        )

    def _update_items_metadata(
        self, memory_set: MemorySet, ids: List[str], metadatas: List[Metadata]
    ) -> None:
        """Update metadata for retrieved memory items."""
        current_timestamp = datetime.now(timezone.utc).isoformat()
        update_metadatas = []
        for metadata in metadatas:
            metadata = dict(metadata)
            metadata["last_accessed_time"] = current_timestamp
            update_metadatas.append(metadata)
        self.client.get_collection(name=memory_set.name).update(
            ids=ids, metadatas=update_metadatas
        )

    def _reduce(self, memory_set: MemorySet) -> None:
        """Reduce memory set size."""
        reduce_setup: ReduceSetup = memory_set.reduce_setup
        if reduce_setup.strategy == ReduceStrategy.TRIM:
            self._trim(memory_set)
        elif reduce_setup.strategy == ReduceStrategy.SUMMARIZE:
            self._summarize(memory_set)
        else:
            msg = f"Unknown reduce strategy: {reduce_setup.strategy}"
            raise RuntimeError(msg)
        memory_set.reduced = True
        self._update_set_metadata(memory_set)

    def _trim(self, memory_set: MemorySet) -> None:
        reduce_setup: ReduceSetup = memory_set.reduce_setup
        n = reduce_setup.arguments.get("n")
        self.delete(memory_set=memory_set, offset=0, n=n)

    def _summarize(self, memory_set: MemorySet) -> None:
        # get arguments
        reduce_setup: ReduceSetup = memory_set.reduce_setup
        n = reduce_setup.arguments.get("n")

        # retrieve items involved
        items: List[MemorySetItem] = self.slice(memory_set=memory_set, offset=0, n=n)

        response: ChatMessage = summarize(
            items, memory_set.item_type, memory_set.reduce_setup, self.runner_context
        )

        # update memory set
        if memory_set.item_type == ChatMessage:
            text = ChatMessage(
                role=MessageRole.USER, content=response.content
            ).model_dump_json()
        else:
            text = response.content

        start = min([item.created_time.start for item in items]).isoformat()
        end = max([item.created_time.end for item in items]).isoformat()

        # to keep the addition order for items in collection, update the exist item
        # rather than add a new item.
        self.update(
            memory_set=memory_set,
            item_id=memory_set.item_ids[0],
            text=text,
            metadata={
                "compacted": True,
                "created_time_start": start,
                "created_time_end": end,
                "last_accessed_time": max(
                    [item.last_accessed_time for item in items]
                ).isoformat(),
            },
        )

        # delete other items involved in reduction.
        self.delete(memory_set=memory_set, offset=1, n=n - 1)
