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
from pydantic import BaseModel
from pyflink.common import Row
from pyflink.common.typeinfo import BasicTypeInfo, ExternalTypeInfo, RowTypeInfo
from pyflink.datastream import KeySelector, StreamExecutionEnvironment
from pyflink.table import DataTypes, Schema, StreamTableEnvironment, TableDescriptor

from flink_agents.api.agents.react_agent import (
    ErrorHandlingStrategy,
    ReActAgent,
    ReActAgentOptions,
)
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import ResourceDescriptor
from flink_agents.api.tools.tool import Tool
from flink_agents.e2e_tests.ollama_prepare_utils import pull_model
from flink_agents.e2e_tests.react_agent_tools import add, multiply
from flink_agents.integrations.chat_models.ollama_chat_model import (
    OllamaChatModelConnection,
    OllamaChatModelSetup,
)

current_dir = Path(__file__).parent

os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"]

OLLAMA_MODEL = os.environ.get("REACT_OLLAMA_MODEL", "qwen3:1.7b")
os.environ["OLLAMA_CHAT_MODEL"] = OLLAMA_MODEL


class InputData(BaseModel):  # noqa: D101
    a: int
    b: int
    c: int


class OutputData(BaseModel):  # noqa: D101
    result: int


class MyKeySelector(KeySelector):
    """KeySelector for extracting key."""

    def get_key(self, value: Row) -> int:
        """Extract key from Row."""
        return value[0]


client = pull_model(OLLAMA_MODEL)


@pytest.mark.skipif(
    client is None, reason="Ollama client is not available or test model is missing"
)
def test_react_agent_on_local_runner() -> None:  # noqa: D103
    env = AgentsExecutionEnvironment.get_execution_environment()
    env.get_config().set(
        ReActAgentOptions.ERROR_HANDLING_STRATEGY, ErrorHandlingStrategy.IGNORE
    )

    # register resource to execution environment
    (
        env.add_resource(
            "ollama",
            ResourceDescriptor(clazz=OllamaChatModelConnection, request_timeout=240.0),
        )
        .add_resource("add", Tool.from_callable(add))
        .add_resource("multiply", Tool.from_callable(multiply))
    )

    # prepare prompt
    prompt = Prompt.from_messages(
        messages=[
            ChatMessage(
                role=MessageRole.SYSTEM,
                content='An example of output is {"result": 30.32}.',
            ),
            ChatMessage(role=MessageRole.USER, content="What is ({a} + {b}) * {c}"),
        ],
    )

    # create ReAct agent.
    agent = ReActAgent(
        chat_model=ResourceDescriptor(
            clazz=OllamaChatModelSetup,
            connection="ollama",
            model=OLLAMA_MODEL,
            tools=["add", "multiply"],
        ),
        prompt=prompt,
        output_schema=OutputData,
    )

    # execute agent
    input_list = []

    output_list = env.from_list(input_list).apply(agent).to_list()
    input_list.append({"key": "0001", "value": InputData(a=2123, b=2321, c=312)})

    env.execute()

    for output in output_list:
        for key, value in output.items():
            print(f"{key}: {value}")


@pytest.mark.skipif(
    client is None, reason="Ollama client is not available or test model is missing"
)
def test_react_agent_on_remote_runner() -> None:  # noqa: D103
    stream_env = StreamExecutionEnvironment.get_execution_environment()

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

    env = AgentsExecutionEnvironment.get_execution_environment(
        env=stream_env, t_env=t_env
    )

    env.get_config().set(
        ReActAgentOptions.ERROR_HANDLING_STRATEGY, ErrorHandlingStrategy.IGNORE
    )

    # register resource to execution environment
    (
        env.add_resource(
            "ollama",
            ResourceDescriptor(clazz=OllamaChatModelConnection, request_timeout=120.0),
        )
        .add_resource("add", Tool.from_callable(add))
        .add_resource("multiply", Tool.from_callable(multiply))
    )

    # prepare prompt
    prompt = Prompt.from_messages(
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
        chat_model=ResourceDescriptor(
            clazz=OllamaChatModelSetup,
            connection="ollama",
            model=OLLAMA_MODEL,
            tools=["add", "multiply"],
        ),
        prompt=prompt,
        output_schema=output_type_info,
    )

    output_type = ExternalTypeInfo(output_type_info)

    schema = (Schema.new_builder().column("result", DataTypes.INT())).build()

    output_table = (
        env.from_table(input=table, key_selector=MyKeySelector())
        .apply(agent)
        .to_table(schema=schema, output_type=output_type)
    )

    t_env.create_temporary_table(
        "sink",
        TableDescriptor.for_connector("print").schema(schema).build(),
    )

    output_table.execute_insert("sink").wait()
