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
import json
import os
import sysconfig
from collections import Counter
from pathlib import Path

import pytest
from pyflink.common import Configuration, Encoder, WatermarkStrategy
from pyflink.common.typeinfo import BasicTypeInfo, ExternalTypeInfo, RowTypeInfo, Types
from pyflink.datastream import (
    RuntimeExecutionMode,
    StreamExecutionEnvironment,
)
from pyflink.datastream.connectors.file_system import (
    FileSource,
    StreamFormat,
    StreamingFileSink,
)
from pyflink.table import DataTypes, Schema, StreamTableEnvironment, TableDescriptor
from pyflink.table.types import DataType

from flink_agents.api.core_options import AgentConfigOptions
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.e2e_tests.e2e_tests_integration.flink_integration_agent import (
    DataStreamAgent,
    DataStreamToTableAgent,
    ItemData,
    MyKeySelector,
    ScalarOutputAgent,
    TableAgent,
)
from flink_agents.e2e_tests.test_utils import check_result

current_dir = Path(__file__).parent

os.environ["PYTHONPATH"] = (
    f"{current_dir.parent.parent.parent}:{sysconfig.get_paths()['purelib']}"
)


@pytest.mark.parametrize(
    "runtime_mode", [RuntimeExecutionMode.BATCH, RuntimeExecutionMode.AUTOMATIC]
)
@pytest.mark.parametrize(
    ("batch_backend", "sorted_inputs"), [(True, True), (False, True), (False, False)]
)
def test_bounded_workflow_with_async_actions_and_keyed_memory(
    runtime_mode: RuntimeExecutionMode, batch_backend: bool, sorted_inputs: bool
) -> None:
    config = Configuration()
    config.set_string("restart-strategy.type", "disable")
    config.set_boolean("execution.batch-state-backend.enabled", batch_backend)
    config.set_boolean("execution.sorted-inputs.enabled", sorted_inputs)
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_runtime_mode(runtime_mode)
    env.set_parallelism(1)
    inputs = [
        ItemData(id=key, review="review", review_score=1.0)
        for key in [1, 2, 1, 2, 1, 2]
    ]
    agents_env = AgentsExecutionEnvironment.get_execution_environment(env)
    agents_env.get_config().set(AgentConfigOptions.TRACE_LOG_TARGETS, [])
    output = (
        agents_env.from_datastream(env.from_collection(inputs), MyKeySelector())
        .apply(DataStreamAgent())
        .to_datastream()
    )
    with output.execute_and_collect() as results:
        records = [ItemData.model_validate(result) for result in results]
    assert Counter(
        (record.id, record.memory_info["total_reviews"]) for record in records
    ) == Counter((key, count) for key in [1, 2] for count in [1, 2, 3])
    assert all(
        record.review
        == "review first action, log success=True, second action call my tool"
        for record in records
    )


@pytest.mark.parametrize("table_input", [False, True], ids=["datastream", "table"])
@pytest.mark.parametrize(
    ("values", "data_type"),
    [
        ([42, -7, 0, None], DataTypes.INT()),
        ([3.5, 0.0, None], DataTypes.DOUBLE()),
        ([True, False, None], DataTypes.BOOLEAN()),
        (["hello", "", None], DataTypes.STRING()),
    ],
    ids=["int", "float", "bool", "string"],
)
def test_scalar_output_to_single_column_table(
    values: list, data_type: DataType, table_input: bool
) -> None:
    config = Configuration()
    config.set_string("restart-strategy.type", "disable")
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)
    t_env = StreamTableEnvironment.create(env)
    agents_env = AgentsExecutionEnvironment.get_execution_environment(env, t_env)
    if table_input:
        source = t_env.from_elements([(value,) for value in values], ["value"])
        builder = agents_env.from_table(source, key_selector=lambda _: "key")
    else:
        source = env.from_collection([{"value": value} for value in values])
        builder = agents_env.from_datastream(source, key_selector=lambda _: "key")
    schema = Schema.new_builder().column("result", data_type).build()
    table = builder.apply(ScalarOutputAgent()).to_table(schema=schema)
    with table.execute().collect() as results:
        assert Counter(row[0] for row in results) == Counter(values)


