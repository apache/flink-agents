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
"""E2E tests running a deterministic mock chat model through the Flink runner."""

import json
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
from flink_agents.e2e_tests.e2e_tests_integration.mock_chat_model_agent import (
    BuiltInActionAgent,
    GetResourceAgent,
    MockChatModelInput,
    MockChatModelKeySelector,
)
from flink_agents.e2e_tests.test_utils import check_result

current_dir = Path(__file__).parent

os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"]


def test_built_in_chat_tool_action_content(tmp_path: Path) -> None:
    """Built-in chat/tool actions produce deterministic content with a mock model.

    Exercises the built-in ``chat_model_action`` and ``tool_call_action`` flow end
    to end on a real MiniCluster: prompt-template formatting, the ``add`` tool
    binding, and the final assembled content are all verified via the file sink.
    """
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
            f"file:///{current_dir}/../resources/mock_chat_model_built_in_input",
        ).build(),
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="mock_chat_model_built_in_source",
    )

    deserialize_datastream = input_datastream.map(
        lambda x: MockChatModelInput.model_validate_json(x)
    )

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    output_datastream = (
        agents_env.from_datastream(
            input=deserialize_datastream, key_selector=MockChatModelKeySelector()
        )
        .apply(BuiltInActionAgent())
        .to_datastream()
    )

    result_dir = tmp_path / "results"
    result_dir.mkdir(parents=True, exist_ok=True)

    output_datastream.map(lambda x: json.dumps(x), Types.STRING()).add_sink(
        StreamingFileSink.for_row_format(
            base_path=str(result_dir.absolute()),
            encoder=Encoder.simple_string_encoder(),
        ).build()
    )

    agents_env.execute()

    check_result(
        result_dir=result_dir,
        ground_truth_dir=Path(
            f"{current_dir}/../resources/ground_truth/"
            f"test_built_in_action_content.txt"
        ),
    )


def test_chat_model_get_resource_in_action(tmp_path: Path) -> None:
    """get_resource(CHAT_MODEL) in an action resolves custom descriptor fields.

    Verifies that a chat model resolved via ``ctx.get_resource`` inside an action
    carries the custom ``host``/``desc`` fields from its ResourceDescriptor through
    to the instance, and that those fields appear in the produced content.
    """
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
            f"file:///{current_dir}/../resources/mock_chat_model_get_resource_input",
        ).build(),
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="mock_chat_model_get_resource_source",
    )

    deserialize_datastream = input_datastream.map(
        lambda x: MockChatModelInput.model_validate_json(x)
    )

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    output_datastream = (
        agents_env.from_datastream(
            input=deserialize_datastream, key_selector=MockChatModelKeySelector()
        )
        .apply(GetResourceAgent())
        .to_datastream()
    )

    result_dir = tmp_path / "results"
    result_dir.mkdir(parents=True, exist_ok=True)

    output_datastream.map(lambda x: json.dumps(x), Types.STRING()).add_sink(
        StreamingFileSink.for_row_format(
            base_path=str(result_dir.absolute()),
            encoder=Encoder.simple_string_encoder(),
        ).build()
    )

    agents_env.execute()

    check_result(
        result_dir=result_dir,
        ground_truth_dir=Path(
            f"{current_dir}/../resources/ground_truth/"
            f"test_chat_model_get_resource.txt"
        ),
    )
