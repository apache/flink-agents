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
"""Test that connections handle BaseModel schemas that cannot be converted to JSON schema.

This addresses issue #985: when a BaseModel contains fields that pydantic cannot
serialize to JSON schema (e.g., Callable fields), the connection should fall back
to prompt engineering instead of raising PydanticInvalidForJsonSchema.
"""

from __future__ import annotations

import logging
from typing import Callable
from unittest.mock import MagicMock

from pydantic import BaseModel

from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.integrations.chat_models.anthropic import anthropic_chat_model
from flink_agents.integrations.chat_models.azure import azure_openai_chat_model
from flink_agents.integrations.chat_models.openai import openai_chat_model

logger = logging.getLogger(__name__)


class UnserializableModel(BaseModel):
    """A BaseModel with a Callable field that cannot be converted to JSON schema."""

    callback: Callable[[int], int]
    name: str


def _mock_openai_connection() -> openai_chat_model.OpenAIChatModelConnection:
    """Create a mock OpenAI connection."""
    conn = openai_chat_model.OpenAIChatModelConnection(
        name="test", api_key="test-key", api_base_url="https://test.com"
    )
    conn._client = MagicMock()
    mock_response = MagicMock()
    mock_response.choices = [MagicMock()]
    mock_response.choices[0].message = MagicMock()
    mock_response.choices[0].message.role = "assistant"
    mock_response.choices[0].message.content = "test response"
    mock_response.choices[0].message.tool_calls = None
    mock_response.usage = None
    conn._client.chat.completions.create.return_value = mock_response
    return conn


def _mock_azure_connection() -> azure_openai_chat_model.AzureOpenAIChatModelConnection:
    """Create a mock Azure OpenAI connection."""
    conn = azure_openai_chat_model.AzureOpenAIChatModelConnection(
        name="test",
        api_key="test-key",
        azure_endpoint="https://test.openai.azure.com",
        api_version="2024-08-01",
    )
    conn._client = MagicMock()
    mock_response = MagicMock()
    mock_response.choices = [MagicMock()]
    mock_response.choices[0].message = MagicMock()
    mock_response.choices[0].message.role = "assistant"
    mock_response.choices[0].message.content = "test response"
    mock_response.choices[0].message.tool_calls = None
    mock_response.usage = None
    conn._client.chat.completions.create.return_value = mock_response
    return conn


def _mock_anthropic_connection() -> anthropic_chat_model.AnthropicChatModelConnection:
    """Create a mock Anthropic connection."""
    conn = anthropic_chat_model.AnthropicChatModelConnection(
        name="test", api_key="test-key"
    )
    conn._client = MagicMock()
    mock_response = MagicMock()
    mock_response.role = "assistant"
    mock_response.content = [MagicMock()]
    mock_response.content[0].text = "test response"
    mock_response.content[0].type = "text"
    mock_response.usage = MagicMock()
    mock_response.usage.input_tokens = 10
    mock_response.usage.output_tokens = 20
    mock_response.stop_reason = "end_turn"
    conn._client.messages.create.return_value = mock_response
    return conn


def test_openai_handles_unserializable_schema() -> None:
    """OpenAI connection should fall back to prompt when schema cannot be serialized."""
    conn = _mock_openai_connection()

    # This should not raise an exception
    conn.chat(
        messages=[ChatMessage(role=MessageRole.USER, content="test")],
        model="gpt-4o",
        output_schema=OutputSchema(output_schema=UnserializableModel),
    )

    # Verify the call was made without response_format (prompt fallback)
    call_args = conn._client.chat.completions.create.call_args
    assert "response_format" not in call_args.kwargs


def test_azure_handles_unserializable_schema() -> None:
    """Azure OpenAI connection should fall back to prompt when schema cannot be serialized."""
    conn = _mock_azure_connection()

    # This should not raise an exception
    # model_of_azure_deployment must be a model that supports native structured output
    # api_version must be >= 2024-08-01 to support structured output
    conn.chat(
        messages=[ChatMessage(role=MessageRole.USER, content="test")],
        model="gpt-4o",
        model_of_azure_deployment="gpt-4o",
        output_schema=OutputSchema(output_schema=UnserializableModel),
    )

    # Verify the call was made without response_format (prompt fallback)
    call_args = conn._client.chat.completions.create.call_args
    assert "response_format" not in call_args.kwargs


def test_anthropic_handles_unserializable_schema() -> None:
    """Anthropic connection should fall back to prompt when schema cannot be serialized."""
    conn = _mock_anthropic_connection()

    # This should not raise an exception
    # Use a model that supports native structured output
    conn.chat(
        messages=[ChatMessage(role=MessageRole.USER, content="test")],
        model="claude-sonnet-4-5-20250929",
        output_schema=OutputSchema(output_schema=UnserializableModel),
    )

    # Verify the call was made without output_config (prompt fallback)
    call_args = conn._client.messages.create.call_args
    assert "output_config" not in call_args.kwargs
