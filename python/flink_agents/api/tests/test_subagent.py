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
import pytest

from flink_agents.api.agents.agent import Agent
from flink_agents.api.resource import ResourceType
from flink_agents.api.tests.subagent_test_utils import TestSubagentSetup


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
