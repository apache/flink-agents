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
from typing import Any, NoReturn

import httpx2
import pytest
from pydantic import ValidationError
from typesafe_sdk import RetryPolicy, TypeSafeClient
from typesafe_sdk.constants import DEFAULT_TIMEOUT

from flink_agents.api.decision_models import (
    ChoiceAnswer,
    ChoiceProbability,
    ChoiceQuestion,
    DecisionRequest,
    DecisionResult,
    PredicateQuestion,
    ScoreAnswer,
    ScoreProbability,
    ScoreQuestion,
)
from flink_agents.integrations.decision_models import typesafe_decision_model
from flink_agents.integrations.decision_models.typesafe_decision_model import (
    TypeSafeDecisionModelConnection,
    TypeSafeDecisionModelSetup,
)


@pytest.fixture
def request_data():
    return DecisionRequest(
        input="Help! My payouts have been failing for 3 days.",
        questions={
            "is_urgent": PredicateQuestion(instructions="Is this urgent?"),
            "department": ChoiceQuestion(
                instructions="Which department should handle this?",
                criteria={
                    "billing": "Payments, invoicing, refunds",
                    "technical": "Bugs, outages, integrations",
                    "sales": "Pricing, upgrades, new accounts",
                },
            ),
            "frustration": ScoreQuestion(
                instructions="How frustrated is the customer?",
                criteria=["Calm", "Frustrated", "Very angry"],
            ),
        },
    )


@pytest.fixture
def response_data():
    # Combined noul/choice/score examples from Jev's own API reference:
    # https://docs.typesafe.ai/api#answer-types
    # Probabilities are deliberately out of insertion order to check index mapping.
    return {
        "model": "jev-1.13.0",
        "answers": {
            "is_urgent": {"type": "noul", "noul": 0.95},
            "department": {
                "type": "choice",
                "choice": "billing",
                "probabilities": {"sales": 0.0, "technical": 0.12, "billing": 0.88},
                "confidence": 0.81,
            },
            "frustration": {
                "type": "score",
                "score": 1.05,
                "legend": {"0": "Calm", "1": "Frustrated", "2": "Very angry"},
                "probabilities": {"2": 0.05, "0": 0.0, "1": 0.95},
                "confidence": 0.92,
            },
        },
        "usage": {"input_tokens": 318, "output_tokens": 34},
    }


@pytest.fixture
def sdk_transport(monkeypatch):
    """Run the real SDK with its documented http_client injection point."""
    clients = []

    def install(handler) -> list[httpx2.Client]:
        def create(**kwargs: Any) -> TypeSafeClient:
            # Preserve the SDK default when injecting the test HTTP client.
            http_client = httpx2.Client(
                transport=httpx2.MockTransport(handler), timeout=DEFAULT_TIMEOUT
            )
            client = TypeSafeClient(
                **kwargs,
                http_client=http_client,
                retry=RetryPolicy(max_retries=0),
            )
            clients.append(http_client)
            return client

        monkeypatch.setattr(typesafe_decision_model, "TypeSafeClient", create)
        return clients

    yield install
    for client in clients:
        client.close()


