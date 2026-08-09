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
from unittest.mock import MagicMock

import pytest
from pydantic import BaseModel

from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.integrations.chat_models.openai.openai_chat_model import (
    OpenAIChatModelConnection,
)
from flink_agents.integrations.chat_models.vllm.vllm_chat_model import (
    DEFAULT_VLLM_API_BASE_URL,
    DEFAULT_VLLM_API_KEY,
    VLLMChatModelConnection,
    VLLMChatModelSetup,
)


def test_connection_defaults_to_local_vllm_server() -> None:
    connection = VLLMChatModelConnection(name="vllm")
    assert isinstance(connection, OpenAIChatModelConnection)
    assert connection.api_base_url == DEFAULT_VLLM_API_BASE_URL
    assert connection.api_key == DEFAULT_VLLM_API_KEY


def test_connection_honors_explicit_arguments() -> None:
    connection = VLLMChatModelConnection(
        name="vllm",
        api_key="secret-key",
        api_base_url="http://vllm-host:8000/v1",
        timeout=30.0,
        max_retries=1,
    )
    assert connection.api_key == "secret-key"
    assert connection.api_base_url == "http://vllm-host:8000/v1"
    assert connection.timeout == 30.0
    assert connection.max_retries == 1


def test_setup_requires_model() -> None:
    with pytest.raises(ValueError, match="model is required for vLLM"):
        VLLMChatModelSetup(name="vllm_model", connection="vllm")


def test_connection_defaults_whitespace_only_arguments() -> None:
    # Semantic parity with the Java connection, which treats blank values as absent.
    connection = VLLMChatModelConnection(name="vllm", api_key=" ", api_base_url="  ")
    assert connection.api_key == DEFAULT_VLLM_API_KEY
    assert connection.api_base_url == DEFAULT_VLLM_API_BASE_URL


def test_setup_rejects_whitespace_only_model() -> None:
    with pytest.raises(ValueError, match="model is required for vLLM"):
        VLLMChatModelSetup(name="vllm_model", connection="vllm", model=" ")


def test_setup_rejects_empty_model() -> None:
    with pytest.raises(ValueError, match="model is required for vLLM"):
        VLLMChatModelSetup(name="vllm_model", connection="vllm", model="")


class _Person(BaseModel):
    """A representative BaseModel output schema."""

    name: str
    age: int


def test_supports_native_structured_output_follows_served_model() -> None:
    connection = VLLMChatModelConnection(name="vllm")
    assert connection.supports_native_structured_output("Qwen/Qwen2.5-7B-Instruct")
    assert connection.supports_native_structured_output(
        "meta-llama/Llama-3.1-8B-Instruct"
    )
    assert not connection.supports_native_structured_output(None)
    assert not connection.supports_native_structured_output(" ")


def test_native_response_format_applied_for_qwen_model() -> None:
    connection = VLLMChatModelConnection(name="vllm")
    mock_client = MagicMock()
    mock_message = MagicMock()
    mock_message.role = "assistant"
    mock_message.content = "ok"
    mock_message.tool_calls = None
    mock_client.chat.completions.create.return_value.choices = [
        MagicMock(message=mock_message)
    ]
    mock_client.chat.completions.create.return_value.usage = None
    connection._client = mock_client

    connection.chat(
        [ChatMessage(role=MessageRole.USER, content="hi")],
        model="Qwen/Qwen2.5-7B-Instruct",
        output_schema=OutputSchema(output_schema=_Person),
    )

    kwargs = mock_client.chat.completions.create.call_args.kwargs
    assert "response_format" in kwargs
    assert kwargs["response_format"]["type"] == "json_schema"


def test_setup_carries_served_model_name() -> None:
    setup = VLLMChatModelSetup(
        name="vllm_model",
        connection="vllm",
        model="Qwen/Qwen2.5-7B-Instruct",
        temperature=0.3,
        max_tokens=512,
    )
    kwargs = setup.model_kwargs
    assert kwargs["model"] == "Qwen/Qwen2.5-7B-Instruct"
    assert kwargs["temperature"] == 0.3
    assert kwargs["max_tokens"] == 512
