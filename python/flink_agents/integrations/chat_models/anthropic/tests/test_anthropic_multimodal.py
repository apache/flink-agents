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
"""How content blocks reach an Anthropic messages request.

Mirrors the Java AnthropicMultimodalTest.
"""

from typing import Any, Dict
from unittest.mock import MagicMock

import pytest
from anthropic.types import Message, Usage
from anthropic.types import TextBlock as AnthropicTextBlock

from flink_agents.api.chat_message import (
    AudioBlock,
    Base64Source,
    ChatMessage,
    ContentBlock,
    DocumentBlock,
    ImageBlock,
    MessageRole,
    TextBlock,
    UnsupportedContentBlockError,
    VideoBlock,
)
from flink_agents.integrations.chat_models.anthropic.anthropic_chat_model import (
    AnthropicChatModelConnection,
)

URL = "https://example.com/cat.png?sig=secret"
BASE64 = "aGVsbG8="  # "hello"


def _connection() -> tuple[AnthropicChatModelConnection, MagicMock]:
    connection = AnthropicChatModelConnection(api_key="fake-key")
    client = MagicMock()
    client.messages.create.return_value = Message(
        id="m",
        model="claude",
        role="assistant",
        type="message",
        stop_reason="end_turn",
        content=[AnthropicTextBlock(type="text", text="ok")],
        usage=Usage(input_tokens=1, output_tokens=1),
    )
    connection._client = client
    return connection, client


def _chat(*messages: ChatMessage) -> MagicMock:
    connection, client = _connection()
    connection.chat(list(messages), model="claude-sonnet-4-5")
    return client


def _sent(message: ChatMessage) -> Dict[str, Any]:
    """Chat through a mocked client and return the first message as sent."""
    return _chat(message).messages.create.call_args.kwargs["messages"][0]


def test_text_only_user_message_keeps_string_content() -> None:
    """A user message without media keeps plain string content."""
    assert _sent(ChatMessage.user([TextBlock(text="hi")]))["content"] == "hi"


def test_images_become_image_blocks() -> None:
    """Images become image blocks in block order, by URL or as Base64 data."""
    content = _sent(
        ChatMessage.user(
            [
                TextBlock(text="Compare"),
                ImageBlock.from_url("image/png", URL),
                ImageBlock.from_base64("image/png", BASE64),
            ]
        )
    )["content"]

    assert content == [
        {"type": "text", "text": "Compare"},
        {"type": "image", "source": {"type": "url", "url": URL}},
        {
            "type": "image",
            "source": {"type": "base64", "media_type": "image/png", "data": BASE64},
        },
    ]


def test_documents_become_document_blocks() -> None:
    """PDFs and Base64 plain text become document blocks, titled by the name."""
    content = _sent(
        ChatMessage.user(
            [
                DocumentBlock(
                    media_type="application/pdf",
                    source=Base64Source(data=BASE64),
                    name="report.pdf",
                ),
                DocumentBlock.from_url("application/pdf", "https://example.com/a.pdf"),
                DocumentBlock.from_base64("text/plain", BASE64),
            ]
        )
    )["content"]

    assert content == [
        {
            "type": "document",
            "source": {
                "type": "base64",
                "media_type": "application/pdf",
                "data": BASE64,
            },
            "title": "report.pdf",
        },
        {
            "type": "document",
            "source": {"type": "url", "url": "https://example.com/a.pdf"},
        },
        {
            "type": "document",
            "source": {"type": "text", "media_type": "text/plain", "data": "hello"},
        },
    ]


@pytest.mark.parametrize(
    "block",
    [
        AudioBlock.from_base64("audio/wav", BASE64),
        VideoBlock.from_url("video/mp4", URL),
        ImageBlock.from_base64("image/bmp", BASE64),
        DocumentBlock.from_url("text/plain", URL),
        DocumentBlock.from_base64("application/msword", BASE64),
    ],
    ids=str,
)
def test_unsupported_blocks_fail_explicitly(block: ContentBlock) -> None:
    """Blocks Anthropic has no block for fail without leaking the source."""
    connection, client = _connection()
    message = ChatMessage.user([TextBlock(text="hi"), block])

    with pytest.raises(UnsupportedContentBlockError) as error:
        connection.chat([message], model="claude-sonnet-4-5")

    text = str(error.value)
    assert text.startswith("Anthropic cannot send a")
    assert f" {block.type} block" in text
    assert "secret" not in text
    assert BASE64 not in text
    client.messages.create.assert_not_called()


@pytest.mark.parametrize(
    "role", [MessageRole.SYSTEM, MessageRole.ASSISTANT, MessageRole.TOOL]
)
def test_media_outside_user_messages_fails(role: MessageRole) -> None:
    """Media outside user messages fails explicitly."""
    message = ChatMessage(
        role=role,
        blocks=[TextBlock(text="see"), ImageBlock.from_url("image/png", URL)],
    )

    with pytest.raises(
        UnsupportedContentBlockError, match="only user messages can carry media"
    ):
        _chat(ChatMessage.user("hi"), message)


def test_invalid_base64_text_document_fails() -> None:
    """Undecodable plain-text data fails with ValueError, not as unsupported."""
    message = ChatMessage.user([DocumentBlock.from_base64("text/plain", "not base64!")])

    with pytest.raises(ValueError, match="could not be decoded") as error:
        _chat(message)

    assert not isinstance(error.value, UnsupportedContentBlockError)
