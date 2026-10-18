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
"""Inputs and questions for constrained model decisions."""

from typing import Annotated, Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    JsonValue,
    StrictStr,
    model_validator,
)
from typing_extensions import Self


class _DecisionData(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, allow_inf_nan=False)


class PredicateCriteria(_DecisionData):
    """Descriptions of what makes a predicate true or false.

    None leaves an outcome undescribed. Either outcome may be described alone.
    """

    true: JsonValue = None
    false: JsonValue = None


class PredicateQuestion(_DecisionData):
    """Ask for the probability that the supplied condition is true.

    Supply instructions or describe at least one outcome in criteria. A false
    criterion describes when the condition does not hold; the answer still
    estimates the probability of true. Providers must reject content they cannot
    represent rather than silently discard an outcome description.
    """

    type: Literal["predicate"] = "predicate"
    instructions: JsonValue = None
    criteria: PredicateCriteria | None = None

    @model_validator(mode="after")
    def _require_condition(self) -> Self:
        if self.instructions is None and (
            self.criteria is None
            or (self.criteria.true is None and self.criteria.false is None)
        ):
            msg = "A predicate requires instructions or a described true/false outcome."
            raise ValueError(msg)
        return self


class ChoiceQuestion(_DecisionData):
    """Choose among named, unordered alternatives.

    Criteria map case-sensitive string values to their JSON descriptions.
    A null description leaves the value itself to supply the option's meaning.
    Instructions may be omitted when the provider accepts a question defined by
    its options alone. Adapters preserve option values and descriptions or reject
    content that the provider cannot represent.
    """

    type: Literal["choice"] = "choice"
    instructions: JsonValue = None
    criteria: dict[StrictStr, JsonValue] = Field(min_length=1)


class ScoreQuestion(_DecisionData):
    """Evaluate evidence against levels ordered from lowest to highest.

    Criteria contain complete JSON descriptions in ascending order. Their
    zero-based positions identify the levels; descriptions need not be unique.
    Objects are business content with no reserved label or description fields.
    The result is the probability-weighted mean of the zero-based level indices,
    not the label of a single winning level.
    Instructions may be text or structured JSON, or omitted when supported by
    the provider.
    """

    type: Literal["score"] = "score"
    instructions: JsonValue = None
    criteria: list[JsonValue] = Field(min_length=1)


DecisionQuestion = Annotated[
    PredicateQuestion | ChoiceQuestion | ScoreQuestion,
    Field(discriminator="type"),
]


class DecisionRequest(_DecisionData):
    """Evaluate named independent questions against shared evidence.

    Input and question content are JSON values. Strings are preserved as text
    even when they contain JSON. Adapters encode structured content where the
    provider requires text and reject content they cannot represent.

    Question identifiers are case-sensitive. Each identifier maps to exactly one
    question and, on success, one answer. Reuse question objects across calls;
    business facts belong in each call's input. Common constraints are validated
    on construction; provider-specific restrictions are checked before sending.
    """

    input: JsonValue
    questions: dict[str, DecisionQuestion] = Field(min_length=1)
