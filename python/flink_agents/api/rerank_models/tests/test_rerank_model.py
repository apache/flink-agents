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
from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import TextBlock
from flink_agents.api.decorators import rerank_model
from flink_agents.api.rerank_models.rerank_model import RerankHit, RerankResult
from flink_agents.api.resource import ResourceDescriptor, ResourceType
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration


class _AgentWithRerank(Agent):
    """Declares one rerank model through the decorator."""

    @rerank_model
    @staticmethod
    def ranker() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz="flink_agents.integrations.rerank_models.dashscope_rerank_model.DashScopeRerankModel",
            model="qwen3-rerank",
        )


def test_rerank_decorator_is_registered() -> None:
    plan = AgentPlan.from_agent(_AgentWithRerank(), AgentConfiguration())

    provider = plan.resource_providers[ResourceType.RERANK_MODEL]["ranker"]
    assert provider.descriptor.arguments["model"] == "qwen3-rerank"


def test_rerank_decorator_marks_the_function() -> None:
    @rerank_model
    def rank() -> None:
        return None

    assert rank._is_rerank_model is True


def test_rerank_result_json_roundtrip_keeps_the_document() -> None:
    result = RerankResult(
        results=[RerankHit(index=1, relevance_score=0.5, document=TextBlock(text="b"))],
        usage={"total_tokens": 3},
        request_id="req-1",
    )

    restored = RerankResult.model_validate(result.model_dump(mode="json"))

    assert restored == result
    assert restored.results[0].document == TextBlock(text="b")
    assert ResourceType.RERANK_MODEL.value == "rerank_model"
