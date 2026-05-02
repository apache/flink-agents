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
import uuid
from dataclasses import dataclass
from pathlib import Path

import pytest
from pyflink.common import Configuration, Encoder
from pyflink.common.typeinfo import BasicTypeInfo, ExternalTypeInfo, RowTypeInfo, Types
from pyflink.datastream import StreamExecutionEnvironment
from pyflink.datastream.connectors.file_system import StreamingFileSink
from pyflink.table import DataTypes, Schema, StreamTableEnvironment, TableDescriptor

from flink_agents.api.agents.agent import Agent
from flink_agents.api.agents.react_agent import ReActAgent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.core_options import AgentExecutionOptions, ErrorHandlingStrategy
from flink_agents.api.decorators import (
    action,
    chat_model_connection,
    chat_model_setup,
    prompt,
    skills,
)
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import InputEvent, OutputEvent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import ResourceDescriptor, ResourceName, ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.skills import Skills
from flink_agents.e2e_tests.e2e_tests_integration.react_agent_test import MyKeySelector

current_dir = Path(__file__).parent

PYTHON_PATH = sysconfig.get_paths()["purelib"]
MODEL = "qwen3.6-plus"
BASE_URL = os.environ.get("ACTION_BASE_URL", "https://coding.dashscope.aliyuncs.com/v1")
API_KEY = os.environ.get("ACTION_API_KEY")


@dataclass
class Operation:
    a: int
    b: int


class SkillTestAgent(Agent):
    """Agent for testing async execution."""

    @chat_model_connection
    @staticmethod
    def openai_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OPENAI_COMPLETIONS_CONNECTION,
            api_key=API_KEY,
            api_base_url=BASE_URL,
            request_timeout=300,
        )

    @chat_model_setup
    @staticmethod
    def openai_setup() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OPENAI_COMPLETIONS_SETUP,
            connection="openai_connection",
            model=MODEL,
            skills=["math-calculator", "joke-generator"],
            allowed_commands=["echo", "bc", "python"],
            prompt="system_prompt",
        )

    @skills
    @staticmethod
    def my_skills() -> Skills:
        return Skills.from_local_dir(str(current_dir.parent / "resources" / "skills"))

    @prompt
    @staticmethod
    def system_prompt() -> Prompt:
        return Prompt.from_messages(
            messages=[
                ChatMessage(
                    role=MessageRole.SYSTEM,
                    content="You are a help assistant. Use the math-calculator skill when asked to evaluate "
                    "an expression. You **must load the skill first** and strictly follow the instructions "
                    "of the skill.",
                )
            ],
        )

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def process_input(event: InputEvent, ctx: RunnerContext) -> None:
        if isinstance(event.input, Operation):
            input: Operation = event.input
            ctx.send_event(
                ChatRequestEvent(
                    model="openai_setup",
                    messages=[
                        ChatMessage(
                            role=MessageRole.USER,
                            content=f"Please evaluate the expression: ({input.a} ^ {input.b})",
                        )
                    ],
                )
            )
        else:
            input: str = event.input
            ctx.send_event(
                ChatRequestEvent(
                    model="openai_setup",
                    messages=[
                        ChatMessage(
                            role=MessageRole.USER,
                            content=input,
                        )
                    ],
                )
            )

    @action(ChatResponseEvent.EVENT_TYPE)
    @staticmethod
    def process_chat_response(event: ChatResponseEvent, ctx: RunnerContext) -> None:
        input = event.response
        ctx.send_event(OutputEvent(output=input.content))


