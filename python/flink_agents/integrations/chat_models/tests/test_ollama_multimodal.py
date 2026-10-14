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
"""How content blocks reach an Ollama chat request.

Mirrors the Java OllamaMultimodalTest.
"""

from typing import Any, Dict, List
from unittest.mock import MagicMock

import pytest

from flink_agents.api.chat_message import (
    AudioBlock,
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
from flink_agents.integrations.chat_models.ollama_chat_model import (
    OllamaChatModelConnection,
)

URL = "https://example.com/cat.png?sig=secret"
FIRST = "Zmlyc3Q="  # "first"
SECOND = "c2Vjb25k"  # "second"


def _sent_messages(message: ChatMessage) -> List[Dict[str, Any]]:
    """Chat through a mocked client and return the messages as serialized."""
    conn = OllamaChatModelConnection()
    response = MagicMock()
    response.message.role = "assistant"
    response.message.content = "ok"
    response.message.tool_calls = None
    response.prompt_eval_count = 1
    response.eval_count = 2
    response.done_reason = "stop"
    mock_client = MagicMock()
    mock_client.chat.return_value = response
    conn._OllamaChatModelConnection__client = mock_client
    conn.chat([message], model="llava")
    return [
        sent.model_dump(exclude_none=True)
        for sent in mock_client.chat.call_args.kwargs["messages"]
    ]


def test_text_only_message_has_no_images() -> None:
    """A message without media is sent without an images field."""
    sent = _sent_messages(ChatMessage.user([TextBlock(text="hi")]))[0]

    assert sent["content"] == "hi"
    assert "images" not in sent


def test_base64_images_attached_in_block_order() -> None:
    """Images follow block order, next to the text projection."""
    sent = _sent_messages(
        ChatMessage.user(
            [
                TextBlock(text="Compare "),
                ImageBlock.from_base64("image/png", FIRST),
                TextBlock(text="and"),
                ImageBlock.from_base64("image/jpeg", SECOND),
            ]
        )
    )[0]

    assert sent["content"] == "Compare and"
    assert sent["images"] == [FIRST, SECOND]


@pytest.mark.parametrize(
    "block",
    [
        ImageBlock.from_url("image/png", URL),
        AudioBlock.from_base64("audio/wav", FIRST),
        VideoBlock.from_url("video/mp4", URL),
        DocumentBlock.from_base64("application/pdf", FIRST),
    ],
    ids=["image-url", "audio", "video", "document"],
)
def test_unsupported_media_fails_explicitly(block: ContentBlock) -> None:
    """Media Ollama cannot take fails, naming the block but not its source."""
    with pytest.raises(UnsupportedContentBlockError) as error:
        _sent_messages(ChatMessage.user([TextBlock(text="hi"), block]))

    article = "an" if block.type[0] in "aeiou" else "a"
    assert str(error.value).startswith(
        f"Ollama cannot send {article} {block.type} block"
    )
    assert "secret" not in str(error.value)
    assert FIRST not in str(error.value)


@pytest.mark.parametrize(
    "role", [MessageRole.SYSTEM, MessageRole.ASSISTANT, MessageRole.TOOL]
)
def test_images_outside_user_messages_fail(role: MessageRole) -> None:
    """Only user messages can carry images."""
    blocks = [TextBlock(text="see"), ImageBlock.from_base64("image/png", FIRST)]
    if role == MessageRole.SYSTEM:
        with pytest.raises(ValueError, match="SYSTEM messages accept only text"):
            ChatMessage(role=role, blocks=blocks)
        return
    message = (
        ChatMessage.tool(ToolResultBlock(call_id="call", blocks=blocks))
        if role == MessageRole.TOOL
        else ChatMessage(role=role, blocks=blocks)
    )

    with pytest.raises(UnsupportedContentBlockError, match="only user messages"):
        _sent_messages(message)


def test_invalid_base64_fails() -> None:
    """Image data that is not base64 fails with a clear error."""
    message = ChatMessage.user([ImageBlock.from_base64("image/png", "!!")])

    with pytest.raises(ValueError, match="could not be decoded") as error:
        _sent_messages(message)

    assert not isinstance(error.value, UnsupportedContentBlockError)
