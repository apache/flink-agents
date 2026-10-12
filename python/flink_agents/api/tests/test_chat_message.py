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

import pytest
from pydantic import ValidationError

from flink_agents.api.chat_message import (
    AudioBlock,
    Base64Source,
    ChatMessage,
    DocumentBlock,
    ImageBlock,
    MediaBlock,
    MessageRole,
    ReasoningBlock,
    TextBlock,
    ToolCallBlock,
    ToolResultBlock,
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
        "metadata": {},
    }


@pytest.mark.parametrize("blocks", [None, [None]])
def test_null_blocks_rejected_at_construction_and_assignment(blocks) -> None:
    with pytest.raises(ValidationError):
        ChatMessage(role=MessageRole.USER, blocks=blocks)
    message = ChatMessage.user("original")
    with pytest.raises(ValidationError):
        message.blocks = blocks
    assert message.text == "original"


def test_block_list_is_immutable_and_copies_input() -> None:
    blocks = [TextBlock(text="original")]
    message = ChatMessage.user(blocks)
    blocks.clear()
    assert message.text == "original"
    with pytest.raises(AttributeError):
        message.blocks.append(TextBlock(text="appended"))
    with pytest.raises(ValidationError):
        message.blocks = ()
    assert message.with_blocks([TextBlock(text="replacement")]).text == "replacement"
    assert message.text == "original"
    assert isinstance(message.model_dump(mode="json")["blocks"], list)


@pytest.mark.parametrize("kind", ["image", "audio", "video", "document"])
@pytest.mark.parametrize("field", ["media_type", "name", "sha256"])
@pytest.mark.parametrize("value", [5, True, 1.5])
def test_media_strings_reject_coercion(kind, field, value) -> None:
    payload = {
        "type": kind,
        "media_type": "image/png",
        "source": {"type": "base64", "data": "aGk="},
        field: value,
    }
    with pytest.raises(ValidationError):
        ChatMessage.model_validate({"role": "user", "blocks": [payload]})


@pytest.mark.parametrize("value", [-1, True, False, 1.5, 1.0, "1", "1.0", 2**63])
def test_size_bytes_rejects_invalid_values(value) -> None:
    with pytest.raises(ValidationError):
        ImageBlock.from_base64("image/png", "aGk=", size_bytes=value)


@pytest.mark.parametrize("value", [None, 0, 2**63 - 1])
def test_size_bytes_accepts_boundaries(value) -> None:
    block = ImageBlock.from_base64("image/png", "aGk=", size_bytes=value)
    assert block.size_bytes == value


@pytest.mark.parametrize("value", [5, True, 1.5, b"aGk="])
def test_sources_reject_non_strings(value) -> None:
    with pytest.raises(ValidationError):
        Base64Source(data=value)
    with pytest.raises(ValidationError):
        UrlSource(url=value)


@pytest.mark.parametrize(
    "data", ["=", "===", "a", "a===", "aGk==", "aG=k", "aGk\n", "aG-_", "图像"]
)
def test_base64_preserves_unvalidated_payload(data) -> None:
    """The source carries data; malformed input must not produce negative sizes."""
    source = Base64Source(data=data)
    assert source.size_bytes >= 0
    assert Base64Source.model_validate_json(source.model_dump_json()) == source
    assert source.model_dump()["data"] == data


@pytest.mark.parametrize(
    ("data", "size"), [("YQ==", 1), ("YQ", 1), ("aGk=", 2), ("aGk", 2), ("YWJj", 3)]
)
def test_base64_size_and_wire_preservation(data, size) -> None:
    source = Base64Source(data=data)
    assert source.size_bytes == size
    assert source.model_dump()["data"] == data


def test_media_representations_hide_payloads_but_wire_preserves_them() -> None:
    data = "c2VjcmV0"
    url = "https://user:password@example.org/x?token=secret"
    message = ChatMessage.user(
        [
            ImageBlock.from_base64("image/png", data),
            ImageBlock.from_url("image/png", url),
        ]
    )
    assert data not in repr([message])
    assert url not in repr([message])
    assert url not in str(message.blocks[1])
    dumped = message.model_dump(mode="json")
    assert dumped["blocks"][0]["source"]["data"] == data
    assert dumped["blocks"][1]["source"]["url"] == url


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
        role=MessageRole.USER,
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
        "metadata": {},
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
    # Unknown field on a base64 source.
    {
        "type": "image",
        "media_type": "image/png",
        "source": {"type": "base64", "data": "aGk=", "url": "https://example.org/x"},
    },
    # Unknown field on a URL source.
    {
        "type": "image",
        "media_type": "image/png",
        "source": {"type": "url", "url": "https://example.org/x", "data": "aGk="},
    },
    # Unknown field on a media block.
    {
        "type": "image",
        "media_type": "image/png",
        "source": {"type": "base64", "data": "aGk="},
        "caption": "x",
    },
    # Unknown field on a text block.
    {"type": "text", "text": "hi", "caption": "x"},
    # Explicit null text.
    {"type": "text", "text": None},
    # Non-string text.
    {"type": "text", "text": 5},
]


@pytest.mark.parametrize("payload", _INVALID_WIRE_PAYLOADS)
def test_invalid_wire_payloads_rejected(payload: dict) -> None:
    """The wire path rejects exactly the payloads Java rejects."""
    with pytest.raises(ValidationError):
        ChatMessage.model_validate({"role": "user", "blocks": [payload]})


