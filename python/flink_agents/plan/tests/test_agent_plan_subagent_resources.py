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
"""Tests for compiling AGENT resources (SubagentSetup) into the agent plan."""
import pytest

from flink_agents.api.agents.agent import Agent
from flink_agents.api.resource import ResourceDescriptor, ResourceType
from flink_agents.api.subagent import SubagentSetup
from flink_agents.api.tests.subagent_test_utils import TestSubagentSetup
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.resource_provider import PythonResourceProvider


def test_subagent_setup_compiles_into_agent_provider() -> None:
    """A registered SubagentSetup lands in the AGENT provider map and resolves."""
    setup = TestSubagentSetup()
    agent = Agent()
    agent.add_resource("reviewer", ResourceType.AGENT, setup)

    plan = AgentPlan.from_agent(agent, AgentConfiguration())

    agents = plan.resource_providers[ResourceType.AGENT]
    assert agents is not None
    assert "reviewer" in agents
    resolved = agents["reviewer"].provide(
        resource_context=None, config=AgentConfiguration()
    )
    assert isinstance(resolved, SubagentSetup)
    assert resolved.resource_type() == ResourceType.AGENT


def test_agent_descriptor_compiles_into_agent_provider() -> None:
    """Descriptor-shaped AGENT resources (the YAML path) compile into providers."""
    agent = Agent()
    agent.add_resource(
        "summarizer",
        ResourceType.AGENT,
        ResourceDescriptor(
            clazz=f"{TestSubagentSetup.__module__}.{TestSubagentSetup.__name__}",
            endpoint_url="http://summarizer:8080",
        ),
    )

    plan = AgentPlan.from_agent(agent, AgentConfiguration())

    agents = plan.resource_providers[ResourceType.AGENT]
    assert agents is not None
    assert "summarizer" in agents


def test_non_setup_agent_resource_is_rejected() -> None:
    """A bare object registered under AGENT fails plan compilation."""
    agent = Agent()
    agent.resources[ResourceType.AGENT]["bad"] = object()

    with pytest.raises(TypeError, match="must be a SubagentSetup"):
        AgentPlan.from_agent(agent, AgentConfiguration())


def test_live_external_subagent_is_rebuilt_after_plan_json_round_trip() -> None:
    """A live setup crosses the plan JSON boundary as its descriptor and is
    rebuilt with its configuration on the other side.
    """
    agent = Agent()
    agent.add_resource(
        "reviewer",
        ResourceType.AGENT,
        TestSubagentSetup(
            endpoint_url="http://review.internal:8080", fail_on_call=True
        ),
    )

    plan = AgentPlan.from_agent(agent, AgentConfiguration())

    # The transfer that carries a compiled plan between processes: the live
    # setup does not travel with it and must be rebuilt from what does.
    restored = AgentPlan.model_validate_json(
        plan.model_dump_json(serialize_as_any=True)
    )

    provider = restored.resource_providers[ResourceType.AGENT]["reviewer"]
    assert isinstance(provider, PythonResourceProvider)
    assert provider.descriptor.clazz is TestSubagentSetup

    resolved = provider.provide(resource_context=None, config=AgentConfiguration())
    assert isinstance(resolved, TestSubagentSetup)
    assert resolved.endpoint_url == "http://review.internal:8080"
    assert resolved.fail_on_call is True
    assert resolved.resource_type() == ResourceType.AGENT
