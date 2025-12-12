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
import importlib
from abc import ABC, abstractmethod
from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Type

from pydantic import (
    BaseModel,
    Field,
    field_serializer,
    model_validator,
)
from typing_extensions import override

from flink_agents.api.chat_message import ChatMessage
from flink_agents.api.prompts.prompt import Prompt

ItemType = str | ChatMessage


class CompactionStrategyType(Enum):
    """Strategy for compact memory set."""

    SUMMARIZATION = "summarization"


class CompactionStrategy(BaseModel, ABC):
    """Strategy for compact memory set."""

    @property
    @abstractmethod
    def type(self) -> CompactionStrategyType:
        """Return type of this strategy."""


class SummarizationStrategy(CompactionStrategy):
    """Summarization strategy.

    Attributes:
        model: The name of the llm used for generating the summarization.
        prompt: The prompt instance or  name of the prompt used for generating
            the summarization. Optional.
        limit: How many summarization should generate for the context. Defaults to 1.
    """

    model: str
    prompt: str | Prompt | None = None
    limit: int = 1

    @property
    @override
    def type(self) -> CompactionStrategyType:
        return CompactionStrategyType.SUMMARIZATION


class LongTermMemoryBackend(Enum):
    """Backend for Long-Term Memory."""

    EXTERNAL_VECTOR_STORE = "external_vector_store"


class DatetimeRange(BaseModel):
    """Represents a datetime range."""

    start: datetime
    end: datetime


class MemorySetItem(BaseModel):
    """Represents a long term memory item retrieved from vector store.

    Attributes:
        memory_set_name: The name of the memory set this item belongs to.
        id: The id of this item.
        value: The value of this item.
        compacted: Whether this item has been compacted.
        created_time: The timestamp this item was added to the memory set.
        last_accessed_time: The timestamp this item was last accessed.
        additional_metadata: Additional metadata for this item.
    """

    memory_set_name: str
    id: str
    value: Any
    compacted: bool = False
    created_time: datetime | DatetimeRange = None
    last_accessed_time: datetime
    additional_metadata: Dict[str, Any] | None = None


class MemorySet(BaseModel):
    """Represents a long term memory set contains memory items.

    Attributes:
        name: The name of this memory set.
        item_type: The type of items stored in this set.
        capacity: The capacity of this memory set.
        compaction_strategy: Compaction strategy and additional arguments used
        to compact memory set.
    """

    name: str
    item_type: Type[str] | Type[ChatMessage]
    capacity: int
    compaction_strategy: CompactionStrategy
    ltm: "BaseLongTermMemory" = Field(default=None, exclude=True)

    @field_serializer("item_type")
    def _serialize_item_type(self, item_type: Type) -> Dict[str, str]:
        return {"module": item_type.__module__, "name": item_type.__name__}

    @field_serializer("compaction_strategy")
    def _serialize_compaction_strategy(
        self, compaction_strategy: CompactionStrategy
    ) -> Dict[str, str]:
        data = compaction_strategy.model_dump()
        data.update(
            {
                "module": compaction_strategy.__class__.__module__,
                "name": compaction_strategy.__class__.__name__,
            }
        )
        return data

    @model_validator(mode="before")
    def _deserialize_item_type(self) -> "MemorySet":
        if isinstance(self["item_type"], Dict):
            module = importlib.import_module(self["item_type"]["module"])
            self["item_type"] = getattr(module, self["item_type"]["name"])
        if isinstance(self["compaction_strategy"], Dict):
            module = importlib.import_module(self["compaction_strategy"].pop("module"))
            clazz = getattr(module, self["compaction_strategy"].pop("name"))
            self["compaction_strategy"] = clazz.model_validate(
                self["compaction_strategy"]
            )
        return self

    @property
    def size(self) -> int:
        """The count of items of this memory set."""
        return self.ltm.size(memory_set=self)

    def add(
        self, items: ItemType | List[ItemType], ids: str | List[str] | None = None
    ) -> None:
        """Add a memory item to the set, currently only support item with
        type str or ChatMessage.

        If the capacity of this memory set is reached, will trigger reduce
        operation to manage the memory set size.

        Args:
            items: The items to be inserted to this set.
            ids: The ids of the items to be inserted. Optional.
        """
        self.ltm.add(memory_set=self, memory_items=items, ids=ids)

    def get(
        self, ids: str | List[str] | None = None
    ) -> MemorySetItem | List[MemorySetItem]:
        """Retrieve memory items. If no item id provided, will return all items.

        Args:
            ids: The ids of the items to retrieve.

        Returns:
            The memory items retrieved.
        """
        return self.ltm.get(memory_set=self, ids=ids)

    def search(self, query: str, limit: int, **kwargs: Any) -> List[MemorySetItem]:
        """Retrieve n memory items related to the query.

        Args:
            query: The query to search for.
            limit: The number of items to retrieve.
            **kwargs: Additional arguments for search.
        """
        return self.ltm.search(memory_set=self, query=query, limit=limit, **kwargs)


