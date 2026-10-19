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
    ChoiceQuestion,
    DecisionRequest,
    PredicateAnswer,
    PredicateCriteria,
    PredicateQuestion,
    RefusalAnswer,
    ScoreAnswer,
    ScoreQuestion,
)
from flink_agents.integrations.decision_models import openai_decision_model
from flink_agents.integrations.decision_models.openai_decision_model import (
    OpenAIDecisionModelConnection,
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
                criteria={"support": "Product support", "billing": "Billing questions"},
            ),
            "severity": ScoreQuestion(
                instructions="Rate the impact.",
                criteria=[
                    "Cosmetic only",
                    {"label": "Impact", "description": "Workaround available"},
                    ["Blocked", {"workaround": False}],
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
                {"value": 2, "label": "2", "probability": 0.2},
                {"value": 0, "label": "0", "probability": 0.1},
                {"value": 1, "label": "1", "probability": 0.7},
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
    connection, requests, _ = sdk_transport(response, timeout=19.0)
    request = mixed_request()
    result = connection.decide(
        request,
        model="gpt-6-luna",
        request_params={
            "safety_identifier": "user-123",
            "extra_headers": {"x-request-id": "first"},
            "extra_query": {"tenant": "42"},
            "extra_body": {"custom": {"items": [None, True, 2, "退款"]}},
            "timeout": 0.5,
        },
    )

    assert requests[0].method == "POST"
    assert str(requests[0].url) == "https://decision.example/v1/decisions?tenant=42"
    assert requests[0].headers["authorization"] == "Bearer test-key"
    assert requests[0].headers["x-request-id"] == "first"
    assert requests[0].url.params["tenant"] == "42"
    assert requests[0].extensions["timeout"]["read"] == 0.5
    body = json.loads(requests[0].content)
    assert body == {
        "model": "gpt-6-luna",
        "input": request.input,
        "safety_identifier": "user-123",
        "custom": {"items": [None, True, 2, "退款"]},
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
                    {"label": "0", "description": "Cosmetic only"},
                    {
                        "label": "1",
                        "description": json.dumps(
                            request.questions["severity"].criteria[1]
                        ),
                    },
                    {
                        "label": "2",
                        "description": json.dumps(
                            request.questions["severity"].criteria[2]
                        ),
                    },
                ],
            },
        ],
    }
    assert result.answers == {
        "damaged": PredicateAnswer(probability_true=0.95),
        "route": ChoiceAnswer(
            choice="support",
            probabilities={"billing": 0.4, "support": 0.6},
            provider_confidence=0.2,
        ),
        "severity": ScoreAnswer(
            score=1.1,
            probabilities={0: 0.1, 1: 0.7, 2: 0.2},
            legend=dict(enumerate(request.questions["severity"].criteria)),
            provider_confidence=0.55,
        ),
    }
    assert list(result.answers["route"].probabilities) == ["billing", "support"]
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

    connection.decide(request, model="gpt-6-luna")
    assert "x-request-id" not in requests[1].headers
    assert "tenant" not in requests[1].url.params
    assert requests[1].extensions["timeout"]["read"] == 19.0
    assert {"safety_identifier", "custom"}.isdisjoint(json.loads(requests[1].content))


def test_json_input_is_encoded_and_undescribed_choices_are_preserved(sdk_transport):
    input_data = [{"role": "user", "content": "导出失败"}]
    criteria = {"support": "", "billing": None}
    response = mixed_response()
    response["answers"] = [response["answers"][1]]
    connection, requests, _ = sdk_transport(response)
    request = DecisionRequest(
        input=input_data,
        questions={
            "route": ChoiceQuestion(instructions="Choose the team.", criteria=criteria)
        },
    )
    before = request.model_dump()
    connection.decide(request, model="gpt-6-luna")
    body = json.loads(requests[0].content)
    assert json.loads(body["input"]) == input_data
    assert "导出失败" in body["input"]
    assert body["questions"][0]["choices"] == [
        {"value": "support", "description": ""},
        {"value": "billing"},
    ]
    assert request.model_dump() == before


