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

from collections.abc import Collection, Mapping
from typing import Any

from pydantic import JsonValue

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
from flink_agents.api.decision_models.decision_model import _validate_request_params


def prepare_request_params(
    request_params: Mapping[str, JsonValue] | None,
    *,
    reserved_fields: Collection[str],
) -> dict[str, Any]:
    """Copy call parameters and protect fields supplied by the framework."""
    params = _validate_request_params(request_params)
    conflicts = params.keys() & set(reserved_fields)
    extra_body = params.get("extra_body")
    if isinstance(extra_body, dict):
        conflicts |= extra_body.keys() & set(reserved_fields)
    if conflicts:
        msg = f"request_params cannot override reserved fields: {', '.join(sorted(conflicts))}."
        raise ValueError(msg)
    return params


def same_json_value(left: JsonValue, right: JsonValue) -> bool:
    """Compare JSON content without conflating booleans with numeric values."""
    if isinstance(left, dict):
        return (
            isinstance(right, dict)
            and left.keys() == right.keys()
            and all(same_json_value(value, right[key]) for key, value in left.items())
        )
    if isinstance(left, list):
        return (
            isinstance(right, list)
            and len(left) == len(right)
            and all(same_json_value(a, b) for a, b in zip(left, right, strict=True))
        )
    return isinstance(left, bool) == isinstance(right, bool) and left == right


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
            if (
                answer.probabilities.keys() != question.criteria.keys()
                or answer.choice not in question.criteria
            ):
                msg = f"Decision answer {name!r} must cover exactly the requested choices."
                raise ValueError(msg)
            continue
        if isinstance(question, ScoreQuestion) and isinstance(answer, ScoreAnswer):
            indices = set(range(len(question.criteria)))
            if (
                answer.probabilities.keys() != indices
                or answer.legend.keys() != indices
                or answer.score > len(question.criteria) - 1
            ):
                msg = f"Decision answer {name!r} must use the requested score levels."
                raise ValueError(msg)
            if any(
                not same_json_value(answer.legend[index], meaning)
                for index, meaning in enumerate(question.criteria)
            ):
                msg = (
                    f"Decision answer {name!r} must retain the requested score levels."
                )
                raise ValueError(msg)
            continue
        msg = f"Decision answer {name!r} has a different type from its question."
        raise ValueError(msg)
    return result