def test_text_block_null_contract() -> None:
    """An omitted text defaults to empty, as in Java; None text is rejected."""
    message = ChatMessage.model_validate({"role": "user", "blocks": [{"type": "text"}]})
    assert message.blocks[0] == TextBlock(text="")
    with pytest.raises(ValidationError):
        TextBlock(text=None)


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
    result = ToolResultBlock(call_id="call", blocks=[TextBlock(text="result")])
    assert ChatMessage.tool(result).blocks == (result,)
    # Empty text becomes an empty block list rather than an empty text block.
    empty = ChatMessage.user("")
    assert empty.blocks == ()
    assert empty.text == ""
    assert empty.with_blocks([TextBlock(text="replaced")]).text == "replaced"
    assert str(ChatMessage.user("hi")) == "user: hi"


_MEDIA_TYPES = [
    (ImageBlock, "image/png"),
    (AudioBlock, "audio/wav"),
    (VideoBlock, "video/mp4"),
    (DocumentBlock, "application/pdf"),
]


@pytest.mark.parametrize(("block_type", "media_type"), _MEDIA_TYPES)
@pytest.mark.parametrize(
    "data", [b"\xff", b"\xff\x00", b"\xff\x00\x80", bytes(range(256))]
)
def test_from_bytes_preserves_binary_and_wire_contract(
    block_type: type[MediaBlock], media_type: str, data: bytes
) -> None:
    """Exercise padding, non-UTF-8 bytes, and payloads longer than a MIME line."""
    block = block_type.from_bytes(media_type, data)
    encoded = block.source.data
    assert "\n" not in encoded
    assert "\r" not in encoded
    assert base64.b64decode(encoded, validate=True) == data
    assert len(encoded) == 4 * ((len(data) + 2) // 3)
    assert block.source.size_bytes == len(data)
    assert block.size_bytes is None
    assert block == block_type.from_base64(
        media_type, base64.b64encode(data).decode("ascii")
    )
    message = ChatMessage.user([block])
    assert ChatMessage.model_validate_json(message.model_dump_json()) == message
    assert message.model_dump(mode="json", exclude_none=True)["blocks"] == [
        {
            "type": block.type,
            "media_type": media_type,
            "source": {"type": "base64", "data": encoded},
        }
    ]
    assert encoded not in repr(message)
    assert encoded not in str(block)


@pytest.mark.parametrize(("block_type", "media_type"), _MEDIA_TYPES)
@pytest.mark.parametrize(
    "data", [None, "aGk=", 3, [1, 2], bytearray(b"hi"), memoryview(b"hi")]
)
def test_from_bytes_rejects_non_bytes(block_type, media_type, data) -> None:
    with pytest.raises(TypeError, match="must be bytes"):
        block_type.from_bytes(media_type, data)


@pytest.mark.parametrize(("block_type", "media_type"), _MEDIA_TYPES)
def test_from_bytes_validation_and_metadata(block_type, media_type) -> None:
    with pytest.raises(ValueError, match="must not be empty"):
        block_type.from_bytes(media_type, b"")
    for invalid_media_type in (None, "", 3):
        with pytest.raises(ValidationError):
            block_type.from_bytes(invalid_media_type, b"hi")
    with pytest.raises(ValidationError):
        block_type.from_bytes(media_type, b"hi", size_bytes=-1)
    metadata = {"name": "sample", "size_bytes": 2, "sha256": "caller-supplied"}
    assert block_type.from_bytes(
        media_type, b"hi", **metadata
    ) == block_type.from_base64(media_type, "aGk=", **metadata)
    # Existing Base64 factories neither encode again nor start validating syntax.
    assert (
        block_type.from_base64(media_type, "not base64!").source.data == "not base64!"
    )


def test_nested_metadata_and_input_use_ordinary_containers() -> None:
    data = {"nested": [{"x": 1}]}
    call = ToolCallBlock(call_id="call", name="tool", input=data, metadata=data)
    data["nested"][0]["x"] = 2
    assert call.input["nested"][0]["x"] == 2
    call.input["nested"][0]["x"] = 3
    assert data["nested"][0]["x"] == 3
    call.metadata["new"] = 1
    assert call.metadata["new"] == 1
    assert ToolCallBlock.model_validate_json(call.model_dump_json()) == call


@pytest.mark.parametrize("value", [object(), float("nan"), {1: "bad"}])
def test_metadata_and_tool_input_accept_arbitrary_values(value) -> None:
    message = ChatMessage.user("hi", metadata={"custom": value})
    assert message.metadata["custom"] is value
    message.metadata["added"] = value
    assert message.metadata["added"] is value
    call = ToolCallBlock(call_id="id", name="tool", input={"custom": value})
    assert call.input["custom"] is value


def test_tool_and_reasoning_blocks_roundtrip_without_text_leak() -> None:
    call = ToolCallBlock(call_id="provider-id", name="weather", input={"city": "杭州"})
    message = ChatMessage.assistant(
        [ReasoningBlock(text="private"), call, TextBlock(text="answer")]
    )
    restored = ChatMessage.model_validate_json(message.model_dump_json())
    assert restored == message
    assert restored.text == "answer"
    assert restored.tool_calls == (call,)
    assert "tool_calls" not in restored.model_dump()
    with pytest.raises(ValidationError, match="Duplicate"):
        ChatMessage.assistant([call, call])
    for role in (MessageRole.USER, MessageRole.SYSTEM, MessageRole.TOOL):
        with pytest.raises(ValidationError):
            ChatMessage(role=role, blocks=[call])
    with pytest.raises(ValidationError):
        ToolResultBlock(call_id="id", blocks=[call])
