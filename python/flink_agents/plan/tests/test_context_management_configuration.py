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
"""Tests that the agent's configuration declares the default policy.

The ``context-management.*`` options are read from the configuration an agent plan
carries, in the forms a configuration file gives them. The cases are the same as in
the Java test ``ContextManagementConfigurationTest``.
"""

import pytest

from flink_agents.api.context_management import ContextManagementPolicy
from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.plan.configuration import AgentConfiguration


def test_no_option_gives_a_disabled_policy() -> None:
    assert (
        ContextManagementPolicy.from_configuration(AgentConfiguration())
        == ContextManagementPolicy.disabled()
    )


def test_the_options_are_read_from_the_text_and_number_forms_of_a_file() -> None:
    config = AgentConfiguration(
        {
            "context-management.tool-results.max-chars": "20000",
            "context-management.tool-results.keep-last-rounds": 3,
            "context-management.tool-results.mask-step-rounds": "5",
            "context-management.max-input-tokens": "100000",
            # A whole number is a valid ratio.
            "context-management.summarization.trigger-ratio": 1,
            "context-management.summarization.keep-last-rounds": "2",
            "context-management.summarization.model": "small",
        }
    )

    assert ContextManagementPolicy.from_configuration(
        config
    ) == ContextManagementPolicy(
        tool_result_max_chars=20_000,
        tool_result_keep_last_rounds=3,
        tool_result_mask_step_rounds=5,
        max_input_tokens=100_000,
        summarization_trigger_ratio=1.0,
        summarization_keep_last_rounds=2,
        summarizer="small",
    )


def test_the_options_set_through_their_constants_are_read() -> None:
    config = AgentConfiguration()
    config.set(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 5000)
    config.set(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO, 0.75
    )
    config.set(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 64_000)

    policy = ContextManagementPolicy.from_configuration(config)

    assert policy.tool_result_max_chars == 5000
    assert policy.max_input_tokens == 64_000
    assert policy.summarization_trigger_ratio == 0.75


def test_a_value_of_the_wrong_type_is_reported_by_its_configuration_key() -> None:
    not_a_number = AgentConfiguration({"context-management.max-input-tokens": "many"})
    with pytest.raises(
        ValueError, match="context-management.max-input-tokens must be an integer"
    ) as error:
        ContextManagementPolicy.from_configuration(not_a_number)
    assert "many" in str(error.value)

    not_a_ratio = AgentConfiguration(
        {
            "context-management.max-input-tokens": 1000,
            "context-management.summarization.trigger-ratio": "most",
        }
    )
    with pytest.raises(
        ValueError,
        match="context-management.summarization.trigger-ratio must be a number",
    ) as error:
        ContextManagementPolicy.from_configuration(not_a_ratio)
    assert "most" in str(error.value)


def test_a_number_that_was_not_written_as_one_is_rejected() -> None:
    # A boolean is no number and a number with a fraction is no integer, whether the
    # configuration holds the value or its text. Read with the type of the option,
    # true and 1.9 would both be the integer 1.
    for written in (True, 1.9, "1.9", "true", " 3"):
        config = AgentConfiguration(
            {"context-management.tool-results.keep-last-rounds": written}
        )
        with pytest.raises(
            ValueError,
            match="context-management.tool-results.keep-last-rounds must be an integer",
        ):
            ContextManagementPolicy.from_configuration(config)
    not_a_ratio = AgentConfiguration(
        {
            "context-management.max-input-tokens": 1000,
            "context-management.summarization.trigger-ratio": True,
        }
    )
    with pytest.raises(
        ValueError,
        match="context-management.summarization.trigger-ratio must be a number",
    ):
        ContextManagementPolicy.from_configuration(not_a_ratio)


def test_a_number_is_written_the_same_way_for_java_and_python() -> None:
    # Java and Python each parse more texts as a number than a decimal number, and
    # not the same ones: 0.8d is a number in Java only and 0.8_0 in Python only. One
    # syntax decides, so both sides accept and reject the same configuration.
    ratio = "context-management.summarization.trigger-ratio"
    budget = "context-management.max-input-tokens"
    for written, value in (
        ("0.8", 0.8),
        (".8", 0.8),
        ("0.80", 0.8),
        ("+0.8", 0.8),
        ("8e-1", 0.8),
        ("8E-1", 0.8),
        (0.8, 0.8),
        ("1", 1.0),
        ("1.", 1.0),
        (1, 1.0),
    ):
        config = AgentConfiguration({budget: 1000, ratio: written})
        policy = ContextManagementPolicy.from_configuration(config)
        assert policy.summarization_trigger_ratio == value, written
    for written in (
        "0.8d",
        "0.8f",
        "0.8_0",
        "0x1p-1",
        " 0.8",
        "0.8 ",
        "0,8",
        "NaN",
        "nan",
        "inf",
        "Infinity",
        "\u0660.\u0668",
        "",
    ):
        config = AgentConfiguration({budget: 1000, ratio: written})
        with pytest.raises(ValueError, match=f"{ratio} must be a number"):
            ContextManagementPolicy.from_configuration(config)

    for written in ("1000", "+1000", 1000):
        policy = ContextManagementPolicy.from_configuration(
            AgentConfiguration({budget: written})
        )
        assert policy.max_input_tokens == 1000, written
    for written in ("\u0661\u0660\u0660\u0660", "1_000", "0x3e8", "1e3", "1000.0", ""):
        config = AgentConfiguration({budget: written})
        with pytest.raises(ValueError, match=f"{budget} must be an integer"):
            ContextManagementPolicy.from_configuration(config)


def test_a_value_out_of_range_is_reported_by_its_configuration_key() -> None:
    config = AgentConfiguration(
        {"context-management.tool-results.keep-last-rounds": "0"}
    )

    with pytest.raises(
        ValueError,
        match="context-management.tool-results.keep-last-rounds must be at least 1, "
        "got 0",
    ):
        ContextManagementPolicy.from_configuration(config)
