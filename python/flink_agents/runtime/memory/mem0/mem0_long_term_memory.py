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
from __future__ import annotations

import contextlib
import json
import logging
import queue
from dataclasses import dataclass
from datetime import datetime
from enum import Enum
from typing import Any, Dict, List

from pydantic import ConfigDict, Field, PrivateAttr, field_validator
from typing_extensions import override

from flink_agents.api.memory.long_term_memory import (
    MemorySet,
    MemorySetItem,
)
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext  # noqa: TC001
from flink_agents.runtime.memory.internal_base_long_term_memory import (
    InternalBaseLongTermMemory,
)

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# Provider name registered with Mem0's factories.
_PROVIDER_NAME = "flink_agents"


class _LtmObservationOp(str, Enum):
    ADD = "ADD"
    UPDATE = "UPDATE"
    DELETE = "DELETE"
    DELETE_SET = "DELETE_SET"
    GET = "GET"
    SEARCH = "SEARCH"


_MEM0_ADD_RESULT_OPERATIONS = {
    "ADD": _LtmObservationOp.ADD,
    "UPDATE": _LtmObservationOp.UPDATE,
    "DELETE": _LtmObservationOp.DELETE,
}


@dataclass(frozen=True)
class _LtmObservationRecord:
    op: str
    set: str
    id: str | None = None
    query: str | None = None
    value: Any = None
    version: int = 1

    def to_wire(self) -> Dict[str, Any]:
        return {
            "op": self.op,
            "set": self.set,
            "id": self.id,
            "query": self.query,
            "value": self.value,
            "version": self.version,
        }


def _create_flink_agents_config_classes() -> tuple:
    """Create custom LLM/Embedder config classes that accept the
    ``flink_agents`` provider.

    Mem0's ``LlmConfig`` and ``EmbedderConfig`` hard-code a whitelist of
    supported providers.  We subclass them and override ``validate_config``
    so that any provider registered in the corresponding factory is accepted.
    """
    from mem0.embeddings.configs import EmbedderConfig
    from mem0.llms.configs import LlmConfig

    class _FlinkAgentsLlmConfig(LlmConfig):
        @field_validator("config")
        @classmethod
        def validate_config(cls, v: Any, values: Any) -> Any:
            from mem0.utils.factory import LlmFactory

            provider = values.data.get("provider")
            if provider in LlmFactory.provider_to_class:
                return v
            msg = f"Unsupported LLM provider: {provider}"
            raise ValueError(msg)

    class _FlinkAgentsEmbedderConfig(EmbedderConfig):
        @field_validator("config")
        @classmethod
        def validate_config(cls, v: Any, values: Any) -> Any:
            from mem0.utils.factory import EmbedderFactory

            provider = values.data.get("provider")
            if provider in EmbedderFactory.provider_to_class:
                return v
            msg = f"Unsupported Embedder provider: {provider}"
            raise ValueError(msg)

    return _FlinkAgentsLlmConfig, _FlinkAgentsEmbedderConfig


