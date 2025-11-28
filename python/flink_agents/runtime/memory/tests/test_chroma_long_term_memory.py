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
import os
import signal
import subprocess
import tempfile
import time
from pathlib import Path
from typing import TYPE_CHECKING, Generator, List
from unittest.mock import create_autospec

import pytest

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.memory.long_term_memory import (
    MemorySet,
    ReduceSetup,
)
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.integrations.chat_models.ollama_chat_model import (
    OllamaChatModelConnection,
    OllamaChatModelSetup,
)
from flink_agents.integrations.embedding_models.local.ollama_embedding_model import (
    OllamaEmbeddingModelConnection,
    OllamaEmbeddingModelSetup,
)
from flink_agents.runtime.memory.chroma_long_term_memory import ChromaLongTermMemory

if TYPE_CHECKING:
    from flink_agents.api.memory.long_term_memory import (
        MemorySetItem,
    )

current_dir = Path(__file__).parent


@pytest.fixture(scope="module")
def start_chroma() -> Generator:  # noqa: D103
    chromadb_path = tempfile.mkdtemp()
    print(f"Starting ChromaDB in {chromadb_path}...")
    process = subprocess.Popen(
        ["bash", f"{current_dir}/start_chroma_server.sh", chromadb_path],
        preexec_fn=os.setsid,
    )
    time.sleep(10)
    yield
    # clean up running chroma process
    os.killpg(os.getpgid(process.pid), signal.SIGTERM)


@pytest.fixture(scope="module")
def long_term_memory() -> ChromaLongTermMemory:  # noqa: D103
    embedding_model_connection = OllamaEmbeddingModelConnection()

    def get_embed_connection(name: str, type: ResourceType) -> Resource:
        return embedding_model_connection

    embedding_model = OllamaEmbeddingModelSetup(
        get_resource=get_embed_connection,
        connection="embedding_model_connection",
        model="nomic-embed-text",
    )

    chat_model_connection = OllamaChatModelConnection()

    def get_chat_connection(name: str, type: ResourceType) -> Resource:
        return chat_model_connection

    chat_model = OllamaChatModelSetup(
        get_resource=get_chat_connection,
        connection="chat_model_connection",
        model="qwen3:8b",
    )

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.CHAT_MODEL:
            return chat_model
        else:
            return embedding_model

    mock_runner_context = create_autospec(RunnerContext, instance=True)
    mock_runner_context.get_resource = get_resource

    use_ollama = os.environ.get("USE_OLLAMA")

    return ChromaLongTermMemory(
        runner_context=mock_runner_context,
        database="bc0b2ad61ecd4a615d92ce25390f61ad.00001",
        embedding_model="embedding_model" if use_ollama else None,
        host="localhost",
        port=8000,
    )


def prepare_memory_set(  # noqa: D103
    long_term_memory: ChromaLongTermMemory,
    reduce_setup: ReduceSetup = ReduceSetup.trim_setup(10),  # noqa:B008
) -> (MemorySet, List[ChatMessage]):
    memory_set: MemorySet = long_term_memory.create_memory_set(
        name="chat_history",
        item_type=ChatMessage,
        capacity=100,
        reduce_setup=reduce_setup,
    )

    msgs: List[ChatMessage] = []
    for i in range(20):
        msg = ChatMessage(role=MessageRole.USER, content=f"This is the no.{i} message.")
        msgs.append(msg)
        memory_set.add(item=msg)

    return memory_set, msgs


def test_get_memory_set(  # noqa:D103
    start_chroma: Generator, long_term_memory: ChromaLongTermMemory
) -> None:
    memory_set, _ = prepare_memory_set(long_term_memory)
    retrieved = long_term_memory.get_memory_set(memory_set.name)
    assert retrieved == memory_set

    long_term_memory.delete_memory_set(name="chat_history")


def test_add_and_get(  # noqa:D103
    start_chroma: Generator, long_term_memory: ChromaLongTermMemory
) -> None:
    memory_set, msgs = prepare_memory_set(long_term_memory)

    retrieved: List[MemorySetItem] = memory_set.get()
    retrieved_msgs = [item.value for item in retrieved]

    assert retrieved_msgs == msgs

    long_term_memory.delete_memory_set(name="chat_history")


def test_get_recent_n(  # noqa:D103
    start_chroma: Generator, long_term_memory: ChromaLongTermMemory
) -> None:
    memory_set, msgs = prepare_memory_set(long_term_memory)

    retrieved: List[MemorySetItem] = memory_set.get_recent(10)
    retrieved_msgs = [item.value for item in retrieved]

    assert retrieved_msgs == msgs[10:]

    long_term_memory.delete_memory_set(name="chat_history")


def test_search(  # noqa:D103
    start_chroma: Generator, long_term_memory: ChromaLongTermMemory
) -> None:
    memory_set, msgs = prepare_memory_set(long_term_memory)

    retrieved: List[MemorySetItem] = memory_set.search(
        query="The no.10 message", limit=1
    )
    retrieved_msgs = [item.value for item in retrieved]

    assert retrieved_msgs == msgs[10:11]

    long_term_memory.delete_memory_set(name="chat_history")


def test_reduce_trim(  # noqa:D103
    start_chroma: Generator, long_term_memory: ChromaLongTermMemory
) -> None:
    memory_set, _ = prepare_memory_set(long_term_memory)

    msgs: List[ChatMessage] = []

    for i in range(100):
        msg = ChatMessage(
            role=MessageRole.USER, content=f"This is the no.{i + 20} message."
        )
        msgs.append(msg)
        memory_set.add(item=msg)

    retrieved: List[MemorySetItem] = memory_set.get()
    retrieved_msgs = [item.value for item in retrieved]

    assert retrieved_msgs == msgs

    long_term_memory.delete_memory_set(name="chat_history")


@pytest.mark.skip("Depend on ollama server")
def test_reduce_summarize(  # noqa:D103
    start_chroma: Generator, long_term_memory: ChromaLongTermMemory
) -> None:
    memory_set, _ = prepare_memory_set(
        long_term_memory,
        reduce_setup=ReduceSetup.summarize_setup(n=20, model="chat_model"),
    )

    msgs: List[ChatMessage] = []

    for i in range(100):
        msg = ChatMessage(
            role=MessageRole.USER, content=f"This is the no.{i + 20} message."
        )
        msgs.append(msg)
        memory_set.add(item=msg)

    retrieved: List[MemorySetItem] = memory_set.get()
    retrieved_msgs = [item.value for item in retrieved]

    assert retrieved[0].compacted
    assert retrieved[0].created_time.start < retrieved[0].created_time.end
    assert memory_set.size == 82
    assert len(retrieved_msgs) == 82
    assert retrieved_msgs[1:] == msgs[19:]

    long_term_memory.delete_memory_set(name="chat_history")


def test_update_metadata(  # noqa:D103
    start_chroma: Generator, long_term_memory: ChromaLongTermMemory
) -> None:
    memory_set, msgs = prepare_memory_set(long_term_memory)

    memory_set.search(query="The no.10 message", limit=1)
    retrieved: List[MemorySetItem] = memory_set.search(
        query="The no.10 message", limit=1
    )
    item = retrieved[0]

    assert item.last_accessed_time > item.created_time.start
