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
"""Declaration rules, defaults and the map form of ContextManagementPolicy."""

import json
import math
from typing import Any, Dict

import pytest
from pydantic import ValidationError

from flink_agents.api.configuration import ConfigOption, ReadableConfiguration
from flink_agents.api.context_management import ContextManagementPolicy
from flink_agents.api.core_options import AgentExecutionOptions


class _MapConfiguration(ReadableConfiguration):
    """A configuration backed by a dict of option keys, as the agent's is."""

    def __init__(self, values: Dict[ConfigOption, Any] | None = None) -> None:
        self._values = {
            option.get_key(): value for option, value in (values or {}).items()
        }

    def get_int(self, key: str, default: int | None = None) -> int:
        return self._values.get(key, default)

    def get_float(self, key: str, default: float | None = None) -> float:
        return self._values.get(key, default)

    def get_bool(self, key: str, default: bool | None = None) -> bool:
        return self._values.get(key, default)

    def get_str(self, key: str, default: str | None = None) -> str:
        return self._values.get(key, default)

    def get(self, option: ConfigOption) -> Any:
        return self._values.get(option.get_key(), option.get_default_value())


def _all_set() -> ContextManagementPolicy:
    return ContextManagementPolicy(
        tool_result_max_chars=20_000,
        tool_result_keep_last_rounds=3,
        tool_result_mask_step_rounds=5,
        max_input_tokens=100_000,
        summarization_trigger_ratio=0.7,
        summarization_keep_last_rounds=2,
        summarizer="small",
    )


def test_a_policy_with_nothing_set_is_disabled() -> None:
    disabled = ContextManagementPolicy.disabled()

    assert not disabled.is_enabled
    assert disabled.to_map() == {}
    assert ContextManagementPolicy() == disabled
    assert hash(ContextManagementPolicy()) == hash(disabled)
    assert disabled.tool_result_max_chars is None
    assert disabled.tool_result_keep_last_rounds is None
    assert disabled.max_input_tokens is None
    assert disabled.summarizer is None


def test_the_policy_keeps_what_was_set() -> None:
    policy = _all_set()

    assert policy.is_enabled
    assert policy.tool_result_max_chars == 20_000
    assert policy.tool_result_keep_last_rounds == 3
    assert policy.effective_tool_result_mask_step_rounds == 5
    assert policy.max_input_tokens == 100_000
    assert policy.effective_summarization_trigger_ratio == 0.7
    assert policy.effective_summarization_keep_last_rounds == 2
    assert policy.summarizer == "small"


def test_unset_tuning_values_fall_back_to_their_defaults() -> None:
    policy = ContextManagementPolicy(
        tool_result_keep_last_rounds=3, max_input_tokens=100_000
    )

    assert policy.tool_result_mask_step_rounds is None
    assert policy.effective_tool_result_mask_step_rounds == 1
    assert policy.effective_summarization_trigger_ratio == 0.8
    assert policy.effective_summarization_keep_last_rounds == 3
    # Defaults are not written to the map form: it carries what was declared.
    assert set(policy.to_map()) == {"tool_result_keep_last_rounds", "max_input_tokens"}


def test_the_map_form_round_trips() -> None:
    policy = _all_set()

    data = policy.to_map()

    assert data == {
        "tool_result_max_chars": 20_000,
        "tool_result_keep_last_rounds": 3,
        "tool_result_mask_step_rounds": 5,
        "max_input_tokens": 100_000,
        "summarization_trigger_ratio": 0.7,
        "summarization_keep_last_rounds": 2,
        "summarizer": "small",
    }
    assert list(data) == list(ContextManagementPolicy.model_fields)
    assert ContextManagementPolicy.model_validate(data) == policy
    assert hash(ContextManagementPolicy.model_validate(data)) == hash(policy)


def test_the_map_form_accepts_a_whole_number_as_the_ratio() -> None:
    policy = ContextManagementPolicy.model_validate(
        {"max_input_tokens": 100_000, "summarization_trigger_ratio": 1}
    )

    assert policy.summarization_trigger_ratio == 1.0
    assert isinstance(policy.summarization_trigger_ratio, float)


