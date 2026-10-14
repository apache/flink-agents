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
"""How content blocks become Chat Completions content parts.

The converter is shared by the OpenAI, Azure OpenAI and vLLM connections, so
these cases pin the wire shape all three send. They mirror the Java
OpenAIChatCompletionsMultimodalTest.
"""

import pytest

from flink_agents.api.chat_message import (
    AudioBlock,
    Base64Source,
    ChatMessage,
    ContentBlock,
    DocumentBlock,
    ImageBlock,
    MessageRole,
    TextBlock,
    ToolResultBlock,
    UnsupportedContentBlockError,
    VideoBlock,
)
from flink_agents.integrations.chat_models.openai.openai_utils import (
    convert_to_openai_message,
)

IMAGE_URL = "https://example.com/cat.png?sig=secret"
BASE64 = "aGVsbG8="


def test_text_only_user_message_keeps_string_content() -> None:
    """A user message without media keeps the plain string content."""
    message = ChatMessage.user([TextBlock(text="Describe "), TextBlock(text="this")])

    assert convert_to_openai_message(message)["content"] == "Describe this"


def test_user_media_becomes_ordered_content_parts() -> None:
    """Media switches the user content to parts, in block order."""
    message = ChatMessage.user(
        [
            TextBlock(text="Compare"),
            ImageBlock.from_url("image/png", IMAGE_URL),
            ImageBlock.from_base64("image/jpeg", BASE64),
        ]
    )

    assert convert_to_openai_message(message)["content"] == [
        {"type": "text", "text": "Compare"},
        {"type": "image_url", "image_url": {"url": IMAGE_URL}},
        {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{BASE64}"}},
    ]


def test_audio_becomes_input_audio() -> None:
    """WAV and MP3 audio map onto the two formats input_audio accepts."""
    message = ChatMessage.user(
        [
            AudioBlock.from_base64("audio/wav", BASE64),
            AudioBlock.from_base64("audio/mpeg", BASE64),
        ]
    )

    assert convert_to_openai_message(message)["content"] == [
        {"type": "input_audio", "input_audio": {"data": BASE64, "format": "wav"}},
        {"type": "input_audio", "input_audio": {"data": BASE64, "format": "mp3"}},
    ]


def test_document_becomes_file_part() -> None:
    """A base64 document is sent as file data with a file name."""
    message = ChatMessage.user(
        [
            DocumentBlock(
                media_type="application/pdf",
                source=Base64Source(data=BASE64),
                name="report.pdf",
            ),
            DocumentBlock.from_base64("application/pdf", BASE64),
        ]
    )

    parts = convert_to_openai_message(message)["content"]
    assert parts[0] == {
        "type": "file",
        "file": {
            "file_data": f"data:application/pdf;base64,{BASE64}",
            "filename": "report.pdf",
        },
    }
    assert parts[1]["file"]["filename"] == "document"


@pytest.mark.parametrize(
    "block",
    [
        VideoBlock.from_url("video/mp4", IMAGE_URL),
        AudioBlock.from_url("audio/wav", IMAGE_URL),
        AudioBlock.from_base64("audio/ogg", BASE64),
        DocumentBlock.from_url("application/pdf", IMAGE_URL),
    ],
    ids=["video", "audio-url", "audio-ogg", "document-url"],
)
def test_unsupported_user_blocks_fail_explicitly(block: ContentBlock) -> None:
    """Blocks without a content part fail, naming the block but not its source."""
    message = ChatMessage.user([TextBlock(text="hi"), block])

    with pytest.raises(UnsupportedContentBlockError) as error:
        convert_to_openai_message(message)

    assert f"{block.type} block" in str(error.value)
    assert "secret" not in str(error.value)
    assert BASE64 not in str(error.value)


@pytest.mark.parametrize(
    "role", [MessageRole.SYSTEM, MessageRole.ASSISTANT, MessageRole.TOOL]
)
def test_media_outside_user_messages_fails(role: MessageRole) -> None:
    """Only user messages can carry media."""
    blocks = [TextBlock(text="see"), ImageBlock.from_url("image/png", IMAGE_URL)]
    if role == MessageRole.SYSTEM:
        with pytest.raises(ValueError, match="SYSTEM messages accept only text"):
            ChatMessage(role=role, blocks=blocks)
        return
    message = (
        ChatMessage.tool(ToolResultBlock(call_id="call-1", blocks=blocks))
        if role == MessageRole.TOOL
        else ChatMessage(role=role, blocks=blocks)
    )
    with pytest.raises(UnsupportedContentBlockError, match="only user messages"):
        convert_to_openai_message(message)


def test_raw_bytes_preserved_in_provider_content_parts() -> None:
    """No extra encoding or text decoding occurs when converting binary media."""
    data = b"\xff\x00\x80\xfb"
    raw = ChatMessage.user(
        [
            ImageBlock.from_bytes("image/png", data),
            AudioBlock.from_bytes("audio/wav", data),
            DocumentBlock.from_bytes("application/pdf", data),
        ]
    )
    encoded = "/wCA+w=="
    existing = ChatMessage.user(
        [
            ImageBlock.from_base64("image/png", encoded),
            AudioBlock.from_base64("audio/wav", encoded),
            DocumentBlock.from_base64("application/pdf", encoded),
        ]
    )
    converted = convert_to_openai_message(raw)
    assert converted == convert_to_openai_message(existing)
    parts = converted["content"]
    assert parts[0]["image_url"]["url"] == f"data:image/png;base64,{encoded}"
    assert parts[1]["input_audio"]["data"] == encoded
    assert parts[2]["file"]["file_data"] == f"data:application/pdf;base64,{encoded}"
    with pytest.raises(UnsupportedContentBlockError):
        convert_to_openai_message(
            ChatMessage.user([VideoBlock.from_bytes("video/mp4", data)])
        )
