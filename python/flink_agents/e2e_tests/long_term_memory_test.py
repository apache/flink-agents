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
import sysconfig
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, List

from pydantic import BaseModel
from pyflink.common import Encoder, Types, WatermarkStrategy
from pyflink.datastream import (
    KeySelector,
    RuntimeExecutionMode,
    StreamExecutionEnvironment,
)
from pyflink.datastream.connectors.file_system import (
    FileSource,
    StreamFormat,
    StreamingFileSink,
)

from flink_agents.api.agents.agent import Agent
from flink_agents.api.core_options import AgentConfigOptions
from flink_agents.api.decorators import (
    action,
    chat_model_connection,
    chat_model_setup,
    embedding_model_connection,
    embedding_model_setup,
    vector_store,
)
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.memory.long_term_memory import (
    LongTermMemoryBackend,
    LongTermMemoryOptions,
    MemorySetItem,
    SummarizationStrategy,
)
from flink_agents.api.resource import ResourceDescriptor
from flink_agents.api.runner_context import RunnerContext
from flink_agents.e2e_tests.test_utils import pull_model
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

current_dir = Path(__file__).parent

os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"]

chromadb_path = tempfile.mkdtemp()

OLLAMA_CHAT_MODEL = "qwen3:8b"
OLLAMA_EMBEDDING_MODEL = "nomic-embed-text"
pull_model(OLLAMA_CHAT_MODEL)
pull_model(OLLAMA_EMBEDDING_MODEL)


class ItemData(BaseModel):
    """Data model for storing item information.

    Attributes:
    ----------
    id : int
        Unique identifier of the item
    review : str
        The user review of the item
    review_score: float
        The review_score of the item
    """

    id: int
    review: str
    review_score: float
    memory_info: dict | None = None


class Record(BaseModel):  # noqa: D101
    id: int
    count: int
    timestamp_before_add: str
    timestamp_after_add: str
    timestamp_second_action: str | None = None
    items: List[MemorySetItem] | None = None


class MyEvent(Event):  # noqa D101
    value: Any


class MyKeySelector(KeySelector):
    """KeySelector for extracting key."""

    def get_key(self, value: ItemData) -> int:
        """Extract key from ItemData."""
        return value.id


class LongTermMemoryAgent(Agent):
    """Agent used for testing long term memory ."""

    @chat_model_connection
    @staticmethod
    def ollama_connection() -> ResourceDescriptor:
        """ChatModelConnection responsible for ollama model service connection."""
        return ResourceDescriptor(
            clazz=OllamaChatModelConnection, request_timeout=240.0
        )

    @chat_model_setup
    @staticmethod
    def ollama_qwen3() -> ResourceDescriptor:
        """ChatModel which focus on math, and reuse ChatModelConnection."""
        return ResourceDescriptor(
            clazz=OllamaChatModelSetup,
            connection="ollama_connection",
            model=OLLAMA_CHAT_MODEL,
            extract_reasoning=True,
        )

    @embedding_model_connection
    @staticmethod
    def ollama_embedding_connection() -> ResourceDescriptor:  # noqa D102
        return ResourceDescriptor(
            clazz=OllamaEmbeddingModelConnection, request_timeout=240.0
        )

    @embedding_model_setup
    @staticmethod
    def ollama_nomic_embed_text() -> ResourceDescriptor:  # noqa D102
        return ResourceDescriptor(
            clazz=OllamaEmbeddingModelSetup,
            connection="ollama_embedding_connection",
            model=OLLAMA_EMBEDDING_MODEL,
        )

    @vector_store
    @staticmethod
    def chroma_vector_store() -> ResourceDescriptor:
        """Vector store setup for knowledge base."""
        return ResourceDescriptor(
            clazz=ChromaVectorStore,
            embedding_model="ollama_nomic_embed_text",
            persist_directory=chromadb_path,
        )

    @action(InputEvent)
    @staticmethod
    def add_items(event: Event, ctx: RunnerContext):  # noqa D102
        input_data = event.input
        ltm = ctx.long_term_memory

        timestamp_before_add = datetime.now(timezone.utc).isoformat()
        memory_set = ltm.get_or_create_memory_set(
            name="test_ltm",
            item_type=str,
            capacity=5,
            compaction_strategy=SummarizationStrategy(model="ollama_qwen3"),
        )
        yield from ctx.execute_async(memory_set.add, items=input_data.review)
        timestamp_after_add = datetime.now(timezone.utc).isoformat()

        stm = ctx.short_term_memory
        count = stm.get("count") or 1
        stm.set("count", count + 1)

        ctx.send_event(
            MyEvent(
                value=Record(
                    id=input_data.id,
                    count=count,
                    timestamp_before_add=timestamp_before_add,
                    timestamp_after_add=timestamp_after_add,
                )
            )
        )

    @action(MyEvent)
    @staticmethod
    def retrieve_items(event: Event, ctx: RunnerContext):  # noqa D102
        record: Record = event.value
        record.timestamp_second_action = datetime.now(timezone.utc).isoformat()
        memory_set = ctx.long_term_memory.get_memory_set(name="test_ltm")
        items = yield from ctx.execute_async(memory_set.get)
        if (
            (record.id == 1 and record.count == 3)
            or (record.id == 2 and record.count == 5)
            or (record.id == 3 and record.count == 2)
        ):
            record.items = items
        ctx.send_event(OutputEvent(output=record))


