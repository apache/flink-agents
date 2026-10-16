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
"""Shared utilities for decision model integrations."""

from typing import Any

from flink_agents.api.decision_models import (
    ChoiceAnswer,
    ChoiceQuestion,
    DecisionAnswer,
    DecisionRequest,
    DecisionResult,
    PredicateAnswer,
    PredicateQuestion,
    RefusalAnswer,
    ScoreAnswer,
    ScoreQuestion,
    TokenUsage,
)


def validate_model_name(model: str) -> str:
    """Require an explicit non-blank provider model."""
    if not isinstance(model, str) or not model.strip():
        msg = "A non-blank decision model name is required."
        raise ValueError(msg)
    return model


def create_result(
    request: DecisionRequest,
    response: dict[str, Any],
    answers: dict[str, DecisionAnswer],
) -> DecisionResult:
    """Assemble typed answers, usage and remaining response-level fields."""
    metadata = {
        key: value
        for key, value in response.items()
        if key not in {"answers", "model", "usage"}
    }
    raw_usage = response.get("usage")
    usage = None
    if raw_usage is not None:
        usage = TokenUsage(
            **{
                key: value
                for key, value in raw_usage.items()
                if key in TokenUsage.model_fields
            }
        )
        details = {
            key: value
            for key, value in raw_usage.items()
            if key not in TokenUsage.model_fields
        }
        if details:
            metadata["usage"] = details
    return validate_result(
        request,
        DecisionResult(
            answers=answers,
            model=response.get("model"),
            usage=usage,
            metadata=metadata,
        ),
    )


def validate_result(request: DecisionRequest, result: DecisionResult) -> DecisionResult:
    """Reject responses that cannot be matched unambiguously to the request."""
    if result.answers.keys() != request.questions.keys():
        msg = "Decision answer identifiers must exactly match the requested questions."
        raise ValueError(msg)
    for name, question in request.questions.items():
        answer = result.answers[name]
        if isinstance(answer, RefusalAnswer):
            continue
        if isinstance(question, PredicateQuestion) and isinstance(
            answer, PredicateAnswer
        ):
            continue
        if isinstance(question, ChoiceQuestion) and isinstance(answer, ChoiceAnswer):
            criteria = question.criteria
            requested_values = (
                criteria
                if isinstance(criteria, dict)
                else (option["value"] for option in criteria)
            )
            identifiers = {(type(value), value) for value in requested_values}
            values = [
                (type(entry.value), entry.value) for entry in answer.probabilities
            ]
            if (
                len(values) != len(set(values))
                or set(values) != identifiers
                or (type(answer.choice), answer.choice) not in identifiers
            ):
                msg = f"Decision answer {name!r} must cover exactly the requested choices."
                raise ValueError(msg)
            continue
        if isinstance(question, ScoreQuestion) and isinstance(answer, ScoreAnswer):
            if [entry.value for entry in answer.probabilities] != list(
                range(len(question.criteria))
            ) or answer.score > len(question.criteria) - 1:
                msg = f"Decision answer {name!r} must use the requested score levels."
                raise ValueError(msg)
            continue
        msg = f"Decision answer {name!r} has a different type from its question."
        raise ValueError(msg)
    return result
