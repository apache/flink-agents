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
from uuid import uuid4

import pytest
from pydantic import ValidationError

from flink_agents.api.chat_message import (
    ChatMessage,
    ImageBlock,
    ReasoningBlock,
    TextBlock,
    ToolCallBlock,
    ToolResultBlock,
)
from flink_agents.api.chat_result import ChatResult, TokenUsage
from flink_agents.api.events.chat_event import ChatResponseEvent
from flink_agents.api.events.event import Event
from flink_agents.api.events.tool_event import ToolRequestEvent, ToolResponseEvent
from flink_agents.api.tools.tool_response import ToolResponse


@pytest.mark.parametrize(
    "reason",
    [None, "", "stop", "tool_calls", "length", "content_filter", "some_vendor_reason"],
)
def test_finish_reason_roundtrip_preserves_open_values(reason):
    response = ChatResult(
        message=ChatMessage.assistant([TextBlock(text="answer")]), finish_reason=reason
    )
    event = ChatResponseEvent.success(uuid4(), response)
    restored = ChatResponseEvent.from_event(Event.from_json(event.model_dump_json()))
    assert restored.response.finish_reason == reason
    assert restored.response == response
    assert ChatResult.model_validate(response.model_dump()) == response


@pytest.mark.parametrize("reason", [True, 5, 1.5, [], {}])
def test_finish_reason_rejects_non_string_values(reason):
    wire = {
        "message": {
            "role": "assistant",
            "blocks": [{"type": "text", "text": "answer"}],
        },
        "finish_reason": reason,
    }
    with pytest.raises(ValidationError):
        ChatResult.model_validate(wire)
    with pytest.raises(ValidationError):
        ChatResult.model_validate_json(json.dumps(wire))


def test_response_event_roundtrip_preserves_envelope_and_parsed_output():
    response = ChatResult(
        message=ChatMessage.assistant([TextBlock(text="answer")]),
        model="local",
        usage=TokenUsage(
            prompt_tokens=0,
            prompt_token_details={"cached": 0},
            completion_token_details={"reasoning": 1},
        ),
        finish_reason="stop",
        metadata={"opaque": [1]},
    )
    event = ChatResponseEvent.success(uuid4(), response)
    event.attributes["structured_output"] = {"answer": 42}
    restored = ChatResponseEvent.from_event(Event.from_json(event.model_dump_json()))
    assert restored.response == response
    assert restored.response.usage.prompt_tokens == 0
    assert restored.response.usage.completion_tokens is None
    assert restored.response.usage.prompt_token_details == {"cached": 0}
    assert restored.response.usage.completion_token_details == {"reasoning": 1}
    restored.response.usage.prompt_token_details["cached"] = 1
    assert restored.response.usage.prompt_token_details["cached"] == 1
    assert set(response.model_dump()["usage"]) == {
        "prompt_tokens",
        "completion_tokens",
        "prompt_token_details",
        "completion_token_details",
    }
    assert restored.structured_output == {"answer": 42}
    assert restored.request_id == event.request_id
    assert "structured_output" not in restored.response.metadata


def test_response_preserves_ordered_blocks_and_projects_only_assistant_text():
    call = ToolCallBlock(call_id="provider-id", name="tool", input={"x": 1})
    blocks = [
        TextBlock(text="before"),
        ReasoningBlock(text="private"),
        ImageBlock.from_base64("image/png", "aGk="),
        call,
        TextBlock(text="after"),
    ]
    response = ChatResult(message=ChatMessage.assistant(blocks))
    blocks.clear()
    assert len(response.message.blocks) == 5
    assert response.text == "beforeafter"
    assert response.tool_calls == (call,)
    assert set(response.model_dump()) == {
        "message",
        "model",
        "response_id",
        "usage",
        "finish_reason",
        "metadata",
    }
    assert ChatResult.model_validate_json(response.model_dump_json()) == response
    with pytest.raises(ValidationError):
        response.message.blocks = ()
    assert ChatResult(message=ChatMessage.assistant([])).text == ""


@pytest.mark.parametrize(
    "wire",
    [
        {},
        {"message": None},
        {"message": {"role": "user", "blocks": []}},
        {"message": {"role": "system", "blocks": []}},
        {"message": {"role": "assistant", "blocks": [None]}},
        {
            "message": {
                "role": "assistant",
                "blocks": [{"type": "tool_result", "call_id": "id", "blocks": []}],
            }
        },
        {
            "message": {
                "role": "assistant",
                "blocks": [{"type": "tool_call", "call_id": "id", "name": "tool"}] * 2,
            }
        },
    ],
)
def test_response_rejects_invalid_blocks_before_dispatch(wire):
    with pytest.raises(ValidationError):
        ChatResult.model_validate(wire)
    with pytest.raises(ValidationError):
        ChatResult.model_validate_json(json.dumps(wire))


def test_tool_cycle_recovers_typed_results_with_original_id():
    request = ToolRequestEvent(
        "local", [ToolCallBlock(call_id="provider-id", name="tool", input={"x": 1})]
    )
    restored_request = ToolRequestEvent.from_event(
        Event.from_json(request.model_dump_json())
    )
    assert restored_request.tool_calls == request.tool_calls
    response = ToolResponse(result={"private": 1}, blocks=[TextBlock(text="public")])
    event = ToolResponseEvent(request.id, {"provider-id": response})
    restored = ToolResponseEvent.from_event(Event.from_json(event.model_dump_json()))
    result = restored.responses["provider-id"].to_result_block("provider-id")
    assert result == ToolResultBlock(
        call_id="provider-id", blocks=[TextBlock(text="public")]
    )
    assert restored.request_id == request.id
    assert "external_ids" not in restored.attributes
    assert ToolResponse.error("failed").to_result_block("provider-id").is_error
