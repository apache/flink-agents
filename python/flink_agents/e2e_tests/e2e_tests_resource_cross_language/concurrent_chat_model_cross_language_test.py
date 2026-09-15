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
import sys
import sysconfig
from pathlib import Path

from pyflink.common import Encoder, WatermarkStrategy
from pyflink.common.typeinfo import Types
from pyflink.datastream import RuntimeExecutionMode, StreamExecutionEnvironment
from pyflink.datastream.connectors.file_system import (
    FileSource,
    StreamFormat,
    StreamingFileSink,
)

from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.e2e_tests.e2e_tests_resource_cross_language.concurrent_chat_model_cross_language_agent import (
    ConcurrentChatModelCrossLanguageAgent,
)

current_dir = Path(__file__).parent

os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"]


def test_concurrent_java_setup_with_python_connection(tmp_path: Path) -> None:
    """Run two overlapping chat requests through Java setup and Python connection."""
    env = StreamExecutionEnvironment.get_execution_environment()
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)
    env.set_python_executable(sys.executable)

    input_datastream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(),
            f"file:///{current_dir}/../resources/java_chat_module_input",
        ).build(),
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="concurrent_chat_inputs",
    ).map(lambda value: str(value))

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    agents_env.get_config().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 2)
    agents_env.get_config().set(AgentExecutionOptions.CHAT_ASYNC, True)
    output_datastream = (
        agents_env.from_datastream(
            input=input_datastream,
            key_selector=lambda value: value,
        )
        .apply(ConcurrentChatModelCrossLanguageAgent())
        .to_datastream()
    )

    result_dir = tmp_path / "results"
    result_dir.mkdir(parents=True, exist_ok=True)
    output_datastream.map(
        lambda value: str(value).replace("\n", "").replace("\r", ""),
        Types.STRING(),
    ).add_sink(
        StreamingFileSink.for_row_format(
            base_path=str(result_dir.absolute()),
            encoder=Encoder.simple_string_encoder(),
        ).build()
    )

    agents_env.execute()

    responses: list[str] = []
    for file in result_dir.iterdir():
        if file.is_dir():
            for child in file.iterdir():
                with child.open() as result_file:
                    responses.extend(line.strip() for line in result_file if line.strip())
        elif file.is_file():
            with file.open() as result_file:
                responses.extend(line.strip() for line in result_file if line.strip())

    assert len(responses) == 2
    assert set(responses) == {
        "python-connection:calculate the sum of 1 and 2.",
        "python-connection:Tell me a joke about cats.",
    }
