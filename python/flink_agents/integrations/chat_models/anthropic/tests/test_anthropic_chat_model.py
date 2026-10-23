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
import base64
import os
import struct
import zlib
from unittest.mock import MagicMock

import pytest

from flink_agents.api.chat_message import (
    ChatMessage,
    ImageBlock,
    MessageRole,
    TextBlock,
    ToolResultBlock,
)
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.resource_context import ResourceContext
from flink_agents.api.tools.tool import Tool
from flink_agents.integrations.chat_models.anthropic.anthropic_chat_model import (
    DEFAULT_ANTHROPIC_MODEL,
    AnthropicChatModelConnection,
    AnthropicChatModelSetup,
)
from flink_agents.plan.function import PythonFunction
from flink_agents.plan.tools.function_tool import FunctionTool

pytestmark = pytest.mark.integration

test_model = os.environ.get("TEST_MODEL")
api_key = os.environ.get("TEST_API_KEY")


@pytest.mark.skipif(api_key is None, reason="TEST_API_KEY is not set")
def test_anthropic_chat_model() -> None:
    connection = AnthropicChatModelConnection(api_key=api_key)

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.CHAT_MODEL_CONNECTION:
            return connection
        else:
            return get_resource(name, ResourceType.TOOL)

    mock_ctx = MagicMock(spec=ResourceContext)
    mock_ctx.get_resource = get_resource

    chat_model = AnthropicChatModelSetup(
        model=test_model,
        connection="anthropic_server",
        resource_context=mock_ctx,
    )
    response = chat_model.chat([ChatMessage.of(MessageRole.USER, "Hello!")])
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


@pytest.mark.skipif(api_key is None, reason="TEST_API_KEY is not set")
def test_anthropic_chat_with_tools() -> None:
    connection = AnthropicChatModelConnection(api_key=api_key)

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.CHAT_MODEL_CONNECTION:
            return connection
        else:
            return Tool.from_callable(func=add)

    mock_ctx = MagicMock(spec=ResourceContext)
    mock_ctx.get_resource = get_resource

    chat_model = AnthropicChatModelSetup(
        model=test_model,
        connection="anthropic_server",
        tools=["add"],
        resource_context=mock_ctx,
    )
    response = chat_model.chat([ChatMessage.of(MessageRole.USER, "What is 1 + 1?")])
    tool_calls = response.tool_calls
    assert len(tool_calls) == 1
    tool_call = tool_calls[0]
    assert add(**tool_call.input) == 2
    assert tool_call.call_id is not None


def get_swatch() -> str:
    """Return the color swatch to identify, as an image."""
    return "The test returns the swatch image itself."


def _red_square_png() -> str:
    """A 16x16 red PNG."""
    rows = b"".join(b"\x00" + b"\xff\x00\x00" * 16 for _ in range(16))

    def chunk(kind: bytes, data: bytes) -> bytes:
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(rows))
        + chunk(b"IEND", b"")
    )
    return base64.b64encode(png).decode()


@pytest.mark.skipif(api_key is None, reason="TEST_API_KEY is not set")
def test_anthropic_reads_image_returned_by_tool() -> None:
    """The model calls a tool, gets an image in the tool result and uses it."""
    connection = AnthropicChatModelConnection(api_key=api_key)
    tools = [FunctionTool(func=PythonFunction.from_callable(get_swatch))]
    params = {"model": test_model or DEFAULT_ANTHROPIC_MODEL, "max_tokens": 256}
    question = ChatMessage.user(
        "Call get_swatch, then answer with the color of the swatch in one word."
    )

    first = connection.chat([question], tools=tools, **params)
    assert len(first.tool_calls) == 1

    swatch = ToolResultBlock(
        call_id=first.tool_calls[0].call_id,
        blocks=(
            TextBlock(text="The swatch:"),
            ImageBlock.from_base64("image/png", _red_square_png()),
        ),
    )
    second = connection.chat(
        [question, first.message, ChatMessage.tool(swatch)], tools=tools, **params
    )
    assert "red" in second.text.lower()


def test_model_field_roundtrip() -> None:
    """Verify `model` is preserved through pydantic dump/validate round-trip."""
    setup = AnthropicChatModelSetup(connection="conn", model="test-model")
    restored = AnthropicChatModelSetup.model_validate(setup.model_dump())
    assert restored.model == "test-model"


def test_default_model_when_omitted() -> None:
    """Verify per-integration default applies when `model` is omitted from __init__."""
    setup = AnthropicChatModelSetup(connection="conn")
    assert setup.model == "claude-sonnet-4-6"
