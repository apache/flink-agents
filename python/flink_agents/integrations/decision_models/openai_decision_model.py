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

from typing import Any

from openai import OpenAI
from pydantic import Field, PrivateAttr
from typing_extensions import override

from flink_agents.api.decision_models import (
    BaseDecisionModelConnection,
    BaseDecisionModelSetup,
    ChoiceAnswer,
    ChoiceProbability,
    ChoiceQuestion,
    DecisionAnswer,
    DecisionQuestion,
    DecisionRequest,
    DecisionResult,
    PredicateAnswer,
    RefusalAnswer,
    ScoreAnswer,
    ScoreProbability,
    ScoreQuestion,
)
from flink_agents.integrations.decision_models.decision_model_utils import (
    create_result,
    validate_model_name,
)


def _questions(request: DecisionRequest) -> list[dict[str, Any]]:
    questions = []
    for name, question in request.questions.items():
        entry: dict[str, Any] = {
            "name": name,
            "type": question.type,
            "instructions": question.instructions,
        }
        if isinstance(question, ChoiceQuestion):
            entry["choices"] = question.criteria
        elif isinstance(question, ScoreQuestion):
            entry["levels"] = question.criteria
        questions.append(entry)
    return questions


def _answer(answer: dict[str, Any], question: DecisionQuestion) -> DecisionAnswer:
    kind = answer.get("type")
    if kind == "refusal":
        return RefusalAnswer(reason=answer.get("reason"))
    if kind != question.type:
        msg = "OpenAI decision answer has a different type from its question."
        raise ValueError(msg)
    if kind == "predicate":
        return PredicateAnswer(probability_true=answer["probability"])
    if kind == "choice":
        return ChoiceAnswer(
            choice=answer["choice"],
            probabilities=[
                ChoiceProbability(
                    value=entry["value"], probability=entry["probability"]
                )
                for entry in answer["probabilities"]
            ],
            provider_confidence=answer.get("confidence"),
        )
    if kind == "score" and isinstance(question, ScoreQuestion):
        return ScoreAnswer(
            score=answer["score"],
            probabilities=sorted(
                (
                    ScoreProbability(
                        value=entry["value"],
                        label=entry["label"],
                        probability=entry["probability"],
                    )
                    for entry in answer["probabilities"]
                ),
                key=lambda entry: entry.value,
            ),
            provider_confidence=answer.get("confidence"),
        )
    msg = f"Unsupported OpenAI decision answer type: {kind!r}."
    raise ValueError(msg)


def _result(request: DecisionRequest, response: dict[str, Any]) -> DecisionResult:
    answers = {}
    raw_answers = response.get("answers")
    if not isinstance(raw_answers, list):
        msg = "OpenAI decision response must contain an answers array."
        raise TypeError(msg)
    for answer in raw_answers:
        name = answer.get("name")
        if (
            not isinstance(name, str)
            or name not in request.questions
            or name in answers
        ):
            msg = (
                "OpenAI decision answers contain missing, unknown, or duplicate names."
            )
            raise ValueError(msg)
        answers[name] = _answer(answer, request.questions[name])

    return create_result(request, response, answers)


class OpenAIDecisionModelConnection(BaseDecisionModelConnection):
    """Call the OpenAI Decisions API through the official SDK.

    api_key and base_url fall back to the SDK's environment configuration, including
    OPENAI_API_KEY and OPENAI_BASE_URL. The client is created by open or the first
    call and reused until close. Input and question content are passed unchanged
    to the SDK. Choice and Score criteria become choices and levels without
    converting their contents. Predicate criteria are ignored; instructions must
    contain the complete decision rule. The SDK and service determine whether
    the supplied format is supported; FA adds no provider request validation.
    Request errors retain their SDK exception types. Unset timeout and retry
    limits use the SDK defaults. Set timeout to 0 to disable timeouts.
    """

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
    def decide(self, request: DecisionRequest, *, model: str) -> DecisionResult:
        """Evaluate named questions and return answers matched by their names."""
        validate_model_name(model)
        response = self._get_client().decisions.with_raw_response.create(
            model=model, input=request.input, questions=_questions(request)
        )
        try:
            # Preserve exact JSON identities: SDK model coercion can turn a boolean or
            # string level index into an integer before FA can validate the response.
            return _result(request, response.parse(to=dict))
        except (AttributeError, KeyError, TypeError) as exc:
            msg = "Malformed OpenAI decision response."
            raise ValueError(msg) from exc

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