class BaseLongTermMemory(ABC, BaseModel):
    """Base Abstract class for long term memory."""

    @abstractmethod
    def get_or_create_memory_set(
        self,
        name: str,
        item_type: str | Type[ChatMessage],
        capacity: int,
        compaction_strategy: CompactionStrategy,
    ) -> MemorySet:
        """Create a memory set, if the memory set already exists, return it.

        Args:
            name: The name of the memory set.
            item_type: The type of the memory item.
            capacity: The capacity of the memory set.
            compaction_strategy: The compaction strategy and arguments for
            storge management.

        Returns:
            The created memory set.
        """

    @abstractmethod
    def get_memory_set(self, name: str) -> MemorySet:
        """Get the memory set.

        Args:
            name: The name of the memory set.

        Returns:
            The memory set.
        """

    @abstractmethod
    def delete_memory_set(self, name: str) -> bool:
        """Delete the memory set.

        Args:
            name: The name of the memory set.

        Returns:
            Whether the memory set was deleted.
        """

    @abstractmethod
    def size(self, memory_set: MemorySet) -> int:
        """Get the size of the memory set.

        Args:
            memory_set: The memory set to count.
        """

    @abstractmethod
    def add(
        self,
        memory_set: MemorySet,
        memory_items: ItemType | List[ItemType],
        ids: str | List[str] | None = None,
        metadatas: Dict[str, Any] | List[Dict[str, Any]] | None = None,
    ) -> None:
        """Add items to the memory set, currently only support items with
        type str or ChatMessage.

        This method may trigger compaction to manage the memory set size.

        Args:
            memory_set: The memory set to add to.
            memory_items: The items to be added to this set.
            ids: The IDs of items. Will be automatically generated if not provided.
            Optional.
            metadatas: The metadata for items. Optional.
        """

    @abstractmethod
    def get(
        self, memory_set: MemorySet, ids: str | List[str] | None = None
    ) -> MemorySetItem | List[MemorySetItem]:
        """Retrieve memory items. If no item id provided, return all items.

        Args:
            memory_set: The set to be retrieved.
            ids: The ids of the items to retrieve. If not provided, all items will
            be retrieved. Optional.

        Returns:
            The memory items retrieved.
        """

    @abstractmethod
    def delete(self, memory_set: MemorySet, ids: str | List[str] | None = None) -> None:
        """Delete memory items. If no item id provided, delete all items.

        Args:
            memory_set: The memory set to delete from.
            ids: The ids of items to be deleted, If not provided, all items will be
            deleted. Optional.
        """

    @abstractmethod
    def search(
        self, memory_set: MemorySet, query: str, limit: int, **kwargs: Any
    ) -> List[MemorySetItem]:
        """Retrieve n memory items related to the query.

        Args:
            memory_set: The set to be retrieved.
            query: The query for sematic search.
            limit: The number of items to retrieve.
            **kwargs: Additional arguments for sematic search.

        Returns:
            Related memory items retrieved.
        """
