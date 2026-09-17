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

    with pytest.raises(
        TypeError,
        match="must be a SubagentSetup, a ResourceDescriptor, or an Agent",
    ):
        AgentPlan.from_agent(agent, AgentConfiguration())


def test_child_agent_compiles_into_internal_provider() -> None:
    """A directly-registered child Agent becomes an internal sub-agent."""
    root = Agent()
    child = Agent()
    root.add_resource("child", ResourceType.AGENT, child)

    plan = AgentPlan.from_agent(root, AgentConfiguration())

    agents = plan.resource_providers[ResourceType.AGENT]
    provider = agents["child"]
    assert provider.module == "flink_agents.runtime.internal_subagent"
    assert provider.clazz == "InternalSubagentSetup"
    assert provider.serialized["scope"] == "child"
    assert provider.serialized["child_plan"] is not None


def test_shared_child_agent_compiles_to_single_plan() -> None:
    """The same Agent instance under two names shares one compiled plan."""
    root = Agent()
    child = Agent()
    root.add_resource("first", ResourceType.AGENT, child)
    root.add_resource("second", ResourceType.AGENT, child)

    plan = AgentPlan.from_agent(root, AgentConfiguration())

    agents = plan.resource_providers[ResourceType.AGENT]
    first_plan = agents["first"].serialized["child_plan"]
    second_plan = agents["second"].serialized["child_plan"]
    assert first_plan is second_plan


def test_cycle_not_through_root_is_rejected_with_cycle_path() -> None:
    """A cycle below the root is rejected and reports the resource path."""
    root, agent_a, agent_b = Agent(), Agent(), Agent()
    root.add_resource("a", ResourceType.AGENT, agent_a)
    agent_a.add_resource("b", ResourceType.AGENT, agent_b)
    agent_b.add_resource("a", ResourceType.AGENT, agent_a)

    with pytest.raises(
        ValueError, match=r"Cyclic sub-agent definition detected: a -> b -> a"
    ):
        AgentPlan.from_agent(root, AgentConfiguration())


def test_cycle_through_root_is_rejected() -> None:
    """A cycle running through the root agent is rejected like any other."""
    root = Agent()
    child = Agent()
    root.add_resource("b", ResourceType.AGENT, child)
    child.add_resource("root", ResourceType.AGENT, root)

    with pytest.raises(
        ValueError,
        match=r"Cyclic sub-agent definition detected: <root> -> b -> root",
    ):
        AgentPlan.from_agent(root, AgentConfiguration())


def test_self_reference_is_rejected() -> None:
    """An agent registered as its own sub-agent is rejected."""
    root = Agent()
    root.add_resource("itself", ResourceType.AGENT, root)

    with pytest.raises(
        ValueError,
        match=r"Cyclic sub-agent definition detected: <root> -> itself",
    ):
        AgentPlan.from_agent(root, AgentConfiguration())