@pytest.mark.parametrize("field", ["model", "usage"])
@pytest.mark.parametrize("missing", [True, False], ids=["missing", "null"])
def test_required_response_fields_rejected_by_sdk(sdk_transport, field, missing):
    response = predicate_response()
    if missing:
        del response[field]
    else:
        response[field] = None
    connection, _, _ = sdk_transport(response)
    with pytest.raises(ValidationError, match=field):
        connection.decide(predicate_request(), model="gpt-6-luna")


@pytest.mark.parametrize("answer_index", [0, 1], ids=["score", "choice"])
@pytest.mark.parametrize("missing", [True, False], ids=["missing", "null"])
def test_required_confidence_rejected_by_sdk(sdk_transport, answer_index, missing):
    response = mixed_response()
    answer = response["answers"][answer_index]
    if missing:
        del answer["confidence"]
    else:
        answer["confidence"] = None
    connection, _, _ = sdk_transport(response)
    with pytest.raises(ValidationError, match="confidence"):
        connection.decide(mixed_request(), model="gpt-6-luna")


@pytest.mark.parametrize(
    ("field", "details"),
    [
        ("input_tokens_details", {}),
        ("input_tokens_details", None),
        ("input_tokens_details", {"cached_tokens": "0", "cache_write_tokens": 0}),
        ("output_tokens_details", {}),
        ("output_tokens_details", {"reasoning_tokens": True}),
    ],
)
def test_malformed_usage_details_rejected_by_sdk(sdk_transport, field, details):
    response = predicate_response()
    response["usage"][field] = details
    connection, _, _ = sdk_transport(response)
    with pytest.raises(ValidationError, match=field):
        connection.decide(predicate_request(), model="gpt-6-luna")


def test_partial_usage_rejected_by_sdk(sdk_transport):
    response = predicate_response()
    response["usage"] = {"input_tokens": 7}
    connection, _, _ = sdk_transport(response)
    with pytest.raises(ValidationError, match="output_tokens"):
        connection.decide(predicate_request(), model="gpt-6-luna")


@pytest.mark.parametrize("index", ["0", False, 0.0], ids=["string", "bool", "float"])
def test_score_indices_are_not_coerced_by_sdk(sdk_transport, index):
    response = mixed_response()
    response["answers"][0]["probabilities"][1]["value"] = index
    connection, _, _ = sdk_transport(response)
    with pytest.raises(ValidationError, match="value"):
        connection.decide(mixed_request(), model="gpt-6-luna")


def test_refusal_keeps_other_answers_and_no_invented_reason(sdk_transport):
    response = mixed_response()
    response["answers"][1] = {"name": "route", "type": "refusal"}
    connection, _, _ = sdk_transport(response)
    result = connection.decide(mixed_request(), model="gpt-6-luna")
    assert result.answers["route"] == RefusalAnswer()
    assert result.answers["damaged"] == PredicateAnswer(probability_true=0.95)


@pytest.mark.parametrize(
    ("answer", "field", "error_type"),
    [
        ({"type": "unsupported", "name": "damaged"}, "type", "literal_error"),
        ({"type": "predicate", "name": "damaged"}, "probability", "missing"),
        (
            {"type": "predicate", "name": "damaged", "probability": "0.5"},
            "probability",
            "float_type",
        ),
        (
            {"type": "predicate", "name": "damaged", "probability": True},
            "probability",
            "float_type",
        ),
    ],
)
def test_malformed_answer_fields_are_rejected_by_sdk(
    sdk_transport, answer, field, error_type
):
    response = predicate_response()
    response["answers"] = [answer]
    connection, _, _ = sdk_transport(response)
    with pytest.raises(ValidationError) as error:
        connection.decide(predicate_request(), model="gpt-6-luna")
    assert error.value.title == "Decision"
    assert any(
        item["loc"][:2] == ("answers", 0)
        and item["loc"][-1] == field
        and item["type"] == error_type
        for item in error.value.errors()
    )


