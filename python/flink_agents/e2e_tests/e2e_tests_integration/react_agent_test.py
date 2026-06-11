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
from pathlib import Path

import pytest
from pydantic import BaseModel
from pyflink.common import Row
from pyflink.common.typeinfo import BasicTypeInfo, ExternalTypeInfo, RowTypeInfo
from pyflink.datastream import KeySelector, StreamExecutionEnvironment
from pyflink.table import DataTypes, Schema, StreamTableEnvironment, TableDescriptor

from flink_agents.api.agents.react_agent import (
    ReActAgent,
)
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.core_options import (
    AgentExecutionOptions,
    ErrorHandlingStrategy,
)
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import (
    ResourceDescriptor,
    ResourceName,
    ResourceType,
)
from flink_agents.api.tools.tool import Tool
from flink_agents.e2e_tests.e2e_tests_integration.react_agent_tools import add, multiply
from flink_agents.e2e_tests.test_utils import (
    assert_tool_invoked,
    collect_tool_invocations,
    pull_model,
    tool_invocations_from_events,
)

current_dir = Path(__file__).parent

os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"]

OLLAMA_MODEL = os.environ.get("REACT_OLLAMA_MODEL", "qwen3:1.7b")


class InputData(BaseModel):
    a: int
    b: int
    c: int


class OutputData(BaseModel):
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
def test_react_agent_on_local_runner(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("OLLAMA_CHAT_MODEL", OLLAMA_MODEL)
    env = AgentsExecutionEnvironment.get_execution_environment()
    env.get_config().set(
        AgentExecutionOptions.ERROR_HANDLING_STRATEGY, ErrorHandlingStrategy.RETRY
    )
    env.get_config().set(AgentExecutionOptions.MAX_RETRIES, 3)

    # register resource to execution environment
    (
        env.add_resource(
            "ollama",
            ResourceType.CHAT_MODEL_CONNECTION,
            ResourceDescriptor(
                clazz=ResourceName.ChatModel.OLLAMA_CONNECTION, request_timeout=240.0
            ),
        )
        .add_resource("add", ResourceType.TOOL, Tool.from_callable(add))
        .add_resource("multiply", ResourceType.TOOL, Tool.from_callable(multiply))
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
            clazz=ResourceName.ChatModel.OLLAMA_SETUP,
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

    assert len(output_list) == 1, (
        "This may be caused by the LLM response does not match the output schema, you can rerun this case."
    )
    assert int(output_list[0]["0001"].result) == 1386528

    # multiply's first arg (4444 = 2123 + 2321) proves the addition was computed
    # correctly and the multiply tool was used; the model often does the addition
    # without the add tool, so add is not a reliable signal to assert on.
    invocations = tool_invocations_from_events(env.get_tool_request_events())
    assert_tool_invoked(invocations, "multiply", {"a": 4444, "b": 312})


@pytest.mark.skipif(
    client is None, reason="Ollama client is not available or test model is missing"
)
def test_react_agent_on_remote_runner(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("OLLAMA_CHAT_MODEL", OLLAMA_MODEL)
    stream_env = StreamExecutionEnvironment.get_execution_environment()

    stream_env.set_parallelism(1)

    t_env = StreamTableEnvironment.create(stream_execution_environment=stream_env)

    table = t_env.from_elements(
        elements=[(2123, 2321, 312)],
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
        AgentExecutionOptions.ERROR_HANDLING_STRATEGY, ErrorHandlingStrategy.RETRY
    )

    env.get_config().set(AgentExecutionOptions.MAX_RETRIES, 3)

    log_dir = tmp_path / "event_logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    env.get_config().set_str("baseLogDir", str(log_dir))

    # register resource to execution environment
    (
        env.add_resource(
            "ollama",
            ResourceType.CHAT_MODEL_CONNECTION,
            ResourceDescriptor(
                clazz=ResourceName.ChatModel.OLLAMA_CONNECTION, request_timeout=240.0
            ),
        )
        .add_resource("add", ResourceType.TOOL, Tool.from_callable(add))
        .add_resource("multiply", ResourceType.TOOL, Tool.from_callable(multiply))
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
            clazz=ResourceName.ChatModel.OLLAMA_SETUP,
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

    actual_result = []
    for file in result_dir.iterdir():
        if file.is_file():
            with file.open() as f:
                actual_result.extend(f.readlines())

    assert len(actual_result) == 1, (
        "This may be caused by the LLM response does not match the output schema, you can rerun this case."
    )
    assert json.loads(actual_result[0].strip())["result"] == 1386528

    # multiply's first arg (4444 = 2123 + 2321) proves the addition was computed
    # correctly and threaded into multiply; the model often does the addition
    # without the add tool, so add is not a reliable signal to assert on. This
    # exercises the same reasoning chain as the local-runner test, but read back
    # through the event-log capture path.
    invocations = collect_tool_invocations(log_dir)
    assert_tool_invoked(invocations, "multiply", {"a": 4444, "b": 312})


@pytest.mark.skipif(
    client is None, reason="Ollama client is not available or test model is missing"
)
def test_react_agent_no_output_schema_on_remote_runner(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """ReAct agent without an output_schema should emit a plain string result."""
    monkeypatch.setenv("OLLAMA_CHAT_MODEL", OLLAMA_MODEL)
    stream_env = StreamExecutionEnvironment.get_execution_environment()

    stream_env.set_parallelism(1)

    t_env = StreamTableEnvironment.create(stream_execution_environment=stream_env)

    table = t_env.from_elements(
        elements=[(2123, 2321, 312)],
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
        AgentExecutionOptions.ERROR_HANDLING_STRATEGY, ErrorHandlingStrategy.RETRY
    )

    env.get_config().set(AgentExecutionOptions.MAX_RETRIES, 3)

    log_dir = tmp_path / "event_logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    env.get_config().set_str("baseLogDir", str(log_dir))

    # register resource to execution environment
    (
        env.add_resource(
            "ollama",
            ResourceType.CHAT_MODEL_CONNECTION,
            ResourceDescriptor(
                clazz=ResourceName.ChatModel.OLLAMA_CONNECTION, request_timeout=240.0
            ),
        )
        .add_resource("add", ResourceType.TOOL, Tool.from_callable(add))
        .add_resource("multiply", ResourceType.TOOL, Tool.from_callable(multiply))
    )

    # prepare prompt
    prompt = Prompt.from_messages(
        messages=[
            ChatMessage(role=MessageRole.USER, content="What is ({a} + {b}) * {c}"),
        ],
    )

    # create ReAct agent without an output schema; result is emitted as a string.
    agent = ReActAgent(
        chat_model=ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_SETUP,
            connection="ollama",
            model=OLLAMA_MODEL,
            tools=["add", "multiply"],
        ),
        prompt=prompt,
    )

    output_stream = (
        env.from_table(input=table, key_selector=MyKeySelector())
        .apply(agent)
        .to_datastream()
    )
    output_stream.print()

    env.execute()

    # multiply's first arg (4444 = 2123 + 2321) proves the addition was computed
    # correctly and threaded into multiply, even without an output schema.
    invocations = collect_tool_invocations(log_dir)
    assert_tool_invoked(invocations, "multiply", {"a": 4444, "b": 312})
