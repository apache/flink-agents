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
from copy import deepcopy
from typing import Any, NoReturn

import httpx2
import pytest
from pydantic import ValidationError
from typesafe_sdk import (
    RetryPolicy,
    TypeSafeAPIResponseValidationError,
    TypeSafeBadRequestError,
    TypeSafeClient,
)
from typesafe_sdk.constants import DEFAULT_TIMEOUT

from flink_agents.api.decision_models import (
    ChoiceQuestion,
    DecisionRequest,
    DecisionResult,
    PredicateCriteria,
    PredicateQuestion,
    ScoreQuestion,
)
from flink_agents.integrations.decision_models import typesafe_decision_model
from flink_agents.integrations.decision_models.typesafe_decision_model import (
    TypeSafeDecisionModelConnection,
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
                "legend": {"2": "Very angry", "0": "Calm", "1": "Frustrated"},
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
    result = connection.decide(request_data, model="jev-latest")

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
                "criteria": {
                    "billing": "Payments, invoicing, refunds",
                    "technical": "Bugs, outages, integrations",
                    "sales": "Pricing, upgrades, new accounts",
                },
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
    assert result.answers["department"].probabilities == {
        "sales": 0.0,
        "technical": 0.12,
        "billing": 0.88,
    }
    assert list(result.answers["department"].probabilities) == [
        "sales",
        "technical",
        "billing",
    ]
    assert result.answers["department"].provider_confidence == 0.81
    assert result.answers["frustration"].score == 1.05
    assert result.answers["frustration"].probabilities == {0: 0.0, 1: 0.95, 2: 0.05}
    assert result.answers["frustration"].legend == {
        0: "Calm",
        1: "Frustrated",
        2: "Very angry",
    }
    assert list(result.answers["frustration"].probabilities) == [0, 1, 2]
    assert list(result.answers["frustration"].legend) == [0, 1, 2]
    assert result.answers["frustration"].provider_confidence == 0.92
    assert result.model == "jev-1.13.0"
    assert result.usage.input_tokens == 318
    assert result.usage.output_tokens == 34
    assert result.usage.total_tokens is None
    assert DecisionResult.model_validate_json(result.model_dump_json()) == result


def test_request_params_reach_sdk_without_changing_connection_or_later_calls(
    sdk_transport, request_data, response_data
):
    seen = []

    def handle(request) -> httpx2.Response:
        seen.append(request)
        return httpx2.Response(200, json=response_data)

    clients = sdk_transport(handle)
    connection = TypeSafeDecisionModelConnection(api_key="test-key", timeout=11)
    request_params = {
        "timeout": 0.25,
        "extra_headers": {"X-Request-Tag": "first-call"},
        "extra_body": {"tenant": {"name": "租户", "policies": ["a", None, True]}},
    }
    before = deepcopy(request_params)

    first = connection.decide(
        request_data, model="jev-latest", request_params=request_params
    )
    second = connection.decide(
        request_data,
        model="jev-latest",
        request_params={"extra_headers": {"X-Request-Tag": "second-call"}},
    )
    third = connection.decide(request_data, model="jev-latest")

    assert first == second == third
    assert len(clients) == 1
    assert len(seen) == 3
    assert seen[0].headers["X-Request-Tag"] == "first-call"
    assert seen[1].headers["X-Request-Tag"] == "second-call"
    assert "X-Request-Tag" not in seen[2].headers
    assert seen[0].extensions["timeout"]["read"] == 0.25
    assert seen[1].extensions["timeout"]["read"] == 11
    assert seen[2].extensions["timeout"]["read"] == 11
    first_body, second_body, third_body = (
        json.loads(request.content) for request in seen
    )
    assert first_body.pop("tenant") == before["extra_body"]["tenant"]
    assert first_body == second_body == third_body
    assert first_body["model"] == "jev-latest"
    assert first_body["state"] == request_data.input
    assert request_params == before
    assert connection.timeout == 11


def test_request_params_cannot_replace_response_parser(sdk_transport, request_data):
    seen = []
    clients = sdk_transport(lambda request: seen.append(request))
    with pytest.raises(ValueError, match="response_model"):
        TypeSafeDecisionModelConnection(api_key="test-key").decide(
            request_data,
            model="jev-latest",
            request_params={"extra_body": {"response_model": None}},
        )
    assert not seen
    assert not clients


def test_json_retry_policy_retries_selected_status_without_affecting_later_calls(
    sdk_transport, request_data, response_data
):
    seen = []

    def handle(request) -> httpx2.Response:
        seen.append(request)
        if len(seen) == 2:
            return httpx2.Response(200, json=response_data)
        return httpx2.Response(400, json={"error": "Retry this request."})

    clients = sdk_transport(handle)
    connection = TypeSafeDecisionModelConnection(api_key="test-key")
    request_params = {
        "retry": {
            "max_retries": 1,
            "backoff_initial": 0,
            "http_statuses": [400],
            "timeout": None,
        }
    }
    before = deepcopy(request_params)
    result = connection.decide(
        request_data, model="jev-latest", request_params=request_params
    )
    assert result.answers["is_urgent"].probability_true == 0.95
    assert len(seen) == 2
    assert seen[1].headers["X-TypeSafe-Retry-Count"] == "1"
    assert json.loads(seen[0].content) == json.loads(seen[1].content)
    assert request_params == before

    with pytest.raises(TypeSafeBadRequestError):
        connection.decide(request_data, model="jev-latest")
    assert len(seen) == 3
    assert len(clients) == 1


@pytest.mark.parametrize(
    "retry",
    [
        1,
        {"unknown": 1},
        {"max_retries": "1"},
    ],
)
def test_retry_policy_uses_sdk_field_types_without_silent_coercion(
    sdk_transport, request_data, retry
):
    seen = []
    clients = sdk_transport(lambda request: seen.append(request))
    with pytest.raises(ValidationError):
        TypeSafeDecisionModelConnection(api_key="test-key").decide(
            request_data, model="jev-latest", request_params={"retry": retry}
        )
    assert not seen
    assert not clients


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
                "usage": {},
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
    assert result.usage.input_tokens is None
    assert result.usage.output_tokens is None


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


@pytest.mark.parametrize("instructions", [None, "Does the customer have a receipt?"])
@pytest.mark.parametrize(
    "criteria",
    [
        PredicateCriteria(true="Has a receipt."),
        PredicateCriteria(false=["No receipt.", {"example": "Lost the receipt"}]),
        PredicateCriteria(false={"definition": "No receipt."}),
    ],
)
def test_jev_predicate_preserves_partial_criteria_and_omits_missing_instructions(
    sdk_transport, criteria, instructions
):
    seen = []

    def handle(request) -> httpx2.Response:
        seen.append(json.loads(request.content))
        return httpx2.Response(
            200,
            json={
                "model": "jev-1.13.0",
                "answers": {"receipt": {"type": "noul", "noul": 0.2}},
                "usage": {},
            },
        )

    sdk_transport(handle)
    connection = TypeSafeDecisionModelConnection(api_key="test-key")
    request_data = DecisionRequest(
        input="I lost my receipt.",
        questions={
            "receipt": PredicateQuestion(instructions=instructions, criteria=criteria)
        },
    )
    before = request_data.model_dump()
    result = connection.decide(request_data, model="jev-latest")
    expected = {"type": "noul", "criteria": criteria.model_dump(exclude_none=True)}
    if instructions is not None:
        expected["instructions"] = instructions
    assert seen[0]["questions"] == {"receipt": expected}
    assert request_data.model_dump() == before
    assert result.answers["receipt"].probability_true == 0.2


@pytest.mark.parametrize(("choice_count", "level_count"), [(1, 1), (255, 10)])
def test_criteria_only_questions_preserve_supported_count_boundaries(
    sdk_transport, choice_count, level_count
):
    choices = {f"team-{index}": None for index in range(choice_count)}
    levels = [f"Level {index}" for index in range(level_count)]
    selected = f"team-{choice_count - 1}"
    choice_probabilities = {value: float(value == selected) for value in choices}
    score_probabilities = {
        index: float(index == level_count - 1) for index in range(level_count)
    }
    seen = []

    def handle(request) -> httpx2.Response:
        seen.append(json.loads(request.content))
        return httpx2.Response(
            200,
            json={
                "model": "jev-1.13.0",
                "answers": {
                    "route": {
                        "type": "choice",
                        "choice": selected,
                        "probabilities": choice_probabilities,
                        "confidence": 1.0,
                    },
                    "severity": {
                        "type": "score",
                        "score": float(level_count - 1),
                        "probabilities": {
                            str(index): probability
                            for index, probability in score_probabilities.items()
                        },
                        "legend": {
                            str(index): description
                            for index, description in enumerate(levels)
                        },
                        "confidence": 1.0,
                    },
                },
                "usage": {},
            },
        )

    sdk_transport(handle)
    request = DecisionRequest(
        input="Evidence",
        questions={
            "route": ChoiceQuestion(criteria=choices),
            "severity": ScoreQuestion(criteria=levels),
        },
    )
    result = TypeSafeDecisionModelConnection(api_key="test-key").decide(
        request, model="jev-latest"
    )

    assert len(seen) == 1
    assert seen[0]["questions"] == {
        "route": {"type": "choice", "criteria": choices},
        "severity": {"type": "score", "criteria": levels},
    }
    assert result.answers["route"].choice == selected
    assert result.answers["route"].probabilities == choice_probabilities
    assert result.answers["route"].provider_confidence == 1.0
    assert result.answers["severity"].score == float(level_count - 1)
    assert result.answers["severity"].probabilities == score_probabilities
    assert result.answers["severity"].legend == dict(enumerate(levels))
    assert result.answers["severity"].provider_confidence == 1.0


@pytest.mark.parametrize(
    "criteria",
    [
        ["Low likelihood", "High likelihood"],
        ["Low likelihood", ["High likelihood", {"examples": ["Recurring failure"]}]],
        [{"condition": "Low risk"}, {"condition": "High risk", "escalate": True}],
        [
            {"label": {"business_data": True}, "description": ["Complete criterion"]},
            {"label": "Repeated", "description": "More impact"},
        ],
    ],
)
def test_score_criteria_keep_complete_json_content_and_order(sdk_transport, criteria):
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
                        "legend": {"1": criteria[1], "0": criteria[0]},
                        "confidence": 0.8,
                    }
                },
                "usage": {},
            },
        )

    sdk_transport(handle)
    request_data = DecisionRequest(
        input="Evidence",
        questions={
            "risk": ScoreQuestion(instructions="Assess the risk.", criteria=criteria)
        },
    )
    before = request_data.model_dump()
    result = TypeSafeDecisionModelConnection(api_key="test-key").decide(
        request_data, model="jev-latest"
    )
    assert result.answers["risk"].probabilities == {0: 0.3, 1: 0.7}
    assert result.answers["risk"].legend == dict(enumerate(criteria))
    assert list(result.answers["risk"].probabilities) == list(range(len(criteria)))
    assert list(result.answers["risk"].legend) == list(range(len(criteria)))
    assert request_data.model_dump() == before
    assert result.answers["risk"].provider_confidence == 0.8
    assert result.metadata == {}
    assert DecisionResult.model_validate_json(result.model_dump_json()) == result


