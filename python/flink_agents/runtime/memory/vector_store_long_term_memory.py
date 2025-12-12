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
from typing import Any, Dict, List, Type, cast

from pydantic import BaseModel, ConfigDict, Field
from typing_extensions import override

from flink_agents.api.chat_message import ChatMessage
from flink_agents.api.memory.long_term_memory import (
    BaseLongTermMemory,
    CompactionStrategy,
    CompactionStrategyType,
    DatetimeRange,
    ItemType,
    MemorySet,
    MemorySetItem,
)
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.vector_stores.vector_store import (
    CollectionManageableVectorStore,
    Document,
    VectorStoreQuery,
    _maybe_cast_to_list,
)
from flink_agents.runtime.memory.compaction_functions import summarize


# TODO: support async execution for operations and compaction
class VectorStoreLongTermMemory(BaseLongTermMemory):
    """Long-Term Memory based on ChromaDB."""

    model_config = ConfigDict(arbitrary_types_allowed=True)

    ctx: RunnerContext = Field(
        description="The runner context to retrieve resource.", exclude=True
    )

    vector_store: str | CollectionManageableVectorStore = Field(
        description="The vector store backend to store data."
    )

    job_id: str = Field(description="Unique identifier for the job.")

    key: str = Field(description="Unique identifier for the keyed partition.")

    def __init__(
        self,
        *,
        ctx: RunnerContext,
        vector_store: str,
        job_id: str,
        key: str,
        **kwargs: Any,
    ) -> None:
        """Init method."""
        super().__init__(
            ctx=ctx,
            vector_store=vector_store,
            job_id=job_id,
            key=key,
            **kwargs,
        )

    @property
    def store(self) -> CollectionManageableVectorStore:
        """Get backend vector store.

        The vector store used for long-term memory must implement
        CollectionManageableVectorStore.
        """
        if isinstance(self.vector_store, str):
            self.vector_store = cast(
                "CollectionManageableVectorStore",
                self.ctx.get_resource(self.vector_store, ResourceType.VECTOR_STORE),
            )

        return self.vector_store

    @override
    def get_or_create_memory_set(
        self,
        name: str,
        item_type: type[str] | Type[ChatMessage],
        capacity: int,
        compaction_strategy: CompactionStrategy,
    ) -> MemorySet:
        memory_set = MemorySet(
            name=name,
            item_type=item_type,
            capacity=capacity,
            compaction_strategy=compaction_strategy,
            ltm=self,
        )
        self.store.get_or_create_collection(
            name=self._name_mangling(name),
            metadata={"memory_set": memory_set.model_dump_json()},
        )
        return memory_set

    @override
    def get_memory_set(self, name: str) -> MemorySet:
        collection = self.store.get_collection(name=self._name_mangling(name))
        memory_set = MemorySet.model_validate_json(collection.metadata["memory_set"])
        memory_set.ltm = self
        return memory_set

    @override
    def delete_memory_set(self, name: str) -> bool:
        return self.store.delete_collection(name=self._name_mangling(name)) is not None

    @override
    def size(self, memory_set: MemorySet) -> int:
        return self.store.size(collection_name=self._name_mangling(memory_set.name))

    @override
    def add(
        self,
        memory_set: MemorySet,
        memory_items: ItemType | List[ItemType],
        ids: str | List[str] | None = None,
        metadatas: Dict[str, Any] | List[Dict[str, Any]] | None = None,
    ) -> None:
        memory_items = _maybe_cast_to_list(memory_items)
        ids = _maybe_cast_to_list(ids)
        metadatas = _maybe_cast_to_list(metadatas)

        if ids is None:
            ids = [str(uuid.uuid4()) for _ in memory_items]

        timestamp = datetime.now(timezone.utc).isoformat()
        meta = {
            "compacted": False,
            "created_time": timestamp,
            "last_accessed_time": timestamp,
        }

        merge_metadatas = [meta for _ in memory_items]

        if metadatas is not None:
            for merge_meta, metadata in zip(merge_metadatas, metadatas, strict=False):
                merge_meta.update(metadata)

        if issubclass(memory_set.item_type, BaseModel):
            memory_items = [item.model_dump_json() for item in memory_items]

        documents = []
        for id, item, metadata in zip(ids, memory_items, merge_metadatas, strict=False):
            documents.append(
                Document(
                    id=id,
                    content=item,
                    metadata=metadata,
                )
            )

        self.store.add(
            documents=documents, collection_name=self._name_mangling(memory_set.name)
        )

        if memory_set.size >= memory_set.capacity:
            # trigger compaction
            self._compact(memory_set)

    @override
    def get(
        self, memory_set: MemorySet, ids: str | List[str] | None = None
    ) -> List[MemorySetItem]:
        documents = self.store.get(
            ids=ids, collection_name=self._name_mangling(memory_set.name)
        )
        return self._convert_to_items(memory_set=memory_set, documents=documents)

    @override
    def delete(self, memory_set: MemorySet, ids: str | List[str] | None = None) -> None:
        self.store.delete(ids=ids, collection_name=self._name_mangling(memory_set.name))

    @override
    def search(
        self, memory_set: MemorySet, query: str, limit: int, **kwargs: Any
    ) -> List[MemorySetItem]:
        query = VectorStoreQuery(
            query_text=query,
            limit=limit,
            collection_name=self._name_mangling(memory_set.name),
            extra_args=kwargs,
        )
        result = self.store.query(query=query)

        return self._convert_to_items(memory_set=memory_set, documents=result.documents)

    def _name_mangling(self, name: str) -> str:
        """Mangle memory set name to actually name in vector store."""
        return f"{self.job_id}-{self.key}-{name}"

    def _compact(self, memory_set: MemorySet) -> None:
        """Compact memory set to manage storge."""
        compaction_strategy: CompactionStrategy = memory_set.compaction_strategy
        if compaction_strategy.type == CompactionStrategyType.SUMMARIZATION:
            # currently, only support summarize all the items.
            summarize(ltm=self, memory_set=memory_set, ctx=self.ctx)
        else:
            msg = f"Unknown compaction strategy: {compaction_strategy.type}"
            raise RuntimeError(msg)

    @staticmethod
    def _convert_to_items(
        memory_set: MemorySet,
        documents: List[Document],
    ) -> List[MemorySetItem]:
        """Convert retrival documents to memory items."""
        items = []
        for doc in documents:
            metadata = doc.metadata
            item = MemorySetItem(
                memory_set_name=memory_set.name,
                id=doc.id,
                value=memory_set.item_type.model_validate_json(doc.content)
                if issubclass(memory_set.item_type, BaseModel)
                else doc.content,
                compacted=metadata.pop("compacted"),
                last_accessed_time=datetime.fromisoformat(
                    metadata["last_accessed_time"]
                ),
            )
            if item.compacted:
                item.created_time = DatetimeRange(
                    start=datetime.fromisoformat(metadata.pop("created_time_start")),
                    end=datetime.fromisoformat(metadata.pop("created_time_end")),
                )
            else:
                item.created_time = datetime.fromisoformat(metadata.pop("created_time"))

            if len(metadata) > 0:
                item.additional_metadata = metadata

            items.append(item)
        return items