@pytest.mark.parametrize("options", [{}, {"timeout": None}])
def test_jev_sdk_request_and_typed_answers(
    sdk_transport, request_data, response_data, options
):
    seen = []

    def handle(request) -> httpx2.Response:
        seen.append(request)
        return httpx2.Response(200, json=response_data)

    sdk_transport(handle)
    connection = TypeSafeDecisionModelConnection(api_key="test-key", **options)
    setup = TypeSafeDecisionModelSetup(connection=connection, model="jev-latest")
    result = setup.decide(request_data)

    assert len(seen) == 1
    assert str(seen[0].url) == "https://api.typesafe.ai/v1/systemone"
    assert seen[0].method == "POST"
    assert seen[0].headers["Authorization"] == "Bearer test-key"
    assert seen[0].headers["Content-Type"] == "application/json"
    assert seen[0].extensions["timeout"]["read"] == DEFAULT_TIMEOUT
    assert json.loads(seen[0].content) == {
        "model": "jev-latest",
        "state": request_data.input,
        "questions": {
            "is_urgent": {"type": "noul", "instructions": "Is this urgent?"},
            "department": {
                "type": "choice",
                "instructions": "Which department should handle this?",
                "criteria": request_data.questions["department"].criteria,
            },
            "frustration": {
                "type": "score",
                "instructions": "How frustrated is the customer?",
                "criteria": ["Calm", "Frustrated", "Very angry"],
            },
        },
    }
    assert result.answers["is_urgent"].probability_true == 0.95
    assert result.answers["department"].choice == "billing"
    assert result.answers["department"].probabilities == [
        ChoiceProbability(value="sales", probability=0.0),
        ChoiceProbability(value="technical", probability=0.12),
        ChoiceProbability(value="billing", probability=0.88),
    ]
    assert result.answers["department"].provider_confidence == 0.81
    assert result.answers["frustration"].score == 1.05
    assert result.answers["frustration"].probabilities == [
        ScoreProbability(value=0, label="Calm", probability=0.0),
        ScoreProbability(value=1, label="Frustrated", probability=0.95),
        ScoreProbability(value=2, label="Very angry", probability=0.05),
    ]
    assert result.answers["frustration"].provider_confidence == 0.92
    assert result.model == "jev-1.13.0"
    assert result.usage.input_tokens == 318
    assert result.usage.output_tokens == 34
    assert result.usage.total_tokens is None
    assert DecisionResult.model_validate_json(result.model_dump_json()) == result


def test_single_choice_and_score_level_allow_omitted_instructions(sdk_transport):
    request_data = DecisionRequest(
        input="Evidence",
        questions={
            "route": ChoiceQuestion(criteria={"only": None}),
            "risk": ScoreQuestion(criteria=[{"condition": "The only level"}]),
        },
    )

    def handle(request) -> httpx2.Response:
        assert json.loads(request.content)["questions"] == {
            "route": {"type": "choice", "criteria": {"only": None}},
            "risk": {
                "type": "score",
                "criteria": [{"condition": "The only level"}],
            },
        }
        return httpx2.Response(
            200,
            json={
                "model": "jev-1.13.0",
                "answers": {
                    "route": {
                        "type": "choice",
                        "choice": "only",
                        "probabilities": {"only": 1.0},
                    },
                    "risk": {
                        "type": "score",
                        "score": 0.0,
                        "probabilities": {"0": 1.0},
                        "legend": {"0": {"condition": "The only level"}},
                    },
                },
            },
        )

    sdk_transport(handle)
    result = TypeSafeDecisionModelConnection(api_key="test-key").decide(
        request_data, model="jev-latest"
    )
    assert result.answers == {
        "route": ChoiceAnswer(
            choice="only",
            probabilities=[ChoiceProbability(value="only", probability=1.0)],
        ),
        "risk": ScoreAnswer(
            score=0.0,
            probabilities=[
                ScoreProbability(
                    value=0, label={"condition": "The only level"}, probability=1.0
                )
            ],
        ),
    }


@pytest.mark.parametrize(
    "value", [{"message": "请退款", "history": []}, [1, None, True], '{"paid": true}']
)
def test_input_state_preserves_native_type(sdk_transport, value):
    seen = []

    def handle(request) -> httpx2.Response:
        seen.append(json.loads(request.content))
        return httpx2.Response(
            200,
            json={
                "model": "jev-1.13.0",
                "answers": {"refund": {"type": "noul", "noul": 0.9}},
            },
        )

    sdk_transport(handle)
    connection = TypeSafeDecisionModelConnection(api_key="test-key")
    result = connection.decide(
        DecisionRequest(
            input=value,
            questions={"refund": PredicateQuestion(instructions="Requests a refund?")},
        ),
        model="jev-latest",
    )
    assert seen[0]["state"] == value
    assert result.usage is None


def test_choice_descriptions_reach_jev_unchanged(
    sdk_transport, request_data, response_data
):
    criteria = {
        "billing": {"policy": "退款", "days": 30, "notes": None},
        "technical": ["Bugs", {"product": "Flink"}],
        "sales": None,
    }
    request_data.questions["department"].criteria.update(criteria)
    seen = []

    def handle(request) -> httpx2.Response:
        seen.append(json.loads(request.content))
        return httpx2.Response(200, json=response_data)

    sdk_transport(handle)
    connection = TypeSafeDecisionModelConnection(api_key="test-key")
    connection.decide(request_data, model="jev-latest")
    assert seen[0]["questions"]["department"]["criteria"] == criteria


