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
"""Decision resources survive declaration and plan serialization."""

import pytest

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import decision_model_connection, decision_model_setup
from flink_agents.api.resource import (
    JavaResourceDescriptor,
    ResourceDescriptor,
    ResourceName,
    ResourceType,
)
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.resource_provider import PythonResourceProvider


class DecisionAgent(Agent):
    def __init__(self, model: str) -> None:
        super().__init__()
        self._model = model

    @decision_model_connection
    def connection(self) -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.DecisionModel.TYPESAFE_CONNECTION,
            api_key="test-key",
        )

    @decision_model_setup
    def decision(self) -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.DecisionModel.TYPESAFE_SETUP,
            connection="connection",
            model=self._model,
        )


@pytest.mark.parametrize("declaration", ["decorators", "programmatic"])
def test_decision_resources_survive_plan_round_trip(declaration: str) -> None:
    declared = DecisionAgent(model="test-model")
    if declaration == "decorators":
        agent = declared
    else:
        agent = Agent()
        agent.add_resource(
            "connection",
            ResourceType.DECISION_MODEL_CONNECTION,
            declared.connection(),
        )
        agent.add_resource("decision", ResourceType.DECISION_MODEL, declared.decision())

    plan = AgentPlan.from_agent(agent, AgentConfiguration())
    restored = AgentPlan.model_validate_json(plan.model_dump_json())

    for resource_type, name in [
        (ResourceType.DECISION_MODEL_CONNECTION, "connection"),
        (ResourceType.DECISION_MODEL, "decision"),
    ]:
        provider = restored.resource_providers[resource_type][name]
        assert isinstance(provider, PythonResourceProvider)
        assert provider.type == resource_type
        assert (
            provider.descriptor
            == plan.resource_providers[resource_type][name].descriptor
        )
        assert provider.descriptor.language == "python"
    assert restored.resource_providers[ResourceType.DECISION_MODEL][
        "decision"
    ].descriptor.arguments == {"connection": "connection", "model": "test-model"}


@pytest.mark.parametrize(
    "resource_type",
    [ResourceType.DECISION_MODEL, ResourceType.DECISION_MODEL_CONNECTION],
)
def test_java_decision_implementations_are_rejected(
    resource_type: ResourceType,
) -> None:
    agent = Agent()
    agent.add_resource(
        "decision", resource_type, JavaResourceDescriptor(clazz="example.DecisionModel")
    )

    with pytest.raises(ValueError, match="Unsupported Java resource type"):
        AgentPlan.from_agent(agent, AgentConfiguration())