def test_from_datastream_to_datastream(tmp_path: Path) -> None:
    config = Configuration()
    config.set_string("state.backend.type", "rocksdb")
    config.set_string("execution.checkpointing.interval", "1s")
    config.set_string("restart-strategy.type", "disable")
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)

    # currently, bounded source is not supported due to runtime implementation, so
    # we use continuous file source here.
    input_datastream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(), f"file:///{current_dir}/../resources/input"
        ).build(),
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="streaming_agent_example",
    )

    deserialize_datastream = input_datastream.map(
        lambda x: ItemData.model_validate_json(x)
    )

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    output_datastream = (
        agents_env.from_datastream(
            input=deserialize_datastream, key_selector=MyKeySelector()
        )
        .apply(DataStreamAgent())
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
            f"{current_dir}/../resources/ground_truth/test_from_datastream_to_datastream.txt"
        ),
    )


def test_from_table_to_table(tmp_path: Path) -> None:
    env = StreamExecutionEnvironment.get_execution_environment()

    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)

    t_env = StreamTableEnvironment.create(stream_execution_environment=env)

    t_env.create_temporary_table(
        "source",
        TableDescriptor.for_connector("filesystem")
        .schema(
            Schema.new_builder()
            .column("id", DataTypes.BIGINT())
            .column("review", DataTypes.STRING())
            .column("review_score", DataTypes.FLOAT())
            .build()
        )
        .option("format", "json")
        .option("path", f"file:///{current_dir}/../resources/input")
        .build(),
    )

    table = t_env.from_path("source")

    agents_env = AgentsExecutionEnvironment.get_execution_environment(
        env=env, t_env=t_env
    )

    output_type = ExternalTypeInfo(
        RowTypeInfo(
            [
                BasicTypeInfo.LONG_TYPE_INFO(),
                BasicTypeInfo.STRING_TYPE_INFO(),
                BasicTypeInfo.FLOAT_TYPE_INFO(),
            ],
            ["id", "review", "review_score"],
        )
    )

    schema = (
        Schema.new_builder()
        .column("id", DataTypes.BIGINT())
        .column("review", DataTypes.STRING())
        .column("review_score", DataTypes.FLOAT())
    ).build()

    output_table = (
        agents_env.from_table(input=table, key_selector=MyKeySelector())
        .apply(TableAgent())
        .to_table(schema=schema, output_type=output_type)
    )

    result_dir = tmp_path / "results"
    result_dir.mkdir(parents=True, exist_ok=True)

    t_env.create_temporary_table(
        "sink",
        TableDescriptor.for_connector("filesystem")
        .option("path", str(result_dir.absolute()))
        .format("json")
        .schema(schema)
        .build(),
    )

    output_table.execute_insert("sink").wait()

    check_result(
        result_dir=result_dir,
        ground_truth_dir=Path(
            f"{current_dir}/../resources/ground_truth/test_from_table_to_table.txt"
        ),
    )


def test_from_datastream_to_table(tmp_path: Path) -> None:
    env = StreamExecutionEnvironment.get_execution_environment()

    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)
    t_env = StreamTableEnvironment.create(stream_execution_environment=env)

    # currently, bounded source is not supported due to runtime implementation, so
    # we use continuous file source here.
    input_datastream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(), f"file:///{current_dir}/../resources/input"
        ).build(),
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="streaming_agent_example",
    )

    deserialize_datastream = input_datastream.map(
        lambda x: ItemData.model_validate_json(x)
    )

    agents_env = AgentsExecutionEnvironment.get_execution_environment(
        env=env, t_env=t_env
    )

    output_type = ExternalTypeInfo(
        RowTypeInfo(
            [
                BasicTypeInfo.LONG_TYPE_INFO(),
                BasicTypeInfo.STRING_TYPE_INFO(),
                BasicTypeInfo.FLOAT_TYPE_INFO(),
            ],
            ["id", "review", "review_score"],
        )
    )

    schema = (
        Schema.new_builder()
        .column("id", DataTypes.BIGINT())
        .column("review", DataTypes.STRING())
        .column("review_score", DataTypes.FLOAT())
    ).build()

    output_table = (
        agents_env.from_datastream(
            input=deserialize_datastream, key_selector=MyKeySelector()
        )
        .apply(DataStreamToTableAgent())
        .to_table(schema=schema, output_type=output_type)
    )

    result_dir = tmp_path / "results"
    result_dir.mkdir(parents=True, exist_ok=True)

    t_env.create_temporary_table(
        "sink",
        TableDescriptor.for_connector("filesystem")
        .option("path", str(result_dir.absolute()))
        .format("json")
        .schema(schema)
        .build(),
    )

    output_table.execute_insert("sink").wait()

    check_result(
        result_dir=result_dir,
        ground_truth_dir=Path(
            f"{current_dir}/../resources/ground_truth/test_from_table_to_table.txt"
        ),
    )
