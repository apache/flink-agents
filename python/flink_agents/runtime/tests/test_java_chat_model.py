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
from pydantic import BaseModel

from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import (
    ChatMessage,
    MessageRole,
    ReasoningBlock,
    ToolCallBlock,
)
from flink_agents.plan.resource.java.conversions import from_java_chat_message
from flink_agents.plan.resource.java.java_chat_model import (
    JavaChatModelSetup,
    _to_java_chat_message,
)
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


class _Answer(BaseModel):
    text: str


def _java_chat_model_setup(
    **arguments: Any,
) -> tuple[JavaChatModelSetup, Mock, Mock]:
    j_resource = Mock()
    adapter = Mock()
    setup = JavaChatModelSetup(
        j_resource=j_resource,
        j_resource_adapter=adapter,
        connection="connection",
        model="model",
        **arguments,
    )
    return setup, j_resource, adapter


def test_java_chat_model_setup_builds_without_a_model() -> None:
    """A Java setup declared from Python may omit the model and let the Java
    setup apply its default, so the wrapper must not reject the omission.
    """
    setup = JavaChatModelSetup(
        j_resource=Mock(),
        j_resource_adapter=Mock(),
        connection="connection",
    )

    assert setup.connection == "connection"


@pytest.mark.parametrize("strategy", [None, "auto", "PROMPT"])
def test_java_chat_model_setup_never_applies_native_structured_output(
    strategy: str | None,
) -> None:
    """No connection is resolved on this side, so the gate answers without one.

    The strategy is passed the way a descriptor argument reaches the bridge,
    including a descriptor that sets none.
    """
    arguments = {} if strategy is None else {"structured_output_strategy": strategy}
    setup, j_resource, adapter = _java_chat_model_setup(**arguments)

    assert (
        setup.will_apply_native_structured_output(OutputSchema(output_schema=_Answer))
        is False
    )
    assert j_resource.mock_calls == []
    assert adapter.mock_calls == []


def test_java_chat_model_setup_rejects_native_strategy_with_a_schema() -> None:
    """NATIVE cannot be honored across the bridge, so it fails instead of degrading."""
    setup, j_resource, _ = _java_chat_model_setup(structured_output_strategy="NATIVE")

    with pytest.raises(ValueError, match="JavaChatModelSetup"):
        setup.will_apply_native_structured_output(OutputSchema(output_schema=_Answer))
    assert j_resource.mock_calls == []


def test_java_chat_model_setup_native_strategy_without_a_schema_is_false() -> None:
    """An unconstrained call under NATIVE has nothing to carry, so it is not refused."""
    setup, _, _ = _java_chat_model_setup(structured_output_strategy="NATIVE")

    assert setup.will_apply_native_structured_output(None) is False


@pytest.mark.parametrize(
    "call_kwargs",
    [{"tools": []}, {"output_schema": OutputSchema(output_schema=_Answer)}],
    ids=["tools", "output_schema"],
)
def test_java_chat_model_setup_refuses_tools_or_output_schema(
    call_kwargs: dict[str, Any],
) -> None:
    """Tools or a schema are refused before anything crosses to Java: the bridge
    cannot send messages without the Java setup's bound prompt and tools, nor carry
    a schema.
    """
    setup, j_resource, adapter = _java_chat_model_setup()

    with pytest.raises(NotImplementedError, match="Java chat model setup"):
        setup.chat([ChatMessage.of(MessageRole.USER, "hi")], **call_kwargs)
    assert j_resource.mock_calls == []
    assert adapter.mock_calls == []
