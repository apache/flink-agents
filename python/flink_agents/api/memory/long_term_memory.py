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
from abc import ABC, abstractmethod
from datetime import datetime
from typing import Any, Dict, List

from pydantic import BaseModel, Field

from flink_agents.api.configuration import ConfigOption


class LongTermMemoryOptions:
    """Config options for Long-Term Memory."""

    class Mem0:
        """Config options for the Mem0-based Long-Term Memory backend."""

        CHAT_MODEL_SETUP = ConfigOption(
            key="long-term-memory.mem0.chat-model-setup",
            config_type=str,
            default=None,
        )

        EMBEDDING_MODEL_SETUP = ConfigOption(
            key="long-term-memory.mem0.embedding-model-setup",
            config_type=str,
            default=None,
        )

        VECTOR_STORE = ConfigOption(
            key="long-term-memory.mem0.vector-store",
            config_type=str,
            default=None,
        )


class MemorySetItem(BaseModel):
    """Represents a long term memory item.

    Attributes:
        memory_set_name: The name of the memory set this item belongs to.
        id: The id of this item.
        value: The value of this item.
        created_at: The timestamp this item was created.
        updated_at: The timestamp this item was last updated.
        additional_metadata: Additional metadata for this item.
    """

    memory_set_name: str
    id: str
    value: str
    created_at: datetime | None = None
    updated_at: datetime | None = None
    additional_metadata: Dict[str, Any] | None = None


class MemorySet(BaseModel):
    """Represents a long term memory set contains memory items.

    A set is bound to the action context it was obtained in. Operations run on a
    worker thread when submitted through ``durable_execute_async``, by which time
    the owning long term memory may already have switched to another partition
    key, so they take the context from the set rather than from it. A set must
    therefore be obtained per action and not reused across actions.

    Attributes:
        name: The name of this memory set.
        partition_key: The partition key this set is scoped to.
        observation_id: Identifier for the owning action's observations.
        observation_suppressed: Whether observation is suppressed for the owning
            action.
    """

    name: str
    ltm: "BaseLongTermMemory" = Field(default=None, exclude=True)
    partition_key: str | None = Field(default=None, exclude=True)
    observation_id: str = Field(default="", exclude=True)
    observation_suppressed: bool = Field(default=False, exclude=True)

    def add(
        self,
        items: str | List[str],
        metadatas: Dict[str, Any] | List[Dict[str, Any]] | None = None,
    ) -> List[str]:
        """Add a memory item to the set.

        Args:
            items: The items to be inserted to this set.
            metadatas: The metadata for items. Optional.

        Returns:
            The IDs of the items added.
        """
        return self.ltm.add(memory_set=self, memory_items=items, metadatas=metadatas)

    def get(
        self,
        ids: str | List[str] | None = None,
        filters: Dict[str, Any] | None = None,
        limit: int = 100,
    ) -> List[MemorySetItem]:
        """Retrieve memory items.

        Args:
            ids: The ids of the items to retrieve. If provided, ``filters``
                and ``limit`` are ignored.
            filters: Optional metadata filters applied when listing items.
            limit: Maximum number of items to return. Defaults to 100.

        Returns:
            The memory items retrieved.
        """
        return self.ltm.get(memory_set=self, ids=ids, filters=filters, limit=limit)

    def search(
        self,
        query: str,
        limit: int,
        filters: Dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> List[MemorySetItem]:
        """Retrieve n memory items related to the query.

        Args:
            query: The query to search for.
            limit: The number of items to retrieve.
            filters: Optional metadata filters for search.
            **kwargs: Additional arguments for search.
        """
        return self.ltm.search(
            memory_set=self, query=query, limit=limit, filters=filters, **kwargs
        )

    def delete(self, ids: str | List[str] | None = None) -> None:
        """Delete memory items. If no id provided, delete all items.

        Args:
            ids: The ids of items to be deleted. If not provided, all items
                will be deleted.
        """
        self.ltm.delete(memory_set=self, ids=ids)


class BaseLongTermMemory(ABC, BaseModel):
    """Base Abstract class for long term memory."""

    @abstractmethod
    def get_memory_set(self, name: str) -> MemorySet:
        """Get the memory set by name. If it does not exist, create it.

        The returned set is bound to the calling action, so call this from the action
        body itself, not from a callback running on another thread, and obtain one per
        action rather than holding one across actions. Operating on a set that carries
        no binding raises rather than silently widening the operation to every
        partition key.

        Args:
            name: The name of the memory set.

        Returns:
            The memory set.
        """

    @abstractmethod
    def delete_memory_set(self, name: str) -> bool:
        """Delete the memory set.

        Unlike the set-scoped operations, this takes a name and applies to the key
        currently in scope, so it must be called from the action body rather than from
        another thread, and can target a different key than ``MemorySet.delete`` on a
        same-named set would.

        Args:
            name: The name of the memory set.

        Returns:
            Whether the memory set was deleted.
        """

    @abstractmethod
    def add(
        self,
        memory_set: MemorySet,
        memory_items: str | List[str],
        metadatas: Dict[str, Any] | List[Dict[str, Any]] | None = None,
    ) -> List[str]:
        """Add items to the memory set.

        This method may trigger compaction to manage the memory set size.

        Args:
            memory_set: The memory set to add to.
            memory_items: The items to be added to this set.
            metadatas: The metadata for items. Optional.

        Returns:
            The IDs of added items.
        """

    @abstractmethod
    def get(
        self,
        memory_set: MemorySet,
        ids: str | List[str] | None = None,
        filters: Dict[str, Any] | None = None,
        limit: int = 100,
    ) -> List[MemorySetItem]:
        """Retrieve memory items.

        Args:
            memory_set: The set to be retrieved.
            ids: The ids of the items to retrieve. If provided, ``filters``
                and ``limit`` are ignored.
            filters: Optional metadata filters applied when listing items.
            limit: Maximum number of items to return. Defaults to 100.

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
        self,
        memory_set: MemorySet,
        query: str,
        limit: int,
        filters: Dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> List[MemorySetItem]:
        """Retrieve n memory items related to the query.

        Args:
            memory_set: The set to be retrieved.
            query: The query for semantic search.
            limit: The number of items to retrieve.
            filters: Optional metadata filters for search.
            **kwargs: Additional arguments for semantic search.

        Returns:
            Related memory items retrieved.
        """

    @abstractmethod
    def close(self) -> None:
        """Logic executed when job close."""
