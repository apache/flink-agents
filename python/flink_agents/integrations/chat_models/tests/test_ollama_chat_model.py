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
import subprocess
import sys
from pathlib import Path

import pytest
from ollama import Client

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.resource import ResourceType
from flink_agents.integrations.chat_models.ollama_chat_model import OllamaChatModel
from flink_agents.plan.tools.function_tool import FunctionTool, from_callable

test_model = os.environ.get("OLLAMA_CHAT_MODEL", "qwen3:0.6b")
current_dir = Path(__file__).parent

try:
    # only auto setup ollama in ci with python3.9 to reduce ci cost.
    if "3.9" in sys.version:
        subprocess.run(["bash", f"{current_dir}/start_ollama_server.sh"],
                       timeout=300, check=True)
    client = Client()
    models = client.list()

    model_found = False
    for model in models["models"]:
        if model.model == test_model:
            model_found = True
            break

    if not model_found:
        client = None  # type: ignore
except Exception:
    client = None  # type: ignore


@pytest.mark.skipif(
    client is None, reason="Ollama client is not available or test model is missing"
)
def test_ollama_chat() -> None:  # noqa :D103
    llm = OllamaChatModel(name="ollama", model=test_model)
    response = llm.chat([ChatMessage(role=MessageRole.USER, content="Hello!")])
    assert response is not None
    assert str(response).strip() != ""


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


def get_tool(name: str, type: ResourceType) -> FunctionTool:  # noqa :D103
    return from_callable(name=name, func=add)


@pytest.mark.skipif(
    client is None, reason="Ollama client is not available or test model is missing"
)
def test_ollama_chat_with_tools() -> None:  # noqa :D103
    llm = OllamaChatModel(
        name="ollama", model=test_model, tools=["add"], get_resource=get_tool
    )
    response = llm.chat([
        ChatMessage(
            role=MessageRole.USER,
            content="Could you help me calculate the sum of 1 and 2?",
        )
    ])

    tool_calls = response.tool_calls
    assert len(tool_calls) == 1
    tool_call = tool_calls[0]
    assert add(**tool_call["function"]["arguments"]) == 3
