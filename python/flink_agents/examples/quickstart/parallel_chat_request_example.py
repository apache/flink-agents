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
import time
from pathlib import Path

from pyflink.common.typeinfo import BasicTypeInfo, ExternalTypeInfo, RowTypeInfo
from pyflink.datastream import StreamExecutionEnvironment
from pyflink.table import DataTypes, Schema, StreamTableEnvironment, TableDescriptor

from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.resource import ResourceDescriptor, ResourceName, ResourceType
from flink_agents.examples.quickstart.agents.parallel_chat_agent import (
    INPUT_TEXT,
    N_ASPECTS,
    OLLAMA_MODEL,
    ParallelChatAgent,
    ParallelChatKeySelector,
)


def main() -> None:
    """Main function for the parallel chat request quickstart example.

    This example demonstrates how to use Flink Agents to analyze a restaurant
    review by fanning out multiple parallel LLM calls — one per sentiment
    dimension — and aggregating the results with a final LLM call. This serves
    as a minimal, end-to-end example of integrating parallel LLM-powered agents
    with Flink streaming jobs.
    """
    # Set up the Flink streaming environment and the Agents execution environment.
    stream_env = StreamExecutionEnvironment.get_execution_environment()
    stream_env.set_parallelism(1)
    t_env = StreamTableEnvironment.create(stream_execution_environment=stream_env)
    agents_env = AgentsExecutionEnvironment.get_execution_environment(
        env=stream_env, t_env=t_env
    )

    # Add Ollama chat model connection to be used by the ParallelChatAgent.
    agents_env.add_resource(
        "ollama_server",
        ResourceType.CHAT_MODEL_CONNECTION,
        ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_CONNECTION,
            request_timeout=240.0,
        ),
    )

    # Create input table with a single restaurant review.
    input_table = t_env.from_elements(
        elements=[(1, INPUT_TEXT)],
        schema=DataTypes.ROW(
            [
                DataTypes.FIELD("id", DataTypes.INT()),
                DataTypes.FIELD("text", DataTypes.STRING()),
            ]
        ),
    )

    # Define output schema and type info.
    output_type_info = RowTypeInfo(
        [
            BasicTypeInfo.INT_TYPE_INFO(),
            BasicTypeInfo.STRING_TYPE_INFO(),
            BasicTypeInfo.STRING_TYPE_INFO(),
        ],
        ["id", "text", "summary"],
    )
    output_type = ExternalTypeInfo(output_type_info)
    output_schema = (
        Schema.new_builder()
        .column("id", DataTypes.INT())
        .column("text", DataTypes.STRING())
        .column("summary", DataTypes.STRING())
        .build()
    )

    # Register a filesystem sink for collecting results.
    result_dir = Path("/tmp/parallel_chat_results")
    result_dir.mkdir(parents=True, exist_ok=True)

    t_env.create_temporary_table(
        "sink",
        TableDescriptor.for_connector("filesystem")
        .option("path", str(result_dir.absolute()))
        .format("json")
        .schema(output_schema)
        .build(),
    )

    # Use the ParallelChatAgent to analyze the review with parallel LLM calls.
    output_table = (
        agents_env.from_table(input=input_table, key_selector=ParallelChatKeySelector())
        .apply(ParallelChatAgent())
        .to_table(schema=output_schema, output_type=output_type)
    )

    # Execute the Flink pipeline.
    wall_start = time.monotonic()
    output_table.execute_insert("sink").wait()
    wall_elapsed = time.monotonic() - wall_start

    # Print the analysis results to stdout.
    rows = []
    for file in result_dir.iterdir():
        if file.is_file():
            with file.open() as f:
                for line in f:
                    line = line.strip()
                    if line:
                        rows.append(json.loads(line))

    print(f"OUTPUT rows: {rows}")
    print(f"End-to-end wall time: {wall_elapsed:.2f}s")
    print("=== Done ===")


if __name__ == "__main__":
    main()
