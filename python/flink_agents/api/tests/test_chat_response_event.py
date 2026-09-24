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

"""Terminal chat response contracts."""

from uuid import uuid4

import pytest

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.events.chat_event import ChatResponseError, ChatResponseEvent
from flink_agents.api.events.event import Event


def test_failed_event_round_trip_and_response_access() -> None:
    request_id = uuid4()
    event = ChatResponseEvent.failed(request_id, "TimeoutError: timed out", 2, 3)
    restored = ChatResponseEvent.from_event(
        Event.model_validate_json(event.model_dump_json())
    )
    assert restored.id == event.id
    assert restored.is_failed
    assert restored.error == "TimeoutError: timed out"
    assert restored.retry_count == 2
    assert restored.total_retry_wait_sec == 3
    with pytest.raises(ChatResponseError, match="TimeoutError: timed out") as exc:
        _ = restored.response
    assert exc.value.request_id == request_id


def test_success_response_and_error_access() -> None:
    event = ChatResponseEvent.success(
        uuid4(), ChatMessage(role=MessageRole.ASSISTANT, content="ok")
    )
    restored = ChatResponseEvent.from_event(
        Event.model_validate_json(event.model_dump_json())
    )
    assert restored.is_success
    assert restored.response.content == "ok"
    with pytest.raises(RuntimeError, match="no error"):
        _ = restored.error


@pytest.mark.parametrize(
    ("status", "response", "error"),
    [
        ("FAILED", ChatMessage(role=MessageRole.ASSISTANT, content="ok"), "bad"),
        ("SUCCESS", None, None),
        ("SUCCESS", ChatMessage(role=MessageRole.ASSISTANT, content="ok"), "bad"),
        ("FAILED", None, ""),
        ("UNKNOWN", None, None),
    ],
)
def test_invalid_payload_rejected(status, response, error) -> None:
    with pytest.raises(ValueError):
        ChatResponseEvent(uuid4(), status, response, error)


def test_old_event_without_status_rejected() -> None:
    base = Event(
        type=ChatResponseEvent.EVENT_TYPE,
        attributes={
            "request_id": uuid4(),
            "response": ChatMessage(role=MessageRole.ASSISTANT, content="ok"),
        },
    )
    with pytest.raises(KeyError, match="status"):
        ChatResponseEvent.from_event(base)


def test_react_consumer_propagates_unhandled_failure() -> None:
    from flink_agents.api.agents.react_agent import ReActAgent

    event = ChatResponseEvent.failed(uuid4(), "provider failed")
    with pytest.raises(ChatResponseError, match="provider failed"):
        ReActAgent.stop_action(event, None)
