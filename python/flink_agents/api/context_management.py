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
"""How the built-in chat action bounds the model input of one chat request."""

import math
import re
from typing import Any, Callable, ClassVar, Dict, Mapping

from pydantic import (
    BaseModel,
    ConfigDict,
    StrictInt,
    StrictStr,
    field_validator,
    model_serializer,
    model_validator,
)

from flink_agents.api.configuration import ConfigOption, ReadableConfiguration
from flink_agents.api.core_options import AgentExecutionOptions

_TOOL_RESULT_MAX_CHARS = "tool_result_max_chars"
_TOOL_RESULT_KEEP_LAST_ROUNDS = "tool_result_keep_last_rounds"
_TOOL_RESULT_MASK_STEP_ROUNDS = "tool_result_mask_step_rounds"
_MAX_INPUT_TOKENS = "max_input_tokens"
_SUMMARIZATION_TRIGGER_RATIO = "summarization_trigger_ratio"
_SUMMARIZATION_KEEP_LAST_ROUNDS = "summarization_keep_last_rounds"
_SUMMARIZER = "summarizer"

# The largest value of an integer field. A policy is also read on the Java side,
# where these fields are 32-bit integers.
_MAX_INT = 2_147_483_647

# The execution option that sets each field, in declaration order.
_OPTIONS: Dict[str, ConfigOption] = {
    _TOOL_RESULT_MAX_CHARS: (
        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS
    ),
    _TOOL_RESULT_KEEP_LAST_ROUNDS: (
        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS
    ),
    _TOOL_RESULT_MASK_STEP_ROUNDS: (
        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MASK_STEP_ROUNDS
    ),
    _MAX_INPUT_TOKENS: AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS,
    _SUMMARIZATION_TRIGGER_RATIO: (
        AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO
    ),
    _SUMMARIZATION_KEEP_LAST_ROUNDS: (
        AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS
    ),
    _SUMMARIZER: AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL,
}


def _check(values: Mapping[str, Any], name: Callable[[str], str]) -> None:
    """Apply the policy's rules to the values that are set.

    ``name`` turns a field name into the name the error reports: the field itself, or
    the execution option that set it.
    """

    def at_least(field: str, minimum: int) -> None:
        value = values.get(field)
        if value is None:
            return
        if value < minimum:
            msg = f"{name(field)} must be at least {minimum}, got {value}"
            raise ValueError(msg)
        if value > _MAX_INT:
            msg = f"{name(field)} is out of range, got {value}"
            raise ValueError(msg)

    def only_with(field: str, required: str) -> None:
        if values.get(field) is not None and values.get(required) is None:
            msg = (
                f"{name(field)} has no effect without {name(required)}; "
                f"set {name(required)} or remove {name(field)}"
            )
            raise ValueError(msg)

    at_least(_TOOL_RESULT_MAX_CHARS, ContextManagementPolicy.MIN_TOOL_RESULT_MAX_CHARS)
    at_least(_TOOL_RESULT_KEEP_LAST_ROUNDS, 1)
    at_least(_TOOL_RESULT_MASK_STEP_ROUNDS, 1)
    at_least(_MAX_INPUT_TOKENS, 1)
    at_least(_SUMMARIZATION_KEEP_LAST_ROUNDS, 1)
    ratio = values.get(_SUMMARIZATION_TRIGGER_RATIO)
    if ratio is not None and not 0.0 < ratio <= 1.0:
        msg = (
            f"{name(_SUMMARIZATION_TRIGGER_RATIO)} must be greater than 0 and at "
            f"most 1, got {ratio}"
        )
        raise ValueError(msg)
    summarizer = values.get(_SUMMARIZER)
    if summarizer is not None and _BLANK.fullmatch(summarizer):
        msg = f"{name(_SUMMARIZER)} must name a chat model, got a blank value"
        raise ValueError(msg)
    only_with(_TOOL_RESULT_MASK_STEP_ROUNDS, _TOOL_RESULT_KEEP_LAST_ROUNDS)
    only_with(_SUMMARIZATION_TRIGGER_RATIO, _MAX_INPUT_TOKENS)
    only_with(_SUMMARIZATION_KEEP_LAST_ROUNDS, _MAX_INPUT_TOKENS)
    only_with(_SUMMARIZER, _MAX_INPUT_TOKENS)


# A name without a character that is not white space, by the Unicode property
# White_Space. The characters are listed, so that Java and Python read the same names
# as blank.
_BLANK = re.compile(
    "[\t-\r \x85\xa0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]*"
)

