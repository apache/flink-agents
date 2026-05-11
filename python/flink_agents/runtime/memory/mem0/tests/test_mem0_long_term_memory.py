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
import tempfile
from typing import Any
from unittest.mock import MagicMock, create_autospec

import pytest

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.memory.long_term_memory import (
    MemorySetItem,
)
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.vector_stores.vector_store import (
    CollectionManageableVectorStore,
)
from flink_agents.integrations.vector_stores.chroma.chroma_vector_store import (
    ChromaVectorStore,
)
from flink_agents.integrations.vector_stores.mem0.mem0_vector_store import (
    Mem0VectorStore,
)
from flink_agents.runtime.memory.mem0.mem0_long_term_memory import Mem0LongTermMemory


class MockChatModelSetup:
    """Mock chat model that returns appropriate responses for Mem0.

    Mem0's ``_add_to_vector_store`` makes two LLM calls per ``add``:

    1. **Fact extraction** — has a ``system`` role message.  The user message
       contains the input text prefixed with ``Input:``.  Expected response:
       ``{"facts": ["..."]}``
    2. **Memory update** — has NO ``system`` message (single ``user``
       message containing "smart memory manager").  Expected response:
       ``{"memory": [{"text": "...", "event": "ADD"}]}``

    This mock extracts the user content from the messages and passes it
    through so that tests can verify the stored memory values.
    """

    def __init__(self) -> None:
        self._last_facts: list[str] = []

    def chat(self, messages, **kwargs: Any):
        import json

        has_system_msg = any(
            isinstance(msg, ChatMessage) and msg.role == MessageRole.SYSTEM
            for msg in messages
        )

        if has_system_msg:
            # Call 1: Fact extraction.
            # The user message content is formatted as "Input:\n<text>".
            user_msg = next(
                (
                    msg
                    for msg in messages
                    if isinstance(msg, ChatMessage) and msg.role == MessageRole.USER
                ),
                None,
            )
            user_text = user_msg.content if user_msg else ""
            if "Input:" in user_text:
                user_text = user_text.split("Input:", 1)[1].strip()
            # Mem0 formats input as "role: content" — strip the role prefix.
            if ": " in user_text:
                user_text = user_text.split(": ", 1)[1]

            self._last_facts = [user_text] if user_text else []
            return ChatMessage(
                role=MessageRole.ASSISTANT,
                content=json.dumps({"facts": self._last_facts}),
            )

        # Call 2: Memory update — return ADD for each extracted fact.
        memory_ops = [{"text": fact, "event": "ADD"} for fact in self._last_facts]
        return ChatMessage(
            role=MessageRole.ASSISTANT,
            content=json.dumps({"memory": memory_ops}),
        )


class MockEmbeddingModelSetup:
    """Mock embedding model that returns a fixed-dimension vector."""

    def embed(self, text):
        # Return a deterministic embedding based on text hash
        import hashlib

        h = hashlib.md5(text.encode()).hexdigest()
        # Create a 384-dim vector from the hash
        vector = []
        for i in range(384):
            idx = i % len(h)
            vector.append(float(int(h[idx], 16)) / 15.0)
        return vector


def _build_vector_store(backend: str, tmpdir: str) -> CollectionManageableVectorStore:
    """Build the backing vector store for Mem0 LTM.

    - ``"chroma"`` — the flink-agents native :class:`ChromaVectorStore`.
    - ``"mem0"`` — the reverse adapter :class:`Mem0VectorStore` wrapping
      Mem0's native chroma provider. Exercises the double-wrap path
      (Mem0 → FlinkAgentsMem0VectorStore → Mem0VectorStore → Mem0 chroma).
    """
    if backend == "chroma":
        return ChromaVectorStore(
            embedding_model="test_embedding_model",
            persist_directory=tmpdir,
            collection="test_mem0",
        )
    if backend == "mem0":
        return Mem0VectorStore(
            provider="chroma",
            provider_config={"path": tmpdir},
            collection="test_mem0",
        )
    msg = f"Unknown backend: {backend}"
    raise ValueError(msg)


