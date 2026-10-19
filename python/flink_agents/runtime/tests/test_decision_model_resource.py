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

from collections.abc import Mapping
from unittest.mock import MagicMock

from pydantic import Field, JsonValue

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decision_models import (
    BaseDecisionModelConnection,
    BaseDecisionModelSetup,
    DecisionRequest,
    DecisionResult,
    PredicateAnswer,
    PredicateQuestion,
)
from flink_agents.api.decision_models.decision_model import _validate_request_params
from flink_agents.api.decorators import decision_model_connection, decision_model_setup
from flink_agents.api.resource import ResourceDescriptor, ResourceType
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.runtime.flink_runner_context import FlinkRunnerContext


class RecordingDecisionConnection(BaseDecisionModelConnection):
    opens: int = 0
    closes: int = 0
    calls: list[tuple[DecisionRequest, str, dict[str, JsonValue]]] = Field(
        default_factory=list
    )

    def open(self) -> None:
        self.opens += 1

    def close(self) -> None:
        self.closes += 1

    def decide(
        self,
        request: DecisionRequest,
        *,
        model: str,
        request_params: Mapping[str, JsonValue] | None = None,
    ) -> DecisionResult:
        self.calls.append((request, model, _validate_request_params(request_params)))
        return DecisionResult(
            answers={"eligible": PredicateAnswer(probability_true=0.9)}, model=model
        )


class DecisionAgent(Agent):
    @decision_model_connection
    @staticmethod
    def connection() -> ResourceDescriptor:
        return ResourceDescriptor(clazz=f"{__name__}.RecordingDecisionConnection")

    @decision_model_setup
    @staticmethod
    def decision() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz="flink_agents.api.decision_models.decision_model.BaseDecisionModelSetup",
            connection="connection",
            model="test-model",
        )


def test_python_flink_context_owns_decision_resources() -> None:
    plan = AgentPlan.from_agent(DecisionAgent(), AgentConfiguration())
    java_context, java_adapter, metric_group = MagicMock(), MagicMock(), MagicMock()
    java_context.getActiveScopePlanJson.return_value = None
    ctx = FlinkRunnerContext(java_context, plan.model_dump_json(), None, java_adapter)

    try:
        setup = ctx.get_resource("decision", ResourceType.DECISION_MODEL, metric_group)
        assert isinstance(setup, BaseDecisionModelSetup)
        connection = ctx.get_resource(
            "connection", ResourceType.DECISION_MODEL_CONNECTION, metric_group
        )
        assert setup.connection == "connection"
        assert connection.opens == 1
        assert setup.metric_group is metric_group
        request = DecisionRequest(
            input="The customer has a valid receipt.",
            questions={"eligible": PredicateQuestion(instructions="Has a receipt.")},
        )

        params = {"extra_headers": {"x-request-id": "first"}, "timeout": 0.5}
        result = setup.decide(request, request_params=params)
        setup.decide(request)

        assert result.answers["eligible"].probability_true == 0.9
        assert connection.calls == [
            (request, "test-model", params),
            (request, "test-model", {}),
        ]
        params["extra_headers"]["x-request-id"] = "changed"
        assert connection.calls[0][2]["extra_headers"]["x-request-id"] == "first"
        assert (
            ctx.get_resource("decision", ResourceType.DECISION_MODEL, metric_group)
            is setup
        )
        java_adapter.getResource.assert_not_called()
    finally:
        ctx.close()

    assert connection.closes == 1
