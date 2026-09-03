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
"""Tests registering sub-agents as AGENT resources."""

import json

import pytest
from pydantic import ValidationError

from flink_agents.api.agents.agent import Agent
from flink_agents.api.resource import ResourceType
from flink_agents.api.tests.subagent_test_utils import (
    TestSubagentSetup,
    TypedTestSubagentSetup,
)

DECLARED_SCHEMA = '{"type":"object","properties":{"path":{"type":"string"}}}'


def test_register_subagent_setup_as_resource() -> None:
    """A ``SubagentSetup`` registers under the AGENT resource map."""
    agent = Agent()
    setup = TestSubagentSetup()

    agent.add_resource("reviewer", ResourceType.AGENT, setup)

    agent_resources = agent.resources[ResourceType.AGENT]
    assert len(agent_resources) == 1
    assert agent_resources["reviewer"] is setup
    assert setup.resource_type() == ResourceType.AGENT


def test_duplicate_name_throws() -> None:
    """Registering a duplicate AGENT name raises."""
    agent = Agent()
    agent.add_resource("reviewer", ResourceType.AGENT, TestSubagentSetup())

    with pytest.raises(ValueError):
        agent.add_resource("reviewer", ResourceType.AGENT, TestSubagentSetup())


def test_multiple_subagents_registered() -> None:
    """Multiple distinct AGENT resources coexist."""
    agent = Agent()
    reviewer = TestSubagentSetup()
    coder = TestSubagentSetup()

    agent.add_resource("reviewer", ResourceType.AGENT, reviewer)
    agent.add_resource("coder", ResourceType.AGENT, coder)

    agent_resources = agent.resources[ResourceType.AGENT]
    assert len(agent_resources) == 2
    assert agent_resources["reviewer"] is reviewer
    assert agent_resources["coder"] is coder


def test_a_setup_that_declares_nothing_states_no_shape_for_its_arguments() -> None:
    """Without declared metadata, a sub-agent states no shape for its arguments."""
    setup = TestSubagentSetup()

    assert setup.description == ""
    assert setup.input_type() is object
    assert setup.result_type() is object
    assert setup.input_schema is None


def test_declared_metadata_is_kept_verbatim() -> None:
    """A declared description and input schema reach the caller unchanged."""
    setup = TestSubagentSetup(
        description="Reviews a file.", input_schema=DECLARED_SCHEMA
    )

    assert setup.description == "Reviews a file."
    assert setup.input_schema == DECLARED_SCHEMA


def test_a_blank_input_schema_is_rejected() -> None:
    """A blank input schema would leave the caller with nothing to fill in."""
    with pytest.raises(ValidationError, match="input schema must not be blank"):
        TestSubagentSetup(input_schema=" ")


def test_an_input_type_is_rendered_as_the_input_schema() -> None:
    """A declared argument type is rendered, so it need not be written out."""
    schema = json.loads(TypedTestSubagentSetup().input_schema)

    assert schema["type"] == "object"
    assert schema["properties"]["path"]["type"] == "string"
    assert schema["properties"]["lines"]["type"] == "integer"


def test_an_explicit_input_schema_wins_over_the_input_type() -> None:
    """What is written out is what gets declared, even next to a type."""
    setup = TypedTestSubagentSetup(input_schema=DECLARED_SCHEMA)

    assert setup.input_schema == DECLARED_SCHEMA


def test_an_input_type_that_is_not_a_model_is_rejected() -> None:
    """Only a pydantic model states a shape a schema can be rendered from."""

    class NotAModel(TestSubagentSetup):
        @classmethod
        def input_type(cls) -> type:
            return dict

    with pytest.raises(TypeError, match="is not supported"):
        NotAModel()


def test_the_declared_types_stay_out_of_the_plan_json() -> None:
    """The declared types drive behavior, so they must not leak into the plan."""
    dumped = json.loads(TypedTestSubagentSetup().model_dump_json())

    assert "input_type" not in dumped
    assert "result_type" not in dumped


def test_a_derived_input_schema_is_carried_by_the_plan_json() -> None:
    """The plan carries the schema a caller reads off the setup, derived or not."""
    dumped = json.loads(TypedTestSubagentSetup().model_dump_json())

    assert json.loads(dumped["input_schema"])["type"] == "object"


def test_the_metadata_serializes_under_the_cross_language_keys() -> None:
    """The plan JSON keys are a contract with the Java side, so they are pinned."""
    dumped = json.loads(
        TestSubagentSetup(
            description="Reviews a file.", input_schema=DECLARED_SCHEMA
        ).model_dump_json()
    )

    assert dumped["description"] == "Reviews a file."
    assert dumped["input_schema"] == DECLARED_SCHEMA
    assert "inputSchema" not in dumped
