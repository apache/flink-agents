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
"""Validate the provider-neutral decision contract and connection ownership."""

import json
from collections.abc import Mapping
from unittest.mock import Mock

import pytest
from pydantic import JsonValue, ValidationError

from flink_agents.api.decision_models import (
    BaseDecisionModelConnection,
    BaseDecisionModelSetup,
    ChoiceAnswer,
    ChoiceQuestion,
    DecisionRequest,
    DecisionResult,
    PredicateAnswer,
    PredicateCriteria,
    PredicateQuestion,
    RefusalAnswer,
    ScoreAnswer,
    ScoreQuestion,
    TokenUsage,
)
from flink_agents.api.resource import ResourceType
from flink_agents.api.resource_context import ResourceContext


def _questions() -> dict[str, PredicateQuestion | ChoiceQuestion | ScoreQuestion]:
    return {
        "Actionable": PredicateQuestion(instructions="Does this require action?"),
        "team": ChoiceQuestion(
            instructions="Who should handle this?",
            criteria={"Billing": "Payments and invoices", "Support": "Product issues"},
        ),
        "severity": ScoreQuestion(
            instructions="How severe is this?",
            criteria=[
                {"label": "Level", "description": "A workaround exists"},
                {"label": "Level", "description": "No workaround exists"},
            ],
        ),
    }


def test_questions_round_trip_with_case_sensitive_ids_and_json_criteria():
    request = DecisionRequest(
        input={"ticket": {"paid": True, "amount": 25}, "notes": []},
        questions=_questions(),
    )
    restored = DecisionRequest.model_validate_json(request.model_dump_json())
    assert restored == request
    assert isinstance(restored.questions["Actionable"], PredicateQuestion)
    assert restored.questions["team"].criteria == {
        "Billing": "Payments and invoices",
        "Support": "Product issues",
    }
    assert restored.questions["severity"].criteria == [
        {"label": "Level", "description": "A workaround exists"},
        {"label": "Level", "description": "No workaround exists"},
    ]


@pytest.mark.parametrize(
    "value",
    ["", '{"paid": true}', {}, [], [{"paid": True}, None, 3], 3.5, True, None],
)
def test_json_input_retains_its_type_and_value_on_round_trip(value):
    request = DecisionRequest(input=value, questions=_questions())
    restored = DecisionRequest.model_validate_json(request.model_dump_json())
    assert type(restored.input) is type(value)
    assert restored.input == value


@pytest.mark.parametrize(
    "invalid", [object(), b"bytes", {"not", "json"}, float("nan"), float("inf")]
)
@pytest.mark.parametrize(
    "field", ["input", "instructions", "choice", "score", "outcome"]
)
def test_non_json_content_is_rejected_recursively(invalid, field):
    content = {"nested": [invalid]}
    model, values = {
        "input": (DecisionRequest, {"input": content, "questions": _questions()}),
        "instructions": (PredicateQuestion, {"instructions": content}),
        "choice": (ChoiceQuestion, {"criteria": {"route": content}}),
        "score": (ScoreQuestion, {"criteria": [content]}),
        "outcome": (PredicateCriteria, {"true": content}),
    }[field]
    with pytest.raises(ValidationError):
        model.model_validate(values)


@pytest.mark.parametrize(
    "content",
    ["", {"policy": [None, 30, True]}, ["text", {"days": 30}], 3, False, None],
)
def test_criteria_preserve_all_json_values_without_field_interpretation(content):
    request = DecisionRequest(
        input="Evidence",
        questions={
            "choice": ChoiceQuestion(instructions=content, criteria={"route": content}),
            "score": ScoreQuestion(instructions=content, criteria=[content]),
        },
    )
    restored = DecisionRequest.model_validate_json(request.model_dump_json())
    assert restored == request
    assert restored.questions["choice"].criteria == {"route": content}
    assert restored.questions["score"].criteria == [content]


@pytest.mark.parametrize(
    "criteria",
    [
        PredicateCriteria(true="The ticket reports a prior contact."),
        PredicateCriteria(true="", false=" "),
        PredicateCriteria(false={"definition": "No prior contact."}),
        PredicateCriteria(false=["No prior contact.", {"count": 0}]),
    ],
)
def test_predicate_can_be_defined_by_either_outcome_without_instructions(criteria):
    question = PredicateQuestion(criteria=criteria)
    restored = PredicateQuestion.model_validate_json(question.model_dump_json())
    assert restored.instructions is None
    assert restored.criteria == criteria