def test_native_openai_choices_are_left_to_typesafe_sdk_validation(
    sdk_transport, response_data
):
    request_data = DecisionRequest(
        input="Evidence",
        questions={
            "department": ChoiceQuestion(
                instructions="Which department?",
                criteria=[{"value": "a"}, {"value": "b"}],
            )
        },
    )
    seen = []

    def handle(request) -> httpx2.Response:
        seen.append(request)
        return httpx2.Response(200, json=response_data)

    sdk_transport(handle)
    connection = TypeSafeDecisionModelConnection(api_key="test-key")
    with pytest.raises(ValidationError):
        connection.decide(request_data, model="jev-latest")
    assert not seen


@pytest.mark.parametrize(
    "instructions",
    [
        {"question": "Evaluate the ticket.", "policy": {"refund_days": 30}},
        ["Evaluate the ticket.", {"refund_days": 30}],
    ],
)
def test_structured_questions_reach_jev_unchanged(
    sdk_transport, request_data, response_data, instructions
):
    criteria = {
        "true": {"definition": "The customer is blocked.", "examples": ["Cannot pay"]},
        "false": ["A workaround is available.", {"example": "Another payment method"}],
    }
    payload = request_data.model_dump()
    for question in payload["questions"].values():
        question["instructions"] = instructions
    payload["questions"]["is_urgent"]["criteria"] = criteria
    request_data = DecisionRequest.model_validate(payload)

    def handle(request) -> httpx2.Response:
        questions = json.loads(request.content)["questions"]
        for question in questions.values():
            assert question["instructions"] == instructions
        assert questions["is_urgent"]["criteria"] == criteria
        assert questions["is_urgent"]["type"] == "noul"
        return httpx2.Response(200, json=response_data)

    sdk_transport(handle)
    connection = TypeSafeDecisionModelConnection(api_key="test-key")
    connection.decide(request_data, model="jev-latest")


@pytest.mark.parametrize(
    "criteria",
    [
        {"true": "Has a receipt."},
        {"false": ["No receipt.", {"example": "Lost the receipt"}]},
        {"true": None, "false": {"definition": "No receipt."}},
    ],
)
def test_jev_predicate_preserves_partial_criteria(sdk_transport, criteria):
    instructions = "Does the customer have a receipt?"

    def handle(request) -> httpx2.Response:
        assert json.loads(request.content)["questions"] == {
            "receipt": {
                "type": "noul",
                "instructions": instructions,
                "criteria": criteria,
            }
        }
        return httpx2.Response(
            200,
            json={
                "model": "jev-1.13.0",
                "answers": {"receipt": {"type": "noul", "noul": 0.2}},
            },
        )

    sdk_transport(handle)
    connection = TypeSafeDecisionModelConnection(api_key="test-key")
    result = connection.decide(
        DecisionRequest(
            input="I lost my receipt.",
            questions={
                "receipt": PredicateQuestion(
                    instructions=instructions, criteria=criteria
                )
            },
        ),
        model="jev-latest",
    )
    assert result.answers["receipt"].probability_true == 0.2


@pytest.mark.parametrize(
    "criteria",
    [
        [
            {"label": "Risk", "description": "Low likelihood"},
            {"label": "Risk", "description": "High likelihood"},
        ],
        ["Low likelihood", ["High likelihood", {"examples": ["Recurring failure"]}]],
        [{"condition": "Low risk"}, {"condition": "High risk", "escalate": True}],
        ["Risk", "Risk"],
    ],
)
def test_score_criteria_keep_native_content_and_order(sdk_transport, criteria):
    def handle(request) -> httpx2.Response:
        assert json.loads(request.content)["questions"]["risk"]["criteria"] == criteria
        return httpx2.Response(
            200,
            json={
                "model": "jev-1.13.0",
                "answers": {
                    "risk": {
                        "type": "score",
                        "score": 0.7,
                        "probabilities": {"1": 0.7, "0": 0.3},
                        "legend": {"0": criteria[0], "1": criteria[1]},
                    }
                },
            },
        )

    sdk_transport(handle)
    connection = TypeSafeDecisionModelConnection(api_key="test-key")
    result = connection.decide(
        DecisionRequest(
            input="Evidence",
            questions={
                "risk": ScoreQuestion(
                    instructions="Assess the risk.",
                    criteria=criteria,
                )
            },
        ),
        model="jev-latest",
    )
    assert result.answers["risk"].probabilities == [
        ScoreProbability(value=0, label=criteria[0], probability=0.3),
        ScoreProbability(value=1, label=criteria[1], probability=0.7),
    ]
    assert result.answers["risk"].provider_confidence is None
    assert result.metadata == {}
    assert DecisionResult.model_validate_json(result.model_dump_json()) == result


