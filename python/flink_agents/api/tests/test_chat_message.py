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
import pytest
from pydantic import ValidationError

from flink_agents.api.chat_message import (
    AudioBlock,
    ChatMessage,
    DocumentBlock,
    ImageBlock,
    MessageRole,
    TextBlock,
    VideoBlock,
)


def test_text_only_wire_shape() -> None:
    """A text-only message serializes to a single typed text block."""
    message = ChatMessage.user("hello world")
    dumped = message.model_dump(mode="json", exclude_none=True)
    assert dumped == {
        "role": "user",
        "blocks": [{"type": "text", "text": "hello world"}],
        "tool_calls": [],
        "extra_args": {},
    }


def test_media_block_wire_shape_omits_absent_fields() -> None:
    message = ChatMessage.user(
        [
            TextBlock(text="What's in this picture?"),
            ImageBlock(mime_type="image/png", data="aGk="),
        ]
    )
    image = message.model_dump(mode="json", exclude_none=True)["blocks"][1]
    assert image == {"type": "image", "mime_type": "image/png", "data": "aGk="}


def test_mixed_blocks_round_trip_preserves_order_and_types() -> None:
    original = ChatMessage(
        role=MessageRole.TOOL,
        blocks=[
            TextBlock(text="before"),
            ImageBlock(
                mime_type="image/jpeg",
                url="https://example.org/cat.jpg",
                name="cat.jpg",
                size_bytes=123,
            ),
            DocumentBlock(mime_type="application/pdf", data="cGRm"),
            TextBlock(text="after"),
        ],
    )
    restored = ChatMessage.model_validate_json(original.model_dump_json())
    assert restored == original
    assert [type(b).__name__ for b in restored.blocks] == [
        "TextBlock",
        "ImageBlock",
        "DocumentBlock",
        "TextBlock",
    ]
    assert restored.text == "beforeafter"


def test_audio_and_video_round_trip() -> None:
    original = ChatMessage.user(
        [
            AudioBlock(mime_type="audio/wav", data="d2F2"),
            VideoBlock(mime_type="video/mp4", url="https://example.org/v.mp4"),
        ]
    )
    restored = ChatMessage.model_validate_json(original.model_dump_json())
    assert restored == original


def test_java_wire_shape_deserializes() -> None:
    """The exact JSON the Java API emits validates into typed blocks."""
    payload = {
        "role": "user",
        "blocks": [
            {"type": "text", "text": "hi"},
            {"type": "image", "mime_type": "image/png", "data": "aGk="},
        ],
        "tool_calls": [],
        "extra_args": {},
    }
    message = ChatMessage.model_validate(payload)
    assert isinstance(message.blocks[0], TextBlock)
    assert isinstance(message.blocks[1], ImageBlock)
    assert message.text == "hi"


def test_legacy_content_kwarg_fails_loudly() -> None:
    """The replaced `content` field is rejected, never silently dropped."""
    with pytest.raises(ValidationError):
        ChatMessage(role=MessageRole.USER, content="hi")


def test_media_requires_exactly_one_source() -> None:
    with pytest.raises(ValidationError):
        ImageBlock(mime_type="image/png")
    with pytest.raises(ValidationError):
        ImageBlock(mime_type="image/png", data="aGk=", url="https://example.org/x")


def test_factories_and_text_projection() -> None:
    assert ChatMessage.system("be nice").role == MessageRole.SYSTEM
    assert ChatMessage.assistant("ok").text == "ok"
    assert ChatMessage.tool("result").blocks == [TextBlock(text="result")]
    # Empty text becomes an empty block list rather than an empty text block.
    empty = ChatMessage.user("")
    assert empty.blocks == []
    assert empty.text == ""
    empty.set_text("replaced")
    assert empty.text == "replaced"
    assert str(ChatMessage.user("hi")) == "user: hi"