@pytest.mark.parametrize("criteria", [None, PredicateCriteria()])
def test_predicate_requires_instructions_or_a_described_outcome(criteria):
    with pytest.raises(ValidationError, match="instructions.*outcome"):
        PredicateQuestion(criteria=criteria)
    assert PredicateQuestion(instructions="", criteria=criteria).instructions == ""


@pytest.mark.parametrize(
    "question",
    [ChoiceQuestion(criteria={"only": None}), ScoreQuestion(criteria=["Only level"])],
)
def test_choice_and_score_instructions_can_be_omitted(question):
    request = DecisionRequest(input="Evidence", questions={"question": question})
    restored = DecisionRequest.model_validate_json(request.model_dump_json())
    assert restored == request
    assert restored.questions["question"].instructions is None


@pytest.mark.parametrize("value", [True, 1, None, b"route"])
def test_choice_keys_and_answer_values_are_strict_strings(value):
    with pytest.raises(ValidationError):
        ChoiceQuestion(criteria={value: "Meaning"})
    with pytest.raises(ValidationError):
        ChoiceAnswer(choice="route", probabilities={value: 1.0})
    with pytest.raises(ValidationError):
        ChoiceAnswer(
            choice=value,
            probabilities={"route": 1.0},
        )


@pytest.mark.parametrize("question_type", [ChoiceQuestion, ScoreQuestion])
def test_questions_preserve_pydantic_missing_field_errors(question_type):
    with pytest.raises(ValidationError, match="criteria"):
        question_type.model_validate({})
    with pytest.raises(ValidationError, match="criteria"):
        question_type.model_validate_json("{}")


@pytest.mark.parametrize(
    ("question_type", "criteria"), [(ChoiceQuestion, {}), (ScoreQuestion, [])]
)
def test_choice_and_score_require_at_least_one_criterion(question_type, criteria):
    with pytest.raises(ValidationError, match="criteria"):
        question_type(criteria=criteria)


def test_question_schemas_match_their_serialized_criteria():
    for question_type, expected in [
        (ChoiceQuestion, "object"),
        (ScoreQuestion, "array"),
    ]:
        for mode in ["validation", "serialization"]:
            criteria = question_type.model_json_schema(mode=mode)["properties"][
                "criteria"
            ]
            assert criteria["type"] == expected


def test_questions_are_frozen_and_reject_unknown_fields():
    question = ChoiceQuestion(criteria={"route": "Meaning"})
    with pytest.raises(ValidationError, match="frozen"):
        question.instructions = "Changed"
    with pytest.raises(ValidationError, match="extra_forbidden"):
        ChoiceQuestion(criteria={"route": "Meaning"}, context={"hidden": "meaning"})
    with pytest.raises(ValidationError, match="extra_forbidden"):
        PredicateCriteria(yes="meaning")


def test_request_requires_a_question():
    with pytest.raises(ValidationError, match="questions"):
        DecisionRequest(input="Evidence", questions={})


@pytest.mark.parametrize(
    "probability", [-0.01, 1.01, float("nan"), float("inf"), True, "0.5", None]
)
def test_invalid_probabilities_are_rejected(probability):
    with pytest.raises(ValidationError):
        PredicateAnswer(probability_true=probability)
    with pytest.raises(ValidationError):
        ChoiceAnswer(choice="route", probabilities={"route": probability})
    with pytest.raises(ValidationError):
        ScoreAnswer(score=0, probabilities={0: probability}, legend={0: "Only"})


@pytest.mark.parametrize("answer_type", [ChoiceAnswer, ScoreAnswer])
@pytest.mark.parametrize(
    "confidence", [-0.01, 1.01, float("nan"), float("inf"), True, "0.5"]
)
def test_provider_confidence_must_be_a_finite_probability(answer_type, confidence):
    values = (
        {"choice": "route", "probabilities": {"route": 1.0}}
        if answer_type is ChoiceAnswer
        else {"score": 0.0, "probabilities": {0: 1.0}, "legend": {0: "Only"}}
    )
    with pytest.raises(ValidationError) as error:
        answer_type(**values, provider_confidence=confidence)
    assert error.value.errors()[0]["loc"] == ("provider_confidence",)