def test_bailian_endpoint_and_json_extensions(
    sdk_transport, request_data, response_data
):
    # Bailian documents the same SDK with a /compatible-mode API root:
    # https://help.aliyun.com/zh/model-studio/decision-model-api
    # request_id, latency_ms and partial usage are its documented response shape.
    response_data.update(request_id="request-123", latency_ms=27.5)
    response_data["model"] = "decision-model-preview"
    response_data["usage"] = {"input_tokens": 128}

    def handle(request) -> httpx2.Response:
        assert str(request.url) == (
            "https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/systemone"
        )
        assert json.loads(request.content)["model"] == "decision-model-preview"
        return httpx2.Response(200, json=response_data)

    sdk_transport(handle)
    result = TypeSafeDecisionModelConnection(
        api_key="dashscope-test-key",
        base_url="https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/",
    ).decide(request_data, model="decision-model-preview")
    assert result.usage.input_tokens == 128
    assert result.usage.output_tokens is None
    assert result.usage.total_tokens is None
    assert result.metadata == {
        "request_id": "request-123",
        "latency_ms": 27.5,
    }


@pytest.mark.parametrize("answer_type", ["refusal", "future-answer"])
def test_unknown_answer_type_is_not_silently_dropped(
    sdk_transport, request_data, response_data, answer_type
):
    response_data["answers"]["is_urgent"] = {
        "type": answer_type,
        "reason": "Unavailable",
    }
    sdk_transport(lambda request: httpx2.Response(200, json=response_data))
    with pytest.raises(ValueError, match="unsupported answer type"):
        TypeSafeDecisionModelConnection(api_key="test-key").decide(
            request_data, model="jev-latest"
        )


@pytest.mark.parametrize(
    ("mutation", "match"),
    [
        ("missing_question", "identifiers"),
        ("unexpected_question", "identifiers"),
        ("wrong_type", "different type"),
        ("missing_choice", "requested choices"),
        ("missing_noul", "missing field"),
        ("wrong_score_index", "score level indices"),
        ("wrong_legend_index", "score level indices"),
        ("missing_legend", "missing field"),
        ("malformed_legend", "score level indices"),
        ("missing_label", "score level indices"),
        ("choice_probabilities_null", "choice distribution"),
        ("choice_probabilities_list", "choice distribution"),
        ("choice_probabilities_string", "choice distribution"),
    ],
)
def test_responses_must_match_questions(
    sdk_transport, request_data, response_data, mutation, match
):
    answers = response_data["answers"]
    if mutation == "missing_question":
        del answers["is_urgent"]
    elif mutation == "unexpected_question":
        answers["unknown"] = {"type": "noul", "noul": 0.5}
    elif mutation == "wrong_type":
        answers["department"] = {"type": "noul", "noul": 0.5}
    elif mutation == "missing_choice":
        del answers["department"]["probabilities"]["sales"]
    elif mutation == "missing_noul":
        del answers["is_urgent"]["noul"]
    elif mutation == "wrong_score_index":
        answers["frustration"]["probabilities"]["01"] = answers["frustration"][
            "probabilities"
        ].pop("1")
    elif mutation == "missing_legend":
        del answers["frustration"]["legend"]
    elif mutation == "malformed_legend":
        answers["frustration"]["legend"] = ["Calm", "Frustrated", "Very angry"]
    elif mutation == "missing_label":
        del answers["frustration"]["legend"]["1"]
    elif mutation.startswith("choice_probabilities_"):
        answers["department"]["probabilities"] = {
            "choice_probabilities_null": None,
            "choice_probabilities_list": [],
            "choice_probabilities_string": "invalid",
        }[mutation]
    else:
        answers["frustration"]["legend"]["3"] = "Unrequested level"
    sdk_transport(lambda request: httpx2.Response(200, json=response_data))
    with pytest.raises(ValueError, match=match):
        TypeSafeDecisionModelConnection(api_key="test-key").decide(
            request_data, model="jev-latest"
        )


