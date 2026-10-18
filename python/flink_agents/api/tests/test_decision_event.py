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

"""Decision event identity, terminal status and nested DTO contracts."""

from uuid import uuid4

import pytest

from flink_agents.api.decision_models import (
    DecisionRequest,
    DecisionResult,
    PredicateQuestion,
    RefusalAnswer,
    ScoreAnswer,
)
from flink_agents.api.events.decision_event import (
    DecisionRequestEvent,
    DecisionResponseError,
    DecisionResponseEvent,
)
from flink_agents.api.events.event import Event


def test_request_restores_typed_payload_identity_and_attachments() -> None:
    request = DecisionRequest(
        input={"receipt": True},
        questions={"eligible": PredicateQuestion(instructions="Has a receipt?")},
    )
    params = {
        "timeout": 4.0,
        "extra_headers": {"X-Trace-ID": "追踪-1"},
        "extra_body": {"thresholds": [0.4, None]},
    }
    event = DecisionRequestEvent("registered-model", request, request_params=params)
    event.set_attachment("reference", {"key": "review"})
    params["extra_body"]["thresholds"].append(0.9)

    restored = Event.from_json(event.model_dump_json())
    assert isinstance(restored, DecisionRequestEvent)
    assert restored.model == "registered-model"
    assert restored.request == request
    assert restored.request_params == {
        "timeout": 4.0,
        "extra_headers": {"X-Trace-ID": "追踪-1"},
        "extra_body": {"thresholds": [0.4, None]},
    }
    assert restored.id == event.id
    assert restored.attachments == event.attachments
    assert DecisionRequestEvent.from_event(restored) == restored


def test_request_parameters_default_to_empty_after_restore() -> None:
    request = DecisionRequest(
        input="review", questions={"q": PredicateQuestion(instructions="Approve?")}
    )
    event = DecisionRequestEvent("registered-model", request)
    assert event.request_params == {}
    del event.attributes["request_params"]
    assert Event.from_json(event.model_dump_json()).request_params == {}


@pytest.mark.parametrize(
    "params",
    [
        {"timeout": float("inf")},
        {"headers": {1: "not a string key"}},
        [("timeout", 4)],
    ],
)
def test_request_parameters_require_json_objects_at_each_entry_point(params) -> None:
    request = DecisionRequest(
        input="review", questions={"q": PredicateQuestion(instructions="Approve?")}
    )
    with pytest.raises((ValueError, TypeError)):
        DecisionRequestEvent("registered-model", request, request_params=params)
    event = Event(
        type=DecisionRequestEvent.EVENT_TYPE,
        attributes={
            "model": "registered-model",
            "request": request,
            "request_params": params,
        },
    )
    with pytest.raises((ValueError, TypeError)):
        DecisionRequestEvent.from_event(event)


def test_refusal_is_a_successful_result_not_a_failed_request() -> None:
    result = DecisionResult(
        answers={"restricted": RefusalAnswer(reason="Cannot answer")}
    )
    event = DecisionResponseEvent.success(uuid4(), result, 1, 2)
    restored = Event.from_json(event.model_dump_json())
    assert isinstance(restored, DecisionResponseEvent)
    assert restored.is_success
    assert not restored.is_failed
    assert restored.response == result
    assert restored.request_id == event.request_id
    assert restored.retry_count == 1
    assert restored.total_retry_wait_sec == 2
    with pytest.raises(RuntimeError, match="no error"):
        _ = restored.error


def test_score_response_restores_integer_indices_and_original_descriptions() -> None:
    result = DecisionResult(
        answers={
            "impact": ScoreAnswer(
                score=0.8,
                probabilities={0: 0.2, 1: 0.8},
                legend={
                    0: "Low",
                    1: {"label": "业务内容", "description": [True, None]},
                },
            )
        }
    )
    event = DecisionResponseEvent.success(uuid4(), result)
    event.set_attachment("reference", {"key": "review"})
    wire_event = event.model_dump(mode="json")
    decoded = Event(type=event.type, attributes=wire_event["attributes"])

    for restored in (
        Event.from_json(event.model_dump_json()),
        DecisionResponseEvent.from_event(decoded),
    ):
        assert restored.response == result
        answer = restored.response.answers["impact"]
        assert list(answer.probabilities) == [0, 1]
        assert list(answer.legend) == [0, 1]
        assert answer.legend[1] == {"label": "业务内容", "description": [True, None]}


def test_failure_serializes_without_reading_throwing_response_property() -> None:
    event = DecisionResponseEvent.failed(uuid4(), "TimeoutError: timed out", 2, 3)
    event.set_attachment("reference", {"key": "review"})
    restored = Event.from_json(event.model_dump_json())
    assert isinstance(restored, DecisionResponseEvent)
    assert restored.id == event.id
    assert restored.attachments == event.attachments
    assert restored.is_failed
    assert restored.error == "TimeoutError: timed out"
    assert restored.retry_count == 2
    assert restored.total_retry_wait_sec == 3
    assert restored.get_attr("response") is None
    with pytest.raises(DecisionResponseError, match="TimeoutError: timed out") as error:
        _ = restored.response
    assert error.value.request_id == event.request_id
    assert DecisionResponseEvent.from_event(restored) == restored


@pytest.mark.parametrize(
    ("status", "response", "error"),
    [
        ("SUCCESS", None, None),
        ("FAILED", None, ""),
        ("FAILED", DecisionResult(answers={"q": RefusalAnswer()}), "failure"),
        ("SUCCESS", DecisionResult(answers={"q": RefusalAnswer()}), "failure"),
        ("UNKNOWN", None, None),
    ],
)
def test_inconsistent_terminal_payload_rejected(status, response, error) -> None:
    with pytest.raises(ValueError):
        DecisionResponseEvent(uuid4(), status, response, error)


def test_request_rejects_invalid_envelope_at_json_boundary() -> None:
    for attributes in (
        {"model": "decision"},
        {"model": "decision", "request": 1},
        {"model": "decision", "request": {}, "unknown": True},
    ):
        event = Event(type=DecisionRequestEvent.EVENT_TYPE, attributes=attributes)
        with pytest.raises(ValueError, match="Malformed built-in event"):
            Event.from_json(event.model_dump_json())
