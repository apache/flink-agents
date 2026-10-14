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
import sys
import sysconfig
from pathlib import Path

import pytest
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
)
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import (
    ResourceDescriptor,
    ResourceName,
    ResourceType,
)
from flink_agents.api.tools.tool import Tool
from flink_agents.e2e_tests.e2e_tests_integration.react_agent_native_structured_output import (
    CalcResult,
    RecordingOllamaChatModelConnection,
)
from flink_agents.e2e_tests.e2e_tests_integration.react_agent_tools import add, multiply
from flink_agents.e2e_tests.test_utils import (
    assert_tool_invoked,
    collect_tool_invocations,
    pull_model,
)

current_dir = Path(__file__).parent

os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"]

OLLAMA_MODEL = os.environ.get("REACT_OLLAMA_MODEL", "qwen3:1.7b")


class MyKeySelector(KeySelector):
    """KeySelector for extracting key."""

    def get_key(self, value: Row) -> int:
        """Extract key from Row."""
        return value[0]


client = pull_model(OLLAMA_MODEL)


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

    env.get_config().set(AgentExecutionOptions.MAX_RETRIES, 3)

    log_dir = tmp_path / "trace_logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    env.get_config().set_str("trace-log.base-dir", str(log_dir))

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
            ChatMessage.of(
                MessageRole.SYSTEM,
                'An example of output is {"result": 30.32}.',
            ),
            ChatMessage.of(MessageRole.USER, "What is ({a} + {b}) * {c}"),
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
    # through the trace-log capture path.
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

    env.get_config().set(AgentExecutionOptions.MAX_RETRIES, 3)

    log_dir = tmp_path / "trace_logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    env.get_config().set_str("trace-log.base-dir", str(log_dir))

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
            ChatMessage.of(MessageRole.USER, "What is ({a} + {b}) * {c}"),
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


@pytest.mark.skipif(
    client is None, reason="Ollama client is not available or test model is missing"
)
def test_react_agent_native_structured_output_on_remote_runner(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A BaseModel schema takes the native path through the tool-calling loop.

    The tool loop runs unconstrained, then the final answer is converted in a request
    that carries Ollama's native ``format`` and no tools. The recording connection
    proves that request was sent, so a prompt-fallback run cannot pass.
    """
    monkeypatch.setenv("OLLAMA_CHAT_MODEL", OLLAMA_MODEL)
    stream_env = StreamExecutionEnvironment.get_execution_environment()
    stream_env.set_python_executable(sys.executable)
    stream_env.set_parallelism(1)

    input_stream = stream_env.from_collection([{"a": 2123, "b": 2321, "c": 312}])

    env = AgentsExecutionEnvironment.get_execution_environment(env=stream_env)

    env.get_config().set(AgentExecutionOptions.MAX_RETRIES, 3)

    log_dir = tmp_path / "trace_logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    env.get_config().set_str("trace-log.base-dir", str(log_dir))

    record_file = tmp_path / "ollama_requests.jsonl"
    connection = RecordingOllamaChatModelConnection
    (
        env.add_resource(
            "recording_ollama",
            ResourceType.CHAT_MODEL_CONNECTION,
            ResourceDescriptor(
                clazz=f"{connection.__module__}.{connection.__name__}",
                request_timeout=240.0,
                record_file=str(record_file),
            ),
        )
        .add_resource("add", ResourceType.TOOL, Tool.from_callable(add))
        .add_resource("multiply", ResourceType.TOOL, Tool.from_callable(multiply))
    )

    prompt = Prompt.from_messages(
        messages=[
            ChatMessage.of(
                MessageRole.SYSTEM, "Must call function tool to do the calculate."
            ),
            ChatMessage.of(MessageRole.USER, "What is ({a} + {b}) * {c}"),
        ],
    )

    # Unlike a RowTypeInfo, a BaseModel schema has a native translation on Ollama, so
    # under the default AUTO strategy the final answer is converted with it applied.
    agent = ReActAgent(
        chat_model=ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_SETUP,
            connection="recording_ollama",
            model=OLLAMA_MODEL,
            tools=["add", "multiply"],
        ),
        prompt=prompt,
        output_schema=CalcResult,
    )

    output = (
        env.from_datastream(input=input_stream, key_selector=lambda value: value["a"])
        .apply(agent)
        .to_datastream(CalcResult)
    )

    results = list(output.execute_and_collect())

    assert len(results) == 1, (
        "This may be caused by the LLM response does not match the output schema, you can rerun this case."
    )
    assert isinstance(results[0], CalcResult), (
        f"The result should be the parsed CalcResult, got: {results[0]!r}"
    )
    assert results[0].result == 1386528

    records = [json.loads(line) for line in record_file.read_text().splitlines()]
    conversions = [record for record in records if record["format"] is not None]
    loop_requests = [record for record in records if record["format"] is None]
    assert conversions, (
        "No request carried the native schema, so the run may have used the prompt "
        f"fallback. Requests: {records}"
    )
    for conversion in conversions:
        assert conversion["tools"] == [], (
            f"The conversion request should carry no tools, got: {conversion}"
        )
        assert conversion["format"] == CalcResult.model_json_schema(), (
            f"The conversion request should carry CalcResult's schema, got: {conversion}"
        )
    for loop_request in loop_requests:
        assert sorted(loop_request["tools"]) == ["add", "multiply"], (
            f"Every tool-loop request should carry the bound tools, got: {loop_request}"
        )
    assert any("multiply" in record["tool_calls"] for record in loop_requests), (
        "The model should call the multiply tool in the loop; it may have computed "
        f"without tools, you can rerun this case. Requests: {records}"
    )
    assert records[-1]["format"] is not None, (
        f"The native conversion should be the last model call. Requests: {records}"
    )

    # 4444 = 2123 + 2321, so multiply received the correctly computed sum.
    invocations = collect_tool_invocations(log_dir)
    assert_tool_invoked(invocations, "multiply", {"a": 4444, "b": 312})
