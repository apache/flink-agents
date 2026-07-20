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

from anthropic.types import Message, TextBlock, ToolUseBlock, Usage

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.integrations.chat_models.anthropic.anthropic_chat_model import (
    AnthropicChatModelConnection,
)


def _connection_returning(message: Message) -> AnthropicChatModelConnection:
    connection = AnthropicChatModelConnection(name="test", api_key="dummy")
    client = MagicMock()
    client.messages.create.return_value = message
    connection._client = client
    return connection


def _usage() -> Usage:
    return Usage(input_tokens=1, output_tokens=1)


def test_tool_use_response_without_leading_text() -> None:
    # When the model calls a tool it commonly returns only a tool_use block, so
    # content[0] is not a text block. Parsing must not assume content[0].text.
    message = Message(
        id="m",
        model="claude",
        role="assistant",
        type="message",
        stop_reason="tool_use",
        content=[
            ToolUseBlock(type="tool_use", id="t1", name="add", input={"a": 1, "b": 2})
        ],
        usage=_usage(),
    )
    response = _connection_returning(message).chat(
        [ChatMessage(role=MessageRole.USER, content="add 1 and 2")]
    )
    assert response.content == ""
    assert len(response.tool_calls) == 1
    assert response.tool_calls[0]["function"]["name"] == "add"


def test_tool_use_response_keeps_leading_text() -> None:
    # A tool_use response may be preceded by a text block; that text is kept.
    message = Message(
        id="m",
        model="claude",
        role="assistant",
        type="message",
        stop_reason="tool_use",
        content=[
            TextBlock(type="text", text="Let me add those."),
            ToolUseBlock(type="tool_use", id="t1", name="add", input={"a": 1, "b": 2}),
        ],
        usage=_usage(),
    )
    response = _connection_returning(message).chat(
        [ChatMessage(role=MessageRole.USER, content="add 1 and 2")]
    )
    assert response.content == "Let me add those."
    assert len(response.tool_calls) == 1


def test_plain_text_response() -> None:
    message = Message(
        id="m",
        model="claude",
        role="assistant",
        type="message",
        stop_reason="end_turn",
        content=[TextBlock(type="text", text="Hello!")],
        usage=_usage(),
    )
    response = _connection_returning(message).chat(
        [ChatMessage(role=MessageRole.USER, content="hi")]
    )
    assert response.content == "Hello!"
