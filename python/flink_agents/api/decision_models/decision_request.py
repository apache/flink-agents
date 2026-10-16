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

from typing import Annotated, Any, Dict, List, Literal

from pydantic import BaseModel, ConfigDict, Field

DecisionInput = str | Dict[str, Any] | List[Any]


class _DecisionData(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class PredicateQuestion(_DecisionData):
    """Ask for the probability that the supplied condition is true.

    Instructions are required and must define the condition, as text or structured
    JSON. Optional criteria supplement the instructions with true/false outcome
    descriptions for providers that support them; other providers ignore criteria.
    Either outcome may be omitted or left undescribed with None. A false criterion
    describes when the condition does not hold; the answer still estimates the
    probability of true.
    """

    type: Literal["predicate"] = "predicate"
    instructions: DecisionInput
    criteria: Dict[Literal["true", "false"], DecisionInput | None] | None = None


class ChoiceQuestion(_DecisionData):
    """Choose among named, unordered alternatives.

    Criteria use the selected provider's native format: a map of identifiers to
    descriptions, or a list of option objects. Map identifiers are strings;
    option values may be strings or booleans. Values preserve their type and
    string case in the answer. Map descriptions may be text,
    a JSON object or array, or None. Option objects follow the provider's schema.
    Formats are not converted into one another. The SDK and service determine
    whether the supplied shape is supported.
    Instructions may be text or structured JSON, or omitted when supported by
    the provider.
    """

    type: Literal["choice"] = "choice"
    instructions: DecisionInput | None = None
    criteria: Dict[str, DecisionInput | None] | List[Dict[str, Any]]


class ScoreQuestion(_DecisionData):
    """Evaluate evidence against levels ordered from lowest to highest.

    Criteria contain ordered levels, each described by text or
    structured JSON. Level descriptions need not be unique; their positions
    identify the levels. Dictionaries have no required or reserved keys.
    Providers may restrict the supported structure and reject other shapes.
    The result is the probability-weighted mean of the zero-based level indices,
    not the label of a single winning level.
    Instructions may be text or structured JSON, or omitted when supported by
    the provider.
    """

    type: Literal["score"] = "score"
    instructions: DecisionInput | None = None
    criteria: List[DecisionInput]


DecisionQuestion = Annotated[
    PredicateQuestion | ChoiceQuestion | ScoreQuestion,
    Field(discriminator="type"),
]


class DecisionRequest(_DecisionData):
    """Evaluate named independent questions against shared evidence.

    Input is text or a JSON object or array. Strings are preserved as text even
    when they contain JSON. The selected provider must support the supplied
    shape; objects and arrays are not automatically encoded as text.

    Question identifiers are case-sensitive. Each identifier maps to exactly one
    question and, on success, one answer. Reuse question objects across calls;
    business facts belong in each call's input. Provider restrictions and content
    serialization are handled by the SDK and service.
    """

    input: DecisionInput
    questions: Dict[str, DecisionQuestion]
