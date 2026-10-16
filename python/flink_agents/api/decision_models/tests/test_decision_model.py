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

from unittest.mock import Mock

import pytest
from pydantic import ValidationError

from flink_agents.api.decision_models import (
    BaseDecisionModelConnection,
    BaseDecisionModelSetup,
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


def test_typed_questions_round_trip_with_case_sensitive_ids_and_duplicate_labels():
    request = DecisionRequest(
        input={"ticket": {"paid": True, "amount": 25}, "notes": []},
        questions=_questions(),
    )
    restored = DecisionRequest.model_validate_json(request.model_dump_json())
    assert restored == request
    assert isinstance(restored.input, dict)
    assert isinstance(restored.questions["Actionable"], PredicateQuestion)
    assert isinstance(restored.questions["team"], ChoiceQuestion)
    assert isinstance(restored.questions["severity"], ScoreQuestion)
    assert restored.questions["severity"].criteria[0]["label"] == "Level"
    assert restored.questions["severity"].criteria[1]["label"] == "Level"


@pytest.mark.parametrize(
    "value",
    [
        "",
        '{"paid": true}',
        {},
        {"": ""},
        {"type": "text", "text": "Business data, not an input wrapper."},
        [],
        [{"paid": True}, None, 3],
    ],
)
def test_native_inputs_retain_their_type_and_value_on_round_trip(value):
    request = DecisionRequest(input=value, questions=_questions())
    restored = DecisionRequest.model_validate_json(request.model_dump_json())
    assert type(restored.input) is type(value)
    assert restored.input == value


def test_score_criteria_preserve_native_content_and_order():
    criteria = [
        "",
        "A task still works.",
        {"condition": "A task fails.", "examples": ["Cannot pay", None]},
        ["No workaround.", {"affected_tasks": 3}],
        {"label": {"business_data": True}, "description": ["Not reserved fields"]},
    ]
    question = ScoreQuestion(instructions="Rate the impact.", criteria=criteria)
    restored = ScoreQuestion.model_validate_json(question.model_dump_json())
    assert restored.criteria == criteria
    assert [type(value) for value in restored.criteria] == [str, str, dict, list, dict]


def test_choice_descriptions_preserve_native_json_and_undescribed_alternatives():
    criteria = {
        "text": '  {"policy": "as written"}\n',
        "object": {"rule": "Has a receipt", "days": 30, "notes": None},
        "array": ["Has a receipt", {"days": 30}],
        "undescribed": None,
        "empty": "",
    }
    question = ChoiceQuestion(instructions="Choose a route.", criteria=criteria)
    restored = ChoiceQuestion.model_validate_json(question.model_dump_json())
    assert restored.criteria == criteria
    assert [type(value) for value in restored.criteria.values()] == [
        str,
        dict,
        list,
        type(None),
        str,
    ]


def test_choice_option_objects_preserve_native_content_on_round_trip():
    criteria = [
        {"value": "billing", "description": "Payments", "context": {"days": 30}},
        {"value": "other"},
    ]
    question = ChoiceQuestion(instructions="Choose a route.", criteria=criteria)
    restored = ChoiceQuestion.model_validate_json(question.model_dump_json())
    assert restored.criteria == criteria


@pytest.mark.parametrize(
    "instructions",
    [
        {"question": "Does the ticket match this policy?", "policy": {"days": 30}},
        ["Check the ticket against the policy.", {"days": 30}],
    ],
)
def test_structured_instructions_round_trip_for_all_question_types(instructions):
    questions = {
        name: type(question).model_validate(
            {**question.model_dump(), "instructions": instructions}
        )
        for name, question in _questions().items()
    }
    request = DecisionRequest(input="ticket", questions=questions)
    restored = DecisionRequest.model_validate_json(request.model_dump_json())
    assert restored == request
    for question in restored.questions.values():
        assert question.instructions == instructions
        assert type(question.instructions) is type(instructions)


@pytest.mark.parametrize(
    "criteria",
    [
        {"true": "The ticket reports a prior contact."},
        {"true": "", "false": " "},
        {"false": {"definition": "No prior contact.", "examples": ["First request"]}},
        {"true": None, "false": ["No prior contact.", {"count": 0}]},
    ],
)
def test_predicate_supports_either_outcome_description(criteria):
    question = PredicateQuestion(instructions="Has prior contact?", criteria=criteria)
    restored = PredicateQuestion.model_validate_json(question.model_dump_json())
    assert restored.instructions == question.instructions
    assert restored.criteria == criteria


@pytest.mark.parametrize(
    "criteria",
    [
        None,
        {},
        {"true": None, "false": None},
        {"true": "Has prior contact."},
        {"false": "No prior contact."},
    ],
)
def test_predicate_requires_instructions_even_with_criteria(criteria):
    with pytest.raises(ValidationError, match="instructions"):
        PredicateQuestion(criteria=criteria)
    with pytest.raises(ValidationError, match="instructions"):
        PredicateQuestion(instructions=None, criteria=criteria)
    assert PredicateQuestion(instructions="Has prior contact?", criteria=criteria)


@pytest.mark.parametrize(
    "question",
    [ChoiceQuestion(criteria={"only": None}), ScoreQuestion(criteria=["Only level"])],
)
def test_choice_and_score_instructions_can_be_omitted(question):
    request = DecisionRequest(input="Evidence", questions={"question": question})
    restored = DecisionRequest.model_validate_json(request.model_dump_json())
    assert restored == request
    assert restored.questions["question"].instructions is None


@pytest.mark.parametrize(
    "questions",
    [
        {},
        {"": {"type": "predicate", "instructions": " "}},
        {"route": {"type": "choice", "criteria": []}},
        {"severity": {"type": "score", "criteria": []}},
    ],
)
def test_request_leaves_provider_content_constraints_to_the_sdk(questions):
    payload = {"input": "", "questions": questions}
    request = DecisionRequest.model_validate(payload)
    assert request.model_dump(exclude_unset=True) == payload


@pytest.mark.parametrize("probability", [-0.01, 1.01, float("nan"), True, "0.5"])
def test_invalid_probabilities_are_rejected(probability):
    with pytest.raises(ValidationError):
        PredicateAnswer(probability_true=probability)


def test_result_preserves_refusal_confidence_and_unknown_usage():
    result = DecisionResult(
        answers={
            "Actionable": RefusalAnswer(),
            "team": ChoiceAnswer(
                choice="Billing",
                probabilities=[
                    ChoiceProbability(value="Billing", probability=0.8),
                    ChoiceProbability(value="Support", probability=0.2),
                ],
                provider_confidence=0.6,
            ),
            "severity": ScoreAnswer(
                score=0.25,
                probabilities=[
                    ScoreProbability(value=0, label="Low", probability=0.75),
                    ScoreProbability(
                        value=1, label={"impact": "High"}, probability=0.25
                    ),
                ],
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
    def decide(self, request: DecisionRequest, *, model: str) -> DecisionResult:
        return DecisionResult(
            answers={
                name: PredicateAnswer(probability_true=0.8)
                for name in request.questions
            },
            metadata={"requested_model": model},
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


def test_direct_setup_uses_connection_without_resource_context():
    setup = BaseDecisionModelSetup(connection=_Connection(), model="decision-model")
    setup.open()
    request = DecisionRequest(
        input="ticket",
        questions={"first": PredicateQuestion(instructions="First condition?")},
    )
    assert setup.decide(request).metadata == {"requested_model": "decision-model"}


@pytest.mark.parametrize(
    "kwargs", [{"connection": "", "model": "m"}, {"connection": "c", "model": " "}]
)
def test_blank_setup_configuration_is_rejected(kwargs):
    with pytest.raises(ValidationError):
        BaseDecisionModelSetup(**kwargs)