def test_the_map_form_ignores_none_and_rejects_unknown_keys_and_wrong_types() -> None:
    assert (
        ContextManagementPolicy.model_validate({"tool_result_max_chars": None})
        == ContextManagementPolicy.disabled()
    )

    with pytest.raises(ValidationError, match="max_chars"):
        ContextManagementPolicy.model_validate({"max_chars": 500})
    with pytest.raises(ValidationError, match="max_input_tokens"):
        ContextManagementPolicy.model_validate({"max_input_tokens": "many"})
    with pytest.raises(ValidationError, match="tool_result_max_chars"):
        ContextManagementPolicy.model_validate({"tool_result_max_chars": 2.5})
    with pytest.raises(ValidationError, match="tool_result_max_chars"):
        ContextManagementPolicy.model_validate({"tool_result_max_chars": True})
    with pytest.raises(ValidationError, match="summarizer"):
        ContextManagementPolicy.model_validate(
            {"max_input_tokens": 1000, "summarizer": 7}
        )
    with pytest.raises(
        ValidationError, match="summarization_trigger_ratio must be a number"
    ):
        ContextManagementPolicy.model_validate(
            {"max_input_tokens": 1000, "summarization_trigger_ratio": "most"}
        )


def test_values_out_of_range_are_rejected_with_the_name_and_the_value() -> None:
    with pytest.raises(
        ValidationError, match="tool_result_max_chars must be at least 100, got 99"
    ):
        ContextManagementPolicy(tool_result_max_chars=99)
    with pytest.raises(
        ValidationError, match="tool_result_keep_last_rounds must be at least 1"
    ):
        ContextManagementPolicy(tool_result_keep_last_rounds=0)
    with pytest.raises(
        ValidationError, match="tool_result_mask_step_rounds must be at least 1"
    ):
        ContextManagementPolicy(
            tool_result_keep_last_rounds=2, tool_result_mask_step_rounds=0
        )
    with pytest.raises(ValidationError, match="max_input_tokens must be at least 1"):
        ContextManagementPolicy(max_input_tokens=0)
    for ratio in (0.0, -0.5, 1.01, math.nan):
        with pytest.raises(
            ValidationError,
            match="summarization_trigger_ratio must be greater than 0 and at most 1",
        ):
            ContextManagementPolicy(
                max_input_tokens=1000, summarization_trigger_ratio=ratio
            )
    # An integer too large for a float is out of range as well.
    for ratio, written in ((10**400, "inf"), (-(10**400), "-inf")):
        with pytest.raises(
            ValidationError,
            match="summarization_trigger_ratio must be greater than 0 and at most 1, "
            f"got {written}",
        ):
            ContextManagementPolicy(
                max_input_tokens=1000, summarization_trigger_ratio=ratio
            )
    with pytest.raises(
        ValidationError, match="summarization_keep_last_rounds must be at least 1"
    ):
        ContextManagementPolicy(max_input_tokens=1000, summarization_keep_last_rounds=0)
    # A name of white space only is blank, by the same list of characters as on the
    # Java side.
    for blank in ("", " ", "\t\n", "\u00a0", "\u2003", "\u3000", "\u2028", "\x85 "):
        with pytest.raises(ValidationError, match="summarizer must name a chat model"):
            ContextManagementPolicy(max_input_tokens=1000, summarizer=blank)
    for name in ("small", " small ", "\u200b", "\x1f", "\u2003x"):
        policy = ContextManagementPolicy(max_input_tokens=1000, summarizer=name)
        assert policy.summarizer == name


def test_an_integer_the_java_side_cannot_hold_is_rejected() -> None:
    # A policy is also read on the Java side, where these fields are 32-bit integers.
    largest = 2_147_483_647
    policy = ContextManagementPolicy(
        tool_result_max_chars=largest,
        tool_result_keep_last_rounds=largest,
        tool_result_mask_step_rounds=largest,
        max_input_tokens=largest,
        summarization_keep_last_rounds=largest,
    )
    assert policy.max_input_tokens == largest

    for field in (
        "tool_result_max_chars",
        "tool_result_keep_last_rounds",
        "max_input_tokens",
    ):
        with pytest.raises(
            ValidationError, match=f"{field} is out of range, got 2147483648"
        ):
            ContextManagementPolicy(**{field: largest + 1})
    with pytest.raises(
        ValidationError,
        match="tool_result_mask_step_rounds is out of range, got 2147483648",
    ):
        ContextManagementPolicy(
            tool_result_keep_last_rounds=2, tool_result_mask_step_rounds=largest + 1
        )
    with pytest.raises(
        ValidationError,
        match="summarization_keep_last_rounds is out of range, got 3000000000",
    ):
        ContextManagementPolicy(
            max_input_tokens=1000, summarization_keep_last_rounds=3_000_000_000
        )


