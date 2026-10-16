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
################################################################################
import json
from typing import Any

import httpx
import pytest
from openai import DEFAULT_MAX_RETRIES, DEFAULT_TIMEOUT, APIStatusError, OpenAI
from pydantic import ValidationError

from flink_agents.api.decision_models import (
    ChoiceAnswer,
    ChoiceProbability,
    ChoiceQuestion,
    DecisionRequest,
    DecisionResult,
    PredicateAnswer,
    PredicateQuestion,
    RefusalAnswer,
    ScoreAnswer,
    ScoreProbability,
    ScoreQuestion,
)
from flink_agents.integrations.decision_models import openai_decision_model
from flink_agents.integrations.decision_models.openai_decision_model import (
    OpenAIDecisionModelConnection,
    OpenAIDecisionModelSetup,
)


@pytest.fixture
def sdk_transport(monkeypatch):
    """Exercise real SDK serialization/parsing, with HTTP replaced by MockTransport."""
    connections = []
    clients = []
    requests = []

    def create(
        response, *, status=200, **kwargs: Any
    ) -> tuple[OpenAIDecisionModelConnection, list, list]:
        def handle(request) -> httpx.Response:
            requests.append(request)
            return httpx.Response(status, json=response)

        def client_factory(**options: Any) -> OpenAI:
            client = OpenAI(
                **options,
                http_client=httpx.Client(transport=httpx.MockTransport(handle)),
            )
            clients.append(client)
            return client

        monkeypatch.setattr(openai_decision_model, "OpenAI", client_factory)
        connection = OpenAIDecisionModelConnection(
            **{
                "api_key": "test-key",
                "base_url": "https://decision.example/v1",
                **kwargs,
            }
        )
        connections.append(connection)
        return connection, requests, clients

    yield create
    for connection in connections:
        connection.close()
    for client in clients:
        client.close()


def predicate_request():
    return DecisionRequest(
        input='{"message": "broken screen"}',
        questions={"damaged": PredicateQuestion(instructions="Is the item damaged?")},
    )


def predicate_response():
    # Wire fixture follows the official Decisions API reference, retrieved 2026-10-13:
    # https://developers.openai.com/api/reference/resources/decisions/methods/create
    return {
        "model": "gpt-6-luna",
        "answers": [{"type": "predicate", "name": "damaged", "probability": 0.95}],
        "usage": {
            "input_tokens": 42,
            "input_tokens_details": {"cached_tokens": 0, "cache_write_tokens": 0},
            "output_tokens": 0,
            "output_tokens_details": {"reasoning_tokens": 0},
            "total_tokens": 42,
        },
    }


def mixed_request():
    return DecisionRequest(
        input="导出失败。已重试两次。",
        questions={
            "damaged": PredicateQuestion(instructions="Is the item damaged?"),
            "route": ChoiceQuestion(
                instructions="Choose the team.",
                criteria=[
                    {"value": "support", "description": "Product support"},
                    {"value": "billing", "description": "Billing questions"},
                ],
            ),
            "severity": ScoreQuestion(
                instructions="Rate the impact.",
                criteria=[
                    {"label": "Impact", "description": "Cosmetic only"},
                    {"label": "Impact", "description": "Workaround available"},
                    {"label": "Blocked"},
                ],
            ),
        },
    )


def mixed_response():
    # Choice/score fixtures use documented value/probability arrays, with order varied:
    # https://developers.openai.com/api/docs/guides/decisions
    response = predicate_response()
    response["answers"] = [
        {
            "type": "score",
            "name": "severity",
            "score": 1.1,
            "probabilities": [
                {"value": 2, "label": "Blocked", "probability": 0.2},
                {"value": 0, "label": "Impact", "probability": 0.1},
                {"value": 1, "label": "Impact", "probability": 0.7},
            ],
            "confidence": 0.55,
        },
        {
            "type": "choice",
            "name": "route",
            "choice": "support",
            "probabilities": [
                {"value": "billing", "probability": 0.4},
                {"value": "support", "probability": 0.6},
            ],
            "confidence": 0.2,
        },
        response["answers"][0],
    ]
    return response


