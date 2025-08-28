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
import argparse
import os

from pydantic import BaseModel
from pyflink.common.typeinfo import BasicTypeInfo, RowTypeInfo

from flink_agents.api.agents.react_agent import ReActAgent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.integrations.chat_models.ollama_chat_model import (
    OllamaChatModelConnection,
    OllamaChatModelSetup,
)

model = os.environ.get("OLLAMA_CHAT_MODEL", "qwen2.5:7b")


class InputData(BaseModel):  # noqa: D101
    a: int
    b: int
    c: int


class OutputData(BaseModel):  # noqa: D101
    result: int


def get_output_schema() -> type[OutputData]:
    """Get the output schema."""
    return OutputData


def get_output_row_type() -> RowTypeInfo:
    """Get the output row type."""
    return RowTypeInfo(
        [BasicTypeInfo.INT_TYPE_INFO()],
        ["result"],
    )


def add(a: int, b: int) -> int:
    """Calculate the sum of a and b.

    Parameters
    ----------
    a : int
        The first operand
    b : int
        The second operand

    Returns:
    -------
    int:
        The sum of a and b
    """
    return a + b


def multiply(a: int, b: int) -> int:
    """Useful function to multiply two numbers.

    Parameters
    ----------
    a : int
        The first operand
    b : int
        The second operand

    Returns:
    -------
    int:
        The product of a and b
    """
    return a * b


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output_row",
        type=bool,
        default=False,
        help="Whether the agent should output row.",
    )
    args = parser.parse_args()

    env = AgentsExecutionEnvironment.get_execution_environment()

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

    # create ReAct agent.
    agent = ReActAgent(
        chat_model_setup=OllamaChatModelSetup,
        connection="ollama",
        prompt=prompt,
        tools=["add", "multiply"],
        output_schema_provider=get_output_row_type
        if args.output_row
        else get_output_schema,
    )

    # execute agent
    input_list = []

    output_list = env.from_list(input_list).apply(agent).to_list()
    input_list.append({"key": "0001", "value": InputData(a=2123, b=2321, c=312)})

    env.execute()

    for output in output_list:
        for key, value in output.items():
            print(f"{key}: {value}")
