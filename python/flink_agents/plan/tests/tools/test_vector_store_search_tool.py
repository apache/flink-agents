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
import datetime
import json
from unittest.mock import MagicMock

from flink_agents.api.agents.agent import Agent
from flink_agents.api.resource import ResourceType
from flink_agents.api.resource_context import ResourceContext
from flink_agents.api.tools.vector_store_search_tool import VectorStoreSearchTool
from flink_agents.api.vector_stores.vector_store import (
    BaseVectorStore,
    Document,
    VectorStoreQueryResult,
)
from flink_agents.plan.actions.tool_result_utils import to_tool_response
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration


def _resource_context(documents: list[Document]) -> MagicMock:
    store = MagicMock(spec=BaseVectorStore)
    store.query.return_value = VectorStoreQueryResult(documents=documents)
    ctx = MagicMock(spec=ResourceContext)
    ctx.get_resource.return_value = store
    return ctx


def test_tool_survives_agent_plan_json_round_trip() -> None:
    agent = Agent()
    agent.add_resource(
        name="search_docs",
        resource_type=ResourceType.TOOL,
        instance=VectorStoreSearchTool(
            name="search_docs",
            description="Search the docs.",
            vector_store="knowledge_base",
            max_results=5,
        ),
    )
    plan = AgentPlan.from_agent(agent, AgentConfiguration())

    restored_plan = AgentPlan.model_validate_json(
        plan.model_dump_json(serialize_as_any=True)
    )
    ctx = _resource_context([Document(content="Checkpoints recover state.")])
    provider = restored_plan.resource_providers[ResourceType.TOOL]["search_docs"]
    tool = provider.provide(ctx, AgentConfiguration())
    tool.open()

    assert isinstance(tool, VectorStoreSearchTool)
    assert tool.name == "search_docs"
    assert tool.metadata.description == "Search the docs."
    assert list(tool.metadata.args_schema["properties"]) == ["query"]
    assert tool.vector_store == "knowledge_base"
    assert tool.max_results == 5
    assert tool.resource_context is ctx
    assert tool.call(query="recovery") == [
        {"content": "Checkpoints recover state.", "metadata": {}}
    ]
    ctx.get_resource.assert_called_once_with(
        "knowledge_base", ResourceType.VECTOR_STORE
    )


def test_tool_response_is_json_with_non_json_metadata() -> None:
    tool = VectorStoreSearchTool(
        name="search_docs",
        description="Search the docs.",
        vector_store="knowledge_base",
        resource_context=_resource_context(
            [
                Document(
                    content="Checkpoints recover state.",
                    metadata={
                        "ingested_at": datetime.datetime(2026, 11, 14, 9, 30),
                        "score": float("nan"),
                    },
                )
            ]
        ),
    )
    tool.open()

    text = to_tool_response(tool.call(query="recovery")).get_text()

    assert json.loads(text) == [
        {
            "content": "Checkpoints recover state.",
            "metadata": {"ingested_at": "2026-11-14T09:30:00", "score": "nan"},
        }
    ]


def test_tool_response_is_plain_message_when_nothing_matches() -> None:
    tool = VectorStoreSearchTool(
        name="search_docs",
        description="Search the docs.",
        vector_store="knowledge_base",
        resource_context=_resource_context([]),
    )
    tool.open()

    text = to_tool_response(tool.call(query="recovery")).get_text()

    assert text == "No relevant documents found."