@pytest.fixture(scope="module", params=["chroma", "mem0"])
def mock_ctx(request):
    ctx = create_autospec(RunnerContext, instance=True)
    ctx.config = MagicMock()
    ctx.config.get = MagicMock(side_effect=lambda opt: None)

    chat_model = MockChatModelSetup()
    embedding_model = MockEmbeddingModelSetup()
    vector_store = _build_vector_store(request.param, tempfile.mkdtemp())

    def get_resource(name: str, resource_type: Any, **kwargs: Any) -> Any:
        if resource_type == ResourceType.CHAT_MODEL and name == "test_chat_model":
            return chat_model
        if (
            resource_type == ResourceType.EMBEDDING_MODEL
            and name == "test_embedding_model"
        ):
            return embedding_model
        if resource_type == ResourceType.VECTOR_STORE and name == "test_vector_store":
            return vector_store
        return None

    ctx.get_resource = get_resource
    return ctx


@pytest.fixture(scope="module")
def ltm(mock_ctx):
    mem0_ltm = Mem0LongTermMemory(
        ctx=mock_ctx,
        job_id="test_job_001",
        chat_model_name="test_chat_model",
        embedding_model_name="test_embedding_model",
        vector_store_name="test_vector_store",
    )
    return mem0_ltm


def test_get_memory_set(ltm) -> None:
    memory_set = ltm.get_memory_set(name="test_set")
    assert memory_set.name == "test_set"

    # Getting the same name should return the same set
    memory_set2 = ltm.get_memory_set(name="test_set")
    assert memory_set2.name == memory_set.name


def test_add_and_get(ltm) -> None:
    memory_set = ltm.get_memory_set(name="add_get_set")

    ids = memory_set.add(items="The user prefers Python over Java.")
    assert len(ids) > 0

    # Get all items and verify content
    items = memory_set.get()
    assert len(items) > 0
    assert all(isinstance(item, MemorySetItem) for item in items)
    values = {item.value for item in items}
    assert "The user prefers Python over Java." in values

    # Get by id
    item = memory_set.get(ids=ids[0])
    assert len(item) == 1
    assert item[0].id == ids[0]
    assert item[0].value == "The user prefers Python over Java."


def test_add_multiple_items(ltm) -> None:
    memory_set = ltm.get_memory_set(name="multi_add_set")

    ids = memory_set.add(
        items=[
            "User likes coffee in the morning.",
            "User works from home on Fridays.",
        ],
    )
    assert len(ids) > 0

    items = memory_set.get()
    values = {item.value for item in items}
    assert "User likes coffee in the morning." in values
    assert "User works from home on Fridays." in values


def test_search(ltm) -> None:
    memory_set = ltm.get_memory_set(name="search_set")

    memory_set.add(items="The capital of France is Paris.")

    results = memory_set.search(
        query="What is the capital of France?",
        limit=5,
    )
    assert isinstance(results, list)
    assert len(results) > 0
    assert all(isinstance(item, MemorySetItem) for item in results)
    values = {item.value for item in results}
    assert "The capital of France is Paris." in values


def test_get_with_filters(ltm) -> None:
    memory_set = ltm.get_memory_set(name="get_filters_set")

    memory_set.add(
        items="Important meeting tomorrow.",
        metadatas={"category": "work"},
    )
    memory_set.add(
        items="Buy groceries after work.",
        metadatas={"category": "personal"},
    )

    # Filter by category
    work_items = memory_set.get(filters={"category": "work"})
    assert len(work_items) > 0
    assert all(
        item.additional_metadata.get("category") == "work" for item in work_items
    )

    personal_items = memory_set.get(filters={"category": "personal"})
    assert len(personal_items) > 0
    assert all(
        item.additional_metadata.get("category") == "personal"
        for item in personal_items
    )


def test_search_with_filters(ltm) -> None:
    memory_set = ltm.get_memory_set(name="search_filters_set")

    memory_set.add(
        items="Python is a great language for data science.",
        metadatas={"topic": "programming"},
    )
    memory_set.add(
        items="The weather in Paris is lovely in spring.",
        metadatas={"topic": "travel"},
    )

    # Search with filter should only return matching topic
    results = memory_set.search(
        query="programming language",
        limit=5,
        filters={"topic": "programming"},
    )
    assert len(results) > 0
    assert all(
        item.additional_metadata.get("topic") == "programming" for item in results
    )