def test_sdk_payload_and_named_answers(sdk_transport):
    response = mixed_response()
    response["answers"][0]["probabilities"][0]["label"] = "  Reported label  "
    connection, requests, _ = sdk_transport(response)
    request = mixed_request()
    setup = OpenAIDecisionModelSetup(connection=connection, model="gpt-6-luna")
    result = setup.decide(request)

    assert requests[0].method == "POST"
    assert str(requests[0].url) == "https://decision.example/v1/decisions"
    assert requests[0].headers["authorization"] == "Bearer test-key"
    body = json.loads(requests[0].content)
    assert body == {
        "model": "gpt-6-luna",
        "input": request.input,
        "questions": [
            {
                "name": "damaged",
                "type": "predicate",
                "instructions": "Is the item damaged?",
            },
            {
                "name": "route",
                "type": "choice",
                "instructions": "Choose the team.",
                "choices": [
                    {"value": "support", "description": "Product support"},
                    {"value": "billing", "description": "Billing questions"},
                ],
            },
            {
                "name": "severity",
                "type": "score",
                "instructions": "Rate the impact.",
                "levels": [
                    {"label": "Impact", "description": "Cosmetic only"},
                    {"label": "Impact", "description": "Workaround available"},
                    {"label": "Blocked"},
                ],
            },
        ],
    }
    assert result.answers == {
        "damaged": PredicateAnswer(probability_true=0.95),
        "route": ChoiceAnswer(
            choice="support",
            probabilities=[
                ChoiceProbability(value="billing", probability=0.4),
                ChoiceProbability(value="support", probability=0.6),
            ],
            provider_confidence=0.2,
        ),
        "severity": ScoreAnswer(
            score=1.1,
            probabilities=[
                ScoreProbability(value=0, label="Impact", probability=0.1),
                ScoreProbability(value=1, label="Impact", probability=0.7),
                ScoreProbability(value=2, label="  Reported label  ", probability=0.2),
            ],
            provider_confidence=0.55,
        ),
    }
    assert result.model == "gpt-6-luna"
    assert result.usage.model_dump() == {
        "input_tokens": 42,
        "output_tokens": 0,
        "total_tokens": 42,
    }
    assert result.metadata == {
        "usage": {
            "input_tokens_details": {"cached_tokens": 0, "cache_write_tokens": 0},
            "output_tokens_details": {"reasoning_tokens": 0},
        },
    }


def test_native_message_input_and_choice_options_are_preserved(sdk_transport):
    input_data = [{"role": "user", "content": "导出失败"}]
    criteria = [
        {"value": "support", "description": ""},
        {"value": "billing"},
    ]
    response = mixed_response()
    response["answers"] = [response["answers"][1]]
    connection, requests, _ = sdk_transport(response)
    connection.decide(
        DecisionRequest(
            input=input_data,
            questions={
                "route": ChoiceQuestion(
                    instructions="Choose the team.", criteria=criteria
                )
            },
        ),
        model="gpt-6-luna",
    )
    body = json.loads(requests[0].content)
    assert body["input"] == input_data
    assert body["questions"][0]["choices"] == criteria


def test_boolean_and_string_choices_keep_typed_identity_and_response_order(
    sdk_transport,
):
    criteria = [{"value": True}, {"value": "true"}, {"value": False}]
    response = {
        "answers": [
            {
                "name": "match",
                "type": "choice",
                "choice": True,
                "probabilities": [
                    {"value": "true", "probability": 0.2},
                    {"value": False, "probability": 0.1},
                    {"value": True, "probability": 0.7},
                ],
            }
        ]
    }
    connection, requests, _ = sdk_transport(response)
    result = connection.decide(
        DecisionRequest(
            input="Evidence",
            questions={
                "match": ChoiceQuestion(
                    instructions="Choose a match.", criteria=criteria
                )
            },
        ),
        model="gpt-6-luna",
    )
    assert json.loads(requests[0].content)["questions"][0]["choices"] == criteria
    expected = ChoiceAnswer(
        choice=True,
        probabilities=[
            ChoiceProbability(value="true", probability=0.2),
            ChoiceProbability(value=False, probability=0.1),
            ChoiceProbability(value=True, probability=0.7),
        ],
    )
    assert result.answers["match"] == expected
    restored = DecisionResult.model_validate_json(result.model_dump_json())
    assert restored == result
    assert restored.answers["match"].choice is True
    assert [type(item.value) for item in restored.answers["match"].probabilities] == [
        str,
        bool,
        bool,
    ]


