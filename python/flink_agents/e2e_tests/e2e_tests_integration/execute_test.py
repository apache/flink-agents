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
"""E2E tests for durable_execute() method in Flink execution environment."""

import os
import sysconfig
from pathlib import Path

from pyflink.common import Configuration, Encoder, WatermarkStrategy
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
from flink_agents.e2e_tests.e2e_tests_integration.execute_test_agent import (
    ExecuteMultipleTestAgent,
    ExecuteTestAgent,
    ExecuteTestData,
    ExecuteTestKeySelector,
    ExecuteWithAsyncExceptionTestAgent,
    ExecuteWithAsyncTestAgent,
)
from flink_agents.e2e_tests.test_utils import check_result

current_dir = Path(__file__).parent

os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"]


def test_durable_execute_basic_flink(tmp_path: Path) -> None:
    """Test basic synchronous durable_execute() functionality in Flink environment."""
    config = Configuration()
    config.set_string("state.backend.type", "rocksdb")
    config.set_string("checkpointing.interval", "1s")
    config.set_string("restart-strategy.type", "disable")
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)

    input_datastream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(),
            f"file:///{current_dir}/../resources/execute_test_input",
        ).build(),
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="execute_test_source",
    )

    deserialize_datastream = input_datastream.map(
        lambda x: ExecuteTestData.model_validate_json(x)
    )

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    output_datastream = (
        agents_env.from_datastream(
            input=deserialize_datastream, key_selector=ExecuteTestKeySelector()
        )
        .apply(ExecuteTestAgent())
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

    check_result(
        result_dir=result_dir,
        groud_truth_dir=Path(
            f"{current_dir}/../resources/ground_truth/test_execute_basic.txt"
        ),
    )


def test_durable_execute_multiple_calls_flink(tmp_path: Path) -> None:
    """Test multiple durable_execute() calls in Flink environment."""
    config = Configuration()
    config.set_string("state.backend.type", "rocksdb")
    config.set_string("checkpointing.interval", "1s")
    config.set_string("restart-strategy.type", "disable")
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)

    input_datastream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(),
            f"file:///{current_dir}/../resources/execute_test_input",
        ).build(),
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="execute_test_source",
    )

    deserialize_datastream = input_datastream.map(
        lambda x: ExecuteTestData.model_validate_json(x)
    )

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    output_datastream = (
        agents_env.from_datastream(
            input=deserialize_datastream, key_selector=ExecuteTestKeySelector()
        )
        .apply(ExecuteMultipleTestAgent())
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

    check_result(
        result_dir=result_dir,
        groud_truth_dir=Path(
            f"{current_dir}/../resources/ground_truth/test_execute_multiple.txt"
        ),
    )


def test_durable_execute_with_async_flink(tmp_path: Path) -> None:
    """Test durable_execute() and durable_execute_async() in Flink environment."""
    config = Configuration()
    config.set_string("state.backend.type", "rocksdb")
    config.set_string("checkpointing.interval", "1s")
    config.set_string("restart-strategy.type", "disable")
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)

    input_datastream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(),
            f"file:///{current_dir}/../resources/execute_test_input",
        ).build(),
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="execute_test_source",
    )

    deserialize_datastream = input_datastream.map(
        lambda x: ExecuteTestData.model_validate_json(x)
    )

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    output_datastream = (
        agents_env.from_datastream(
            input=deserialize_datastream, key_selector=ExecuteTestKeySelector()
        )
        .apply(ExecuteWithAsyncTestAgent())
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

    check_result(
        result_dir=result_dir,
        groud_truth_dir=Path(
            f"{current_dir}/../resources/ground_truth/test_execute_with_async.txt"
        ),
    )


def test_durable_execute_async_exception_flink(tmp_path: Path) -> None:
    """Test durable_execute_async() exception handling in Flink environment."""
    config = Configuration()
    config.set_string("state.backend.type", "rocksdb")
    config.set_string("checkpointing.interval", "1s")
    config.set_string("restart-strategy.type", "disable")
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)

    input_datastream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(),
            f"file:///{current_dir}/../resources/execute_test_input",
        ).build(),
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="execute_test_source",
    )

    deserialize_datastream = input_datastream.map(
        lambda x: ExecuteTestData.model_validate_json(x)
    )

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    output_datastream = (
        agents_env.from_datastream(
            input=deserialize_datastream, key_selector=ExecuteTestKeySelector()
        )
        .apply(ExecuteWithAsyncExceptionTestAgent())
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

    check_result(
        result_dir=result_dir,
        groud_truth_dir=Path(
            f"{current_dir}/../resources/ground_truth/test_execute_async_exception.txt"
        ),
    )

