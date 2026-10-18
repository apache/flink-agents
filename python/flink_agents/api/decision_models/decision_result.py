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
"""Typed answers and provider-reported usage for one decision call."""

import re
from typing import Annotated, Any, Dict, Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    JsonValue,
    StrictStr,
    ValidationInfo,
    field_validator,
)

_Probability = Annotated[float, Field(ge=0.0, le=1.0, strict=True)]
_TokenCount = Annotated[int, Field(ge=0, strict=True)]
_LevelIndex = Annotated[int, Field(ge=0, strict=True)]


class _DecisionData(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, allow_inf_nan=False)


class TokenUsage(_DecisionData):
    """Token counts reported for the entire request.

    None means the provider did not report that count. Missing counts are neither
    inferred nor replaced with zero.
    """

    input_tokens: _TokenCount | None = None
    output_tokens: _TokenCount | None = None
    total_tokens: _TokenCount | None = None


class PredicateAnswer(_DecisionData):
    """Provider estimate that the predicate is true; callers choose thresholds."""

    type: Literal["predicate"] = "predicate"
    probability_true: _Probability


class ChoiceAnswer(_DecisionData):
    """Selected choice and the full distribution over the requested choices.

    provider_confidence preserves the provider's separate confidence measure.
    It is not defined as the probability of the selected choice.
    Probabilities map case-sensitive choice values to their probabilities,
    retaining provider order.
    """

    type: Literal["choice"] = "choice"
    choice: StrictStr
    probabilities: dict[StrictStr, _Probability] = Field(min_length=1)
    provider_confidence: _Probability | None = None


class ScoreAnswer(_DecisionData):
    """Expected zero-based level index and described probabilities in level order.

    The score may lie between levels. provider_confidence retains the provider's
    own measure without translating it into a selected-level probability.
    Probabilities are keyed by level index. Legend contains the original request
    descriptions at those indices, independent of provider labels or encoding.
    """

    type: Literal["score"] = "score"
    score: float = Field(ge=0.0, strict=True)
    probabilities: dict[_LevelIndex, _Probability] = Field(min_length=1)
    legend: dict[_LevelIndex, JsonValue] = Field(default_factory=dict)
    provider_confidence: _Probability | None = None

    @field_validator("probabilities", "legend", mode="before")
    @classmethod
    def _require_json_indices(cls, value: Any, info: ValidationInfo) -> Any:
        if info.mode == "json" and isinstance(value, dict):
            if any(re.fullmatch(r"0|[1-9][0-9]*", key) is None for key in value):
                msg = "Score indices must be canonical nonnegative integers."
                raise ValueError(msg)
            return {int(key): item for key, item in value.items()}
        return value


class RefusalAnswer(_DecisionData):
    """A provider's explicit refusal to answer a particular question.

    A refusal is distinct from low confidence or a failed request. A reason is
    included only when the provider reports one.
    """

    type: Literal["refusal"] = "refusal"
    reason: str | None = None


DecisionAnswer = Annotated[
    PredicateAnswer | ChoiceAnswer | ScoreAnswer | RefusalAnswer,
    Field(discriminator="type"),
]


class DecisionResult(_DecisionData):
    """Answers keyed by the original question identifiers.

    model and usage contain only provider-reported values. metadata holds
    additional response-level and usage fields without assigning them portable
    semantics.
    Authentication, transport, and malformed-response errors raise exceptions
    rather than being represented as answers.
    """

    answers: Dict[str, DecisionAnswer] = Field(min_length=1)
    model: str | None = Field(
        default=None,
        description=(
            "Model identifier reported by the provider for this result. It may "
            "differ from the requested model alias. None when not reported."
        ),
    )
    usage: TokenUsage | None = None
    metadata: Dict[str, Any] = Field(default_factory=dict)
