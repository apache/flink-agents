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
from pathlib import Path

from pyflink.common import Row
from pyflink.common.typeinfo import BasicTypeInfo, ExternalTypeInfo, RowTypeInfo
from pyflink.datastream import (
    KeySelector,
    RuntimeExecutionMode,
    StreamExecutionEnvironment,
)
from pyflink.table import (
    DataTypes,
    Schema,
    StreamTableEnvironment,
    TableDescriptor,
)

from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.e2e_tests.my_agent import TableAgent

current_dir = Path(__file__).parent


class MyKeySelector(KeySelector):
    """KeySelector for extracting key."""

    def get_key(self, value: Row) -> int:
        """Extract key from Row."""
        return value[0]


# if this example raises exception "No module named 'flink_agents'", you could set
# PYTHONPATH like "os.environ["PYTHONPATH"] = ($VENV_HOME/lib/$PYTHON_VERSION/
# site-packages) in this file.
if __name__ == "__main__":
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
            .column("info", DataTypes.STRING())
            .column("info_score", DataTypes.FLOAT())
            .build()
        )
        .option("format", "json")
        .option("path", f"file:///{current_dir}/resources")
        .option("source.monitor-interval", "60s")
        .build(),
    )

    table = t_env.from_path("source")

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env, t_env=t_env)

    output_type = ExternalTypeInfo(
        RowTypeInfo(
            [
                BasicTypeInfo.LONG_TYPE_INFO(),
                BasicTypeInfo.STRING_TYPE_INFO(),
                BasicTypeInfo.FLOAT_TYPE_INFO(),
            ],
            ["id", "info", "info_score"],
        )
    )

    schema = (
        Schema.new_builder()
        .column("id", DataTypes.BIGINT())
        .column("info", DataTypes.STRING())
        .column("info_score", DataTypes.FLOAT())
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