@pytest.mark.parametrize(
    "answers",
    [
        [{"type": "predicate", "probability": 0.5}],
        [{"type": "predicate", "name": "unknown", "probability": 0.5}],
        [{"type": "predicate", "name": "damaged", "probability": 0.5}] * 2,
        [{"type": "refusal", "name": None}],
    ],
)
def test_answer_names_must_map_unambiguously_to_questions(sdk_transport, answers):
    response = predicate_response()
    response["answers"] = answers
    connection, _, _ = sdk_transport(response)
    with pytest.raises(
        ValueError, match="missing, unknown, or duplicate names"
    ) as error:
        connection.decide(predicate_request(), model="gpt-6-luna")
    assert type(error.value) is ValueError


@pytest.mark.parametrize(
    ("mutation", "model_name", "field"),
    [
        ("empty_answers", "DecisionResult", "answers"),
        ("predicate_probability", "PredicateAnswer", "probability_true"),
        ("choice_value", "ChoiceAnswer", "choice"),
        ("confidence", "ScoreAnswer", "provider_confidence"),
    ],
)
def test_sdk_answers_are_subject_to_common_value_constraints(
    sdk_transport, mutation, model_name, field
):
    response = mixed_response()
    if mutation == "empty_answers":
        response["answers"] = []
    elif mutation == "predicate_probability":
        response["answers"][2]["probability"] = 1.5
    elif mutation == "choice_value":
        response["answers"][1]["choice"] = True
    else:
        response["answers"][0]["confidence"] = 1.5
    connection, _, _ = sdk_transport(response)
    with pytest.raises(ValidationError) as error:
        connection.decide(mixed_request(), model="gpt-6-luna")
    assert error.value.title == model_name
    assert error.value.errors()[0]["loc"] == (field,)


def test_score_probability_label_is_required_by_sdk(sdk_transport):
    response = mixed_response()
    del response["answers"][0]["probabilities"][0]["label"]
    connection, _, _ = sdk_transport(response)
    with pytest.raises(ValidationError) as error:
        connection.decide(mixed_request(), model="gpt-6-luna")
    assert error.value.title == "Decision"
    assert any(
        item["loc"][:2] == ("answers", 0)
        and item["loc"][-3:] == ("probabilities", 0, "label")
        and item["type"] == "missing"
        for item in error.value.errors()
    )


@pytest.mark.parametrize(
    ("mutation", "match"),
    [
        ("duplicate_index", "invalid score level index"),
        ("wrong_label", "unexpected score level label"),
    ],
)
def test_score_mapping_rejects_ambiguous_levels(sdk_transport, mutation, match):
    response = mixed_response()
    if mutation == "wrong_label":
        response["answers"][0]["probabilities"][0]["label"] = "Unrequested label"
    else:
        response["answers"][0]["probabilities"][1]["value"] = 2
    connection, _, _ = sdk_transport(response)
    with pytest.raises(ValueError, match=match) as error:
        connection.decide(mixed_request(), model="gpt-6-luna")
    assert type(error.value) is ValueError


def test_choice_mapping_rejects_duplicate_values_before_building_map(sdk_transport):
    response = mixed_response()
    response["answers"][1]["probabilities"].append(
        {"value": "billing", "probability": 0.4}
    )
    connection, _, _ = sdk_transport(response)
    with pytest.raises(ValueError, match="duplicate choices") as error:
        connection.decide(mixed_request(), model="gpt-6-luna")
    assert type(error.value) is ValueError


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


@pytest.mark.parametrize("model", ["", " ", None])
def test_blank_model_is_rejected_before_sdk_or_network(sdk_transport, model):
    connection, requests, clients = sdk_transport(predicate_response())
    with pytest.raises(ValueError, match="model name"):
        connection.decide(predicate_request(), model=model)
    assert not requests
    assert not clients


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
        ["Evaluate the evidence", {"fallback": None}],
        3,
        False,
    ],
)
def test_json_instructions_are_encoded_for_every_question_without_mutation(
    sdk_transport, instructions
):
    original = mixed_request()
    request = DecisionRequest(
        input=original.input,
        questions={
            name: type(question)(
                **{**question.model_dump(), "instructions": instructions}
            )
            for name, question in original.questions.items()
        },
    )
    before = request.model_dump()
    connection, requests, _ = sdk_transport(mixed_response())
    connection.decide(request, model="gpt-6-luna")
    wire = json.loads(requests[0].content)
    for question in wire["questions"]:
        assert json.loads(question["instructions"]) == instructions
    assert request.model_dump() == before


