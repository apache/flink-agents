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
from __future__ import annotations

import uuid
from typing import Any, Dict, List

# ``VectorStoreBase`` annotates the pydantic ``_stores`` PrivateAttr, so it
# must be imported at runtime (TC002 would move it into type-checking).
from mem0.vector_stores.base import VectorStoreBase  # noqa: TC002
from pydantic import Field, PrivateAttr
from typing_extensions import override

from flink_agents.api.vector_stores.vector_store import (
    CollectionManageableVectorStore,
    Document,
    _maybe_cast_to_list,
)


def _mem0_output_to_document(output: Any) -> Document:
    """Convert a Mem0 ``OutputData``-like object into a flink-agents ``Document``.

    Mem0 stores define local ``OutputData`` classes with ``id`` / ``score`` /
    ``payload`` attributes; we treat it as a structural contract. ``payload``'s
    ``"data"`` key is our convention for the document's textual content (mirrors
    :class:`FlinkAgentsMem0VectorStore`).
    """
    rid = getattr(output, "id", None)
    score = getattr(output, "score", None)
    payload = getattr(output, "payload", None) or {}
    if not isinstance(payload, dict):
        payload = {}
    content = payload.get("data", "")
    metadata = {k: v for k, v in payload.items() if k != "data"}
    return Document(id=rid, content=content, metadata=metadata, score=score)


class Mem0VectorStore(CollectionManageableVectorStore):
    """flink-agents ``CollectionManageableVectorStore`` backed by Mem0's native stores.

    Lets flink-agents users access Mem0's broader vector-store ecosystem
    (pgvector, milvus, qdrant, redis, ...) through flink-agents' resource
    system.

    Mem0's ``VectorStoreBase`` is a single-collection API — one instance per
    ``collection_name``. This class keeps a lazy ``{collection_name -> Mem0
    store}`` cache and injects ``collection_name`` into ``provider_config``
    when constructing each underlying instance.

    Filters are forwarded to the underlying Mem0 store unchanged. The
    unified DSL is equality-only (see :class:`BaseVectorStore`), which
    matches the filter shape Mem0 itself emits for session scoping and
    which every Mem0 backend is guaranteed to honour.
    """

    provider: str = Field(
        description="Mem0 vector store provider name (e.g. 'chroma', 'qdrant', 'pgvector').",
    )
    provider_config: Dict[str, Any] = Field(
        default_factory=dict,
        description="Provider-specific config dict passed to Mem0's VectorStoreFactory.",
    )
    collection: str = Field(
        default="flink_agents_mem0_vs",
        description="Default collection used when a caller does not specify one.",
    )

    _stores: Dict[str, VectorStoreBase] = PrivateAttr(default_factory=dict)

    @property
    @override
    def store_kwargs(self) -> Dict[str, Any]:
        return {}

    def _resolve(self, collection_name: str | None) -> Any:
        """Return the Mem0 store for ``collection_name``, creating it lazily."""
        name = collection_name or self.collection
        if name not in self._stores:
            from mem0.utils.factory import VectorStoreFactory

            config = {**self.provider_config, "collection_name": name}
            self._stores[name] = VectorStoreFactory.create(self.provider, config)
        return self._stores[name]

    def _get_existing(self, collection_name: str | None) -> Any:
        """Return the Mem0 store for an existing collection, or raise."""
        name = collection_name or self.collection
        if name not in self._stores:
            err_msg = f"Collection {name!r} does not exist"
            raise ValueError(err_msg)
        return self._stores[name]

    @override
    def create_collection_if_not_exists(self, name: str, **kwargs: Any) -> None:
        self._resolve(name)

    @override
    def delete_collection(self, name: str) -> None:
        store = self._stores.pop(name, None)
        if store is None:
            err_msg = f"Collection {name!r} does not exist"
            raise ValueError(err_msg)
        store.delete_col()

    @override
    def _add_embedding(
        self,
        *,
        documents: List[Document],
        collection_name: str | None = None,
        **kwargs: Any,
    ) -> List[str]:
        store = self._resolve(collection_name)
        ids = [doc.id or str(uuid.uuid4()) for doc in documents]
        vectors = [doc.embedding for doc in documents]
        payloads = [{**doc.metadata, "data": doc.content} for doc in documents]
        store.insert(vectors=vectors, payloads=payloads, ids=ids)
        return ids

    @override
    def _query_embedding(
        self,
        embedding: List[float],
        limit: int = 10,
        collection_name: str | None = None,
        filters: Dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> List[Document]:
        store = self._get_existing(collection_name)
        results = store.search(
            query="", vectors=[embedding], limit=limit, filters=filters
        )
        return [_mem0_output_to_document(r) for r in results]

    @override
    def _update_embedding(
        self,
        *,
        documents: List[Document],
        collection_name: str | None = None,
        **kwargs: Any,
    ) -> None:
        store = self._get_existing(collection_name)
        for doc in documents:
            payload = {**doc.metadata, "data": doc.content}
            store.update(vector_id=doc.id, vector=doc.embedding, payload=payload)

    @override
    def get(
        self,
        ids: str | List[str] | None = None,
        collection_name: str | None = None,
        filters: Dict[str, Any] | None = None,
        limit: int | None = 100,
        **kwargs: Any,
    ) -> List[Document]:
        store = self._get_existing(collection_name)
        if ids is not None:
            docs: List[Document] = []
            for id_ in _maybe_cast_to_list(ids):
                output = store.get(vector_id=id_)
                if output is None or getattr(output, "id", None) is None:
                    continue
                docs.append(_mem0_output_to_document(output))
            return docs
        listed = store.list(filters=filters, limit=limit)
        return [_mem0_output_to_document(r) for r in _flatten_list(listed)]

    @override
    def delete(
        self,
        ids: str | List[str] | None = None,
        collection_name: str | None = None,
        filters: Dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> None:
        store = self._get_existing(collection_name)
        if ids is not None:
            for id_ in _maybe_cast_to_list(ids):
                store.delete(vector_id=id_)
            return
        # No ids: delete everything matching ``filters`` (None = all). Mem0
        # has no bulk-delete-by-filter, so list-then-delete one by one — O(N).
        listed = store.list(filters=filters) if filters is not None else store.list()
        for r in _flatten_list(listed):
            rid = getattr(r, "id", None)
            if rid:
                store.delete(vector_id=rid)


def _flatten_list(result: Any) -> List[Any]:
    """Flatten Mem0's ``list()`` return shape.

    Some stores return ``List[OutputData]``, others return
    ``[[OutputData, ...]]`` (a single-element list of lists). Tolerate both.
    """
    if not result:
        return []
    if isinstance(result, list) and result and isinstance(result[0], list):
        return list(result[0])
    return list(result)