def test_delete_by_id(ltm) -> None:
    memory_set = ltm.get_memory_set(name="delete_id_set")

    ids = memory_set.add(items="Memory to delete.")

    if ids:
        memory_set.delete(ids=ids[0])
        items = memory_set.get()
        deleted_ids = {item.id for item in items}
        assert ids[0] not in deleted_ids


def test_delete_all(ltm) -> None:
    memory_set = ltm.get_memory_set(name="delete_all_set")

    memory_set.add(items="Memory 1")
    memory_set.add(items="Memory 2")

    memory_set.delete()
    items = memory_set.get()
    assert len(items) == 0


def test_delete_memory_set(ltm) -> None:
    memory_set = ltm.get_memory_set(name="to_delete_set")
    memory_set.add(items="Some content")

    result = ltm.delete_memory_set(name="to_delete_set")
    assert result is True

    # After deletion, get_memory_set creates a fresh empty set
    new_set = ltm.get_memory_set(name="to_delete_set")
    assert len(new_set.get()) == 0


def test_switch_context(ltm) -> None:
    ltm.switch_context("key_a")
    memory_set = ltm.get_memory_set(name="context_set")
    memory_set.add(items="Data for key_a")

    ltm.switch_context("key_b")
    # key_b should have no items in the same memory set name
    items = memory_set.get()
    # Items from key_a should not be visible under key_b
    # (They have different agent_id scoping)
    assert len(items) == 0

    # Reset context
    ltm.switch_context("")


class MockChatModelWithTokenUsage:
    """Mock chat model that returns token usage in extra_args."""

    def __init__(self) -> None:
        self._last_facts: list[str] = []
        self._call_count = 0

    def chat(self, messages, **kwargs: Any):
        import json

        self._call_count += 1

        has_system_msg = any(
            isinstance(msg, ChatMessage) and msg.role == MessageRole.SYSTEM
            for msg in messages
        )

        extra_args = {
            "model_name": "mock-model",
            "promptTokens": 10,
            "completionTokens": 5,
        }

        if has_system_msg:
            user_msg = next(
                (
                    msg
                    for msg in messages
                    if isinstance(msg, ChatMessage) and msg.role == MessageRole.USER
                ),
                None,
            )
            user_text = user_msg.content if user_msg else ""
            if "Input:" in user_text:
                user_text = user_text.split("Input:", 1)[1].strip()
            if ": " in user_text:
                user_text = user_text.split(": ", 1)[1]

            self._last_facts = [user_text] if user_text else []
            return ChatMessage(
                role=MessageRole.ASSISTANT,
                content=json.dumps({"facts": self._last_facts}),
                extra_args=extra_args,
            )

        memory_ops = [{"text": fact, "event": "ADD"} for fact in self._last_facts]
        return ChatMessage(
            role=MessageRole.ASSISTANT,
            content=json.dumps({"memory": memory_ops}),
            extra_args=extra_args,
        )


class MockMetricGroup:
    """Mock Flink MetricGroup that records counter increments."""

    def __init__(self) -> None:
        self._sub_groups: dict[str, MockMetricGroup] = {}
        self._counters: dict[str, int] = {}

    def get_sub_group(self, name: str) -> "MockMetricGroup":
        if name not in self._sub_groups:
            self._sub_groups[name] = MockMetricGroup()
        return self._sub_groups[name]

    def get_counter(self, name: str) -> "MockCounter":
        return MockCounter(self, name)


class MockCounter:
    def __init__(self, group: MockMetricGroup, name: str) -> None:
        self._group = group
        self._name = name

    def inc(self, value: int) -> None:
        self._group._counters[self._name] = (
            self._group._counters.get(self._name, 0) + value
        )