def test_score_legend_cannot_change_nested_json_types(sdk_transport):
    request_data = DecisionRequest(
        input="Evidence",
        questions={
            "risk": ScoreQuestion(
                instructions="Assess the risk.",
                criteria=[
                    {"eligible": True},
                    "High",
                ],
            )
        },
    )
    response_data = {
        "model": "jev-1.13.0",
        "answers": {
            "risk": {
                "type": "score",
                "score": 0.6,
                "probabilities": {"0": 0.4, "1": 0.6},
                "legend": {"0": {"eligible": 1}, "1": "High"},
                "confidence": 0.8,
            }
        },
        "usage": {},
    }
    sdk_transport(lambda request: httpx2.Response(200, json=response_data))
    with pytest.raises(ValueError, match="retain the requested score levels"):
        TypeSafeDecisionModelConnection(api_key="test-key").decide(
            request_data, model="jev-latest"
        )


@pytest.mark.parametrize(
    "field",
    [
        "instructions",
        "predicate_description",
        "choice_description",
        "score_description",
    ],
)
@pytest.mark.parametrize("value", [3, False])
def test_unsupported_question_content_is_rejected_by_sdk_before_network(
    sdk_transport, field, value
):
    question = PredicateQuestion(instructions="Evaluate the evidence.")
    if field == "instructions":
        question = PredicateQuestion(instructions=value)
    elif field == "predicate_description":
        question = PredicateQuestion(criteria=PredicateCriteria(true=value))
    elif field == "choice_description":
        question = ChoiceQuestion(criteria={"route": value})
    else:
        question = ScoreQuestion(criteria=[value])
    seen = []
    clients = sdk_transport(lambda request: seen.append(request))
    with pytest.raises(
        ValidationError, match="instructions" if field == "instructions" else "criteria"
    ):
        TypeSafeDecisionModelConnection(api_key="test-key").decide(
            DecisionRequest(input="Evidence", questions={"classification": question}),
            model="jev-latest",
        )
    assert not seen
    assert not clients