@pytest.mark.parametrize("score", [-0.01, float("nan"), float("inf"), True, "0.5"])
def test_score_must_be_a_finite_nonnegative_number(score):
    with pytest.raises(ValidationError) as error:
        ScoreAnswer(score=score, probabilities={0: 1.0}, legend={0: "Only"})
    assert error.value.errors()[0]["loc"] == ("score",)


@pytest.mark.parametrize("field", ["input_tokens", "output_tokens", "total_tokens"])
@pytest.mark.parametrize("count", [-1, 0.0, 1.5, True, "1"])
def test_token_counts_must_be_nonnegative_integers(field, count):
    with pytest.raises(ValidationError) as error:
        TokenUsage(**{field: count})
    assert error.value.errors()[0]["loc"] == (field,)


@pytest.mark.parametrize("count", [None, 0, 7])
def test_reported_token_counts_round_trip_without_inference(count):
    usage = TokenUsage(input_tokens=count)
    restored = TokenUsage.model_validate_json(usage.model_dump_json())
    assert restored == usage
    assert restored.input_tokens == count
    assert restored.output_tokens is None
    assert restored.total_tokens is None


def test_choice_requires_a_nonempty_probability_map():
    with pytest.raises(ValidationError, match="probabilities"):
        ChoiceAnswer(choice="route", probabilities={})


def test_choice_probability_map_round_trips_values_case_and_order():
    probabilities = {"Approve": 0.8, "approve": 0.2}
    answer = ChoiceAnswer(choice="Approve", probabilities=probabilities)
    encoded = json.loads(answer.model_dump_json())
    assert encoded["probabilities"] == probabilities
    restored = ChoiceAnswer.model_validate_json(answer.model_dump_json())
    assert restored == answer
    assert list(restored.probabilities) == ["Approve", "approve"]
    probabilities["Approve"] = 0.5
    assert answer.probabilities == {"Approve": 0.8, "approve": 0.2}


@pytest.mark.parametrize("index", [True, -1, "0", 0.5])
@pytest.mark.parametrize("field", ["probabilities", "legend"])
def test_score_python_indices_are_strict_nonnegative_integers(index, field):
    values = {"score": 0.0, "probabilities": {0: 1.0}, "legend": {0: "Only"}}
    values[field] = {index: 1.0 if field == "probabilities" else "Only"}
    with pytest.raises(ValidationError):
        ScoreAnswer.model_validate(values)


@pytest.mark.parametrize("index", ["01", "+1", "1.0", "-0", "-1", "true"])
@pytest.mark.parametrize("field", ["probabilities", "legend"])
def test_score_json_indices_require_canonical_nonnegative_integer_keys(index, field):
    values = {"score": 0.0, "probabilities": {"0": 1.0}, "legend": {"0": "Only"}}
    values[field] = {index: 1.0 if field == "probabilities" else "Only"}
    with pytest.raises(ValidationError, match=field):
        ScoreAnswer.model_validate_json(json.dumps(values))


def test_score_legend_copies_nested_json_and_round_trips_integer_indices():
    original = {
        "label": {"business_data": True},
        "description": ["Complete description", None],
    }
    answer = ScoreAnswer(
        score=0.25, probabilities={0: 0.75, 1: 0.25}, legend={0: original, 1: None}
    )
    restored = ScoreAnswer.model_validate_json(answer.model_dump_json())
    assert restored == answer
    assert list(restored.probabilities) == [0, 1]
    assert list(restored.legend) == [0, 1]
    original["description"].append("Outside change")
    assert answer.legend[0]["description"] == ["Complete description", None]
    answer.legend[0]["label"]["business_data"] = False
    assert original["label"]["business_data"] is True


def test_public_result_allows_unreported_provider_fields():
    result = DecisionResult(
        answers={
            "team": ChoiceAnswer(
                choice="Billing",
                probabilities={"Billing": 0.75, "Support": 0.25},
            ),
            "severity": ScoreAnswer(
                score=0.25,
                probabilities={0: 0.75, 1: 0.25},
                legend={0: "Low", 1: "High"},
            ),
        },
    )
    restored = DecisionResult.model_validate_json(result.model_dump_json())
    assert restored == result
    assert restored.model is None
    assert restored.usage is None
    assert restored.answers["team"].provider_confidence is None
    assert restored.answers["severity"].provider_confidence is None