@pytest.mark.parametrize("criteria", [None, PredicateCriteria()])
def test_absent_predicate_criteria_are_not_sent(sdk_transport, criteria):
    connection, requests, _ = sdk_transport(predicate_response())
    connection.decide(
        DecisionRequest(
            input="Evidence",
            questions={
                "damaged": PredicateQuestion(
                    instructions="Is it damaged?", criteria=criteria
                )
            },
        ),
        model="gpt-6-luna",
    )
    assert json.loads(requests[0].content)["questions"] == [
        {"name": "damaged", "type": "predicate", "instructions": "Is it damaged?"}
    ]


@pytest.mark.parametrize(
    "question",
    [
        ChoiceQuestion(criteria={"a": None, "b": None}),
        ScoreQuestion(criteria=["Low", "High"]),
    ],
)
def test_missing_instructions_fail_before_sdk_or_network(sdk_transport, question):
    connection, requests, clients = sdk_transport(predicate_response())
    with pytest.raises(ValueError, match="instructions"):
        connection.decide(
            DecisionRequest(input="Evidence", questions={"classification": question}),
            model="gpt-6-luna",
        )
    assert not requests
    assert not clients


@pytest.mark.parametrize(
    ("instructions", "criteria", "expected"),
    [
        (None, PredicateCriteria(true="Damaged"), {"yes": "Damaged"}),
        (None, PredicateCriteria(false=False), {"no": False}),
        (
            "Is it damaged?",
            PredicateCriteria(true="Broken", false="Intact"),
            {"question": "Is it damaged?", "yes": "Broken", "no": "Intact"},
        ),
        (
            {"question": "Is it damaged?", "yes": "Old yes", "no": "Old no"},
            PredicateCriteria(true={"condition": "Broken"}, false="Intact"),
            {
                "question": "Is it damaged?",
                "yes": {"condition": "Broken"},
                "no": "Intact",
            },
        ),
        (
            ["Inspect the item"],
            PredicateCriteria(true=[]),
            {"question": ["Inspect the item"], "yes": []},
        ),
    ],
)
def test_predicate_criteria_are_encoded_in_instructions(
    sdk_transport, instructions, criteria, expected
):
    connection, requests, _ = sdk_transport(predicate_response())
    request = DecisionRequest(
        input="Evidence",
        questions={
            "damaged": PredicateQuestion(
                instructions=instructions,
                criteria=criteria,
            )
        },
    )
    before = request.model_dump()
    result = connection.decide(request, model="gpt-6-luna")
    question = json.loads(requests[0].content)["questions"][0]
    assert question.keys() == {"name", "type", "instructions"}
    assert json.loads(question["instructions"]) == expected
    assert result.answers["damaged"].probability_true == 0.95
    assert request.model_dump() == before


@pytest.mark.parametrize(
    "question",
    [
        ChoiceQuestion(instructions="Choose.", criteria={"only": None}),
        ChoiceQuestion(
            instructions="Choose.",
            criteria={str(i): None for i in range(256)},
        ),
        ScoreQuestion(instructions="Rate.", criteria=["Only"]),
        ScoreQuestion(instructions="Rate.", criteria=[str(i) for i in range(11)]),
    ],
)
def test_unsupported_criterion_counts_fail_before_network(sdk_transport, question):
    connection, requests, clients = sdk_transport(predicate_response())
    with pytest.raises(ValueError, match="classification.*(choices|score levels)"):
        connection.decide(
            DecisionRequest(input="Evidence", questions={"classification": question}),
            model="gpt-6-luna",
        )
    assert not requests
    assert not clients