@pytest.mark.parametrize("duplicate", [True, "true"])
def test_duplicate_choice_values_are_rejected(sdk_transport, duplicate):
    response = {
        "answers": [
            {
                "name": "match",
                "type": "choice",
                "choice": duplicate,
                "probabilities": [
                    {"value": duplicate, "probability": 0.5},
                    {"value": duplicate, "probability": 0.5},
                ],
            }
        ]
    }
    connection, _, _ = sdk_transport(response)
    request = DecisionRequest(
        input="Evidence",
        questions={
            "match": ChoiceQuestion(
                instructions="Choose a match.",
                criteria=[{"value": True}, {"value": "true"}],
            )
        },
    )
    with pytest.raises(ValueError):
        connection.decide(request, model="gpt-6-luna")


def test_text_preserved_and_missing_usage_unknown(sdk_transport):
    response = {
        "answers": predicate_response()["answers"],
    }
    connection, requests, _ = sdk_transport(response)
    request = predicate_request()
    result = connection.decide(request, model="requested-model")
    assert json.loads(requests[0].content)["input"] == request.input
    assert result.model is None
    assert result.usage is None
    assert result.metadata == {}


def test_partial_usage_does_not_infer_missing_counts(sdk_transport):
    response = predicate_response()
    response["usage"] = {"input_tokens": 7}
    connection, _, _ = sdk_transport(response)
    result = connection.decide(predicate_request(), model="gpt-6-luna")
    assert result.usage.input_tokens == 7
    assert result.usage.output_tokens is None
    assert result.usage.total_tokens is None


def test_refusal_keeps_other_answers_and_no_invented_reason(sdk_transport):
    response = mixed_response()
    response["answers"][1] = {"name": "route", "type": "refusal"}
    connection, _, _ = sdk_transport(response)
    result = connection.decide(mixed_request(), model="gpt-6-luna")
    assert result.answers["route"] == RefusalAnswer()
    assert result.answers["damaged"] == PredicateAnswer(probability_true=0.95)


@pytest.mark.parametrize(
    "answers",
    [
        [],
        [{"type": "predicate", "probability": 0.5}],
        [{"type": "predicate", "name": "unknown", "probability": 0.5}],
        [{"type": "predicate", "name": "damaged", "probability": 0.5}] * 2,
        [{"type": "refusal", "name": None}],
        [{"type": "choice", "name": "damaged"}],
        [{"type": "unsupported", "name": "damaged"}],
        [{"type": "predicate", "name": "damaged"}],
        [{"type": "predicate", "name": "damaged", "probability": 1.5}],
        [{"type": "predicate", "name": "damaged", "probability": "0.5"}],
        [{"type": "predicate", "name": "damaged", "probability": True}],
    ],
)
def test_malformed_answers_rejected(sdk_transport, answers):
    connection, _, _ = sdk_transport({"answers": answers})
    with pytest.raises(ValueError):
        connection.decide(predicate_request(), model="gpt-6-luna")


@pytest.mark.parametrize(
    ("answer_index", "values", "requested_choices"),
    [
        (0, [0, 0, 2], None),
        (0, [0, 1, 3], None),
        (0, [0, 1], None),
        (0, [0, 1, "2"], None),
        (0, [False, 1, 2], None),
        (1, ["support", "support"], None),
        (1, ["support", "unknown"], None),
        (1, ["support"], None),
        (1, ["support", True], None),
        pytest.param(1, [True, "support"], [1, "support"], id="true-is-not-one"),
        pytest.param(1, [False, "support"], [0, "support"], id="false-is-not-zero"),
    ],
)
def test_distribution_identity_rejected(
    sdk_transport, answer_index, values, requested_choices
):
    request = mixed_request()
    if requested_choices is not None:
        request.questions["route"].criteria[:] = [
            {"value": value} for value in requested_choices
        ]
    response = mixed_response()
    answer = response["answers"][answer_index]
    answer["probabilities"] = [
        {"value": value, "label": "Impact", "probability": 0.5} for value in values
    ]
    connection, requests, _ = sdk_transport(response)
    with pytest.raises(ValueError):
        connection.decide(request, model="gpt-6-luna")
    assert len(requests) == 1
    if requested_choices is not None:
        choices = json.loads(requests[0].content)["questions"][1]["choices"]
        assert choices == request.questions["route"].criteria
        assert type(choices[0]["value"]) is int