def test_score_cannot_exceed_the_last_requested_level(
    sdk_transport, request_data, response_data
):
    response_data["answers"]["frustration"]["score"] = 2.1
    sdk_transport(lambda request: httpx2.Response(200, json=response_data))
    with pytest.raises(ValueError, match="requested score levels"):
        TypeSafeDecisionModelConnection(api_key="test-key").decide(
            request_data, model="jev-latest"
        )


def test_client_reuse_close_and_env_credentials(
    sdk_transport, monkeypatch, request_data, response_data
):
    monkeypatch.setenv("TYPESAFE_API_KEY", "env-test-key")
    seen = []

    def handle(request) -> httpx2.Response:
        seen.append(request)
        return httpx2.Response(200, json=response_data)

    clients = sdk_transport(handle)
    connection = TypeSafeDecisionModelConnection(timeout=12.0)
    assert clients == []
    connection.open()
    connection.open()
    assert len(clients) == 1
    assert seen == []
    connection.decide(request_data, model="jev-latest")
    connection.decide(request_data, model="jev-latest")
    assert len(clients) == 1
    assert seen[0].headers["Authorization"] == "Bearer env-test-key"
    assert seen[0].extensions["timeout"]["read"] == 12.0
    connection.close()
    assert clients[0].is_closed
    connection.close()
    connection.decide(request_data, model="jev-latest")
    assert len(clients) == 2


@pytest.mark.parametrize("model", ["", " ", None])
def test_blank_model_is_rejected(sdk_transport, request_data, response_data, model):
    sdk_transport(lambda request: httpx2.Response(200, json=response_data))
    connection = TypeSafeDecisionModelConnection(api_key="test-key")
    with pytest.raises(ValueError, match="model name"):
        connection.decide(request_data, model=model)


def test_sdk_errors_propagate(sdk_transport, request_data):
    from typesafe_sdk import TypeSafeAuthenticationError

    sdk_transport(
        lambda request: httpx2.Response(401, json={"detail": "Invalid API key"})
    )
    with pytest.raises(TypeSafeAuthenticationError) as error:
        TypeSafeDecisionModelConnection(api_key="test-key").decide(
            request_data, model="jev-latest"
        )
    assert error.value.status == 401
    assert error.value.body == {"detail": "Invalid API key"}


def test_sdk_timeout_propagates(sdk_transport, request_data):
    from typesafe_sdk import TypeSafeAPITimeoutError

    def handle(request) -> NoReturn:
        msg = "Timed out"
        raise httpx2.ReadTimeout(msg, request=request)

    sdk_transport(handle)
    with pytest.raises(TypeSafeAPITimeoutError):
        TypeSafeDecisionModelConnection(api_key="test-key").decide(
            request_data, model="jev-latest"
        )


def test_malformed_wire_response_uses_sdk_error(sdk_transport, request_data):
    from typesafe_sdk import TypeSafeAPIResponseValidationError

    sdk_transport(lambda request: httpx2.Response(200, json={"answers": []}))
    with pytest.raises(TypeSafeAPIResponseValidationError):
        TypeSafeDecisionModelConnection(api_key="test-key").decide(
            request_data, model="jev-latest"
        )


def test_invalid_probability_is_not_coerced(sdk_transport, request_data, response_data):
    response_data["answers"]["is_urgent"]["noul"] = "0.95"
    sdk_transport(lambda request: httpx2.Response(200, json=response_data))
    with pytest.raises(ValidationError, match="probability_true"):
        TypeSafeDecisionModelConnection(api_key="test-key").decide(
            request_data, model="jev-latest"
        )