def test_long_term_memory_async_execution_in_action(tmp_path: Path) -> None:  # noqa: D103
    env = StreamExecutionEnvironment.get_execution_environment()
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)

    # currently, bounded source is not supported due to runtime implementation, so
    # we use continuous file source here.
    input_datastream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(), f"file:///{current_dir}/resources/input"
        ).build(),
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="streaming_agent_example",
    )

    deserialize_datastream = input_datastream.map(
        lambda x: ItemData.model_validate_json(x)
    )

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    agents_config = agents_env.get_config()
    agents_config.set(AgentConfigOptions.JOB_IDENTIFIER, "LTM_TEST_JOB")
    agents_config.set(
        LongTermMemoryOptions.BACKEND, LongTermMemoryBackend.EXTERNAL_VECTOR_STORE
    )
    agents_config.set(
        LongTermMemoryOptions.EXTERNAL_VECTOR_STORE_NAME, "chroma_vector_store"
    )
    agents_config.set(LongTermMemoryOptions.ASYNC_COMPACTION, True)

    output_datastream = (
        agents_env.from_datastream(
            input=deserialize_datastream, key_selector=MyKeySelector()
        )
        .apply(LongTermMemoryAgent())
        .to_datastream()
    )

    result_dir = tmp_path / "results"
    result_dir.mkdir(parents=True, exist_ok=True)

    output_datastream.map(lambda x: x.model_dump_json(), Types.STRING()).add_sink(
        StreamingFileSink.for_row_format(
            base_path=str(result_dir.absolute()),
            encoder=Encoder.simple_string_encoder(),
        ).build()
    )

    agents_env.execute()

    check_result(result_dir=result_dir)


def check_result(*, result_dir: Path) -> None:  # noqa: D103
    actual_result = []
    for file in result_dir.iterdir():
        if file.is_dir():
            for child in file.iterdir():
                with child.open() as f:
                    actual_result.extend(
                        [Record.model_validate_json(line) for line in f]
                    )

    records = {}
    for record in actual_result:
        records[f"{record.id}.{record.count}"] = record

    # verify async add doesn't block process other key
    assert datetime.fromisoformat(
        records["2.1"].timestamp_before_add
    ) < datetime.fromisoformat(records["1.1"].timestamp_after_add)
    assert datetime.fromisoformat(
        records["3.1"].timestamp_before_add
    ) < datetime.fromisoformat(records["1.1"].timestamp_after_add)

    # verify async compaction doesn't block any operation
    assert not records["2.5"].items[0].compacted
    store = ChromaVectorStore(
        persist_directory=chromadb_path, embedding_model="ollama_nomic_embed_text"
    )
    doc = store.get(collection_name="LTM_TEST_JOB--89360337-test_ltm")
    print(f"Retrieved items: {doc}")
    assert len(doc) == 1
    doc = doc[0]
    assert doc.metadata.get("compacted")
