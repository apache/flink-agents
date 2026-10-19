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
"""Decision resources backed by the official OpenAI Python SDK."""

import json
from collections.abc import Mapping
from typing import Any, ClassVar

from openai import OpenAI
from openai.types import Decision as OpenAIDecision
from openai.types.decision import Answer as OpenAIAnswer
from pydantic import Field, JsonValue, PrivateAttr
from typing_extensions import override

from flink_agents.api.decision_models import (
    BaseDecisionModelConnection,
    BaseDecisionModelSetup,
    ChoiceAnswer,
    ChoiceQuestion,
    DecisionAnswer,
    DecisionQuestion,
    DecisionRequest,
    DecisionResult,
    PredicateAnswer,
    PredicateQuestion,
    RefusalAnswer,
    ScoreAnswer,
    ScoreQuestion,
)
from flink_agents.api.decision_models.decision_model import _validate_model_name
from flink_agents.integrations.decision_models.decision_model_utils import (
    create_result,
    prepare_request_params,
)


def _text(value: JsonValue, field: str) -> str:
    if value is None:
        msg = f"OpenAI {field} is required."
        raise ValueError(msg)
    return (
        value
        if isinstance(value, str)
        else json.dumps(value, ensure_ascii=False, allow_nan=False)
    )


def _description(value: JsonValue, field: str) -> dict[str, str]:
    return {} if value is None else {"description": _text(value, field)}


def _predicate_instructions(question: PredicateQuestion) -> JsonValue:
    """Include outcome meanings in the instructions accepted by OpenAI."""
    instructions = question.instructions
    criteria = question.criteria
    if criteria is None:
        return instructions
    meanings = {
        key: value
        for key, value in (("yes", criteria.true), ("no", criteria.false))
        if value is not None
    }
    if not meanings:
        return instructions
    if instructions is None:
        return meanings
    if isinstance(instructions, dict):
        return {**instructions, **meanings}
    return {"question": instructions, **meanings}


def _questions(request: DecisionRequest) -> list[dict[str, Any]]:
    max_questions = OpenAIDecisionModelConnection._MAX_QUESTIONS
    if len(request.questions) > max_questions:
        msg = f"OpenAI supports at most {max_questions} questions per decision request."
        raise ValueError(msg)
    questions = []
    for name, question in request.questions.items():
        instructions = (
            _predicate_instructions(question)
            if isinstance(question, PredicateQuestion)
            else question.instructions
        )
        entry: dict[str, Any] = {
            "name": name,
            "type": question.type,
            "instructions": _text(instructions, f"question {name!r} instructions"),
        }
        if isinstance(question, ChoiceQuestion):
            min_choices = OpenAIDecisionModelConnection._MIN_CHOICE_OPTIONS
            max_choices = OpenAIDecisionModelConnection._MAX_CHOICE_OPTIONS
            if not min_choices <= len(question.criteria) <= max_choices:
                msg = (
                    f"OpenAI question {name!r} requires between "
                    f"{min_choices} and {max_choices} choices."
                )
                raise ValueError(msg)
            entry["choices"] = [
                {
                    "value": value,
                    **_description(
                        meaning, f"question {name!r} choice {value!r} description"
                    ),
                }
                for value, meaning in question.criteria.items()
            ]
        elif isinstance(question, ScoreQuestion):
            min_levels = OpenAIDecisionModelConnection._MIN_SCORE_LEVELS
            max_levels = OpenAIDecisionModelConnection._MAX_SCORE_LEVELS
            if not min_levels <= len(question.criteria) <= max_levels:
                msg = (
                    f"OpenAI question {name!r} requires between "
                    f"{min_levels} and {max_levels} score levels."
                )
                raise ValueError(msg)
            entry["levels"] = [
                {
                    "label": str(index),
                    **_description(
                        meaning,
                        f"question {name!r} score level {index} description",
                    ),
                }
                for index, meaning in enumerate(question.criteria)
            ]
        questions.append(entry)
    return questions