@pytest.mark.parametrize("value", [None, 3, False])
def test_unsupported_input_root_fails_before_network(
    sdk_transport, request_data, value
):
    seen = []
    clients = sdk_transport(lambda request: seen.append(request))
    request_data = DecisionRequest(input=value, questions=request_data.questions)
    with pytest.raises(TypeError, match="input"):
        TypeSafeDecisionModelConnection(api_key="test-key").decide(
            request_data, model="jev-latest"
        )
    assert not seen
    assert not clients


@pytest.mark.parametrize(
    "question",
    [
        ChoiceQuestion(criteria={str(i): None for i in range(256)}),
        ScoreQuestion(criteria=[str(i) for i in range(11)]),
    ],
)
def test_unsupported_criterion_counts_fail_before_network(sdk_transport, question):
    seen = []
    clients = sdk_transport(lambda request: seen.append(request))
    with pytest.raises(ValueError, match="classification.*(choices|score levels)"):
        TypeSafeDecisionModelConnection(api_key="test-key").decide(
            DecisionRequest(input="Evidence", questions={"classification": question}),
            model="jev-latest",
        )
    assert not seen
    assert not clients


def test_bailian_endpoint_and_json_extensions(
    sdk_transport, request_data, response_data
):
    # Bailian documents the same SDK with a /compatible-mode API root:
    # https://help.aliyun.com/zh/model-studio/decision-model-api
    # request_id, latency_ms and partial usage are its documented response shape.
    response_data.update(request_id="request-123", latency_ms=27.5)
    response_data["model"] = "decision-model-preview"
    response_data["usage"] = {"input_tokens": 128, "billed_tokens": 128}

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
        "usage": {"billed_tokens": 128},
    }


