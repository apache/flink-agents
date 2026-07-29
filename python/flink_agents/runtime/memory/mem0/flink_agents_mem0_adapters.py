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

import sys
import types
import uuid
from typing import TYPE_CHECKING, Any, ClassVar, Dict, List, Literal

from mem0.embeddings.base import EmbeddingBase
from mem0.llms.base import BaseLlmConfig, LLMBase
from mem0.utils.factory import EmbedderFactory, LlmFactory, VectorStoreFactory
from mem0.vector_stores.base import VectorStoreBase
from mem0.vector_stores.configs import VectorStoreConfig
from pydantic import BaseModel, ConfigDict

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.vector_stores.vector_store import (
    Document,
)

if TYPE_CHECKING:
    from mem0.embeddings.base import BaseEmbedderConfig

    from flink_agents.api.chat_models.chat_model import BaseChatModelSetup
    from flink_agents.api.embedding_models.embedding_model import (
        BaseEmbeddingModelSetup,
    )
    from flink_agents.api.vector_stores.vector_store import (
        CollectionManageableVectorStore,
    )


class FlinkAgentsEmbedding(EmbeddingBase):
    """Wrapper for the Flink-Agents embedding model.

    This class wraps the Flink-Agents embedding model to generate embeddings
    within Mem0 using Flink-Agents' embedding model implementation.
    """

    def __init__(self, config: BaseEmbedderConfig | None = None) -> None:
        """Initialize the Mem0 embedding wrapper.

        Args:
            config: Configuration object for the embedder. The ``model``
                field should hold a :class:`BaseEmbeddingModelSetup` instance.
        """
        super().__init__(config)
        self._embedding_model: BaseEmbeddingModelSetup = self.config.model

    def embed(
        self,
        text: str,
        memory_action: Literal["add", "search", "update"] | None = None,
    ) -> List[float]:
        """Get the embedding for the given text.

        Args:
            text: The text to embed.
            memory_action: The type of embedding action. Currently unused.

        Returns:
            The embedding vector.
        """
        return self._embedding_model.embed(text)


class FlinkAgentsLlmConfig(BaseLlmConfig):
    """Extended LLM config that carries a metric-records queue.

    Mem0's ``BaseLlmConfig`` only accepts standard LLM parameters.
    This subclass adds ``metric_records`` so that token usage captured
    inside :class:`FlinkAgentsLLM` can be forwarded to the caller.
    """

    def __init__(self, metric_records: Any = None, **kwargs: Any) -> None:  # noqa: D107
        super().__init__(**kwargs)
        self.metric_records = metric_records


class FlinkAgentsLLM(LLMBase):
    """Wrapper for the Flink-Agents chat model.

    This class wraps the Flink-Agents LLM to generate responses
    within Mem0 using Flink-Agents' chat model implementation.
    """

    def __init__(self, config: FlinkAgentsLlmConfig | None = None) -> None:
        """Initialize the Mem0 LLM wrapper.

        Args:
            config: Configuration object for the LLM. The ``model`` field
                should hold a :class:`BaseChatModelSetup` instance.
                The optional ``metric_records`` field may hold a
                :class:`queue.Queue` for collecting token usage metrics.
        """
        super().__init__(config)
        self._chat_model: BaseChatModelSetup = self.config.model
        self._metric_records = self.config.metric_records

    def generate_response(
        self,
        messages: List[Dict[str, str]],
        tools: List[Dict] | None = None,
        tool_choice: str = "auto",
        **kwargs: Any,
    ) -> str | Dict:
        """Generate a response using the Flink-Agents chat model.

        Args:
            messages: List of message dicts with ``role`` and ``content``.
            tools: List of tool definitions. Currently unused.
            tool_choice: Tool choice method. Currently unused.
            **kwargs: Additional keyword arguments.

        Returns:
            The generated response content as a string.
        """
        chat_messages = [
            ChatMessage(
                role=MessageRole(msg["role"]),
                content=msg["content"],
            )
            for msg in messages
        ]
        response: ChatMessage = self._chat_model.chat(messages=chat_messages)

        # Capture token usage metrics if a queue is available.
        if self._metric_records is not None and response.extra_args:
            self._metric_records.put(response.extra_args)

        # Mem0 expects a plain string response from generate_response.
        # It handles JSON parsing internally via remove_code_blocks/json.loads.
        return response.content


class _OutputData(BaseModel):
    """Structural payload Mem0 consumes from a ``VectorStoreBase``."""

    id: str | None = None
    score: float | None = None
    payload: Dict[str, Any] | None = None


