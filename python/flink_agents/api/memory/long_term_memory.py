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

from flink_agents.api.chat_message import ChatMessage
from flink_agents.api.prompts.prompt import Prompt


class ReduceStrategy(Enum):
    """Strategy for reducing memory set size."""

    TRIM = "trim"
    SUMMARIZE = "summarize"


class ReduceSetup(BaseModel):
    """Reduce setup for managing the storge of memory set."""

    strategy: ReduceStrategy
    arguments: Dict[str, Any] = Field(default_factory=dict)

    @staticmethod
    def trim_setup(n: int) -> "ReduceSetup":
        """Create trim setup."""
        return ReduceSetup(strategy=ReduceStrategy.TRIM, arguments={"n": n})

    @staticmethod
    def summarize_setup(
        n: int, model: str, prompt: Prompt | None = None
    ) -> "ReduceSetup":
        """Create summarize setup."""
        return ReduceSetup(
            strategy=ReduceStrategy.SUMMARIZE,
            arguments={"n": n, "model": model, "prompt": prompt},
        )


class LongTermMemoryBackend(Enum):
    """Backend for Long-Term Memory."""

    CHROMA = "chroma"


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
    """

    memory_set_name: str
    id: str
    value: Any
    compacted: bool = False
    created_time: DatetimeRange
    last_accessed_time: datetime

class MemorySet(BaseModel):
    """Represents a long term memory set contains memory items.

    Attributes:
        name: The name of this memory set.
        item_type: The type of items stored in this set.
        size: Current items count stored in this set.
        capacity: The capacity of this memory set.
        reduce_setup: Reduce strategy and additional arguments used to reduce memory
        set size.
        item_ids: The indices of items stored in this set.
        reduced: Whether this memory set has been reduced.
    """

    name: str
    item_type: Type[str] | Type[ChatMessage]
    size: int = 0
    capacity: int
    reduce_setup: ReduceSetup
    item_ids: List[str] = Field(default_factory=list)
    reduced: bool = False
    ltm: "BaseLongTermMemory" = Field(default=None, exclude=True)

    @field_serializer("item_type")
    def _serialize_item_type(self, item_type: Type) -> Dict[str, str]:
        return {"module": item_type.__module__, "name": item_type.__name__}

    @model_validator(mode="before")
    def _deserialize_item_type(self) -> "MemorySet":
        if isinstance(self["item_type"], Dict):
            module = importlib.import_module(self["item_type"]["module"])
            self["item_type"] = getattr(module, self["item_type"]["name"])
        return self

    def add(self, item: str | ChatMessage) -> None:
        """Add a memory item to the set, currently only support item with
        type str or ChatMessage.

        If the capacity of this memory set is reached, will trigger reduce
        operation to manage the memory set size.

        Args:
            item: The item to be inserted to this set.
        """
        self.ltm.add(memory_set=self, memory_item=item)

    def get(self) -> List[MemorySetItem]:
        """Retrieve all memory items.

        Returns:
            All memory items in this set.
        """
        return self.ltm.get(memory_set=self)

    def get_recent(self, n: int) -> List[MemorySetItem]:
        """Retrieve n most recent memory items.

        Args:
            n: The number of items to retrieve.

        Returns:
            List of memory items retrieved, sorted by creation timestamp.
        """
        return self.ltm.get_recent(memory_set=self, n=n)

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
    def create_memory_set(
        self,
        name: str,
        item_type: str | Type[ChatMessage],
        capacity: int,
        reduce_setup: ReduceSetup,
    ) -> MemorySet:
        """Create a memory set, if the memory set already exists, return it.

        Args:
            name: The name of the memory set.
            item_type: The type of the memory item.
            capacity: The capacity of the memory set.
            reduce_setup: The reduce strategy and arguments for storge management.

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
    def delete_memory_set(self, name: str) -> None:
        """Delete the memory set.

        Args:
            name: The name of the memory set.
        """

    @abstractmethod
    def add(self, memory_set: MemorySet, memory_item: str | ChatMessage) -> None:
        """Add a memory item to the named set, currently only support item with
        type str or ChatMessage.

        This method may trigger reduce operation to manage the memory set size.

        Args:
            memory_set: The memory set to be inserted.
            memory_item: The item to be inserted to this set.
        """

    @abstractmethod
    def get(self, memory_set: MemorySet) -> List[MemorySetItem]:
        """Retrieve all memory items.

        Args:
            memory_set: The set to be retrieved.

        Returns:
            All the memory items of this set.
        """

    @abstractmethod
    def get_recent(self, memory_set: MemorySet, n: int) -> List[MemorySetItem]:
        """Retrieve n most recent memory items.

        Args:
            memory_set: The set to be retrieved.
            n: The number of items to retrieve.

        Returns:
            List of memory items retrieved, sorted by creation timestamp.
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