def test_token_usage_reported_on_switch_context() -> None:
    """Token usage should be deferred until switch_context (mailbox thread)."""
    metric_group = MockMetricGroup()

    ctx = create_autospec(RunnerContext, instance=True)
    ctx.config = MagicMock()
    ctx.config.get = MagicMock(side_effect=lambda opt: None)
    ctx.agent_metric_group = metric_group

    chat_model = MockChatModelWithTokenUsage()
    embedding_model = MockEmbeddingModelSetup()
    tmpdir = tempfile.mkdtemp()
    vector_store = ChromaVectorStore(
        embedding_model="test_embedding_model",
        persist_directory=tmpdir,
        collection="test_token_mem0",
    )

    def get_resource(name: str, resource_type: Any, **kwargs: Any) -> Any:
        if resource_type == ResourceType.CHAT_MODEL and name == "test_chat_model":
            return chat_model
        if (
            resource_type == ResourceType.EMBEDDING_MODEL
            and name == "test_embedding_model"
        ):
            return embedding_model
        if resource_type == ResourceType.VECTOR_STORE and name == "test_vector_store":
            return vector_store
        return None

    ctx.get_resource = get_resource

    ltm = Mem0LongTermMemory(
        ctx=ctx,
        job_id="test_token_job",
        chat_model_name="test_chat_model",
        embedding_model_name="test_embedding_model",
        vector_store_name="test_vector_store",
    )

    # First switch_context triggers lazy init; no metrics yet.
    ltm.switch_context("key_1")

    memory_set = ltm.get_memory_set(name="token_test_set")
    memory_set.add(items="Test content for token tracking.")

    # Metrics are still in the queue — not reported until switch_context.
    ltm_group = metric_group.get_sub_group("long-term-memory")
    model_group = ltm_group.get_sub_group("mock-model")
    assert model_group._counters.get("promptTokens", 0) == 0

    # switch_context drains the queue on the mailbox thread.
    ltm.switch_context("key_2")

    # Mem0 makes 2 LLM calls per add (fact extraction + memory update).
    # Each call returns 10 prompt tokens and 5 completion tokens.
    assert model_group._counters.get("promptTokens", 0) == 20
    assert model_group._counters.get("completionTokens", 0) == 10


def test_token_usage_flushed_on_close() -> None:
    """Remaining token metrics should be flushed when close() is called."""
    metric_group = MockMetricGroup()

    ctx = create_autospec(RunnerContext, instance=True)
    ctx.config = MagicMock()
    ctx.config.get = MagicMock(side_effect=lambda opt: None)
    ctx.agent_metric_group = metric_group

    chat_model = MockChatModelWithTokenUsage()
    embedding_model = MockEmbeddingModelSetup()
    tmpdir = tempfile.mkdtemp()
    vector_store = ChromaVectorStore(
        embedding_model="test_embedding_model",
        persist_directory=tmpdir,
        collection="test_close_mem0",
    )

    def get_resource(name: str, resource_type: Any, **kwargs: Any) -> Any:
        if resource_type == ResourceType.CHAT_MODEL and name == "test_chat_model":
            return chat_model
        if (
            resource_type == ResourceType.EMBEDDING_MODEL
            and name == "test_embedding_model"
        ):
            return embedding_model
        if resource_type == ResourceType.VECTOR_STORE and name == "test_vector_store":
            return vector_store
        return None

    ctx.get_resource = get_resource

    ltm = Mem0LongTermMemory(
        ctx=ctx,
        job_id="test_close_job",
        chat_model_name="test_chat_model",
        embedding_model_name="test_embedding_model",
        vector_store_name="test_vector_store",
    )

    ltm.switch_context("key_1")

    memory_set = ltm.get_memory_set(name="close_test_set")
    memory_set.add(items="First item.")
    memory_set.add(items="Second item.")

    # close() flushes all remaining metrics.
    ltm.close()

    ltm_group = metric_group.get_sub_group("long-term-memory")
    model_group = ltm_group.get_sub_group("mock-model")
    # 2 adds x 2 LLM calls each x 10 prompt tokens = 40
    assert model_group._counters.get("promptTokens", 0) == 40
    # 2 adds x 2 LLM calls each x 5 completion tokens = 20
    assert model_group._counters.get("completionTokens", 0) == 20