@pytest.mark.parametrize("answer_type", ["refusal", "future-answer"])
def test_unknown_answer_type_is_not_silently_dropped(
    sdk_transport, monkeypatch, request_data, response_data, answer_type
):
    response_data["answers"]["is_urgent"] = {
        "type": answer_type,
        "reason": "Unavailable",
    }
    sdk_transport(lambda request: httpx2.Response(200, json=response_data))

    def unexpected_conversion(*args: Any) -> NoReturn:
        pytest.fail("Unknown answer types must fail during SDK response parsing.")

    monkeypatch.setattr(typesafe_decision_model, "_result", unexpected_conversion)
    with pytest.raises(TypeSafeAPIResponseValidationError) as error:
        TypeSafeDecisionModelConnection(api_key="test-key").decide(
            request_data, model="jev-latest"
        )
    assert error.value.body == response_data
    assert error.value.field_path == "answers.is_urgent"


@pytest.mark.parametrize(
    ("path", "field_path"),
    [
        (("model",), "model"),
        (("usage",), "usage"),
        (
            ("answers", "department", "confidence"),
            "answers.department.choice.confidence",
        ),
        (
            ("answers", "frustration", "confidence"),
            "answers.frustration.score.confidence",
        ),
        (("answers", "frustration", "legend"), "answers.frustration.score.legend"),
    ],
)
def test_required_wire_fields_are_validated_by_sdk(
    sdk_transport, request_data, response_data, path, field_path
):
    target = response_data
    for field in path[:-1]:
        target = target[field]
    del target[path[-1]]
    sdk_transport(lambda request: httpx2.Response(200, json=response_data))
    with pytest.raises(TypeSafeAPIResponseValidationError) as error:
        TypeSafeDecisionModelConnection(api_key="test-key").decide(
            request_data, model="jev-latest"
        )
    assert error.value.body == response_data
    assert error.value.field_path == field_path


@pytest.mark.parametrize(
    ("field", "index", "alias", "collision"),
    [
        ("probabilities", "1", "01", False),
        ("legend", "1", "01", False),
        ("probabilities", "1", "01", True),
        ("legend", "1", "01", True),
        ("probabilities", "0", "00", True),
    ],
)
def test_score_json_indices_must_be_canonical(
    sdk_transport, request_data, response_data, field, index, alias, collision
):
    values = response_data["answers"]["frustration"][field]
    values[alias] = values[index] if collision else values.pop(index)
    sdk_transport(lambda request: httpx2.Response(200, json=response_data))
    with pytest.raises(ValueError, match="invalid score level indices") as error:
        TypeSafeDecisionModelConnection(api_key="test-key").decide(
            request_data, model="jev-latest"
        )
    assert type(error.value) is ValueError


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