def _document_to_payload(doc: Document) -> Dict[str, Any]:
    """Return ``{**metadata, "data": content}`` — Mem0's payload shape.

    The ``"data"`` key is Mem0's convention for document content; if the
    underlying store already stores it on ``metadata`` (direct Chroma path),
    keep that copy.
    """
    metadata = dict(doc.metadata) if doc.metadata else {}
    if "data" not in metadata:
        metadata["data"] = doc.content
    return metadata


class FlinkAgentsMem0VectorStore(VectorStoreBase):
    """Mem0 VectorStoreBase backed by a flink-agents CollectionManageableVectorStore.

    Lets Mem0 use any ``CollectionManageableVectorStore`` registered in
    flink-agents' resource system. Mem0's API is single-collection per
    Memory instance, so this adapter pins one collection and routes every
    call through it. Instantiated by
    :class:`mem0.utils.factory.VectorStoreFactory` when the Mem0 config
    uses ``provider="flink_agents"``.

    Mem0 always hands over pre-computed vectors, so every read/write
    routes through the underlying store's protected embedding hooks —
    the public text-in ``add`` / ``query`` / ``update`` API is bypassed.
    Filters pass through verbatim; each store translates them to its
    native dialect.
    """

    # Collection name used when the wrapped vector store doesn't set
    # ``collection`` either.
    DEFAULT_COLLECTION: ClassVar[str] = "flink_agents_mem0_ltm"

    def __init__(
        self,
        vector_store: CollectionManageableVectorStore,
        **_: Any,
    ) -> None:
        """Pin the adapter to ``vector_store``'s collection."""
        self._vs = vector_store
        self._col = vector_store.collection or self.DEFAULT_COLLECTION
        self._vs.create_collection_if_not_exists(self._col)

    def create_col(
        self,
        name: str,
        vector_size: int | None = None,
        distance: str | None = None,
    ) -> None:
        """Create / switch to collection ``name`` on the underlying store."""
        self._col = name
        self._vs.create_collection_if_not_exists(
            name, vector_size=vector_size, distance=distance
        )

    def insert(
        self,
        vectors: List[List[float]],
        payloads: List[Dict[str, Any]] | None = None,
        ids: List[str] | None = None,
    ) -> None:
        """Insert ``vectors`` with optional ``payloads`` / ``ids``."""
        payloads = payloads if payloads is not None else [{} for _ in vectors]
        ids = ids if ids is not None else [str(uuid.uuid4()) for _ in vectors]
        documents = [
            Document(
                id=id_,
                content=(payload.get("data", "") if isinstance(payload, dict) else ""),
                metadata=payload if isinstance(payload, dict) else {},
                embedding=vector,
            )
            for id_, payload, vector in zip(ids, payloads, vectors, strict=False)
        ]
        self._vs._add_embedding(
            documents=documents,
            collection_name=self._col,
            **self._vs.store_kwargs,
        )

    def search(
        self,
        query: str,
        vectors: List[float] | List[List[float]],
        limit: int = 5,
        filters: Dict[str, Any] | None = None,
    ) -> List[_OutputData]:
        """Semantic search with the pre-computed query ``vectors``."""
        embedding: List[float] = (
            vectors[0] if vectors and isinstance(vectors[0], list) else vectors  # type: ignore[assignment]
        )
        docs = self._vs._query_embedding(
            embedding=embedding,
            limit=limit,
            collection_name=self._col,
            filters=filters,
            **self._vs.store_kwargs,
        )
        return [
            _OutputData(id=d.id, score=d.score, payload=_document_to_payload(d))
            for d in docs
        ]

    def delete(self, vector_id: str) -> None:
        """Delete a single document by id."""
        self._vs.delete(ids=[vector_id], collection_name=self._col)

    def update(
        self,
        vector_id: str,
        vector: List[float] | None = None,
        payload: Dict[str, Any] | None = None,
    ) -> None:
        """Update a document's vector and/or payload."""
        # Fast path: Mem0's normal flow supplies both vector and payload,
        # so we can build the updated Document without pre-reading.
        if vector is not None and isinstance(payload, dict):
            updated = Document(
                id=vector_id,
                content=payload.get("data", ""),
                metadata=payload,
                embedding=vector,
            )
        else:
            # Partial update: merge with the existing doc so we don't
            # blow away fields the caller didn't touch.
            existing = self._vs.get(ids=[vector_id], collection_name=self._col)
            if not existing:
                msg = f"Document with id {vector_id!r} not found in {self._col!r}"
                raise ValueError(msg)
            doc = existing[0]
            updated = Document(
                id=vector_id,
                content=(
                    payload.get("data", doc.content)
                    if isinstance(payload, dict)
                    else doc.content
                ),
                metadata=payload if isinstance(payload, dict) else doc.metadata,
                embedding=vector if vector is not None else doc.embedding,
            )
        self._vs._update_embedding(
            documents=[updated],
            collection_name=self._col,
            **self._vs.store_kwargs,
        )

    def get(self, vector_id: str) -> _OutputData:
        """Return the document with ``vector_id``, or an empty OutputData."""
        docs = self._vs.get(ids=[vector_id], collection_name=self._col)
        if not docs:
            return _OutputData(id=None, score=None, payload=None)
        d = docs[0]
        return _OutputData(
            id=d.id,
            score=None,
            payload=_document_to_payload(d),
        )

    def list_cols(self) -> List[str]:
        """Stub: Mem0 requires this but never calls it."""
        return [self._col]

    def delete_col(self) -> None:
        """Delete the pinned collection on the underlying store."""
        self._vs.delete_collection(self._col)

    def col_info(self) -> Dict[str, Any]:
        """Stub: Mem0 requires this but never calls it."""
        return {"name": self._col}

    def list(
        self,
        filters: Dict[str, Any] | None = None,
        limit: int | None = None,
    ) -> List[List[_OutputData]]:
        """List documents matching ``filters`` (wrapped one layer deep per Mem0)."""
        docs = self._vs.get(
            collection_name=self._col,
            filters=filters,
            limit=limit,
        )
        return [
            [
                _OutputData(
                    id=d.id,
                    score=None,
                    payload=_document_to_payload(d),
                )
                for d in docs
            ]
        ]

    def reset(self) -> None:
        """Delete and recreate the pinned collection."""
        self._vs.delete_collection(self._col)
        self._vs.create_collection_if_not_exists(self._col)


