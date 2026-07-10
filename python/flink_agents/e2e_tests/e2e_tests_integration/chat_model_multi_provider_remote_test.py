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
from flink_agents.e2e_tests.e2e_tests_integration.chat_model_integration_agent import (
    ChatModelTestAgent,
)

current_dir = Path(__file__).parent

TONGYI_MODEL = os.environ.get("TONGYI_CHAT_MODEL", "qwen-plus")
OPENAI_MODEL = os.environ.get("OPENAI_CHAT_MODEL", "gpt-3.5-turbo")
AZURE_OPENAI_MODEL = os.environ.get("AZURE_OPENAI_CHAT_MODEL", "gpt-5")
AZURE_OPENAI_API_VERSION = os.environ.get(
    "AZURE_OPENAI_API_VERSION", "2025-04-01-preview"
)

DASHSCOPE_API_KEY = os.environ.get("DASHSCOPE_API_KEY")
OPENAI_API_KEY = os.environ.get("OPENAI_API_KEY")
AZURE_OPENAI_API_KEY = os.environ.get("AZURE_OPENAI_API_KEY")

os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"]


@pytest.mark.parametrize(
    "model_provider",
    [
        pytest.param(
            "Tongyi",
            marks=pytest.mark.skipif(
                not DASHSCOPE_API_KEY, reason="Tongyi api key is not set."
            ),
        ),
        pytest.param(
            "OpenAI",
            marks=pytest.mark.skipif(
                not OPENAI_API_KEY, reason="OpenAI api key is not set."
            ),
        ),
        pytest.param(
            "AzureOpenAI",
            marks=pytest.mark.skipif(
                not AZURE_OPENAI_API_KEY, reason="Azure OpenAI api key is not set."
            ),
        ),
    ],
)
def test_chat_model_integration_remote(
    model_provider: str, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Non-Ollama providers answer math and creative prompts on the remote path.

    Mirrors the from_list ``chat_model_integration_test`` for the three
    credential-gated providers (Tongyi/OpenAI/AzureOpenAI), but drives the agent
    through a real StreamExecutionEnvironment with a FileSource and file sink.
    Each parameter skips cleanly when its credential is absent.
    """
    monkeypatch.setenv("TONGYI_CHAT_MODEL", TONGYI_MODEL)
    monkeypatch.setenv("OPENAI_CHAT_MODEL", OPENAI_MODEL)
    monkeypatch.setenv("AZURE_OPENAI_CHAT_MODEL", AZURE_OPENAI_MODEL)
    monkeypatch.setenv("AZURE_OPENAI_API_VERSION", AZURE_OPENAI_API_VERSION)
    monkeypatch.setenv("MODEL_PROVIDER", model_provider)

    env = StreamExecutionEnvironment.get_execution_environment()
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)

    # currently, bounded source is not supported due to runtime implementation, so
    # we use continuous file source here.
    input_datastream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(),
            f"file:///{current_dir}/../resources/chat_model_multi_provider_input",
        ).build(),
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="chat_model_multi_provider_source",
    )

    deserialize_datastream = input_datastream.map(lambda x: str(x))

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    output_datastream = (
        agents_env.from_datastream(
            input=deserialize_datastream, key_selector=lambda x: x
        )
        .apply(ChatModelTestAgent())
        .to_datastream()
    )

    result_dir = tmp_path / "results"
    result_dir.mkdir(parents=True, exist_ok=True)

    output_datastream.map(
        lambda x: str(x).replace("\n", "").replace("\r", ""), Types.STRING()
    ).add_sink(
        StreamingFileSink.for_row_format(
            base_path=str(result_dir.absolute()),
            encoder=Encoder.simple_string_encoder(),
        ).build()
    )

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

    joined = "\n".join(actual_result).lower()
    assert "3" in joined, f"sum answer missing '3': {actual_result!r}"
    assert "cat" in joined, f"creative answer missing 'cat': {actual_result!r}"