def _answer(answer: OpenAIAnswer, question: DecisionQuestion) -> DecisionAnswer:
    kind = answer.type
    if kind == "refusal":
        return RefusalAnswer(reason=getattr(answer, "reason", None))
    if kind == "predicate":
        return PredicateAnswer(probability_true=answer.probability)
    if kind == "choice":
        probabilities = {
            entry.value: entry.probability for entry in answer.probabilities
        }
        if len(probabilities) != len(answer.probabilities):
            msg = "OpenAI decision answer contains duplicate choices."
            raise ValueError(msg)
        return ChoiceAnswer(
            choice=answer.choice,
            probabilities=probabilities,
            provider_confidence=answer.confidence,
        )
    if kind == "score" and isinstance(question, ScoreQuestion):
        probabilities = {}
        for entry in answer.probabilities:
            index = entry.value
            if index in probabilities:
                msg = "OpenAI decision answer contains an invalid score level index."
                raise ValueError(msg)
            if entry.label != str(index):
                msg = "OpenAI decision answer contains an unexpected score level label."
                raise ValueError(msg)
            probabilities[index] = entry.probability
        return ScoreAnswer(
            score=answer.score,
            probabilities=dict(sorted(probabilities.items())),
            legend=dict(enumerate(question.criteria)),
            provider_confidence=answer.confidence,
        )
    msg = "OpenAI decision answer has a different type from its question."
    raise ValueError(msg)


def _result(request: DecisionRequest, response: OpenAIDecision) -> DecisionResult:
    answers = {}
    for answer in response.answers:
        name = answer.name
        if name not in request.questions or name in answers:
            msg = (
                "OpenAI decision answers contain missing, unknown, or duplicate names."
            )
            raise ValueError(msg)
        answers[name] = _answer(answer, request.questions[name])

    return create_result(request, response.model_dump(exclude={"answers"}), answers)


class OpenAIDecisionModelConnection(BaseDecisionModelConnection):
    """Call the OpenAI Decisions API through the official SDK.

    api_key and base_url fall back to the SDK's environment configuration, including
    OPENAI_API_KEY and OPENAI_BASE_URL. The client is created by open or the first
    call and reused until close. Strings are preserved; structured input,
    instructions and descriptions are JSON encoded as text. Predicate outcome
    descriptions become yes/no meanings in instructions. Choice and Score require
    explicit instructions. Choice values remain case-sensitive strings. Score labels
    are the zero-based indices; returned legends retain the original JSON level
    descriptions after response validation. Unsupported requests
    raise ValueError before a provider call.
    Responses are strictly validated against the SDK schema before conversion.
    Probabilities and scores are preserved as reported by the provider.
    Request errors retain their SDK exception types. Unset timeout and retry
    limits use the SDK defaults. Set timeout to 0 to disable timeouts.
    """

    _MAX_QUESTIONS: ClassVar[int] = 200
    _MIN_CHOICE_OPTIONS: ClassVar[int] = 2
    _MAX_CHOICE_OPTIONS: ClassVar[int] = 255
    _MIN_SCORE_LEVELS: ClassVar[int] = 2
    _MAX_SCORE_LEVELS: ClassVar[int] = 10
    _RESERVED_REQUEST_FIELDS: ClassVar[frozenset[str]] = frozenset(
        {"model", "input", "questions"}
    )

    api_key: str | None = Field(default=None, repr=False)
    base_url: str | None = None
    timeout: float | None = Field(default=None, ge=0.0, allow_inf_nan=False)
    max_retries: int | None = Field(default=None, ge=0)

    _client: OpenAI | None = PrivateAttr(default=None)

    def _get_client(self) -> OpenAI:
        if self._client is None:
            options: dict[str, Any] = {}
            if self.timeout is not None:
                options["timeout"] = None if self.timeout == 0 else self.timeout
            if self.max_retries is not None:
                options["max_retries"] = self.max_retries
            self._client = OpenAI(
                api_key=self.api_key,
                base_url=self.base_url,
                **options,
            )
        return self._client

    @override
    def open(self) -> None:
        """Initialize the shared SDK client."""
        self._get_client()

    @override
    def decide(
        self,
        request: DecisionRequest,
        *,
        model: str,
        request_params: Mapping[str, JsonValue] | None = None,
    ) -> DecisionResult:
        """Evaluate named questions and return answers matched by their names."""
        _validate_model_name(model)
        decision_input = _text(request.input, "input")
        questions = _questions(request)
        params = prepare_request_params(
            request_params, reserved_fields=self._RESERVED_REQUEST_FIELDS
        )
        response = self._get_client().decisions.with_raw_response.create(
            model=model, input=decision_input, questions=questions, **params
        )
        decision = OpenAIDecision.model_validate_json(response.content, strict=True)
        return _result(request, decision)

    @override
    def close(self) -> None:
        """Release the client; a later call creates a new one."""
        if self._client is not None:
            try:
                self._client.close()
            finally:
                self._client = None


class OpenAIDecisionModelSetup(BaseDecisionModelSetup):
    """OpenAI decision model selected explicitly by the caller."""