class FlinkAgentsProviderConfig(BaseModel):
    """Provider-specific config for the ``flink_agents`` Mem0 provider.

    Wraps the live :class:`CollectionManageableVectorStore` so Mem0's
    internals can attribute-access ``.collection_name`` (derived, not
    stored) and :meth:`VectorStoreFactory.create` can ``model_dump`` us
    into kwargs for :class:`FlinkAgentsMem0VectorStore`.
    """

    model_config = ConfigDict(arbitrary_types_allowed=True)

    vector_store: Any

    @property
    def collection_name(self) -> str:
        """Collection the Mem0 adapter will pin; derived from the store."""
        return (
            self.vector_store.collection
            or FlinkAgentsMem0VectorStore.DEFAULT_COLLECTION
        )

    def model_dump(self, **_: Any) -> Dict[str, Any]:
        """Return a kwargs dict preserving the live ``vector_store`` object."""
        return {"vector_store": self.vector_store}


def _install_flink_agents_vector_store_provider() -> None:
    """Teach Mem0's ``VectorStoreConfig`` about the ``flink_agents`` provider.

    ``VectorStoreConfig`` uses a ``@model_validator(mode="after")`` backed by
    a hard-coded ``_provider_configs`` whitelist — subclassing doesn't help
    us here (pydantic re-runs the parent's after-validator on subclass
    instances nested under a parent-typed field like ``MemoryConfig.vector_store``).

    We instead:

    - add ``"flink_agents"`` to the private ``_provider_configs`` dict, and
    - register a stub module at the import path the validator expects so
      the ``__import__(...)`` call resolves to our
      :class:`FlinkAgentsProviderConfig`.

    The parent validator then coerces the raw dict into our provider
    config and everything flows through Mem0's normal validation path.
    """
    stub_module_name = "mem0.configs.vector_stores.flink_agents"
    stub_module = types.ModuleType(stub_module_name)
    stub_module.FlinkAgentsProviderConfig = FlinkAgentsProviderConfig
    sys.modules[stub_module_name] = stub_module

    # ``_provider_configs`` is a pydantic PrivateAttr on VectorStoreConfig;
    # mutating the descriptor's ``.default`` dict seeds every subsequent
    # instance.
    VectorStoreConfig.__private_attributes__["_provider_configs"].default[
        "flink_agents"
    ] = "FlinkAgentsProviderConfig"


_install_flink_agents_vector_store_provider()


# Register adapters with Mem0's factories so that they can be
# instantiated via ``{"provider": "flink_agents", ...}`` in the config.
EmbedderFactory.provider_to_class["flink_agents"] = (
    "flink_agents.runtime.memory.mem0.flink_agents_mem0_adapters.FlinkAgentsEmbedding"
)
LlmFactory.provider_to_class["flink_agents"] = (
    "flink_agents.runtime.memory.mem0.flink_agents_mem0_adapters.FlinkAgentsLLM",
    FlinkAgentsLlmConfig,
)
VectorStoreFactory.provider_to_class["flink_agents"] = (
    "flink_agents.runtime.memory.mem0.flink_agents_mem0_adapters.FlinkAgentsMem0VectorStore"
)
