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
"""Provider portability of a single public DecisionRequest."""

from typing import Any

import httpx
import httpx2
import pytest
from openai import OpenAI
from pydantic import ValidationError
from typesafe_sdk import RetryPolicy, TypeSafeClient

from flink_agents.api.decision_models import (
    ChoiceQuestion,
    DecisionRequest,
    PredicateQuestion,
    ScoreQuestion,
)
from flink_agents.integrations.decision_models import (
    openai_decision_model,
    typesafe_decision_model,
)
from flink_agents.integrations.decision_models.openai_decision_model import (
    OpenAIDecisionModelConnection,
)
from flink_agents.integrations.decision_models.typesafe_decision_model import (
    TypeSafeDecisionModelConnection,
)


def test_same_request_runs_on_both_providers_with_the_same_score_meaning(monkeypatch):
    criteria = [
        "Minor impact",
        {
            "label": {"business_data": "Impact"},
            "description": ["Partial issue", {"workaround": True}],
        },
        ["Blocked", {"workaround": False}],
    ]
    request = DecisionRequest(
        input={"ticket": {"message": "退款时导出失败"}, "history": [None]},
        questions={
            "refund": PredicateQuestion(
                instructions="Does the customer request a refund?"
            ),
            "route": ChoiceQuestion(
                instructions="Which team should handle the request?",
                criteria={
                    "billing": {"scope": ["支付", "退款"], "policy": None},
                    "support": None,
                },
            ),
            "severity": ScoreQuestion(
                instructions="How severe is the issue?", criteria=criteria
            ),
        },
    )
    before = request.model_dump()
    openai_response = {
        "model": "gpt-6-luna",
        "answers": [
            {"name": "refund", "type": "predicate", "probability": 0.9},
            {
                "name": "route",
                "type": "choice",
                "choice": "billing",
                "confidence": 0.8,
                "probabilities": [
                    {"value": "support", "probability": 0.4},
                    {"value": "billing", "probability": 0.6},
                ],
            },
            {
                "name": "severity",
                "type": "score",
                "score": 1.1,
                "confidence": 0.9,
                "probabilities": [
                    {"value": 2, "label": "2", "probability": 0.2},
                    {"value": 0, "label": "0", "probability": 0.1},
                    {"value": 1, "label": "1", "probability": 0.7},
                ],
            },
        ],
        "usage": {
            "input_tokens": 42,
            "input_tokens_details": {"cached_tokens": 0, "cache_write_tokens": 0},
            "output_tokens": 0,
            "output_tokens_details": {"reasoning_tokens": 0},
            "total_tokens": 42,
        },
    }
    typesafe_response = {
        "model": "jev-1.13.0",
        "answers": {
            "refund": {"type": "noul", "noul": 0.9},
            "route": {
                "type": "choice",
                "choice": "billing",
                "confidence": 0.8,
                "probabilities": {"support": 0.4, "billing": 0.6},
            },
            "severity": {
                "type": "score",
                "score": 1.1,
                "confidence": 0.9,
                "probabilities": {"2": 0.2, "0": 0.1, "1": 0.7},
                "legend": {"2": criteria[2], "0": criteria[0], "1": criteria[1]},
            },
        },
        "usage": {"input_tokens": 42, "output_tokens": 0},
    }

    def openai_client(**options: Any) -> OpenAI:
        return OpenAI(
            **options,
            http_client=httpx.Client(
                transport=httpx.MockTransport(
                    lambda _: httpx.Response(200, json=openai_response)
                )
            ),
        )

    def typesafe_client(**options: Any) -> TypeSafeClient:
        return TypeSafeClient(
            **options,
            http_client=httpx2.Client(
                transport=httpx2.MockTransport(
                    lambda _: httpx2.Response(200, json=typesafe_response)
                )
            ),
            retry=RetryPolicy(max_retries=0),
        )

    monkeypatch.setattr(openai_decision_model, "OpenAI", openai_client)
    monkeypatch.setattr(typesafe_decision_model, "TypeSafeClient", typesafe_client)
    openai_connection = OpenAIDecisionModelConnection(
        api_key="test-key", base_url="https://openai.example/v1", max_retries=0
    )
    typesafe_connection = TypeSafeDecisionModelConnection(
        api_key="test-key", base_url="https://typesafe.example"
    )
    try:
        openai_result = openai_connection.decide(request, model="gpt-6-luna")
        typesafe_result = typesafe_connection.decide(request, model="jev-latest")
    finally:
        openai_connection.close()
        typesafe_connection.close()

    assert request.model_dump() == before
    assert openai_result.answers == typesafe_result.answers
    assert openai_result.answers["route"].probabilities == {
        "support": 0.4,
        "billing": 0.6,
    }
    for result in (openai_result, typesafe_result):
        assert list(result.answers["route"].probabilities) == ["support", "billing"]
    expected_legend = dict(enumerate(criteria))
    for result in [openai_result, typesafe_result]:
        answer = result.answers["severity"]
        assert answer.score == 1.1
        assert answer.probabilities == {0: 0.1, 1: 0.7, 2: 0.2}
        assert answer.legend == expected_legend
        assert answer.legend[0] == "Minor impact"
        assert answer.legend[1]["label"] == {"business_data": "Impact"}
        assert answer.legend[1]["description"] == [
            "Partial issue",
            {"workaround": True},
        ]
        assert answer.legend[2] == ["Blocked", {"workaround": False}]


@pytest.mark.parametrize(
    "connection_class", [OpenAIDecisionModelConnection, TypeSafeDecisionModelConnection]
)
@pytest.mark.parametrize(
    "params",
    [
        {"extra_body": {1: "value"}},
        {"extra_body": {"value": float("nan")}},
    ],
)
def test_request_params_require_string_keys_and_finite_json(connection_class, params):
    request = DecisionRequest(
        input="Evidence",
        questions={"eligible": PredicateQuestion(instructions="Is it eligible?")},
    )
    connection = connection_class(api_key="test-key")
    with pytest.raises(ValidationError):
        connection.decide(request, model="test-model", request_params=params)
