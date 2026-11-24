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
#  limitations under the License.
#################################################################################
"""Rerank resources survive declaration and plan serialization."""

from collections.abc import Mapping, Sequence
from typing import Any

import pytest
from typing_extensions import override

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import rerank_model
from flink_agents.api.rerank_models.rerank_model import (
    BaseRerankModel,
    RerankDocument,
    RerankQuery,
    RerankResult,
)
from flink_agents.api.resource import ResourceDescriptor, ResourceType
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.resource_provider import PythonResourceProvider


class _DummyRerankModel(BaseRerankModel):
    """Minimal rerank model stub for AgentPlan registration tests."""

    @override
    def rerank(
        self,
        query: RerankQuery,
        documents: Sequence[RerankDocument],
        *,
        top_n: int | None = None,
        return_documents: bool | None = None,
        model_parameters: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> RerankResult:
        return RerankResult(results=[])


class _RerankAgent(Agent):
    def __init__(self, model: str) -> None:
        super().__init__()
        self._model = model

    @rerank_model
    def ranker(self) -> ResourceDescriptor:
        clazz = f"{_DummyRerankModel.__module__}.{_DummyRerankModel.__qualname__}"
        return ResourceDescriptor(clazz=clazz, model=self._model)


@pytest.mark.parametrize("declaration", ["decorators", "programmatic"])
def test_rerank_model_survives_plan_round_trip(declaration: str) -> None:
    declared = _RerankAgent(model="test-rerank")
    if declaration == "decorators":
        agent = declared
    else:
        agent = Agent()
        agent.add_resource("ranker", ResourceType.RERANK_MODEL, declared.ranker())

    plan = AgentPlan.from_agent(agent, AgentConfiguration())
    restored = AgentPlan.model_validate_json(plan.model_dump_json())

    provider = restored.resource_providers[ResourceType.RERANK_MODEL]["ranker"]
    assert isinstance(provider, PythonResourceProvider)
    assert provider.type == ResourceType.RERANK_MODEL
    assert provider.descriptor == plan.resource_providers[ResourceType.RERANK_MODEL][
        "ranker"
    ].descriptor
    assert provider.descriptor.clazz is _DummyRerankModel
    assert provider.descriptor.arguments == {"model": "test-rerank"}
