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
"""Decision models using the TypeSafe System One SDK and compatible services."""

from typing import Any

from pydantic import BaseModel, ConfigDict, Field, PrivateAttr
from typesafe_sdk import Choice, Noul, Score, TypeSafeClient
from typing_extensions import override

from flink_agents.api.decision_models import (
    BaseDecisionModelConnection,
    BaseDecisionModelSetup,
    ChoiceAnswer,
    ChoiceProbability,
    DecisionRequest,
    DecisionResult,
    PredicateAnswer,
    ScoreAnswer,
    ScoreProbability,
    ScoreQuestion,
)
from flink_agents.integrations.decision_models.decision_model_utils import (
    create_result,
    validate_model_name,
)


class _SystemOneResponse(BaseModel):
    """Keep JSON extensions and every answer for validation by the adapter."""

    # The SDK's default SystemOneResponse drops unknown answer types. Its public
    # response_model parameter instead validates this schema without that filtering.
    # https://docs.typesafe.ai/sdk/python/api/clients/sync#system-one
    model_config = ConfigDict(extra="allow", strict=True)

    model: str
    answers: dict[str, dict[str, Any]]
    usage: dict[str, Any] | None = None


def _questions(request: DecisionRequest) -> dict[str, Noul | Choice | Score]:
    question_types = {"predicate": Noul, "choice": Choice, "score": Score}
    return {
        name: question_types[question.type](
            instructions=question.instructions, criteria=question.criteria
        )
        for name, question in request.questions.items()
    }


def _answer(
    question_id: str,
    answer: dict[str, Any],
    request: DecisionRequest,
) -> PredicateAnswer | ChoiceAnswer | ScoreAnswer:
    answer_type = answer.get("type")
    if answer_type == "noul":
        return PredicateAnswer(probability_true=answer["noul"])
    if answer_type == "choice":
        probabilities = answer["probabilities"]
        if not isinstance(probabilities, dict):
            msg = (
                f"TypeSafe returned an invalid choice distribution for {question_id!r}."
            )
            raise ValueError(msg)
        return ChoiceAnswer(
            choice=answer["choice"],
            probabilities=[
                ChoiceProbability(value=value, probability=probability)
                for value, probability in probabilities.items()
            ],
            provider_confidence=answer.get("confidence"),
        )
    if answer_type == "score":
        question = request.questions.get(question_id)
        if not isinstance(question, ScoreQuestion):
            msg = f"TypeSafe returned a score for non-score question {question_id!r}."
            raise ValueError(msg)
        indices = {str(index) for index in range(len(question.criteria))}
        probabilities = answer["probabilities"]
        legend = answer["legend"]
        if (
            not isinstance(probabilities, dict)
            or set(probabilities) != indices
            or not isinstance(legend, dict)
            or set(legend) != indices
        ):
            msg = f"TypeSafe returned invalid score level indices for {question_id!r}."
            raise ValueError(msg)
        return ScoreAnswer(
            score=answer["score"],
            probabilities=[
                ScoreProbability(
                    value=index,
                    label=legend[str(index)],
                    probability=probabilities[str(index)],
                )
                for index in range(len(question.criteria))
            ],
            provider_confidence=answer.get("confidence"),
        )
    # Jev documents noul/choice/score only; an undocumented refusal is a
    # protocol error, not enough evidence to construct a RefusalAnswer.
    msg = f"TypeSafe returned unsupported answer type {answer_type!r}."
    raise ValueError(msg)


def _result(request: DecisionRequest, response: _SystemOneResponse) -> DecisionResult:
    answers = {}
    try:
        for question_id, answer in response.answers.items():
            answers[question_id] = _answer(question_id, answer, request)
    except KeyError as error:
        msg = f"TypeSafe answer {question_id!r} is missing field {error.args[0]!r}."
        raise ValueError(msg) from error

    return create_result(request, response.model_dump(), answers)


class TypeSafeDecisionModelConnection(BaseDecisionModelConnection):
    """Connect to Jev or a TypeSafe System One compatible endpoint.

    ``api_key`` falls back to ``TYPESAFE_API_KEY`` through the official SDK.
    ``base_url`` is the API root, without ``/v1/systemone``. For Bailian, use
    the workspace endpoint ending in ``/compatible-mode`` and explicitly pass
    its API key. ``timeout`` is the per-operation HTTP timeout in seconds;
    leaving it unset uses the SDK default.

    One SDK client is reused across calls and released by ``close()``. SDK
    failures retain their exception types. Malformed answer values or answers
    inconsistent with the requested questions raise ``ValueError`` or a subclass.
    """

    api_key: str | None = Field(default=None, repr=False)
    base_url: str = Field(default="https://api.typesafe.ai", min_length=1)
    timeout: float | None = Field(default=None, gt=0, allow_inf_nan=False)

    _client: TypeSafeClient | None = PrivateAttr(default=None)

    def _get_client(self) -> TypeSafeClient:
        if self._client is None:
            options: dict[str, Any] = {}
            if self.timeout is not None:
                options["timeout"] = self.timeout
            self._client = TypeSafeClient(
                api_key=self.api_key, base_url=self.base_url, **options
            )
        return self._client

    @override
    def open(self) -> None:
        """Initialize the reusable SDK client without making a model request."""
        self._get_client()

    @override
    def decide(self, request: DecisionRequest, *, model: str) -> DecisionResult:
        """Evaluate all questions against the supplied text or JSON state.

        Score criteria retain their native structure and order. Returned score
        probabilities follow this same order. Additional response-level fields
        and usage details are retained in result metadata.
        """
        validate_model_name(model)
        questions = _questions(request)
        response = self._get_client().system_one(
            state=request.input,
            questions=questions,
            model=model,
            response_model=_SystemOneResponse,
        )
        return _result(request, response)

    @override
    def close(self) -> None:
        """Release the SDK client; a subsequent call creates a new client."""
        if self._client is not None:
            try:
                self._client.close()
            finally:
                self._client = None


class TypeSafeDecisionModelSetup(BaseDecisionModelSetup):
    """A TypeSafe connection and an explicitly selected Jev or compatible model."""
