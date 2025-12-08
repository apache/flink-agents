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
from pathlib import Path

import pytest
from pyflink.common import Encoder, WatermarkStrategy
from pyflink.common.typeinfo import Types
from pyflink.datastream import (
    RuntimeExecutionMode,
    StreamExecutionEnvironment,
)
from pyflink.datastream.connectors.file_system import (
    FileSource,
    StreamFormat,
    StreamingFileSink,
)

from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.e2e_tests.chat_model_integration_agent import ChatModelTestAgent
from flink_agents.e2e_tests.test_utils import pull_model

current_dir = Path(__file__).parent

TONGYI_MODEL = os.environ.get("TONGYI_CHAT_MODEL", "qwen-plus")
os.environ["TONGYI_CHAT_MODEL"] = TONGYI_MODEL
OLLAMA_MODEL = os.environ.get("OLLAMA_CHAT_MODEL", "qwen3:1.7b")
os.environ["OLLAMA_CHAT_MODEL"] = OLLAMA_MODEL
OPENAI_MODEL = os.environ.get("OPENAI_CHAT_MODEL", "gpt-3.5-turbo")
os.environ["OPENAI_CHAT_MODEL"] = OPENAI_MODEL

DASHSCOPE_API_KEY = os.environ.get("DASHSCOPE_API_KEY")
OPENAI_API_KEY = os.environ.get("OPENAI_API_KEY")

client = pull_model(OLLAMA_MODEL)


@pytest.mark.parametrize(
    "model_provider",
    [
        pytest.param(
            "Ollama",
            marks=pytest.mark.skipif(
                client is None,
                reason="Ollama client is not available or test model is missing.",
            ),
        ),
        pytest.param(
            "Tongyi",
            marks=pytest.mark.skipif(
                DASHSCOPE_API_KEY is None, reason="Tongyi api key is not set."
            ),
        ),
        pytest.param(
            "OpenAI",
            marks=pytest.mark.skipif(
                OPENAI_API_KEY is None, reason="OpenAI api key is not set."
            ),
        ),
    ],
)
def test_chat_model_integration(model_provider: str) -> None:  # noqa: D103
    os.environ["MODEL_PROVIDER"] = model_provider
    env = AgentsExecutionEnvironment.get_execution_environment()
    input_list = []
    agent = ChatModelTestAgent()

    output_list = env.from_list(input_list).apply(agent).to_list()

    input_list.append({"key": "0001", "value": "calculate the sum of 1 and 2."})
    input_list.append({"key": "0002", "value": "Tell me a joke about cats."})

    env.execute()

    for output in output_list:
        for key, value in output.items():
            print(f"{key}: {value}")

    assert "3" in output_list[0]["0001"]
    assert "cat" in output_list[1]["0002"]

@pytest.mark.skipif(client is None, reason="Ollama client is not available or test model is missing.")
def test_java_chat_model_integration(tmp_path: Path) -> None:  # noqa: D103
    os.environ["MODEL_PROVIDER"] = "JAVA"
    env = StreamExecutionEnvironment.get_execution_environment()
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)

    # currently, bounded source is not supported due to runtime implementation, so
    # we use continuous file source here.
    input_datastream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(), f"file:///{current_dir}/resources/java_chat_module_input"
        ).build(),
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="streaming_agent_example",
    )

    deserialize_datastream = input_datastream.map(
        lambda x: str(x)
    )

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    output_datastream = (
        agents_env.from_datastream(
            input=deserialize_datastream, key_selector= lambda x: "orderKey"
        )
        .apply(ChatModelTestAgent())
        .to_datastream()
    )

    result_dir = tmp_path / "results"
    result_dir.mkdir(parents=True, exist_ok=True)

    (output_datastream.map(lambda x: str(x).replace('\n', '')
                          .replace('\r', ''), Types.STRING()).add_sink(
        StreamingFileSink.for_row_format(
            base_path=str(result_dir.absolute()),
            encoder=Encoder.simple_string_encoder(),
        ).build()
    ))

    agents_env.execute()

    actual_result = []
    for file in result_dir.iterdir():
        if file.is_dir():
            for child in file.iterdir():
                with child.open() as f:
                    actual_result.extend(f.readlines())
        if file.is_file():
            with file.open() as f:
                actual_result.extend(f.readlines())

    assert "3" in actual_result[0]
    assert "cat" in actual_result[1]