def test_a_tuning_value_without_the_setting_it_tunes_is_rejected() -> None:
    with pytest.raises(
        ValidationError,
        match="tool_result_mask_step_rounds has no effect without "
        "tool_result_keep_last_rounds",
    ):
        ContextManagementPolicy(tool_result_mask_step_rounds=4)
    with pytest.raises(
        ValidationError,
        match="summarization_trigger_ratio has no effect without max_input_tokens",
    ):
        ContextManagementPolicy(summarization_trigger_ratio=0.5)
    with pytest.raises(
        ValidationError,
        match="summarization_keep_last_rounds has no effect without max_input_tokens",
    ):
        ContextManagementPolicy(summarization_keep_last_rounds=2)
    with pytest.raises(
        ValidationError, match="summarizer has no effect without max_input_tokens"
    ):
        ContextManagementPolicy(summarizer="small")


def test_the_execution_options_are_unset_by_default_and_give_a_disabled_policy() -> (
    None
):
    options = {
        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS: (
            "context-management.tool-results.max-chars"
        ),
        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS: (
            "context-management.tool-results.keep-last-rounds"
        ),
        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MASK_STEP_ROUNDS: (
            "context-management.tool-results.mask-step-rounds"
        ),
        AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS: (
            "context-management.max-input-tokens"
        ),
        AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO: (
            "context-management.summarization.trigger-ratio"
        ),
        AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS: (
            "context-management.summarization.keep-last-rounds"
        ),
        AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL: (
            "context-management.summarization.model"
        ),
    }
    for option, key in options.items():
        assert option.get_key() == key
        assert option.get_default_value() is None

    assert (
        ContextManagementPolicy.from_configuration(_MapConfiguration())
        == ContextManagementPolicy.disabled()
    )


def test_from_configuration_reads_every_execution_option() -> None:
    config = _MapConfiguration(
        {
            AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS: 5000,
            AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS: 4,
            AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MASK_STEP_ROUNDS: 2,
            AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS: 64_000,
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO: 0.9,
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS: 1,
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL: (
                "summarizer-model"
            ),
        }
    )

    assert ContextManagementPolicy.from_configuration(
        config
    ) == ContextManagementPolicy(
        tool_result_max_chars=5000,
        tool_result_keep_last_rounds=4,
        tool_result_mask_step_rounds=2,
        max_input_tokens=64_000,
        summarization_trigger_ratio=0.9,
        summarization_keep_last_rounds=1,
        summarizer="summarizer-model",
    )


def test_an_invalid_execution_option_is_reported_by_its_configuration_key() -> None:
    config = _MapConfiguration(
        {AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS: 10}
    )
    with pytest.raises(
        ValueError,
        match="context-management.tool-results.max-chars must be at least 100, got 10",
    ):
        ContextManagementPolicy.from_configuration(config)

    too_large = _MapConfiguration(
        {AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS: 2_147_483_648}
    )
    with pytest.raises(
        ValueError,
        match="context-management.max-input-tokens is out of range, got 2147483648",
    ):
        ContextManagementPolicy.from_configuration(too_large)

    orphan = _MapConfiguration(
        {AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL: "small"}
    )
    with pytest.raises(
        ValueError,
        match="context-management.summarization.model has no effect without "
        "context-management.max-input-tokens",
    ):
        ContextManagementPolicy.from_configuration(orphan)


def test_a_boolean_or_a_fraction_is_not_an_integer_execution_option() -> None:
    option = AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS
    for written in (True, 1.9):
        config = _MapConfiguration({option: written})
        with pytest.raises(
            ValueError,
            match="context-management.tool-results.keep-last-rounds must be an "
            f"integer: got {written!r}",
        ):
            ContextManagementPolicy.from_configuration(config)
    policy = ContextManagementPolicy.from_configuration(_MapConfiguration({option: 3}))
    assert policy.tool_result_keep_last_rounds == 3


def test_json_carries_the_map_form() -> None:
    policy = ContextManagementPolicy(
        tool_result_max_chars=2000,
        max_input_tokens=8000,
        summarization_trigger_ratio=0.75,
    )

    data = json.loads(policy.model_dump_json())

    assert data == {
        "tool_result_max_chars": 2000,
        "max_input_tokens": 8000,
        "summarization_trigger_ratio": 0.75,
    }
    assert policy.model_dump() == data
    assert ContextManagementPolicy.model_validate(data) == policy
