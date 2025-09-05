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

from pyflink.common import Row
from pyflink.common.typeinfo import BasicTypeInfo, ExternalTypeInfo, RowTypeInfo
from pyflink.datastream import (
    KeySelector,
    StreamExecutionEnvironment,
)
from pyflink.table import DataTypes, Schema, StreamTableEnvironment, TableDescriptor

from flink_agents.api.agents.react_agent import ReActAgent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.examples.common_tools import add, multiply
from flink_agents.integrations.chat_models.ollama_chat_model import (
    OllamaChatModelConnection,
    OllamaChatModelSetup,
)

model = os.environ.get("OLLAMA_CHAT_MODEL", "qwen2.5:7b")


class MyKeySelector(KeySelector):
    """KeySelector for extracting key."""

    def get_key(self, value: Row) -> int:
        """Extract key from Row."""
        return value[0]


current_dir = Path(__file__).parent

#TODO: Currently, this example may cause core dump when being executed, the root cause
# may be known issue of pemja incorrect reference counting:
# https://github.com/apache/flink-agents/issues/83
if __name__ == "__main__":
    stream_env = StreamExecutionEnvironment.get_execution_environment()

    # should compile flink-agents jars before run this example.
    stream_env.add_jars(
        f"file:///{current_dir}/../../../runtime/target/flink-agents-runtime-0.1-SNAPSHOT.jar"
    )
    stream_env.add_jars(
        f"file:///{current_dir}/../../../plan/target/flink-agents-plan-0.1-SNAPSHOT.jar"
    )
    stream_env.add_jars(
        f"file:///{current_dir}/../../../api/target/flink-agents-api-0.1-SNAPSHOT.jar"
    )

    stream_env.set_parallelism(1)

    t_env = StreamTableEnvironment.create(stream_execution_environment=stream_env)

    table = t_env.from_elements(
        elements=[(1, 2, 3)],
        schema=DataTypes.ROW(
            [
                DataTypes.FIELD("a", DataTypes.INT()),
                DataTypes.FIELD("b", DataTypes.INT()),
                DataTypes.FIELD("c", DataTypes.INT()),
            ]
        ),
    )

    env = AgentsExecutionEnvironment.get_execution_environment(env=stream_env)

    # register resource to execution environment
    (
        env.add_chat_model_connection(
            name="ollama", connection=OllamaChatModelConnection, model=model
        )
        .add_tool("add", add)
        .add_tool("multiply", multiply)
    )

    # prepare prompt
    prompt = Prompt.from_messages(
        name="prompt",
        messages=[
            ChatMessage(
                role=MessageRole.SYSTEM,
                content='An example of output is {"result": 30.32}.',
            ),
            ChatMessage(role=MessageRole.USER, content="What is ({a} + {b}) * {c}"),
        ],
    )

    output_type_info = RowTypeInfo(
        [BasicTypeInfo.INT_TYPE_INFO()],
        ["result"],
    )

    # create ReAct agent.
    agent = ReActAgent(
        chat_model_setup=OllamaChatModelSetup,
        connection="ollama",
        prompt=prompt,
        tools=["add", "multiply"],
        output_schema=output_type_info,
    )

    output_type = ExternalTypeInfo(output_type_info)

    schema = (Schema.new_builder().column("result", DataTypes.INT())).build()

    output_table = (
        env.from_table(input=table, t_env=t_env, key_selector=MyKeySelector())
        .apply(agent)
        .to_table(schema=schema, output_type=output_type)
    )

    t_env.create_temporary_table(
        "sink",
        TableDescriptor.for_connector("print").schema(schema).build(),
    )

    output_table.execute_insert("sink").wait()
