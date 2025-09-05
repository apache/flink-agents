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

from pydantic import BaseModel

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


class InputData(BaseModel):  # noqa: D101
    a: int
    b: int
    c: int


class OutputData(BaseModel):  # noqa: D101
    result: int


if __name__ == "__main__":
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