@pytest.mark.parametrize("mutation", ["out_of_range", "missing_label"])
def test_invalid_score_answer_is_rejected(sdk_transport, mutation):
    response = mixed_response()
    if mutation == "out_of_range":
        response["answers"][0]["score"] = 2.1
    else:
        del response["answers"][0]["probabilities"][0]["label"]
    connection, _, _ = sdk_transport(response)
    with pytest.raises(ValueError):
        connection.decide(mixed_request(), model="gpt-6-luna")


@pytest.mark.parametrize("status", [400, 401, 429, 500])
def test_request_errors_remain_sdk_errors(sdk_transport, status):
    connection, requests, _ = sdk_transport(
        {"error": {"message": "Provider request failed", "type": "test_error"}},
        status=status,
        max_retries=0,
    )
    with pytest.raises(APIStatusError) as error:
        connection.decide(predicate_request(), model="gpt-6-luna")
    assert error.value.status_code == status
    assert len(requests) == 1


def test_client_lazy_reused_closed_and_reopened(sdk_transport):
    connection, _, clients = sdk_transport(predicate_response())
    connection.close()
    assert not clients
    connection.decide(predicate_request(), model="gpt-6-luna")
    connection.decide(predicate_request(), model="gpt-6-luna")
    assert len(clients) == 1
    connection.close()
    assert clients[0].is_closed()
    connection.decide(predicate_request(), model="gpt-6-luna")
    assert len(clients) == 2


@pytest.mark.parametrize(
    ("options", "expected_timeout", "expected_retries"),
    [
        ({}, DEFAULT_TIMEOUT.as_dict(), DEFAULT_MAX_RETRIES),
        (
            {"timeout": None, "max_retries": None},
            DEFAULT_TIMEOUT.as_dict(),
            DEFAULT_MAX_RETRIES,
        ),
        (
            {"timeout": 12.0, "max_retries": 0},
            {"connect": 12.0, "read": 12.0, "write": 12.0, "pool": 12.0},
            0,
        ),
    ],
)
def test_sdk_transport_defaults_and_overrides(
    sdk_transport, options, expected_timeout, expected_retries
):
    connection, requests, clients = sdk_transport(predicate_response(), **options)
    connection.decide(predicate_request(), model="gpt-6-luna")
    assert requests[0].extensions["timeout"] == expected_timeout
    assert clients[0].max_retries == expected_retries


def test_environment_key_and_zero_timeout(sdk_transport, monkeypatch):
    monkeypatch.setenv("OPENAI_API_KEY", "environment-key")
    connection, requests, clients = sdk_transport(
        predicate_response(), api_key=None, timeout=0
    )
    connection.decide(predicate_request(), model="gpt-6-luna")
    assert requests[0].headers["authorization"] == "Bearer environment-key"
    assert clients[0].timeout is None


def test_model_name_is_required(sdk_transport):
    connection, _, _ = sdk_transport(predicate_response())
    with pytest.raises(ValidationError, match="model"):
        OpenAIDecisionModelSetup(connection=connection)
    with pytest.raises(ValueError, match="model"):
        connection.decide(predicate_request(), model=" ")


def test_open_reuses_client_without_request(sdk_transport):
    connection, requests, clients = sdk_transport(predicate_response())
    connection.open()
    connection.open()
    assert len(clients) == 1
    assert not requests


@pytest.mark.parametrize(
    "instructions",
    [
        {
            "question": "Evaluate the evidence",
            "policy": {"enabled": True, "locale": "中文"},
        },
        ["Evaluate the evidence", {"policy": ["first", "second"], "fallback": None}],
    ],
)
def test_structured_instructions_are_passed_to_sdk_without_encoding(
    sdk_transport, instructions
):
    request = mixed_request()
    request = DecisionRequest(
        input=request.input,
        questions={
            name: type(question)(
                **{**question.model_dump(), "instructions": instructions}
            )
            for name, question in request.questions.items()
        },
    )
    connection, requests, _ = sdk_transport(
        {"error": {"message": "Invalid instructions"}}, status=400
    )
    with pytest.raises(APIStatusError):
        connection.decide(request, model="gpt-6-luna")
    wire = json.loads(requests[0].content)
    assert {question["type"] for question in wire["questions"]} == {
        "predicate",
        "choice",
        "score",
    }
    for question in wire["questions"]:
        assert question["instructions"] == instructions


