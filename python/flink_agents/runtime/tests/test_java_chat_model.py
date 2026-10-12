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
from typing import Any
from unittest.mock import Mock

import pytest

from flink_agents.api.chat_message import ChatMessage, ReasoningBlock, ToolCallBlock
from flink_agents.plan.resource.java.conversions import from_java_chat_message
from flink_agents.plan.resource.java.java_chat_model import _to_java_chat_message
from flink_agents.runtime.java_resource_adapter import JavaResourceAdapterImpl
from flink_agents.runtime.python_java_utils import (
    from_java_chat_message as runtime_from_java_chat_message,
)
from flink_agents.runtime.python_java_utils import from_java_chat_result


class _JavaResourceAdapter:
    def __init__(self) -> None:
        self.arguments: tuple[Any, ...] | None = None
        self.result = object()

    def fromPythonChatMessage(self, *arguments: Any) -> Any:
        self.arguments = arguments
        return self.result


def test_to_java_chat_message_extracts_java_safe_fields() -> None:
    adapter = _JavaResourceAdapter()
    message = ChatMessage.assistant(
        [
            ToolCallBlock(call_id="provider-id", name="lookup", input={}),
        ],
        metadata={"provider": "local"},
    )

    result = _to_java_chat_message(adapter, message)

    assert result is adapter.result
    # Content crosses as block maps in the wire shape, never as a flattened string.
    assert adapter.arguments == (message.model_dump(mode="json"),)


def test_from_java_chat_result_recovers_assistant_message() -> None:
    class JavaResponse:
        def toMap(self) -> dict:
            return {
                "message": {
                    "role": "assistant",
                    "metadata": {"turn": 1},
                    "blocks": [
                        {"type": "reasoning", "text": "private"},
                        {"type": "text", "text": "answer"},
                        {
                            "type": "tool_call",
                            "call_id": "provider-id",
                            "name": "lookup",
                        },
                    ],
                },
                "response_id": "response-id",
                "usage": {"prompt_tokens": 0, "completion_tokens": None},
                "finish_reason": "tool_calls",
                "metadata": {"opaque": [1]},
            }

    response = from_java_chat_result(JavaResponse())
    assert response.text == "answer"
    assert isinstance(response.message.blocks[0], ReasoningBlock)
    assert response.tool_calls[0].call_id == "provider-id"
    assert response.response_id == "response-id"
    assert response.usage.prompt_tokens == 0
    assert response.usage.completion_tokens is None
    assert response.metadata == {"opaque": [1]}
    assert response.message.metadata == {"turn": 1}
    assert "blocks" not in response.model_dump()


def _multimodal_blocks() -> list[dict]:
    return [
        {"type": "text", "text": "Describe the image"},
        {
            "type": "image",
            "media_type": "image/png",
            "source": {"type": "url", "url": "https://example.com/image.png"},
        },
    ]


def test_plan_message_conversion_preserves_media_through_runtime_adapter() -> None:
    bridge = _JavaResourceAdapter()
    message = ChatMessage.model_validate(
        {"role": "user", "blocks": _multimodal_blocks()}
    )

    result = _to_java_chat_message(JavaResourceAdapterImpl(bridge), message)

    assert result is bridge.result
    assert bridge.arguments == (message.model_dump(mode="json"),)


@pytest.mark.parametrize(
    "convert", [from_java_chat_message, runtime_from_java_chat_message]
)
def test_java_message_conversion_preserves_media_in_plan_and_runtime(convert) -> None:
    message = Mock()
    message.toMap.return_value = {
        "role": "user",
        "blocks": _multimodal_blocks(),
        "metadata": {"provider": "test"},
    }

    result = convert(message)

    assert (
        result.model_dump(mode="json", exclude_none=True)["blocks"]
        == _multimodal_blocks()
    )
    assert result.metadata == {"provider": "test"}
    message.getContent.assert_not_called()
