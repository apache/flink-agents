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
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, List

import pytest
from pydantic import BaseModel
from pyflink.common import Configuration, Encoder, Types, WatermarkStrategy
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
    LongTermMemoryOptions,
    MemorySetItem,
)
from flink_agents.api.resource import (
    ResourceDescriptor,
    ResourceName,
)
from flink_agents.api.runner_context import RunnerContext
from flink_agents.e2e_tests.test_utils import pull_model

current_dir = Path(__file__).parent

OLLAMA_EMBEDDING_MODEL = "nomic-embed-text"
pull_model(OLLAMA_EMBEDDING_MODEL)

MODEL = "qwen3.6-plus"
BASE_URL = os.environ.get("ACTION_BASE_URL", "https://coding.dashscope.aliyuncs.com/v1")
API_KEY = os.environ.get("ACTION_API_KEY")

# Env var used to hand ``chromadb``'s persist path to the agent-level
# ``@vector_store`` resource (which is a staticmethod and has no access
# to the test's pytest fixtures directly).
_CHROMADB_PATH_ENV = "MEM0_LTM_E2E_CHROMADB_PATH"


class ItemData(BaseModel):
    """Data model for storing item information.

    Attributes:
    ----------
    name : str
        Unique name of the user
    fact : str
        One fact about the user
    """

    name: str
    fact: str


class Record(BaseModel):
    name: str
    count: int
    timestamp_before_add: str
    timestamp_after_add: str
    timestamp_second_action: str | None = None
    items: List[MemorySetItem] | None = None


class MyEvent(Event):
    value: Any


class MyKeySelector(KeySelector):
    """KeySelector for extracting key."""

    def get_key(self, value: ItemData) -> str:
        """Extract key from ItemData."""
        return value.name


class LongTermMemoryAgent(Agent):
    """Agent used for testing long term memory ."""

    @chat_model_connection
    @staticmethod
    def openai_connection() -> ResourceDescriptor:
        """ChatModelConnection responsible for ollama model service connection."""
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OPENAI_COMPLETIONS_CONNECTION,
            api_key=API_KEY,
            api_base_url=BASE_URL,
            request_timeout=300,
        )

    @chat_model_setup
    @staticmethod
    def ollama_qwen3() -> ResourceDescriptor:
        """ChatModel which focus on math, and reuse ChatModelConnection."""
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OPENAI_COMPLETIONS_SETUP,
            connection="openai_connection",
            model=MODEL,
            extract_reasoning=True,
            think=False,
        )

    @embedding_model_connection
    @staticmethod
    def ollama_embedding_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.EmbeddingModel.OLLAMA_CONNECTION, request_timeout=240.0
        )

    @embedding_model_setup
    @staticmethod
    def ollama_nomic_embed_text() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.EmbeddingModel.OLLAMA_SETUP,
            connection="ollama_embedding_connection",
            model=OLLAMA_EMBEDDING_MODEL,
            think=False,
        )

    @vector_store
    @staticmethod
    def chroma_ltm_store() -> ResourceDescriptor:
        """ChromaDB vector store backing Mem0 LTM."""
        return ResourceDescriptor(
            clazz=ResourceName.VectorStore.CHROMA_VECTOR_STORE,
            embedding_model="ollama_nomic_embed_text",
            persist_directory=os.environ[_CHROMADB_PATH_ENV],
            collection="context",
        )

    @action(InputEvent)
    @staticmethod
    async def add_items(event: Event, ctx: RunnerContext) -> None:
        input_data: ItemData = event.input
        ltm = ctx.long_term_memory

        timestamp_before_add = datetime.now(timezone.utc).isoformat()
        memory_set = ltm.get_memory_set(name="test_ltm")
        await ctx.durable_execute_async(
            memory_set.add,
            items=f"{input_data.fact}",
        )
        timestamp_after_add = datetime.now(timezone.utc).isoformat()

        stm = ctx.short_term_memory
        count = stm.get("count") or 1
        stm.set("count", count + 1)

        ctx.send_event(
            MyEvent(
                value=Record(
                    name=input_data.name,
                    count=count,
                    timestamp_before_add=timestamp_before_add,
                    timestamp_after_add=timestamp_after_add,
                )
            )
        )

    @action(MyEvent)
    @staticmethod
    async def retrieve_items(event: Event, ctx: RunnerContext):  # noqa D102
        record: Record = event.value
        record.timestamp_second_action = datetime.now(timezone.utc).isoformat()
        memory_set = ctx.long_term_memory.get_memory_set(name="test_ltm")
        items = await ctx.durable_execute_async(memory_set.get)
        if (record.name == "alice" and record.count == 2) or (
            record.name == "bob" and record.count == 2
        ):
            record.items = items
        ctx.send_event(OutputEvent(output=record))


@pytest.mark.skipif(not API_KEY, reason="openai api key is required.")
def test_long_term_memory_async_execution_in_action(tmp_path: Path) -> None:
    chromadb_path = str(tmp_path / "chromadb")
    os.environ[_CHROMADB_PATH_ENV] = chromadb_path

    config = Configuration()
    config.set_string("python.pythonpath", sysconfig.get_paths()["purelib"])
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)

    input_datastream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(),
            f"file:///{current_dir}/../resources/long_term_memory_test",
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
    agents_config.set(LongTermMemoryOptions.Mem0.CHAT_MODEL_SETUP, "ollama_qwen3")
    agents_config.set(
        LongTermMemoryOptions.Mem0.EMBEDDING_MODEL_SETUP, "ollama_nomic_embed_text"
    )
    agents_config.set(LongTermMemoryOptions.Mem0.VECTOR_STORE, "chroma_ltm_store")

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


def check_result(*, result_dir: Path) -> None:
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
        records[f"{record.name}.{record.count}"] = record

    items = records["alice.2"].items
    # LLMs may treat different review comments as updates to the same
    # fact or as distinct facts.
    assert len(items) == 1
    item: MemorySetItem = items[-1]
    assert item.created_at < item.updated_at
    assert "bananas" in item.value

    # verify async add doesn't block process other key
    assert datetime.fromisoformat(
        records["alice.1"].timestamp_before_add
    ) < datetime.fromisoformat(records["bob.1"].timestamp_after_add)
