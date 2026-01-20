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
import tempfile
from pathlib import Path
from typing import TYPE_CHECKING, Any, Dict, List
from unittest.mock import create_autospec

import pytest
from chromadb import EmbeddingFunction
from chromadb.utils import embedding_functions
from pydantic import ConfigDict

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.memory.long_term_memory import (
    CompactionStrategy,
    DatetimeRange,
    MemorySet,
    SummarizationStrategy,
)
from flink_agents.api.metric_group import MetricGroup
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
from flink_agents.integrations.vector_stores.chroma.chroma_vector_store import (
    ChromaVectorStore,
)
from flink_agents.runtime.memory.vector_store_long_term_memory import (
    VectorStoreLongTermMemory,
)

if TYPE_CHECKING:
    from flink_agents.api.memory.long_term_memory import (
        MemorySetItem,
    )

current_dir = Path(__file__).parent


class MockEmbeddingModel(Resource):  # noqa: D101
    model_config = ConfigDict(arbitrary_types_allowed=True)
    ef: EmbeddingFunction = embedding_functions.DefaultEmbeddingFunction()

    @classmethod
    def resource_type(cls) -> ResourceType:  # noqa: D102
        return ResourceType.EMBEDDING_MODEL

    @property
    def model_kwargs(self) -> Dict[str, Any]:  # noqa: D102
        return {}

    def embed(self, text: str, **kwargs: Any) -> Any:  # noqa: D102
        return self.ef([text])[0]


@pytest.fixture(scope="module")
def long_term_memory() -> VectorStoreLongTermMemory:  # noqa: D103
    embedding_model_connection = OllamaEmbeddingModelConnection()

    chat_model_connection = OllamaChatModelConnection(request_timeout=240)

    use_ollama = os.environ.get("USE_OLLAMA")

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.CHAT_MODEL:
            return chat_model
        elif type == ResourceType.CHAT_MODEL_CONNECTION:
            return chat_model_connection
        elif type == ResourceType.EMBEDDING_MODEL:
            if use_ollama:
                return embedding_model
            else:
                return MockEmbeddingModel()
        elif type == ResourceType.EMBEDDING_MODEL_CONNECTION:
            return embedding_model_connection
        else:
            return vector_store

    chat_model = OllamaChatModelSetup(
        get_resource=get_resource, connection="chat_model_connection", model="qwen3:8b"
    )

    embedding_model = OllamaEmbeddingModelSetup(
        get_resource=get_resource,
        connection="embedding_model_connection",
        model="nomic-embed-text",
    )

    chromadb_path = tempfile.mkdtemp()
    print(f"Starting ChromaDB in {chromadb_path}...")

    vector_store = ChromaVectorStore(
        persist_directory=chromadb_path,
        embedding_model="embedding_model",
        get_resource=get_resource,
    )

    mock_runner_context = create_autospec(RunnerContext, instance=True)
    mock_runner_context.get_resource = get_resource
    mock_runner_context.agent_metric_group.get_sub_group.return_value = create_autospec(
        MetricGroup, instance=True
    )

    return VectorStoreLongTermMemory(
        ctx=mock_runner_context,
        vector_store="vector_store",
        job_id="bc0b2ad61ecd4a615d92ce25390f61ad",
        key="00001",
    )


def prepare_memory_set(  # noqa: D103
    long_term_memory: VectorStoreLongTermMemory,
    compaction_strategy: CompactionStrategy = SummarizationStrategy(model="llm"),  # noqa:B008
) -> (MemorySet, List[ChatMessage]):
    memory_set: MemorySet = long_term_memory.get_or_create_memory_set(
        name="chat_history",
        item_type=ChatMessage,
        capacity=100,
        compaction_strategy=compaction_strategy,
    )

    msgs: List[ChatMessage] = []
    for i in range(20):
        msg = ChatMessage(role=MessageRole.USER, content=f"This is the no.{i} message.")
        msgs.append(msg)

    memory_set.add(items=msgs)
    return memory_set, msgs