def test_result_preserves_refusal_confidence_and_unknown_usage():
    result = DecisionResult(
        answers={
            "Actionable": RefusalAnswer(),
            "team": ChoiceAnswer(
                choice="Billing",
                probabilities={"Billing": 0.8, "Support": 0.2},
                provider_confidence=0.6,
            ),
            "severity": ScoreAnswer(
                score=0.25,
                probabilities={0: 0.75, 1: 0.25},
                legend={0: "Low", 1: {"impact": "High"}},
                provider_confidence=0.5,
            ),
        },
        usage=TokenUsage(input_tokens=11),
        metadata={"latency_ms": 12},
    )
    restored = DecisionResult.model_validate_json(result.model_dump_json())
    assert restored == result
    assert restored.answers["Actionable"].reason is None
    assert restored.answers["team"].provider_confidence == 0.6
    assert restored.usage.output_tokens is None
    assert restored.usage.total_tokens is None


class _Connection(BaseDecisionModelConnection):
    def decide(
        self,
        request: DecisionRequest,
        *,
        model: str,
        request_params: Mapping[str, JsonValue] | None = None,
    ) -> DecisionResult:
        metadata = {"requested_model": model}
        if request_params is not None:
            metadata["request_params"] = dict(request_params)
        return DecisionResult(
            answers={
                name: PredicateAnswer(probability_true=0.8)
                for name in request.questions
            },
            metadata=metadata,
        )


def test_setup_resolves_shared_connection_and_accepts_new_questions_each_call():
    connection = _Connection()
    context = Mock(spec=ResourceContext)
    context.get_resource.return_value = connection
    setup = BaseDecisionModelSetup(
        connection="shared", model="decision-model", resource_context=context
    )
    request = DecisionRequest(
        input="ticket",
        questions={"first": PredicateQuestion(instructions="First condition?")},
    )
    with pytest.raises(TypeError, match="Open the setup"):
        setup.decide(request)

    setup.open()
    context.get_resource.assert_called_once_with(
        "shared", ResourceType.DECISION_MODEL_CONNECTION
    )
    assert setup.connection == "shared"
    assert setup.decide(request).answers == {
        "first": PredicateAnswer(probability_true=0.8)
    }
    second = DecisionRequest(
        input=["new facts"],
        questions={"second": PredicateQuestion(instructions="Second condition?")},
    )
    assert setup.decide(second).answers == {
        "second": PredicateAnswer(probability_true=0.8)
    }


def test_setup_request_params_apply_only_to_the_current_call():
    context = Mock(spec=ResourceContext)
    context.get_resource.return_value = _Connection()
    setup = BaseDecisionModelSetup(
        connection="shared", model="decision-model", resource_context=context
    )
    setup.open()
    request = DecisionRequest(
        input="Evidence",
        questions={"damaged": PredicateQuestion(instructions="Is it damaged?")},
    )
    params = {"timeout": 0.5, "extra_headers": {"x-request-id": "first"}}

    assert setup.decide(request, request_params=params).metadata == {
        "requested_model": "decision-model",
        "request_params": params,
    }
    assert setup.decide(request).metadata == {"requested_model": "decision-model"}


def test_setup_lifecycle_does_not_manage_shared_connection(monkeypatch):
    connection = _Connection()
    open_connection = Mock()
    close = Mock()
    monkeypatch.setattr(_Connection, "open", open_connection)
    monkeypatch.setattr(_Connection, "close", close)
    context = Mock(spec=ResourceContext)
    context.get_resource.return_value = connection
    first = BaseDecisionModelSetup(
        connection="shared", model="first-model", resource_context=context
    )
    second = BaseDecisionModelSetup(
        connection="shared", model="second-model", resource_context=context
    )
    request = DecisionRequest(
        input="Evidence",
        questions={"damaged": PredicateQuestion(instructions="Is it damaged?")},
    )
    first.open()
    second.open()
    open_connection.assert_not_called()
    assert first.connection == second.connection == "shared"
    assert first.decide(request).metadata == {"requested_model": "first-model"}

    first.close()

    close.assert_not_called()
    assert second.decide(request).metadata == {"requested_model": "second-model"}
    second.close()
    close.assert_not_called()


@pytest.mark.parametrize(
    "kwargs", [{"connection": "", "model": "m"}, {"connection": "c", "model": " "}]
)
def test_blank_setup_configuration_is_rejected(kwargs):
    with pytest.raises(ValidationError):
        BaseDecisionModelSetup(**kwargs)