# An integer as a configuration writes it: an optional sign and decimal digits.
_INTEGER = re.compile(r"[+-]?[0-9]+", re.ASCII)
# A decimal number that can have an exponent.
_NUMBER = re.compile(r"[+-]?(?:[0-9]+\.?[0-9]*|\.[0-9]+)(?:[eE][+-]?[0-9]+)?", re.ASCII)


def _require_number(written: Any, kind: type) -> None:
    """Reject a value that is not the number its option asks for.

    The value is judged by its text, which is how a configuration file holds it: an
    integer is a sign and digits, so a number with a fraction is not one, and the
    text of a boolean is no number at all. A number is a decimal number that can have
    an exponent. The parsers of Java and Python each take more than that, and not the
    same: ``0.8d`` is a number in Java only and ``0.8_0`` in Python only. So both
    sides judge the text by this one syntax.

    Raises:
        ValueError: If ``written`` is not a number of ``kind``.
    """
    text = written if isinstance(written, str) else str(written)
    syntax = _INTEGER if kind is int else _NUMBER
    if syntax.fullmatch(text) is None:
        msg = f"got {written!r}"
        raise ValueError(msg)


def _read(config: ReadableConfiguration, option: ConfigOption) -> Any:
    """Read one option, naming its key when the value is not of the option's type.

    A configuration converts a value to the type of its option, and for a number that
    conversion loses what was written: ``true`` and ``1.9`` both become the integer
    1. So the value of a number is also read as it was written, and one that is not
    the number its option asks for is rejected, as it is on the Java side.
    """
    kind = option.get_type()
    try:
        value = config.get(option)
        if value is not None and kind in (int, float):
            written = config.get_str(option.get_key())
            if isinstance(written, str | int | float):
                _require_number(written, kind)
    except Exception as error:
        expected = {int: "an integer", float: "a number"}.get(kind, "a string")
        msg = f"{option.get_key()} must be {expected}: {error}"
        raise ValueError(msg) from error
    return value


