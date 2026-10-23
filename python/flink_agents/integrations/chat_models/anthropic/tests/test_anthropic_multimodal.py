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

from typing import Any, Dict, List
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
    TextBlock,
    ToolCallBlock,
    ToolResultBlock,
    UnsupportedContentBlockError,
    VideoBlock,
)
from flink_agents.integrations.chat_models.anthropic.anthropic_chat_model import (
    AnthropicChatModelConnection,
)

URL = "https://example.com/cat.png?sig=secret"
BASE64 = "aGVsbG8="  # "hello"

# Media Anthropic has no block for.
UNSUPPORTED = [
    AudioBlock.from_base64("audio/wav", BASE64),
    VideoBlock.from_url("video/mp4", URL),
    ImageBlock.from_base64("image/bmp", BASE64),
    DocumentBlock.from_url("text/plain", URL),
    DocumentBlock.from_base64("application/msword", BASE64),
]


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
    return _sent_all(message)[0]


def _sent_all(*messages: ChatMessage) -> List[Dict[str, Any]]:
    """Chat through a mocked client and return the messages as sent."""
    return _chat(*messages).messages.create.call_args.kwargs["messages"]


def _tool_result(result: ToolResultBlock) -> Dict[str, Any]:
    """The tool_result block a tool message is sent as."""
    message = _sent(ChatMessage.tool(result))
    assert message["role"] == "user"
    assert len(message["content"]) == 1
    return message["content"][0]


def test_text_only_user_message_is_unchanged() -> None:
    """A user message without media is sent as before, as one text block."""
    assert _sent(ChatMessage.user([TextBlock(text="hi")]))["content"] == [
        {"type": "text", "text": "hi"}
    ]


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


@pytest.mark.parametrize("block", UNSUPPORTED, ids=str)
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


def test_media_in_assistant_messages_fails() -> None:
    """Media in assistant messages fails explicitly."""
    assistant = ChatMessage.assistant(
        [TextBlock(text="see"), ImageBlock.from_url("image/png", URL)]
    )

    with pytest.raises(UnsupportedContentBlockError) as error:
        _chat(ChatMessage.user("hi"), assistant)

    assert str(error.value) == (
        "Anthropic cannot send an image block (image/png, url source): only user "
        "messages and tool results can carry media, not assistant messages."
    )


def test_text_only_tool_result_is_unchanged() -> None:
    """A text-only tool result is sent as before, as a list of text blocks."""
    result = ToolResultBlock(
        call_id="call-1", blocks=(TextBlock(text="4"), TextBlock(text="2"))
    )

    assert _sent(ChatMessage.tool(result)) == {
        "role": "user",
        "content": [
            {
                "type": "tool_result",
                "tool_use_id": "call-1",
                "content": [
                    {"type": "text", "text": "4"},
                    {"type": "text", "text": "2"},
                ],
                "is_error": False,
            }
        ],
    }


def test_tool_result_media_is_sent_in_order() -> None:
    """Media in a tool result goes into tool_result content, in block order."""
    blocks = (
        TextBlock(text="Chart:"),
        ImageBlock.from_url("image/png", URL),
        ImageBlock.from_base64("image/png", BASE64),
        DocumentBlock(
            media_type="application/pdf",
            source=Base64Source(data=BASE64),
            name="report.pdf",
        ),
        DocumentBlock.from_url("application/pdf", "https://example.com/a.pdf"),
        DocumentBlock.from_base64("text/plain", BASE64),
        TextBlock(text="Done."),
    )

    result = _tool_result(ToolResultBlock(call_id="call-1", blocks=blocks))

    assert result == {
        "type": "tool_result",
        "tool_use_id": "call-1",
        "content": [
            {"type": "text", "text": "Chart:"},
            {"type": "image", "source": {"type": "url", "url": URL}},
            {
                "type": "image",
                "source": {"type": "base64", "media_type": "image/png", "data": BASE64},
            },
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
            {"type": "text", "text": "Done."},
        ],
        "is_error": False,
    }
    # Each block is sent exactly as in a user message.
    assert result["content"] == _sent(ChatMessage.user(list(blocks)))["content"]


def test_error_tool_result_with_media() -> None:
    """An error tool result with media keeps is_error."""
    result = _tool_result(
        ToolResultBlock(
            call_id="call-1",
            blocks=(
                TextBlock(text="Render failed; partial output:"),
                ImageBlock.from_url("image/png", URL),
            ),
            is_error=True,
        )
    )

    assert result["tool_use_id"] == "call-1"
    assert result["is_error"] is True
    assert [block["type"] for block in result["content"]] == ["text", "image"]


@pytest.mark.parametrize("block", UNSUPPORTED, ids=str)
def test_unsupported_tool_result_media_fails_as_for_user_messages(
    block: ContentBlock,
) -> None:
    """Media Anthropic cannot send fails in a tool result as in a user message."""
    with pytest.raises(UnsupportedContentBlockError) as in_user_message:
        _chat(ChatMessage.user([block]))
    connection, client = _connection()
    message = ChatMessage.tool(
        ToolResultBlock(call_id="call-1", blocks=(TextBlock(text="hi"), block))
    )

    with pytest.raises(UnsupportedContentBlockError) as error:
        connection.chat([message], model="claude-sonnet-4-5")

    text = str(error.value)
    assert text == str(in_user_message.value)
    assert "secret" not in text
    assert BASE64 not in text
    client.messages.create.assert_not_called()


def test_invalid_base64_text_document_in_tool_result_fails() -> None:
    """Undecodable plain-text data in a tool result fails with ValueError."""
    message = ChatMessage.tool(
        ToolResultBlock(
            call_id="call-1",
            blocks=(DocumentBlock.from_base64("text/plain", "not base64!"),),
        )
    )

    with pytest.raises(ValueError, match="could not be decoded") as error:
        _chat(message)

    assert not isinstance(error.value, UnsupportedContentBlockError)


def test_parallel_tool_results_keep_their_correlation() -> None:
    """Parallel tool results keep their own call IDs and content."""
    messages = _sent_all(
        ChatMessage.user("What is the weather, and show me the map."),
        ChatMessage.assistant(
            [
                ToolCallBlock(call_id="call-1", name="weather"),
                ToolCallBlock(call_id="call-2", name="map"),
            ]
        ),
        ChatMessage.tool(
            ToolResultBlock(call_id="call-1", blocks=(TextBlock(text="Sunny"),))
        ),
        ChatMessage.tool(
            ToolResultBlock(
                call_id="call-2",
                blocks=(TextBlock(text="Map:"), ImageBlock.from_url("image/png", URL)),
            )
        ),
    )

    assert len(messages) == 4
    assert messages[2]["content"] == [
        {
            "type": "tool_result",
            "tool_use_id": "call-1",
            "content": [{"type": "text", "text": "Sunny"}],
            "is_error": False,
        }
    ]
    assert messages[3]["content"] == [
        {
            "type": "tool_result",
            "tool_use_id": "call-2",
            "content": [
                {"type": "text", "text": "Map:"},
                {"type": "image", "source": {"type": "url", "url": URL}},
            ],
            "is_error": False,
        }
    ]


def test_invalid_base64_text_document_fails() -> None:
    """Undecodable plain-text data fails with ValueError, not as unsupported."""
    message = ChatMessage.user([DocumentBlock.from_base64("text/plain", "not base64!")])

    with pytest.raises(ValueError, match="could not be decoded") as error:
        _chat(message)

    assert not isinstance(error.value, UnsupportedContentBlockError)
