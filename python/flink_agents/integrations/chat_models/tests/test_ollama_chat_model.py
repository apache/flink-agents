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
from unittest.mock import MagicMock

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


def test_extract_think_tags() -> None:
    """Test the static method that extracts content from <think></think> tags."""
    # Test with a think tag at the beginning (most common case)
    content = "<think>First, I need to understand the question.\nThen I need to formulate an answer.</think>The answer is 42."
    cleaned, reasoning = OllamaChatModel._OllamaChatModel__extract_think_tags(content)
    assert cleaned == "The answer is 42."
    assert reasoning == "First, I need to understand the question.\nThen I need to formulate an answer."
    # Test with a think tag only
    content = "<think>This is just my thought process.</think>"
    cleaned, reasoning = OllamaChatModel._OllamaChatModel__extract_think_tags(content)
    assert cleaned == ""
    assert reasoning == "This is just my thought process."

    # Test with no think tags
    content = "This is a regular response without any thinking tags."
    cleaned, reasoning = OllamaChatModel._OllamaChatModel__extract_think_tags(content)
    assert cleaned == content
    assert reasoning is None


def test_ollama_chat_with_extract_reasoning() -> None:
    """Test that extract_reasoning functionality works correctly."""
    # Create mock objects for client and response
    mock_client = MagicMock()
    mock_response = MagicMock()
    # Use a more realistic reasoning pattern at the beginning
    mock_response.message.content = "<think>To answer what the meaning of life is, I should consider philosophical perspectives. The question is often associated with the number 42 from Hitchhiker's Guide to the Galaxy.</think>The meaning of life is often considered to be 42, according to the Hitchhiker's Guide to the Galaxy."
    mock_response.message.role = "assistant"
    mock_response.message.tool_calls = None

    # Configure mock client to return our mock response
    mock_client.chat.return_value = mock_response
    # Create model with mocked client
    llm = OllamaChatModel(
        name="ollama",
        model=test_model,
        extract_reasoning=True
    )

    # Replace the real client with our mock client
    llm._OllamaChatModel__client = mock_client

    # Call the chat method
    response = llm.chat([
        ChatMessage(
            role=MessageRole.USER,
            content="What's the meaning of life?",
        )
    ])

    # Verify our mock was called correctly
    mock_client.chat.assert_called_once()

    # Check that the response content has been cleaned
    assert response.content == "The meaning of life is often considered to be 42, according to the Hitchhiker's Guide to the Galaxy."
    # Check that the reasoning has been extracted and stored
    assert "reasoning" in response.extra_args
    assert "philosophical perspectives" in response.extra_args["reasoning"]
    assert "Hitchhiker's Guide to the Galaxy" in response.extra_args["reasoning"]