class Mem0LongTermMemory(InternalBaseLongTermMemory):
    """Long-Term Memory backed by Mem0.

    Uses Mem0's intelligent memory layer for information extraction,
    deduplication, and vector-based storage/retrieval.
    """

    model_config = ConfigDict(arbitrary_types_allowed=True)

    ctx: RunnerContext = Field(
        description="The runner context to retrieve resources.", exclude=True
    )

    job_id: str = Field(description="Unique identifier for the job.")

    key: str = Field(
        default="", description="Unique identifier for the keyed partition."
    )

    chat_model_name: str = Field(
        description="Resource name of the chat model for Mem0."
    )

    embedding_model_name: str = Field(
        description="Resource name of the embedding model for Mem0."
    )

    vector_store_name: str = Field(
        description="Resource name of the flink-agents vector store for Mem0.",
    )

    metric_group: Any = Field(
        default=None,
        description="Metric group for reporting token usage.",
        exclude=True,
    )
    metric_records: queue.Queue = Field(
        default_factory=queue.Queue,
        description="Thread-safe queue for deferred token usage metrics.",
        exclude=True,
    )

    _mem0: Any = PrivateAttr(default=None)

    _ltm_observation_records: queue.Queue[tuple[str, _LtmObservationRecord]] = (
        PrivateAttr(default_factory=queue.Queue)
    )
    _update_observation_enabled: bool = PrivateAttr(default=False)
    _get_observation_enabled: bool = PrivateAttr(default=False)
    _search_observation_enabled: bool = PrivateAttr(default=False)

    def __init__(
        self,
        *,
        ctx: RunnerContext,
        job_id: str,
        chat_model_name: str,
        embedding_model_name: str,
        vector_store_name: str,
    ) -> None:
        """Initialize the Mem0-based Long-Term Memory.

        Args:
            ctx: Runner context for resource resolution.
            job_id: Unique job identifier, mapped to Mem0's ``user_id``.
            chat_model_name: Resource name of the chat model.
            embedding_model_name: Resource name of the embedding model.
            vector_store_name: Resource name of a
                ``CollectionManageableVectorStore`` to back Mem0.
        """
        # Resolve metric group upfront on the main thread so that it is
        # safe to use from any thread later.
        agent_metric_group = ctx.agent_metric_group
        metric_group = (
            agent_metric_group.get_sub_group("long-term-memory")
            if agent_metric_group is not None
            else None
        )
        super().__init__(
            ctx=ctx,
            job_id=job_id,
            chat_model_name=chat_model_name,
            embedding_model_name=embedding_model_name,
            vector_store_name=vector_store_name,
            metric_group=metric_group,
        )

    @property
    def _mem0_instance(self) -> Any:
        """Lazily create the Mem0 Memory instance on first access.

        Resource resolution via ``ctx.get_resource`` requires the Flink
        mailbox thread to be ready, which is not the case during context
        creation. Deferring to first use avoids the NPE.
        """
        if self._mem0 is None:
            self._mem0 = self._create_mem0_instance()
        return self._mem0

    def _create_mem0_instance(self) -> Any:
        """Create and configure a Mem0 Memory instance.

        All three of Mem0's factories — LLM, Embedder, VectorStore — learn
        the ``flink_agents`` provider at import of
        ``flink_agents_mem0_adapters``; MemoryConfig then validates through
        Mem0's normal path.
        """
        from mem0.configs.base import MemoryConfig
        from mem0.memory.main import Memory
        from mem0.vector_stores.configs import VectorStoreConfig

        import flink_agents.runtime.memory.mem0.flink_agents_mem0_adapters  # noqa: F401

        _LlmConfig, _EmbedderConfig = _create_flink_agents_config_classes()

        # Resolve Flink-Agents resources.
        chat_model = self.ctx.get_resource(
            self.chat_model_name, ResourceType.CHAT_MODEL
        )
        embedding_model = self.ctx.get_resource(
            self.embedding_model_name, ResourceType.EMBEDDING_MODEL
        )
        vector_store = self.ctx.get_resource(
            self.vector_store_name, ResourceType.VECTOR_STORE
        )

        mem0_config = MemoryConfig(
            llm=_LlmConfig(
                provider=_PROVIDER_NAME,
                config={
                    "model": chat_model,
                    "metric_records": self.metric_records,
                },
            ),
            embedder=_EmbedderConfig(
                provider=_PROVIDER_NAME,
                config={"model": embedding_model},
            ),
            vector_store=VectorStoreConfig(
                provider=_PROVIDER_NAME,
                config={"vector_store": vector_store},
            ),
        )

        return Memory(mem0_config)

    @override
    def switch_context(
        self,
        key: str,
        update_observation_enabled: bool | None = None,
        get_observation_enabled: bool | None = None,
        search_observation_enabled: bool | None = None,
    ) -> None:
        """Switch the keyed partition context.

        This method is called on the mailbox thread before each action.
        We use it to ensure the Mem0 instance is initialized, since
        ``ctx.get_resource`` requires the mailbox thread to be ready.

        Args:
            key: The new key for partition isolation.
            update_observation_enabled: Whether mutations should be recorded.
            get_observation_enabled: Whether gets should be recorded.
            search_observation_enabled: Whether searches should be recorded.
        """
        # Ensure Mem0 is initialized on the mailbox thread.
        _ = self._mem0_instance
        # Ensure report token usage on the mailbox thread
        self._report_token_metrics()
        self.key = key
        if update_observation_enabled is not None:
            self._update_observation_enabled = update_observation_enabled
        if get_observation_enabled is not None:
            self._get_observation_enabled = get_observation_enabled
        if search_observation_enabled is not None:
            self._search_observation_enabled = search_observation_enabled

    def _record_ltm_op(
        self,
        op: _LtmObservationOp | str,
        memory_set: str,
        mem_id: str | None,
        value: Any,
        observation_key: str,
        *,
        enabled: bool = True,
    ) -> None:
        """Buffer one LTM operation for observation.

        Args:
            op: The operation kind.
            memory_set: The name of the memory set operated on.
            mem_id: The affected memory id, or None for whole-set ops.
            value: The stored memory content, or None when not applicable.
            observation_key: Partition key captured at operation entry.
            enabled: Whether this operation type is configured for observation.
        """
        try:
            if not enabled or getattr(self.ctx, "_j_runner_context", None) is None:
                return
            try:
                operation = _LtmObservationOp(op)
            except ValueError:
                logger.warning("Skipping unknown LTM observation operation %r", op)
                return
            self._ltm_observation_records.put(
                (
                    observation_key,
                    _LtmObservationRecord(
                        op=operation.value,
                        set=memory_set,
                        id=mem_id,
                        value=value,
                    ),
                )
            )
        except Exception:
            logger.debug("LTM observation buffering failed; skipping", exc_info=True)

    def _record_ltm_search(
        self,
        memory_set: str,
        query: str,
        hits: List[Dict[str, Any]],
        observation_key: str,
        *,
        enabled: bool = True,
    ) -> None:
        """Buffer one LTM search call for observation.

        Args:
            memory_set: The set searched.
            query: The search query string.
            hits: The ordered matched records, each with id, value, and score.
            observation_key: Partition key captured at operation entry.
            enabled: Whether search observation is configured.
        """
        try:
            if not enabled or getattr(self.ctx, "_j_runner_context", None) is None:
                return
            self._ltm_observation_records.put(
                (
                    observation_key,
                    _LtmObservationRecord(
                        op=_LtmObservationOp.SEARCH.value,
                        set=memory_set,
                        query=query,
                        value=hits,
                    ),
                )
            )
        except Exception:
            logger.debug("LTM observation buffering failed; skipping", exc_info=True)

    def drain_ltm_observation_records(self, key: str) -> str:
        """Pop buffered LTM records for one partition key as a JSON array.

        Called from Java on the mailbox thread at action-finish flush. Records for
        other partition keys are placed back in the shared queue.

        Args:
            key: Partition key whose records to drain.

        Returns:
            JSON array string of the drained records.
        """
        records: List[_LtmObservationRecord] = []
        other_records: List[tuple[str, _LtmObservationRecord]] = []
        while True:
            try:
                owner_key, record = self._ltm_observation_records.get_nowait()
            except queue.Empty:
                break
            if owner_key == key:
                records.append(record)
            else:
                other_records.append((owner_key, record))
        for record in other_records:
            self._ltm_observation_records.put(record)

        return json.dumps([record.to_wire() for record in records], ensure_ascii=False)

    @override
    def get_memory_set(self, name: str) -> MemorySet:
        """Get the memory set by name.

        Args:
            name: The name of the memory set.

        Returns:
            The memory set.
        """
        return MemorySet(name=name, ltm=self)

    @override
    def delete_memory_set(self, name: str) -> bool:
        """Delete a memory set and all its items.

        Args:
            name: The name of the memory set.

        Returns:
            True if the memory set was deleted.
        """
        observation_key = self.key
        observation_enabled = self._update_observation_enabled
        self._mem0_instance.delete_all(
            user_id=self.job_id,
            agent_id=self.key,
            run_id=name,
        )
        self._record_ltm_op(
            _LtmObservationOp.DELETE_SET,
            name,
            None,
            None,
            observation_key,
            enabled=observation_enabled,
        )
        return True

    @override
    def add(
        self,
        memory_set: MemorySet,
        memory_items: str | List[str],
        metadatas: Dict[str, Any] | List[Dict[str, Any]] | None = None,
    ) -> List[str]:
        """Add items to a memory set.

        Args:
            memory_set: The memory set to add to.
            memory_items: String or list of strings to store.
            metadatas: Optional metadata for each item.

        Returns:
            List of IDs of the added memories.
        """
        observation_key = self.key
        observation_enabled = self._update_observation_enabled
        if isinstance(memory_items, str):
            memory_items = [memory_items]
        if metadatas is not None and isinstance(metadatas, dict):
            metadatas = [metadatas]

        all_ids = []
        for i, item in enumerate(memory_items):
            metadata = metadatas[i] if metadatas and i < len(metadatas) else None
            result = self._mem0_instance.add(
                messages=item,
                user_id=self.job_id,
                agent_id=self.key if self.key else None,
                run_id=memory_set.name,
                metadata=metadata,
            )
            # Extract IDs from the result
            for entry in result.get("results", []):
                if "id" in entry:
                    all_ids.append(entry["id"])
                    event_name = str(entry.get("event", "")).upper()
                    operation = _MEM0_ADD_RESULT_OPERATIONS.get(event_name)
                    if operation is None:
                        logger.warning(
                            "Skipping unsupported Mem0 add result operation %r",
                            event_name,
                        )
                    else:
                        self._record_ltm_op(
                            operation,
                            memory_set.name,
                            entry["id"],
                            None
                            if operation == _LtmObservationOp.DELETE
                            else entry.get("memory"),
                            observation_key,
                            enabled=observation_enabled,
                        )
        return all_ids

    @override
    def get(
        self,
        memory_set: MemorySet,
        ids: str | List[str] | None = None,
        filters: Dict[str, Any] | None = None,
        limit: int = 100,
    ) -> List[MemorySetItem]:
        """Retrieve memory items.

        Args:
            memory_set: The memory set to retrieve from.
            ids: Optional ID or list of IDs. If provided, ``filters`` and
                ``limit`` are ignored.
            filters: Optional metadata filters for listing items.
            limit: Maximum number of items to return. Defaults to 100.

        Returns:
            List of memory items.
        """
        observation_key = self.key
        observation_enabled = self._get_observation_enabled
        if ids is not None:
            if isinstance(ids, str):
                ids = [ids]
            items = []
            for memory_id in ids:
                result = self._mem0_instance.get(memory_id=memory_id)
                items.append(self._convert_mem0_result(memory_set.name, result))
                item = items[-1]
                self._record_ltm_op(
                    _LtmObservationOp.GET,
                    memory_set.name,
                    item.id,
                    item.value,
                    observation_key,
                    enabled=observation_enabled,
                )
            return items

        result = self._mem0_instance.get_all(
            user_id=self.job_id,
            agent_id=self.key,
            run_id=memory_set.name,
            filters=filters,
            limit=limit,
        )
        items = [
            self._convert_mem0_result(memory_set.name, entry)
            for entry in result.get("results", [])
        ]
        for item in items:
            self._record_ltm_op(
                _LtmObservationOp.GET,
                memory_set.name,
                item.id,
                item.value,
                observation_key,
                enabled=observation_enabled,
            )
        return items

    @override
    def delete(self, memory_set: MemorySet, ids: str | List[str] | None = None) -> None:
        """Delete memory items.

        Args:
            memory_set: The memory set to delete from.
            ids: Optional ID or list of IDs. If None, deletes all items.
        """
        observation_key = self.key
        observation_enabled = self._update_observation_enabled
        if ids is None:
            self._mem0_instance.delete_all(
                user_id=self.job_id,
                agent_id=self.key,
                run_id=memory_set.name,
            )
            self._record_ltm_op(
                _LtmObservationOp.DELETE_SET,
                memory_set.name,
                None,
                None,
                observation_key,
                enabled=observation_enabled,
            )
            return

        if isinstance(ids, str):
            ids = [ids]
        for memory_id in ids:
            self._mem0_instance.delete(memory_id=memory_id)
            self._record_ltm_op(
                _LtmObservationOp.DELETE,
                memory_set.name,
                memory_id,
                None,
                observation_key,
                enabled=observation_enabled,
            )

    @override
    def search(
        self,
        memory_set: MemorySet,
        query: str,
        limit: int,
        filters: Dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> List[MemorySetItem]:
        """Search for memories related to the query.

        Args:
            memory_set: The memory set to search in.
            query: The search query.
            limit: Maximum number of results.
            filters: Optional metadata filters for search.
            **kwargs: Additional search arguments.

        Returns:
            List of matching memory items.
        """
        observation_key = self.key
        observation_enabled = self._search_observation_enabled
        result = self._mem0_instance.search(
            query=query,
            user_id=self.job_id,
            agent_id=self.key,
            run_id=memory_set.name,
            limit=limit,
            filters=filters,
            **kwargs,
        )
        raw_entries = result.get("results", [])
        self._record_ltm_search(
            memory_set.name,
            query,
            [
                {"id": e.get("id"), "value": e.get("memory"), "score": e.get("score")}
                for e in raw_entries
                if "id" in e
            ],
            observation_key,
            enabled=observation_enabled,
        )
        return [
            self._convert_mem0_result(memory_set.name, entry) for entry in raw_entries
        ]

    @staticmethod
    def _convert_mem0_result(
        memory_set_name: str, entry: Dict[str, Any]
    ) -> MemorySetItem:
        """Convert a Mem0 result entry to a MemorySetItem.

        Args:
            memory_set_name: The name of the memory set.
            entry: A single Mem0 result dict.

        Returns:
            A MemorySetItem.
        """
        created_at = None
        if "created_at" in entry:
            with contextlib.suppress(ValueError, TypeError):
                created_at = datetime.fromisoformat(entry["created_at"])

        updated_at = None
        if "updated_at" in entry:
            with contextlib.suppress(ValueError, TypeError):
                updated_at = datetime.fromisoformat(entry["updated_at"])

        # Collect extra metadata fields
        metadata = entry.get("metadata") or {}

        return MemorySetItem(
            memory_set_name=memory_set_name,
            id=entry.get("id", ""),
            value=entry.get("memory", ""),
            created_at=created_at,
            updated_at=updated_at,
            additional_metadata=metadata if metadata else None,
        )

    @override
    def close(self) -> None:
        """Clean up resources and flush pending token metrics."""
        self._report_token_metrics()

    def _report_token_metrics(self) -> None:
        """Drain the metric queue and report token usage counters."""
        if self.metric_group is None:
            return
        while not self.metric_records.empty():
            metric = self.metric_records.get()
            if (
                metric.get("model_name")
                and metric.get("promptTokens")
                and metric.get("completionTokens")
            ):
                model_group = self.metric_group.get_sub_group(
                    "model", metric["model_name"]
                )
                model_group.get_counter("promptTokens").inc(metric["promptTokens"])
                model_group.get_counter("completionTokens").inc(
                    metric["completionTokens"]
                )
