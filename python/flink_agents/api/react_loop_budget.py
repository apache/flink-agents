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
#################################################################################
from datetime import timedelta
from enum import Enum
from typing import Annotated

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    field_serializer,
    field_validator,
)

from flink_agents.api.duration import parse_duration


class BudgetMode(str, Enum):
    """How to end a loop that needs more work after reaching a limit."""

    SOFT = "SOFT"
    HARD = "HARD"

    @classmethod
    def _missing_(cls, value: object) -> "BudgetMode | None":
        """Accept case-insensitive mode values, matching Java."""
        if isinstance(value, str):
            return cls.__members__.get(value.upper())
        return None


def _parse_budget_duration(value: object) -> timedelta:
    """Parse a positive Flink duration with millisecond precision."""
    try:
        value = parse_duration(value)
    except (ValueError, TypeError, OverflowError) as error:
        msg = "max_time must be positive with millisecond precision"
        raise ValueError(msg) from error
    if (
        not isinstance(value, timedelta)
        or value.microseconds % 1000 != 0
        or not 0 < value // timedelta(milliseconds=1) <= 2**63 - 1
    ):
        msg = "max_time must be positive with millisecond precision"
        raise ValueError(msg)
    return value


class ReActLoopBudget(BaseModel):
    """Configuration for one loop. Omitted limits are unlimited."""

    model_config = ConfigDict(extra="forbid", frozen=True)
    max_turns: Annotated[int, Field(strict=True, gt=0, le=2**31 - 1)] | None = None
    max_time: timedelta | None = None
    max_tokens: Annotated[int, Field(strict=True, gt=0, le=2**63 - 1)] | None = None
    mode: BudgetMode = BudgetMode.SOFT

    @field_validator("mode", mode="before")
    @classmethod
    def _normalize_null_mode(cls, value: object) -> object:
        """Treat an explicitly null mode as the default SOFT mode."""
        return BudgetMode.SOFT if value is None else value

    @field_validator("max_time", mode="before")
    @classmethod
    def _duration(cls, value: object) -> timedelta | None:
        return None if value is None else _parse_budget_duration(value)

    @field_serializer("max_time", when_used="json")
    def _serialize_duration(self, value: timedelta | None) -> str | None:
        """Avoid Pydantic's year units, which Java Duration cannot parse."""
        if value is None:
            return None
        days = f"{value.days}D" if value.days else ""
        if value.seconds == 0 and value.microseconds == 0:
            return f"P{days}"
        fraction = (
            f".{value.microseconds // 1000:03d}".rstrip("0")
            if value.microseconds
            else ""
        )
        return f"P{days}T{value.seconds}{fraction}S"
