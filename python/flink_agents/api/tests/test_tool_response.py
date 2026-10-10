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
import json

import pytest
from pydantic import TypeAdapter, ValidationError

from flink_agents.api.chat_message import (
    AudioBlock,
    ChatMessage,
    ContentBlock,
    DataContentBlock,
    DocumentBlock,
    ImageBlock,
    ReasoningBlock,
    TextBlock,
    ToolCallBlock,
    ToolResultBlock,
    VideoBlock,
)
from flink_agents.api.tools import ToolResponse


def test_tool_response_represents_success() -> None:
    response = ToolResponse.text("42", tool_name="calculator")

    assert response.is_success()
    assert not response.is_error()
    assert response.blocks == (TextBlock(text="42"),)
    assert str(response) == "42"


def test_tool_response_represents_failure() -> None:
    response = ToolResponse.error("not found", tool_name="lookup")

    assert response.is_error()
    assert not response.is_success()
    assert response.error_message == "not found"
    assert str(response) == "not found"


def test_tool_output_preserves_ordered_text_and_media() -> None:
    blocks = (
        TextBlock(text="before"),
        ImageBlock.from_base64("image/png", "aGk="),
        AudioBlock.from_base64("audio/wav", "aGk="),
        VideoBlock.from_url("video/mp4", "https://example.com/video.mp4"),
        DocumentBlock.from_url("application/pdf", "https://example.com/doc.pdf"),
        TextBlock(text="after"),
    )
    result = ToolResultBlock(call_id="call", blocks=blocks)
    response = ToolResponse(blocks=blocks, metadata={"internal": 42})
    assert response.to_result_block("call") == result
    assert result.text == "beforeafter"
    assert ToolResultBlock.model_validate_json(result.model_dump_json()) == result
    assert ToolResponse.model_validate_json(response.model_dump_json()) == response
    message = ChatMessage.tool(result)
    restored = ChatMessage.model_validate_json(message.model_dump_json())
    assert restored == message
    assert restored.blocks[0].blocks == blocks
    for block in blocks:
        assert (
            TypeAdapter(DataContentBlock).validate_json(block.model_dump_json())
            == block
        )
        assert TypeAdapter(ContentBlock).validate_json(block.model_dump_json()) == block


@pytest.mark.parametrize(
    "block",
    [
        ReasoningBlock(text="private"),
        ToolCallBlock(call_id="nested", name="tool"),
        ToolResultBlock(call_id="nested"),
    ],
)
def test_tool_output_rejects_non_data_blocks(block: ContentBlock) -> None:
    for value in (block, block.model_dump()):
        with pytest.raises(ValidationError, match="union_tag_invalid"):
            ToolResultBlock(call_id="call", blocks=[value])
        with pytest.raises(ValidationError, match="union_tag_invalid"):
            ToolResponse(blocks=[value])
    for model, payload in (
        (ToolResultBlock, {"call_id": "call", "blocks": [block.model_dump()]}),
        (ToolResponse, {"success": True, "blocks": [block.model_dump()]}),
        (
            ChatMessage,
            {
                "role": "tool",
                "blocks": [
                    {
                        "type": "tool_result",
                        "call_id": "call",
                        "blocks": [block.model_dump()],
                    }
                ],
            },
        ),
    ):
        with pytest.raises(ValidationError, match="union_tag_invalid"):
            model.model_validate(payload)
        with pytest.raises(ValidationError, match="union_tag_invalid"):
            model.model_validate_json(json.dumps(payload))


def test_tool_result_schema_lists_only_data_block_types() -> None:
    expected = {"text", "image", "audio", "video", "document"}
    for model in (ToolResultBlock, ToolResponse):
        blocks = model.model_json_schema()["properties"]["blocks"]
        assert set(blocks["items"]["discriminator"]["mapping"]) == expected


def test_metadata_is_not_model_content() -> None:
    response = ToolResponse(
        blocks=[TextBlock(text="visible")], metadata={"private": "internal"}
    )
    block = response.to_result_block("call")
    assert block.text == "visible"
    assert block.metadata == {}
    assert "internal" not in block.model_dump_json()
    wire = response.model_dump(mode="json")
    assert set(wire) == {
        "blocks",
        "metadata",
        "success",
        "error",
        "execution_time_ms",
        "tool_name",
    }
    assert ToolResponse.model_validate(wire) == response


def test_empty_response_stays_empty_without_null_text_fallback() -> None:
    response = ToolResponse()
    assert response.blocks == ()
    assert response.to_result_block("call").blocks == ()
    assert response.get_text() == ""


def test_error_projection_excludes_metadata() -> None:
    response = ToolResponse(error_message="failed", metadata={"binary": b"\xff"})
    block = response.to_result_block("call")
    assert block.is_error
    assert block.text == "failed"
    assert block.metadata == {}
