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

import json
from unittest.mock import create_autospec

import pytest
from pydantic import BaseModel

from flink_agents.api.agents.agent import Agent
from flink_agents.api.resource import JavaResourceDescriptor, ResourceType
from flink_agents.api.subagent import (
    InternalSubagentResourceDescriptorBuilder,
    SubagentResult,
)
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.resource_provider import JavaResourceProvider
from flink_agents.runtime.internal_subagent import InternalSubagentCallFactory
from flink_agents.runtime.resource_cache import ResourceCache


class Request(BaseModel):
    question: str


def test_descriptor_round_trip_builds_a_python_caller_with_metadata() -> None:
    child = Agent()
    descriptor = (
        InternalSubagentResourceDescriptorBuilder()
        .agent(child)
        .description("Answers questions")
        .input_type(Request)
        .build()
    )
    assert type(descriptor) is JavaResourceDescriptor
    root = Agent()
    root.add_resource("child", ResourceType.AGENT, descriptor)
    root.add_resource("second", ResourceType.AGENT, descriptor)
    plan = AgentPlan.from_agent(root, AgentConfiguration())
    restored = AgentPlan.model_validate_json(
        plan.model_dump_json(serialize_as_any=True)
    )
    provider = restored.resource_providers[ResourceType.AGENT]["child"]
    assert isinstance(provider, JavaResourceProvider)
    assert "agent" not in provider.descriptor.arguments
    assert descriptor.arguments["agent"] is child
    assert "scope" not in descriptor.arguments
    before = json.dumps(provider.descriptor.arguments)
    cache = ResourceCache(restored.resource_providers, AgentConfiguration())
    setup = cache.get_resource("child", ResourceType.AGENT)
    assert setup.description == "Answers questions"
    assert "question" in json.loads(setup.input_schema)["properties"]
    assert setup.scope == "child"
    assert cache.get_resource("second", ResourceType.AGENT).scope == "second"
    assert json.dumps(provider.descriptor.arguments) == before
    assert cache.get_resource("child", ResourceType.AGENT) is setup
    # The Python caller retains mailbox bootstrap plus deferred worker await.
    ctx = create_autospec(InternalSubagentCallFactory, instance=True, spec_set=True)
    ctx.await_subagent_call.return_value = ["answer"]
    _, call, _ = setup.prepare(ctx, {"question": "hello"}, "session", "call")
    ctx.bootstrap_subagent_call.assert_called_once_with(
        "child", "session", "call", {"question": "hello"}
    )
    result = call()
    assert result == SubagentResult.ok(["answer"])
    ctx.await_subagent_call.assert_called_once_with("session", "call")
    cache.close()


def test_descriptor_cycle_through_root_is_rejected() -> None:
    root = Agent()
    root.add_resource(
        "self",
        ResourceType.AGENT,
        InternalSubagentResourceDescriptorBuilder().agent(root).build(),
    )
    with pytest.raises(ValueError, match="Cyclic sub-agent"):
        AgentPlan.from_agent(root, AgentConfiguration())


def test_builder_rejects_missing_agent_and_conflicting_schema() -> None:
    with pytest.raises(ValueError, match="requires an Agent"):
        InternalSubagentResourceDescriptorBuilder().build()
    with pytest.raises(ValueError, match="not both"):
        InternalSubagentResourceDescriptorBuilder().agent(Agent()).input_type(
            Request
        ).input_schema("{}").build()
    with pytest.raises(ValueError, match="must not be blank"):
        InternalSubagentResourceDescriptorBuilder().agent(Agent()).input_schema(
            " "
        ).build()