@pytest.mark.skipif(not API_KEY, reason="openai api key is required.")
def test_workflow_with_skills(tmp_path: Path) -> None:
    config = Configuration()
    config.set_string("python.pythonpath", PYTHON_PATH)
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_parallelism(1)

    input_stream = env.from_collection(
        [Operation(a=2, b=3)],
    )

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    output_datastream = (
        agents_env.from_datastream(
            input=input_stream, key_selector=lambda x: uuid.uuid4()
        )
        .apply(SkillTestAgent())
        .to_datastream(Types.STRING())
    )

    result_dir = tmp_path / "results"
    result_dir.mkdir(parents=True, exist_ok=True)

    output_datastream.add_sink(
        StreamingFileSink.for_row_format(
            base_path=str(result_dir.absolute()),
            encoder=Encoder.simple_string_encoder(),
        ).build()
    )

    agents_env.execute()

    for file in result_dir.iterdir():
        if file.is_dir():
            for child in file.iterdir():
                with child.open() as f:
                    content = f.read()

    assert "8" in content


@pytest.mark.skipif(not API_KEY, reason="openai api key is required.")
def test_execute_python_script(tmp_path: Path) -> None:
    config = Configuration()
    config.set_string("python.pythonpath", PYTHON_PATH)
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_parallelism(1)

    input_stream = env.from_collection(
        ["Tell me a joke about cat."],
    )

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    output_datastream = (
        agents_env.from_datastream(
            input=input_stream, key_selector=lambda x: uuid.uuid4()
        )
        .apply(SkillTestAgent())
        .to_datastream(Types.STRING())
    )

    result_dir = tmp_path / "results"
    result_dir.mkdir(parents=True, exist_ok=True)

    output_datastream.add_sink(
        StreamingFileSink.for_row_format(
            base_path=str(result_dir.absolute()),
            encoder=Encoder.simple_string_encoder(),
        ).build()
    )

    agents_env.execute()

    for file in result_dir.iterdir():
        if file.is_dir():
            for child in file.iterdir():
                with child.open() as f:
                    content = f.read()

    assert "Too many cheetahs" in content


@pytest.mark.skipif(not API_KEY, reason="openai api key is required.")
def test_react_agent_with_skills(tmp_path: Path) -> None:
    config = Configuration()
    config.set_string("python.pythonpath", PYTHON_PATH)
    stream_env = StreamExecutionEnvironment.get_execution_environment(config)

    stream_env.set_parallelism(1)

    t_env = StreamTableEnvironment.create(stream_execution_environment=stream_env)

    table = t_env.from_elements(
        elements=[(2, 3)],
        schema=DataTypes.ROW(
            [
                DataTypes.FIELD("a", DataTypes.INT()),
                DataTypes.FIELD("b", DataTypes.INT()),
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

    # register resource to execution environment
    (
        env.add_resource(
            "openai",
            ResourceType.CHAT_MODEL_CONNECTION,
            ResourceDescriptor(
                clazz=ResourceName.ChatModel.OPENAI_COMPLETIONS_CONNECTION,
                api_key=API_KEY,
                api_base_url=BASE_URL,
                request_timeout=300,
            ),
        ).add_resource(
            "my_skill",
            ResourceType.SKILLS,
            Skills.from_local_dir(str(current_dir.parent / "resources" / "skills")),
        )
    )

    # prepare prompt
    prompt = Prompt.from_messages(
        messages=[
            ChatMessage(
                role=MessageRole.SYSTEM,
                content="You are a math calculate assistant. Use the math-calculator skill when asked to evaluate "
                "an expression. You **must load the skill first** and strictly follow the instructions "
                "of the skill.",
            ),
            ChatMessage(
                role=MessageRole.USER,
                content="Please evaluate the expression: {a} ^ {b}",
            ),
        ],
    )

    output_type_info = RowTypeInfo(
        [BasicTypeInfo.INT_TYPE_INFO()],
        ["result"],
    )

    # create ReAct agent.
    agent = ReActAgent(
        chat_model=ResourceDescriptor(
            clazz=ResourceName.ChatModel.OPENAI_COMPLETIONS_SETUP,
            connection="openai",
            model=MODEL,
            skills=["math-calculator"],
            allowed_commands=["echo", "bc"],
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
    content = json.loads(actual_result[0].strip())
    assert "result" in content
    assert int(content["result"]) == 8
