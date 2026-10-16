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
"""Python decision resources resolve through the Flink runner's native cache."""

from unittest.mock import MagicMock

from pydantic import Field

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decision_models import (
    BaseDecisionModelConnection,
    BaseDecisionModelSetup,
    DecisionRequest,
    DecisionResult,
    PredicateAnswer,
    PredicateQuestion,
)
from flink_agents.api.resource import ResourceDescriptor, ResourceType
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.runtime.flink_runner_context import FlinkRunnerContext


class RecordingDecisionConnection(BaseDecisionModelConnection):
    opens: int = 0
    closes: int = 0
    calls: list[tuple[DecisionRequest, str]] = Field(default_factory=list)

    def open(self) -> None:
        self.opens += 1

    def close(self) -> None:
        self.closes += 1

    def decide(self, request: DecisionRequest, *, model: str) -> DecisionResult:
        self.calls.append((request, model))
        return DecisionResult(
            answers={"eligible": PredicateAnswer(probability_true=0.9)}, model=model
        )


def test_python_flink_context_owns_decision_resources() -> None:
    agent = Agent()
    agent.add_resource(
        "connection",
        ResourceType.DECISION_MODEL_CONNECTION,
        ResourceDescriptor(clazz=f"{__name__}.RecordingDecisionConnection"),
    )
    agent.add_resource(
        "decision",
        ResourceType.DECISION_MODEL,
        ResourceDescriptor(
            clazz=(
                "flink_agents.api.decision_models.decision_model.BaseDecisionModelSetup"
            ),
            connection="connection",
            model="test-model",
        ),
    )
    plan = AgentPlan.from_agent(agent, AgentConfiguration())
    java_context, java_adapter, metric_group = MagicMock(), MagicMock(), MagicMock()
    java_context.getActiveScopePlanJson.return_value = None
    ctx = FlinkRunnerContext(java_context, plan.model_dump_json(), None, java_adapter)

    try:
        setup = ctx.get_resource("decision", ResourceType.DECISION_MODEL, metric_group)
        assert isinstance(setup, BaseDecisionModelSetup)
        connection = ctx.get_resource(
            "connection", ResourceType.DECISION_MODEL_CONNECTION, metric_group
        )
        assert setup.connection is connection
        assert connection.opens == 1
        assert setup.metric_group is metric_group
        request = DecisionRequest(
            input="The customer has a valid receipt.",
            questions={"eligible": PredicateQuestion(instructions="Has a receipt.")},
        )

        result = setup.decide(request)

        assert result.answers["eligible"].probability_true == 0.9
        assert connection.calls == [(request, "test-model")]
        assert (
            ctx.get_resource("decision", ResourceType.DECISION_MODEL, metric_group)
            is setup
        )
        java_adapter.getResource.assert_not_called()
    finally:
        ctx.close()

    assert connection.closes == 1