@pytest.mark.parametrize(("choice_count", "level_count"), [(2, 2), (255, 10)])
def test_supported_criterion_count_boundaries_preserve_every_option(
    sdk_transport, choice_count, level_count
):
    choices = {f"team-{index}": f"Team {index}" for index in range(choice_count)}
    levels = [f"Level {index}" for index in range(level_count)]
    selected = f"team-{choice_count - 1}"
    choice_probabilities = {value: float(value == selected) for value in choices}
    score_probabilities = {
        index: float(index == level_count - 1) for index in range(level_count)
    }
    request = DecisionRequest(
        input="Evidence",
        questions={
            "route": ChoiceQuestion(instructions="Choose.", criteria=choices),
            "severity": ScoreQuestion(instructions="Rate.", criteria=levels),
        },
    )
    response = predicate_response()
    response["answers"] = [
        {
            "name": "route",
            "type": "choice",
            "choice": selected,
            "confidence": 1.0,
            "probabilities": [
                {"value": value, "probability": probability}
                for value, probability in choice_probabilities.items()
            ],
        },
        {
            "name": "severity",
            "type": "score",
            "score": float(level_count - 1),
            "confidence": 1.0,
            "probabilities": [
                {"value": index, "label": str(index), "probability": probability}
                for index, probability in score_probabilities.items()
            ],
        },
    ]
    connection, requests, _ = sdk_transport(response)
    result = connection.decide(request, model="gpt-6-luna")

    assert len(requests) == 1
    assert json.loads(requests[0].content)["questions"] == [
        {
            "name": "route",
            "type": "choice",
            "instructions": "Choose.",
            "choices": [
                {"value": value, "description": description}
                for value, description in choices.items()
            ],
        },
        {
            "name": "severity",
            "type": "score",
            "instructions": "Rate.",
            "levels": [
                {"label": str(index), "description": description}
                for index, description in enumerate(levels)
            ],
        },
    ]
    assert result.answers == {
        "route": ChoiceAnswer(
            choice=selected,
            probabilities=choice_probabilities,
            provider_confidence=1.0,
        ),
        "severity": ScoreAnswer(
            score=float(level_count - 1),
            probabilities=score_probabilities,
            legend=dict(enumerate(levels)),
            provider_confidence=1.0,
        ),
    }


@pytest.mark.parametrize("count", [200, 201])
def test_question_count_limit_is_checked_before_network(sdk_transport, count):
    request = DecisionRequest(
        input="Evidence",
        questions={
            str(index): PredicateQuestion(instructions="Is it damaged?")
            for index in range(count)
        },
    )
    response = predicate_response()
    response["answers"] = [
        {"name": name, "type": "predicate", "probability": 0.95}
        for name in request.questions
    ]
    connection, requests, clients = sdk_transport(response)
    if count == 201:
        with pytest.raises(ValueError, match="at most 200 questions"):
            connection.decide(request, model="gpt-6-luna")
        assert not requests
        assert not clients
    else:
        result = connection.decide(request, model="gpt-6-luna")
        assert result.answers.keys() == request.questions.keys()
        assert len(requests) == 1


@pytest.mark.parametrize(
    "description", [{"policy": "退款", "days": 30}, ["退款", None], 3, False]
)
def test_choice_descriptions_are_json_encoded(sdk_transport, description):
    request = mixed_request()
    request.questions["route"].criteria["support"] = description
    before = request.model_dump()
    connection, requests, _ = sdk_transport(mixed_response())
    connection.decide(request, model="gpt-6-luna")
    choices = json.loads(requests[0].content)["questions"][1]["choices"]
    assert json.loads(choices[0]["description"]) == description
    assert request.model_dump() == before


def test_score_uses_index_labels_and_returns_an_independent_original_legend(
    sdk_transport,
):
    criteria = [
        None,
        {"label": "Business data", "description": {"enabled": True}},
        ["Blocked", {"workaround": False}],
        3,
        False,
    ]
    response = predicate_response()
    response["answers"] = [
        {
            "name": "severity",
            "type": "score",
            "score": 2.0,
            "probabilities": [
                {"value": index, "label": str(index), "probability": probability}
                for index, probability in [
                    (4, 0.1),
                    (0, 0.1),
                    (3, 0.2),
                    (1, 0.2),
                    (2, 0.4),
                ]
            ],
            "confidence": 0.4,
        }
    ]
    request = DecisionRequest(
        input="Evidence",
        questions={
            "severity": ScoreQuestion(
                instructions="Rate the impact.", criteria=criteria
            )
        },
    )
    before = request.model_dump()
    connection, requests, _ = sdk_transport(response)
    answer = connection.decide(request, model="gpt-6-luna").answers["severity"]
    wire_levels = json.loads(requests[0].content)["questions"][0]["levels"]
    assert wire_levels[0] == {"label": "0"}
    for index in range(1, len(criteria)):
        assert wire_levels[index]["label"] == str(index)
        assert json.loads(wire_levels[index]["description"]) == criteria[index]
    assert answer.probabilities == {0: 0.1, 1: 0.2, 2: 0.4, 3: 0.2, 4: 0.1}
    assert answer.legend == dict(enumerate(criteria))
    assert request.model_dump() == before
    restored = ScoreAnswer.model_validate_json(answer.model_dump_json())
    assert restored == answer
    answer.legend[1]["description"]["enabled"] = False
    assert request.questions["severity"].criteria[1]["description"]["enabled"] is True
    request.questions["severity"].criteria[2][1]["workaround"] = True
    assert answer.legend[2][1]["workaround"] is False


