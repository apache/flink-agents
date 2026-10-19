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
"""Provider-independent validation of answers against their original questions."""

import pytest

from flink_agents.api.decision_models import (
    ChoiceAnswer,
    ChoiceQuestion,
    DecisionRequest,
    DecisionResult,
    PredicateAnswer,
    PredicateQuestion,
    RefusalAnswer,
    ScoreAnswer,
    ScoreQuestion,
)
from flink_agents.integrations.decision_models.decision_model_utils import (
    validate_result,
)


def choice_request():
    return DecisionRequest(
        input="Evidence",
        questions={
            "decision": ChoiceQuestion(criteria={"a": None, "b": None, "c": None})
        },
    )


def score_request():
    return DecisionRequest(
        input="Evidence",
        questions={
            "decision": ScoreQuestion(
                criteria=["Low", {"eligible": [True]}, ["High", None]]
            )
        },
    )


def result_for(request, kind, probabilities, score=1.0):
    if kind == "choice":
        answer = ChoiceAnswer(
            choice="a",
            probabilities=dict(zip(["c", "a", "b"], probabilities, strict=True)),
            provider_confidence=0.2,
        )
    else:
        answer = ScoreAnswer(
            score=score,
            probabilities=dict(enumerate(probabilities)),
            legend=dict(enumerate(request.questions["decision"].criteria)),
            provider_confidence=0.2,
        )
    return DecisionResult(answers={"decision": answer})


def test_score_cannot_exceed_the_last_requested_level():
    request = score_request()
    result = result_for(request, "score", [0.1, 0.7, 0.2], score=2.1)

    with pytest.raises(ValueError, match="must use the requested score levels"):
        validate_result(request, result)


@pytest.mark.parametrize("mutation", ["missing", "unknown", "extra"])
def test_answer_identifiers_must_exactly_match_requested_questions(mutation):
    request = choice_request()
    result = result_for(request, "choice", [0.1, 0.7, 0.2])
    answers = dict(result.answers)
    if mutation == "missing":
        request.questions["unanswered"] = request.questions["decision"]
    elif mutation == "unknown":
        answers = {"unknown": result.answers["decision"]}
    else:
        answers["extra"] = result.answers["decision"]

    with pytest.raises(ValueError, match="identifiers must exactly match"):
        validate_result(request, DecisionResult(answers=answers))


@pytest.mark.parametrize(
    ("question", "answer"),
    [
        (
            PredicateQuestion(instructions="Is the item damaged?"),
            ChoiceAnswer(
                choice="a",
                probabilities={"a": 1.0},
            ),
        ),
        (ChoiceQuestion(criteria={"a": None}), PredicateAnswer(probability_true=0.8)),
        (ScoreQuestion(criteria=["Only"]), PredicateAnswer(probability_true=0.8)),
    ],
)
def test_answer_type_must_match_its_question(question, answer):
    request = DecisionRequest(input="Evidence", questions={"decision": question})
    with pytest.raises(ValueError, match="has a different type from its question"):
        validate_result(request, DecisionResult(answers={"decision": answer}))


@pytest.mark.parametrize(
    "question",
    [
        PredicateQuestion(instructions="Is the item damaged?"),
        ChoiceQuestion(criteria={"a": None}),
        ScoreQuestion(criteria=["Only"]),
    ],
)
def test_explicit_refusal_can_answer_any_question_type(question):
    request = DecisionRequest(input="Evidence", questions={"decision": question})
    result = DecisionResult(answers={"decision": RefusalAnswer()})

    assert validate_result(request, result) is result


@pytest.mark.parametrize(
    ("values", "choice"),
    [
        (["a", "b"], "a"),
        (["a", "b", "other"], "a"),
        (["a", "b", "c"], "other"),
    ],
)
def test_choice_distribution_and_selection_must_cover_requested_values(values, choice):
    answer = ChoiceAnswer(
        choice=choice,
        probabilities=dict.fromkeys(values, 1 / len(values)),
    )

    with pytest.raises(ValueError, match="must cover exactly the requested choices"):
        validate_result(choice_request(), DecisionResult(answers={"decision": answer}))


@pytest.mark.parametrize("field", ["probabilities", "legend"])
@pytest.mark.parametrize("indices", [[0, 2], [0, 1, 2, 3]])
def test_score_maps_must_cover_exactly_the_requested_indices(field, indices):
    request = score_request()
    result = result_for(request, "score", [0.1, 0.7, 0.2], score=1.1)
    data = result.answers["decision"].model_dump()
    data[field] = dict.fromkeys(indices, 0.2 if field == "probabilities" else "Level")
    result = DecisionResult(answers={"decision": ScoreAnswer.model_validate(data)})

    with pytest.raises(ValueError, match="must use the requested score levels"):
        validate_result(request, result)


@pytest.mark.parametrize("meaning", ["Changed meaning", {"eligible": [1]}])
def test_score_legend_must_retain_original_json_meanings(meaning):
    request = score_request()
    result = result_for(request, "score", [0.1, 0.7, 0.2], score=1.1)
    result.answers["decision"].legend[1] = meaning

    with pytest.raises(ValueError, match="must retain the requested score levels"):
        validate_result(request, result)
