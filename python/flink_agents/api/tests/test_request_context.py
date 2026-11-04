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
#  limitations under the License.
#################################################################################
"""Optional opaque context on built-in request and response events."""

import json
from uuid import UUID, uuid4

import pytest

from flink_agents.api.chat_message import ChatMessage, ToolCallBlock
from flink_agents.api.chat_result import ChatResult
from flink_agents.api.decision_models import (
    DecisionRequest,
    DecisionResult,
    PredicateAnswer,
    PredicateQuestion,
)
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.context_retrieval_event import (
    ContextRetrievalRequestEvent,
    ContextRetrievalResponseEvent,
)
from flink_agents.api.events.decision_event import (
    DecisionRequestEvent,
    DecisionResponseEvent,
)
from flink_agents.api.events.event import Event
from flink_agents.api.events.tool_event import ToolRequestEvent, ToolResponseEvent
from flink_agents.api.tools.tool_response import ToolResponse
from flink_agents.api.vector_stores.vector_store import Document

_CONTEXT = {
    "task": "summary",
    "item_id": "42",
    "n": 1,
    "ok": True,
    "tags": ["a"],
}


def test_omitted_context_is_absent_from_the_serialized_event() -> None:
    request = ChatRequestEvent(model="model", messages=[ChatMessage.user("hi")])
    assert request.context is None
    assert "context" not in request.attributes
    assert "context" not in request.model_dump_json()

    response = ChatResponseEvent.success(
        uuid4(), ChatResult(message=ChatMessage.assistant("ok"))
    )
    assert response.context is None
    assert "context" not in response.model_dump_json()


def test_empty_context_is_kept() -> None:
    request = ChatRequestEvent(
        model="model", messages=[ChatMessage.user("hi")], context={}
    )
    assert "context" in request.attributes
    assert request.context == {}


def test_each_request_and_response_round_trips_its_own_context() -> None:
    request_id = uuid4()
    decision_request = DecisionRequest(
        input="evidence",
        questions={"urgent": PredicateQuestion(instructions="Needs attention?")},
    )
    decision_result = DecisionResult(
        answers={"urgent": PredicateAnswer(probability_true=0.8)}
    )
    events = [
        ChatRequestEvent(
            model="model",
            messages=[ChatMessage.user("hi")],
            prompt_args={"tone": "brief"},
            context=_CONTEXT,
        ),
        ChatRequestEvent(
            model="model", messages=[ChatMessage.user("hi")], context="summary-42"
        ),
        ChatResponseEvent.success(
            request_id,
            ChatResult(message=ChatMessage.assistant("ok")),
            context=_CONTEXT,
        ),
        ChatResponseEvent.failed(
            request_id, "TimeoutError: timed out", 1, 2, context=_CONTEXT
        ),
        ToolRequestEvent(
            model="model",
            tool_calls=[ToolCallBlock(call_id="call-1", name="lookup", input={"x": 1})],
            context=_CONTEXT,
        ),
        ToolResponseEvent(
            request_id=request_id,
            responses={"call-1": ToolResponse.text("ok")},
            context=_CONTEXT,
        ),
        ContextRetrievalRequestEvent(
            query="what is flink", vector_store="store", max_results=2, context=_CONTEXT
        ),
        ContextRetrievalResponseEvent(
            request_id=request_id,
            query="what is flink",
            documents=[Document(content="body", id="d1")],
            context=_CONTEXT,
        ),
        DecisionRequestEvent(
            model="decision", request=decision_request, context=_CONTEXT
        ),
        DecisionResponseEvent.success(request_id, decision_result, context=_CONTEXT),
        DecisionResponseEvent.failed(
            request_id, "provider down", 2, 3, context=_CONTEXT
        ),
    ]
    for event in events:
        restored = Event.from_json(event.model_dump_json())
        assert type(restored) is type(event)
        assert restored.context == event.context
        assert restored.id == event.id


def test_foreign_json_context_survives_the_cross_language_boundary() -> None:
    request_json = """
    {
      "id": "00000000-0000-0000-0000-000000000001",
      "type": "_chat_request_event",
      "attributes": {
        "model": "test-model",
        "messages": [
          {"role": "user", "blocks": [{"type": "text", "text": "hi"}], "metadata": {}}
        ],
        "context": {"task": "summary", "item_id": "42", "n": 1, "ok": true, "tags": ["a"]}
      },
      "attachments": {"trace": "keep"}
    }
    """
    request = ChatRequestEvent.from_event(Event.from_json(request_json))
    assert request.model == "test-model"
    assert request.messages[0].text == "hi"
    assert request.context == _CONTEXT
    assert request.get_attachment("trace") == "keep"
    assert request.id == UUID("00000000-0000-0000-0000-000000000001")

    failed_json = """
    {
      "id": "00000000-0000-0000-0000-000000000001",
      "type": "_chat_response_event",
      "attributes": {
        "request_id": "00000000-0000-0000-0000-000000000002",
        "status": "FAILED",
        "response": null,
        "error": "TimeoutError: timed out",
        "retry_count": 2,
        "total_retry_wait_sec": 3,
        "context": {"task": "summary", "item_id": "42"}
      },
      "attachments": {}
    }
    """
    failed = ChatResponseEvent.from_event(Event.from_json(failed_json))
    assert failed.is_failed
    assert failed.error == "TimeoutError: timed out"
    assert failed.context == {"task": "summary", "item_id": "42"}


def test_unknown_attribute_is_still_rejected_when_context_is_present() -> None:
    event = Event(
        type=ChatRequestEvent.EVENT_TYPE,
        attributes={
            "model": "m",
            "messages": [ChatMessage.user("hi")],
            "context": {"task": "summary"},
            "extra": 1,
        },
    )
    with pytest.raises(ValueError, match="extra"):
        ChatRequestEvent.from_event(event)


def test_serialized_context_is_json_compatible() -> None:
    event = ChatRequestEvent(
        model="model", messages=[ChatMessage.user("hi")], context=_CONTEXT
    )
    payload = json.loads(event.model_dump_json())
    assert payload["attributes"]["context"] == _CONTEXT
    assert "context" not in payload["attributes"]["messages"][0]