@pytest.mark.parametrize("value", [{"ticket": "证据"}, ["Evidence", None], 3, True])
def test_input_json_is_encoded_as_text(sdk_transport, value):
    connection, requests, _ = sdk_transport(predicate_response())
    connection.decide(
        DecisionRequest(input=value, questions=predicate_request().questions),
        model="gpt-6-luna",
    )
    assert json.loads(json.loads(requests[0].content)["input"]) == value


def test_null_input_fails_before_network(sdk_transport):
    connection, requests, clients = sdk_transport(predicate_response())
    with pytest.raises(ValueError, match="input"):
        connection.decide(
            DecisionRequest(input=None, questions=predicate_request().questions),
            model="gpt-6-luna",
        )
    assert not requests
    assert not clients


def test_json_looking_string_content_is_preserved_verbatim(sdk_transport):
    text = '  {"question": "Is the item damaged?"}\n'
    request = mixed_request()
    request = DecisionRequest(
        input=text,
        questions={
            "route": ChoiceQuestion(
                instructions=text,
                criteria={"support": text, "billing": None},
            ),
        },
    )
    response = mixed_response()
    response["answers"] = [response["answers"][1]]
    connection, requests, _ = sdk_transport(response)
    connection.decide(request, model="gpt-6-luna")
    body = json.loads(requests[0].content)
    assert body["input"] == text
    assert body["questions"][0]["instructions"] == text
    assert body["questions"][0]["choices"][0]["description"] == text


@pytest.mark.parametrize(
    ("probabilities", "score"),
    [
        ([0.0] * 3, 1.0),
        ([0.2] * 3, 1.5),
        ([0.6] * 3, 1.0),
        ([0.44962867851783656, 0.5503713214821633, 0.0], 0.5503713214821633),
        ([0.1, 0.7, 0.2], 1.5),
    ],
)
def test_sdk_numeric_answers_preserve_provider_values(
    sdk_transport, probabilities, score
):
    choice_probabilities = dict(
        zip(("billing", "support"), probabilities[:2], strict=True)
    )
    score_probabilities = dict(enumerate(probabilities))
    request = mixed_request()
    response = mixed_response()
    response["answers"][0]["score"] = score
    for entry in response["answers"][0]["probabilities"]:
        entry["probability"] = score_probabilities[entry["value"]]
    response["answers"][1]["probabilities"] = [
        {"value": value, "probability": probability}
        for value, probability in choice_probabilities.items()
    ]
    connection, requests, _ = sdk_transport(response)
    result = connection.decide(request, model="gpt-6-luna")

    assert result.answers["route"].probabilities == choice_probabilities
    assert result.answers["severity"].probabilities == score_probabilities
    assert result.answers["severity"].score == score
    assert result.answers["severity"].legend == dict(
        enumerate(request.questions["severity"].criteria)
    )
    assert len(requests) == 1


def test_request_params_cannot_replace_framework_request_fields(sdk_transport):
    connection, requests, clients = sdk_transport(predicate_response())
    with pytest.raises(ValueError, match="model"):
        connection.decide(
            predicate_request(),
            model="gpt-6-luna",
            request_params={"extra_body": {"model": "replacement"}},
        )
    assert not requests
    assert not clients
