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

import json
from collections.abc import Mapping
from typing import Any, ClassVar

from pydantic import Field, JsonValue, PrivateAttr, RootModel, TypeAdapter
from typesafe_sdk import Answer as TypeSafeAnswer
from typesafe_sdk import Choice, Noul, RetryPolicy, Score, TypeSafeClient
from typesafe_sdk import ChoiceAnswer as TypeSafeChoiceAnswer
from typesafe_sdk import NoulAnswer as TypeSafeNoulAnswer
from typesafe_sdk import SystemOneResponse as TypeSafeSystemOneResponse
from typing_extensions import Self, override

from flink_agents.api.decision_models import (
    BaseDecisionModelConnection,
    BaseDecisionModelSetup,
    ChoiceAnswer,
    ChoiceQuestion,
    DecisionRequest,
    DecisionResult,
    PredicateAnswer,
    PredicateQuestion,
    ScoreAnswer,
    ScoreQuestion,
)
from flink_agents.api.decision_models.decision_model import _validate_model_name
from flink_agents.integrations.decision_models.decision_model_utils import (
    create_result,
    prepare_request_params,
)

_RETRY_POLICY = TypeAdapter(RetryPolicy)


class _SystemOneResponse(RootModel[TypeSafeSystemOneResponse]):
    """Validate the SDK schema while preserving wire identities and extensions."""

    # A custom response_model uses Pydantic parsing rather than the SDK's default
    # decoder, which filters unknown answer types before validating its schema.
    # https://docs.typesafe.ai/sdk/python/api/clients/sync#system-one
    _raw_response: dict[str, Any] = PrivateAttr()

    @classmethod
    @override
    def model_validate_json(
        cls, json_data: str | bytes | bytearray, **kwargs: Any
    ) -> Self:
        # Keep JSON-mode validation so strict integer score keys can be decoded
        # from JSON object keys without loosening the SDK's field validation.
        response = super().model_validate_json(json_data, **kwargs)
        response._raw_response = json.loads(json_data)
        return response


def _questions(request: DecisionRequest) -> dict[str, Noul | Choice | Score]:
    questions = {}
    for name, question in request.questions.items():
        if isinstance(question, PredicateQuestion):
            criteria = (
                question.criteria.model_dump(exclude_none=True)
                if question.criteria is not None
                else None
            )
            questions[name] = Noul(
                instructions=question.instructions, criteria=criteria or None
            )
        elif isinstance(question, ChoiceQuestion):
            max_choices = TypeSafeDecisionModelConnection._MAX_CHOICE_OPTIONS
            if len(question.criteria) > max_choices:
                msg = f"TypeSafe question {name!r} supports at most {max_choices} choices."
                raise ValueError(msg)
            questions[name] = Choice(
                instructions=question.instructions, criteria=question.criteria
            )
        elif isinstance(question, ScoreQuestion):
            max_levels = TypeSafeDecisionModelConnection._MAX_SCORE_LEVELS
            if len(question.criteria) > max_levels:
                msg = f"TypeSafe question {name!r} supports at most {max_levels} score levels."
                raise ValueError(msg)
            questions[name] = Score(
                instructions=question.instructions, criteria=question.criteria
            )
    return questions


def _answer(
    question_id: str,
    answer: TypeSafeAnswer,
    raw_answer: dict[str, Any],
) -> PredicateAnswer | ChoiceAnswer | ScoreAnswer:
    if isinstance(answer, TypeSafeNoulAnswer):
        return PredicateAnswer(probability_true=answer.noul)
    if isinstance(answer, TypeSafeChoiceAnswer):
        return ChoiceAnswer(
            choice=answer.choice,
            probabilities=answer.probabilities,
            provider_confidence=answer.confidence,
        )
    # The SDK converts score keys to integers. Check their original spelling so
    # aliases such as "01" cannot collapse onto another level during parsing.
    if any(
        set(raw_answer[field]) != {str(index) for index in getattr(answer, field)}
        for field in ("probabilities", "legend")
    ):
        msg = f"TypeSafe returned invalid score level indices for {question_id!r}."
        raise ValueError(msg)
    return ScoreAnswer(
        score=answer.score,
        probabilities=dict(sorted(answer.probabilities.items())),
        legend=dict(sorted(answer.legend.items())),
        provider_confidence=answer.confidence,
    )


def _result(request: DecisionRequest, response: _SystemOneResponse) -> DecisionResult:
    raw_response = response._raw_response
    answers = {
        question_id: _answer(question_id, answer, raw_response["answers"][question_id])
        for question_id, answer in response.root.answers.items()
    }
    return create_result(request, raw_response, answers)


class TypeSafeDecisionModelConnection(BaseDecisionModelConnection):
    """Connect to Jev or a TypeSafe System One compatible endpoint.

    ``api_key`` falls back to ``TYPESAFE_API_KEY`` through the official SDK.
    ``base_url`` is the API root, without ``/v1/systemone``. For Bailian, use
    the workspace endpoint ending in ``/compatible-mode`` and explicitly pass
    its API key. ``timeout`` is the per-operation HTTP timeout in seconds;
    leaving it unset uses the SDK default.

    One SDK client is reused across calls and released by ``close()``. SDK
    transport and response-schema failures retain their exception types. Answers
    outside the common value constraints or inconsistent with the requested
    questions raise ``ValueError`` or a subclass.
    Choice values must be strings. Score criteria are complete JSON descriptions
    passed to the provider without interpreting object fields. Returned legends
    retain the original descriptions after response validation. Unsupported
    content is rejected before sending.
    """

    _MAX_CHOICE_OPTIONS: ClassVar[int] = 255
    _MAX_SCORE_LEVELS: ClassVar[int] = 10
    _RESERVED_REQUEST_FIELDS: ClassVar[frozenset[str]] = frozenset(
        {"model", "state", "questions", "response_model"}
    )

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
    def decide(
        self,
        request: DecisionRequest,
        *,
        model: str,
        request_params: Mapping[str, JsonValue] | None = None,
    ) -> DecisionResult:
        """Evaluate all questions against the supplied text or JSON state.

        Returned score probabilities retain the original level definitions in
        request order. Additional response-level fields and usage details are
        retained in result metadata. request_params accepts SDK call options;
        supply retry as a JSON object containing RetryPolicy fields.
        """
        _validate_model_name(model)
        if not isinstance(request.input, str | dict | list):
            msg = "TypeSafe input must be text, a JSON object, or a JSON array."
            raise TypeError(msg)
        questions = _questions(request)
        params = prepare_request_params(
            request_params, reserved_fields=self._RESERVED_REQUEST_FIELDS
        )
        if params.get("retry") is not None:
            params["retry"] = _RETRY_POLICY.validate_json(
                json.dumps(params["retry"]), strict=True, extra="forbid"
            )
        response = self._get_client().system_one(
            state=request.input,
            questions=questions,
            model=model,
            response_model=_SystemOneResponse,
            **params,
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
