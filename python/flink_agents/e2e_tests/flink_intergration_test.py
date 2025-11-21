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

from pyflink.common import Configuration, WatermarkStrategy
from pyflink.common.typeinfo import BasicTypeInfo, ExternalTypeInfo, RowTypeInfo
from pyflink.datastream import RuntimeExecutionMode, StreamExecutionEnvironment
from pyflink.datastream.connectors.file_system import FileSource, StreamFormat
from pyflink.table import DataTypes, Schema, StreamTableEnvironment, TableDescriptor

from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.e2e_tests.flink_integration_agent import (
    DataStreamAgent,
    DataStreamToTableAgent,
    ItemData,
    MyKeySelector,
    TableAgent,
)

current_dir = Path(__file__).parent

os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"]


def test_from_datastream_to_datastream() -> None:  # noqa: D103
    config = Configuration()
    config.set_string("state.backend.type", "rocksdb")
    config.set_string("checkpointing.interval", "1s")
    config.set_string("restart-strategy.type", "disable")
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)

    # currently, bounded source is not supported due to runtime implementation, so
    # we use continuous file source here.
    input_datastream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(), f"file:///{current_dir}/resources"
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

    output_datastream.print()

    agents_env.execute()


def test_from_table_to_table() -> None:  # noqa: D103
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
        .option("path", f"file:///{current_dir}/resources")
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

    t_env.create_temporary_table(
        "sink",
        TableDescriptor.for_connector("print").schema(schema).build(),
    )

    output_table.execute_insert("sink").wait()


def test_from_datastream_to_table() -> None:  # noqa: D103
    env = StreamExecutionEnvironment.get_execution_environment()

    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)
    t_env = StreamTableEnvironment.create(stream_execution_environment=env)

    # currently, bounded source is not supported due to runtime implementation, so
    # we use continuous file source here.
    input_datastream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(), f"file:///{current_dir}/resources"
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

    t_env.create_temporary_table(
        "sink",
        TableDescriptor.for_connector("print").schema(schema).build(),
    )

    output_table.execute_insert("sink").wait()