def test_get_memory_set(  # noqa:D103
    long_term_memory: VectorStoreLongTermMemory,
) -> None:
    memory_set, _ = prepare_memory_set(long_term_memory)
    retrieved = long_term_memory.get_memory_set(memory_set.name)
    assert retrieved == memory_set

    long_term_memory.delete_memory_set(name="chat_history")


def test_add_and_get(  # noqa:D103
    long_term_memory: VectorStoreLongTermMemory,
) -> None:
    memory_set, msgs = prepare_memory_set(long_term_memory)

    retrieved: List[MemorySetItem] = memory_set.get()
    retrieved_msgs = [item.value for item in retrieved]

    assert retrieved_msgs == msgs

    long_term_memory.delete_memory_set(name="chat_history")


def test_search(  # noqa:D103
    long_term_memory: VectorStoreLongTermMemory,
) -> None:
    memory_set, msgs = prepare_memory_set(long_term_memory)

    retrieved: List[MemorySetItem] = memory_set.search(
        query="The no.10 message", limit=1
    )
    retrieved_msgs = [item.value for item in retrieved]

    assert retrieved_msgs == msgs[10:11]

    long_term_memory.delete_memory_set(name="chat_history")


@pytest.mark.skip("Depend on ollama server")
def test_compact(  # noqa:D103
    long_term_memory: VectorStoreLongTermMemory,
) -> None:
    memory_set: MemorySet = long_term_memory.get_or_create_memory_set(
        name="chat_history",
        item_type=ChatMessage,
        capacity=8,
        compaction_strategy=SummarizationStrategy(model="llm", limit=2),
    )

    msgs: List[ChatMessage] = [
        ChatMessage(role=MessageRole.USER, content="What is flink?"),
        ChatMessage(
            role=MessageRole.ASSISTANT,
            content="Apache Flink is a framework and distributed processing engine for stateful computations over unbounded and bounded data streams. Flink has been designed to run in all common cluster environments, perform computations at in-memory speed and at any scale.",
        ),
        ChatMessage(role=MessageRole.USER, content="What is flink agents?"),
        ChatMessage(
            role=MessageRole.ASSISTANT,
            content="Apache Flink Agents is a brand-new sub-project from the Apache Flink community, providing an open-source framework for building event-driven streaming agents.",
        ),
        ChatMessage(
            role=MessageRole.USER, content="What's the whether tomorrow in london?"
        ),
        ChatMessage(
            role=MessageRole.ASSISTANT,
            content="",
            tool_calls=[
                {
                    "id": "186780f8-c79d-4159-83e3-f65859835b14",
                    "type": "function",
                    "function": {
                        "name": "get_weather",
                        "arguments": {"position": "london", "time": "tomorrow"},
                    },
                }
            ],
        ),
        ChatMessage(role=MessageRole.TOOL, content="snow"),
        ChatMessage(
            role=MessageRole.ASSISTANT, content="Tomorrow weather for london is snow."
        ),
    ]
    memory_set.add(items=msgs)

    retrieved: List[MemorySetItem] = memory_set.get()
    retrieved_msgs = [item.value for item in retrieved]

    """
    The retrieved messages likes:
        [ChatMessage(role= < MessageRole.USER: 'user' >, content = 'User inquire about
            Apache Flink and Flink Agents, and the assistant explains Flink as a stream
            processing framework and Flink Agents as its sub-project.',
            tool_calls = [], extra_args = {}),
        ChatMessage(role= < MessageRole.USER: 'user' >, content = "User ask for London's
            weather tomorrow, llm invoke get_weather function, and the answer is snow.",
            tool_calls = [], extra_args = {})]
    The summarization quality is not stable on qwen3:8b.
    """

    assert len(retrieved_msgs) == 2
    assert retrieved[0].compacted
    assert retrieved[1].compacted
    assert isinstance(retrieved[0].created_time, DatetimeRange)
    assert isinstance(retrieved[1].created_time, DatetimeRange)
    assert memory_set.size == 2

    long_term_memory.delete_memory_set(name="chat_history")