@pytest.mark.parametrize(
    "criteria",
    [
        {
            "true": {"condition": "A broken screen"},
            "false": ["Intact item", "Damaged packaging only"],
        },
        {"true": None, "false": "The item is intact"},
    ],
)
def test_predicate_criteria_are_ignored(sdk_transport, criteria):
    instructions = "Is the item damaged?"
    request = DecisionRequest(
        input="Package evidence",
        questions={
            "damaged": PredicateQuestion(instructions=instructions, criteria=criteria)
        },
    )
    connection, requests, _ = sdk_transport(predicate_response())
    result = connection.decide(request, model="gpt-6-luna")
    question = json.loads(requests[0].content)["questions"][0]
    assert question == {
        "name": "damaged",
        "type": "predicate",
        "instructions": instructions,
    }
    assert result.answers["damaged"] == PredicateAnswer(probability_true=0.95)


@pytest.mark.parametrize(
    "criterion",
    [
        "Blocked",
        ["High severity", {"workaround": False}],
        {"label": "Blocked", "description": {"workaround": False}},
    ],
)
def test_score_format_errors_are_left_to_sdk_and_service(sdk_transport, criterion):
    connection, requests, _ = sdk_transport(
        {"error": {"message": "Invalid levels"}}, status=400
    )
    request = DecisionRequest(
        input="Issue description",
        questions={
            "severity": ScoreQuestion(
                instructions="Rate the impact.",
                criteria=[{"label": "Minor"}, criterion],
            )
        },
    )
    with pytest.raises(APIStatusError):
        connection.decide(request, model="gpt-6-luna")
    assert (
        json.loads(requests[0].content)["questions"][0]["levels"]
        == request.questions["severity"].criteria
    )


@pytest.mark.parametrize(
    "description",
    [
        {"policy": "退款", "days": 30, "notes": None},
        ["退款", {"days": 30}],
        None,
    ],
)
def test_unsupported_choice_descriptions_reach_the_service_unchanged(
    sdk_transport, description
):
    connection, requests, _ = sdk_transport(
        {"error": {"message": "Invalid choices"}}, status=400
    )
    request = mixed_request()
    request.questions["route"].criteria[0]["description"] = description
    request.questions["route"].criteria[0]["extension"] = {"keep": True}
    del request.questions["route"].criteria[1]["description"]
    with pytest.raises(APIStatusError):
        connection.decide(request, model="gpt-6-luna")
    assert (
        json.loads(requests[0].content)["questions"][1]["choices"]
        == request.questions["route"].criteria
    )


@pytest.mark.parametrize("input_data", [{"ticket": "Evidence"}, ["Evidence", None]])
def test_provider_request_formats_are_not_rewritten_or_validated(
    sdk_transport, input_data
):
    connection, requests, _ = sdk_transport(
        {"error": {"message": "Invalid request"}}, status=400
    )
    request = DecisionRequest(
        input=input_data,
        questions={
            "route": ChoiceQuestion(
                instructions="Choose a route.",
                criteria={"a": None, "b": {"rule": "Evidence"}},
            )
        },
    )
    with pytest.raises(APIStatusError):
        connection.decide(request, model="gpt-6-luna")
    body = json.loads(requests[0].content)
    assert body["input"] == input_data
    assert body["questions"][0]["choices"] == request.questions["route"].criteria


def test_json_looking_string_instructions_are_preserved_verbatim(sdk_transport):
    instructions = '  {"question": "Is the item damaged?"}\n'
    connection, requests, _ = sdk_transport(predicate_response())
    connection.decide(
        DecisionRequest(
            input="Evidence",
            questions={"damaged": PredicateQuestion(instructions=instructions)},
        ),
        model="gpt-6-luna",
    )
    assert (
        json.loads(requests[0].content)["questions"][0]["instructions"] == instructions
    )