@pytest.mark.parametrize(
    ("path", "value", "field_path"),
    [
        (("model",), 42, "model"),
        (("usage",), None, "usage"),
        (("usage", "input_tokens"), "318", "usage.input_tokens"),
        (("answers", "is_urgent", "noul"), "0.95", "answers.is_urgent.noul.noul"),
        (
            ("answers", "department", "choice"),
            False,
            "answers.department.choice.choice",
        ),
        (
            ("answers", "department", "confidence"),
            None,
            "answers.department.choice.confidence",
        ),
        (
            ("answers", "frustration", "confidence"),
            None,
            "answers.frustration.score.confidence",
        ),
        (
            ("answers", "frustration", "legend", "0"),
            False,
            "answers.frustration.score.legend.0",
        ),
        (
            ("answers", "frustration", "probabilities"),
            None,
            "answers.frustration.score.probabilities",
        ),
    ],
)
def test_malformed_wire_fields_are_rejected_by_sdk(
    sdk_transport, request_data, response_data, path, value, field_path
):
    target = response_data
    for field in path[:-1]:
        target = target[field]
    target[path[-1]] = value
    sdk_transport(lambda request: httpx2.Response(200, json=response_data))
    with pytest.raises(TypeSafeAPIResponseValidationError) as error:
        TypeSafeDecisionModelConnection(api_key="test-key").decide(
            request_data, model="jev-latest"
        )
    assert error.value.body == response_data
    assert error.value.field_path.startswith(field_path)


@pytest.mark.parametrize(
    ("path", "value", "model_name", "field"),
    [
        (("answers", "is_urgent", "noul"), 1.5, "PredicateAnswer", "probability_true"),
        (
            ("answers", "department", "confidence"),
            1.5,
            "ChoiceAnswer",
            "provider_confidence",
        ),
        (("usage", "input_tokens"), -1, "TokenUsage", "input_tokens"),
    ],
)
def test_sdk_answers_are_subject_to_common_value_constraints(
    sdk_transport, request_data, response_data, path, value, model_name, field
):
    from pydantic import ValidationError

    target = response_data
    for part in path[:-1]:
        target = target[part]
    target[path[-1]] = value
    sdk_transport(lambda request: httpx2.Response(200, json=response_data))
    connection = TypeSafeDecisionModelConnection(api_key="test-key")
    with pytest.raises(ValidationError) as error:
        connection.decide(request_data, model="jev-latest")
    assert error.value.title == model_name
    assert error.value.errors()[0]["loc"] == (field,)


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
    sdk_transport, request_data, response_data, probabilities, score
):
    choice_probabilities = dict(
        zip(("sales", "technical", "billing"), probabilities, strict=True)
    )
    response_data["answers"]["department"]["probabilities"] = choice_probabilities
    response_data["answers"]["frustration"]["probabilities"] = {
        str(index): probability for index, probability in enumerate(probabilities)
    }
    response_data["answers"]["frustration"]["score"] = score
    sdk_transport(lambda request: httpx2.Response(200, json=response_data))
    result = TypeSafeDecisionModelConnection(api_key="test-key").decide(
        request_data, model="jev-latest"
    )
    assert result.answers["department"].probabilities == choice_probabilities
    assert result.answers["frustration"].probabilities == dict(enumerate(probabilities))
    assert result.answers["frustration"].score == score
    assert result.answers["frustration"].legend == dict(
        enumerate(request_data.questions["frustration"].criteria)
    )


def test_score_legend_is_independent_of_request_and_provider_data(sdk_transport):
    criteria = [
        {"label": ["Low"], "description": {"enabled": True}},
        ["High", {"impact": ["Blocked"]}],
    ]
    response = {
        "model": "jev-1.13.0",
        "answers": {
            "risk": {
                "type": "score",
                "score": 0.7,
                "probabilities": {"1": 0.7, "0": 0.3},
                "legend": {"0": criteria[0], "1": criteria[1]},
                "confidence": 0.8,
            }
        },
        "usage": {},
    }
    sdk_transport(lambda request: httpx2.Response(200, json=response))
    request_data = DecisionRequest(
        input="Evidence",
        questions={
            "risk": ScoreQuestion(instructions="Assess the risk.", criteria=criteria)
        },
    )
    answer = (
        TypeSafeDecisionModelConnection(api_key="test-key")
        .decide(request_data, model="jev-latest")
        .answers["risk"]
    )
    assert answer.legend == dict(enumerate(criteria))
    answer.legend[0]["description"]["enabled"] = False
    assert request_data.questions["risk"].criteria[0]["description"]["enabled"] is True
    assert response["answers"]["risk"]["legend"]["0"]["description"]["enabled"] is True
    request_data.questions["risk"].criteria[1][1]["impact"].append("Changed")
    assert answer.legend[1][1]["impact"] == ["Blocked"]