class ContextManagementPolicy(BaseModel):
    """How the built-in chat action bounds the model input of one chat request.

    A chat request and its tool-call loop send the request's whole conversation to the
    model on every round, including every earlier tool result. A policy bounds that
    input in three ways, each off unless it is set:

    - Tool result truncation (``tool_result_max_chars``): a tool result longer than
      the limit is cut in the middle before it is sent.
    - Tool result masking (``tool_result_keep_last_rounds``): the results of the last
      rounds are sent as they are, and the results of older rounds are replaced by a
      one line placeholder. The model cannot read a masked result again, so this fits
      requests whose later rounds do not depend on the content of earlier results.
      ``tool_result_mask_step_rounds`` makes the boundary move in steps of several
      rounds, so the messages before it stay unchanged between two steps.
    - Summarization (``max_input_tokens``): when the estimated input exceeds
      ``summarization_trigger_ratio`` of the budget, the older rounds are replaced by
      a summary written by a chat model, and the last
      ``summarization_keep_last_rounds`` rounds are kept as they are. A round whose
      results are already masked is summarized only when what is left of it is still
      longer than a line of the summary: with masking set as well, most rounds are
      summarized only while they still have their results.

    A round is one model reply that requests tool calls together with the results of
    those calls. Only messages of rounds are ever truncated, masked or summarized;
    system, user and plain assistant messages are always sent as they are. So is what
    the built-in ``load_skill`` tool returns, the instructions of a skill: its result
    is neither truncated nor masked, and its round is not summarized. Only the
    messages sent to the model change: the conversation the framework keeps for the
    request stays complete.

    The default policy of an agent comes from the ``context-management.*`` execution
    options (``from_configuration``). A ``ChatRequestEvent`` can carry its own policy,
    which replaces the default as a whole for that request. A policy with nothing set
    changes nothing.

    A field that is not set is ``None``. The three values that only tune another
    setting have a default, which the ``effective_*`` properties return. An integer
    field is at most 2147483647, the largest value the Java side can hold.

    Attributes:
    ----------
    tool_result_max_chars : int | None
        The most characters of one tool result that are sent to the model; a longer
        result is cut in the middle, keeping its beginning and its end. At least 100.
    tool_result_keep_last_rounds : int | None
        How many of the most recent rounds keep their tool results. At least 1: the
        latest round is always kept.
    tool_result_mask_step_rounds : int | None
        How many rounds are masked in one step. With a step of ``s``, masking waits
        until ``s`` rounds beyond ``tool_result_keep_last_rounds`` are eligible and
        then masks them together. At least 1; 1 when not set. Only with
        ``tool_result_keep_last_rounds``.
    max_input_tokens : int | None
        The input budget of one model call in tokens. The budget triggers
        summarization; it is not a hard limit on the request. At least 1.
    summarization_trigger_ratio : float | None
        The share of ``max_input_tokens`` above which older rounds are summarized.
        Greater than 0 and at most 1; 0.8 when not set. Only with
        ``max_input_tokens``.
    summarization_keep_last_rounds : int | None
        How many of the most recent rounds a summarization leaves as they are. At
        least 1; 3 when not set. Only with ``max_input_tokens``.
    summarizer : str | None
        The chat model resource that writes summaries; when not set, the chat model
        that answers the request writes them. Only with ``max_input_tokens``.
    """

    MIN_TOOL_RESULT_MAX_CHARS: ClassVar[int] = 100
    DEFAULT_TOOL_RESULT_MASK_STEP_ROUNDS: ClassVar[int] = 1
    DEFAULT_SUMMARIZATION_TRIGGER_RATIO: ClassVar[float] = 0.8
    DEFAULT_SUMMARIZATION_KEEP_LAST_ROUNDS: ClassVar[int] = 3

    model_config = ConfigDict(frozen=True, extra="forbid", validate_default=True)

    tool_result_max_chars: StrictInt | None = None
    tool_result_keep_last_rounds: StrictInt | None = None
    tool_result_mask_step_rounds: StrictInt | None = None
    max_input_tokens: StrictInt | None = None
    summarization_trigger_ratio: float | None = None
    summarization_keep_last_rounds: StrictInt | None = None
    summarizer: StrictStr | None = None

    @field_validator("summarization_trigger_ratio", mode="before")
    @classmethod
    def _require_number(cls, value: Any) -> Any:
        if value is None:
            return None
        if isinstance(value, bool) or not isinstance(value, int | float):
            msg = (
                f"{_SUMMARIZATION_TRIGGER_RATIO} must be a number, got {value!r} "
                f"({type(value).__name__})"
            )
            raise ValueError(msg)  # noqa: TRY004 - Pydantic validation failure
        try:
            return float(value)
        except OverflowError:
            # An integer too large for a float is far out of the range, and the check
            # of the range reports it.
            return math.inf if value > 0 else -math.inf

    @model_validator(mode="after")
    def _apply_rules(self) -> "ContextManagementPolicy":
        _check(self.__dict__, lambda field: field)
        return self

    @model_serializer(mode="plain")
    def _to_map(self) -> Dict[str, Any]:
        return self.to_map()

    @classmethod
    def disabled(cls) -> "ContextManagementPolicy":
        """A policy with nothing set.

        On a chat request it switches context management off for that request,
        whatever the agent's default policy is.
        """
        return cls()

    @classmethod
    def from_configuration(
        cls, config: ReadableConfiguration
    ) -> "ContextManagementPolicy":
        """The policy declared by the ``context-management.*`` execution options.

        Returns a disabled policy when none of the options is set.

        Raises:
            ValueError: If a configured value has the wrong type or is out of range,
                named by its configuration key.
        """
        values = {field: _read(config, option) for field, option in _OPTIONS.items()}
        _check(values, lambda field: _OPTIONS[field].get_key())
        return cls(**values)

    def to_map(self) -> Dict[str, Any]:
        """The fields that were set, by name, in declaration order.

        This is also how a policy crosses the JSON boundary. Defaults of tuning
        values that were not set are not included.
        """
        return {
            field: getattr(self, field)
            for field in _OPTIONS
            if getattr(self, field) is not None
        }

    @property
    def is_enabled(self) -> bool:
        """Whether the policy truncates, masks or summarizes anything."""
        return (
            self.tool_result_max_chars is not None
            or self.tool_result_keep_last_rounds is not None
            or self.max_input_tokens is not None
        )

    @property
    def effective_tool_result_mask_step_rounds(self) -> int:
        """``tool_result_mask_step_rounds``, or its default of 1."""
        if self.tool_result_mask_step_rounds is None:
            return self.DEFAULT_TOOL_RESULT_MASK_STEP_ROUNDS
        return self.tool_result_mask_step_rounds

    @property
    def effective_summarization_trigger_ratio(self) -> float:
        """``summarization_trigger_ratio``, or its default of 0.8."""
        if self.summarization_trigger_ratio is None:
            return self.DEFAULT_SUMMARIZATION_TRIGGER_RATIO
        return self.summarization_trigger_ratio

    @property
    def effective_summarization_keep_last_rounds(self) -> int:
        """``summarization_keep_last_rounds``, or its default of 3."""
        if self.summarization_keep_last_rounds is None:
            return self.DEFAULT_SUMMARIZATION_KEEP_LAST_ROUNDS
        return self.summarization_keep_last_rounds
