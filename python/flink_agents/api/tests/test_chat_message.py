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
    Base64Source,
    ChatMessage,
    DocumentBlock,
    ImageBlock,
    MessageRole,
    TextBlock,
    UrlSource,
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
            ImageBlock.from_base64("image/png", "aGk="),
        ]
    )
    image = message.model_dump(mode="json", exclude_none=True)["blocks"][1]
    # The payload location is a typed, discriminated source.
    assert image == {
        "type": "image",
        "media_type": "image/png",
        "source": {"type": "base64", "data": "aGk="},
    }


def test_mixed_blocks_round_trip_preserves_order_and_types() -> None:
    original = ChatMessage(
        role=MessageRole.TOOL,
        blocks=[
            TextBlock(text="before"),
            ImageBlock(
                media_type="image/jpeg",
                source=UrlSource(url="https://example.org/cat.jpg"),
                name="cat.jpg",
                size_bytes=123,
            ),
            DocumentBlock.from_base64("application/pdf", "cGRm"),
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
            AudioBlock.from_base64("audio/wav", "d2F2"),
            VideoBlock.from_url("video/mp4", "https://example.org/v.mp4"),
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
            {
                "type": "image",
                "media_type": "image/png",
                "source": {"type": "base64", "data": "aGk="},
            },
        ],
        "tool_calls": [],
        "extra_args": {},
    }
    message = ChatMessage.model_validate(payload)
    assert isinstance(message.blocks[0], TextBlock)
    assert isinstance(message.blocks[1], ImageBlock)
    assert isinstance(message.blocks[1].source, Base64Source)
    assert message.text == "hi"


def test_legacy_content_kwarg_fails_loudly() -> None:
    """The replaced `content` field is rejected, never silently dropped."""
    with pytest.raises(ValidationError):
        ChatMessage(role=MessageRole.USER, content="hi")


# Mirrored verbatim in the Java suite (ChatMessageSerializationTest
# .testJacksonPathValidation), so both languages agree on which wire values
# are valid.
_INVALID_WIRE_PAYLOADS = [
    # Missing source.
    {"type": "image", "media_type": "image/png"},
    # Unknown source kind.
    {
        "type": "image",
        "media_type": "image/png",
        "source": {"type": "blob", "blob_id": "b1"},
    },
    # Empty base64 payload.
    {
        "type": "image",
        "media_type": "image/png",
        "source": {"type": "base64", "data": ""},
    },
    # Empty URL.
    {"type": "image", "media_type": "image/png", "source": {"type": "url", "url": ""}},
    # Missing media type.
    {"type": "image", "source": {"type": "base64", "data": "aGk="}},
    # Empty media type.
    {"type": "image", "media_type": "", "source": {"type": "base64", "data": "aGk="}},
]


@pytest.mark.parametrize("payload", _INVALID_WIRE_PAYLOADS)
def test_invalid_wire_payloads_rejected(payload: dict) -> None:
    """The wire path rejects exactly the payloads Java rejects."""
    with pytest.raises(ValidationError):
        ChatMessage.model_validate({"role": "user", "blocks": [payload]})


def test_media_type_must_not_be_empty() -> None:
    """Java rejects an empty media type, so the Python model must too."""
    with pytest.raises(ValidationError):
        ImageBlock.from_base64("", "aGk=")
    with pytest.raises(ValidationError):
        ImageBlock(media_type="", source=Base64Source(data="aGk="))


def test_blocks_are_frozen() -> None:
    """Blocks are immutable value objects, so sharing them never shares state."""
    text = TextBlock(text="hi")
    with pytest.raises(ValidationError):
        text.text = "mutated"
    image = ImageBlock.from_base64("image/png", "aGk=")
    with pytest.raises(ValidationError):
        image.source = UrlSource(url="https://example.org/x")
    with pytest.raises(ValidationError):
        image.source.data = "bXV0YXRlZA=="
    assert text.text == "hi"
    assert image.source == Base64Source(data="aGk=")


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
