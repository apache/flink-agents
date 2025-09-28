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
import json
from pathlib import Path
from typing import Any, Dict, Sequence

import pytest

from flink_agents.api.agent import Agent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import BaseChatModelSetup
from flink_agents.api.decorators import (
    action,
    chat_model_setup,
    embedding_model_connection,
    embedding_model_setup,
    vector_store,
)
from flink_agents.api.embedding_models.embedding_model import (
    BaseEmbeddingModelConnection,
    BaseEmbeddingModelSetup,
)
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.resource import ResourceDescriptor, ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.vector_stores.vector_store import (
    BaseVectorStore,
    Document,
)
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.function import PythonFunction


class AgentForTest(Agent):  # noqa D101
    @action(InputEvent)
    @staticmethod
    def increment(event: Event, ctx: RunnerContext) -> None:  # noqa D102
        value = event.input
        value += 1
        ctx.send_event(OutputEvent(output=value))


def test_from_agent():  # noqa D102
    agent = AgentForTest()
    agent_plan = AgentPlan.from_agent(agent, AgentConfiguration())
    event_type = f"{InputEvent.__module__}.{InputEvent.__name__}"
    actions = agent_plan.get_actions(event_type)
    assert len(actions) == 1
    action = actions[0]
    assert action.name == "increment"
    func = action.exec
    assert isinstance(func, PythonFunction)
    assert func.module == "flink_agents.plan.tests.test_agent_plan"
    assert func.qualname == "AgentForTest.increment"
    assert action.listen_event_types == [event_type]


class InvalidAgent(Agent):  # noqa D101
    @action(InputEvent)
    @staticmethod
    def invalid_signature_action(event: Event) -> None:  # noqa D102
        pass


def test_to_agent_invalid_signature() -> None:  # noqa D103
    agent = InvalidAgent()
    with pytest.raises(TypeError):
        AgentPlan.from_agent(agent, AgentConfiguration())


class MyEvent(Event):
    """Event for testing purposes."""


class MockChatModelImpl(BaseChatModelSetup):  # noqa: D101
    host: str
    desc: str

    @property
    def model_kwargs(self) -> Dict[str, Any]:  # noqa: D102
        return {}

    @classmethod
    def resource_type(cls) -> ResourceType:  # noqa: D102
        return ResourceType.CHAT_MODEL

    def chat(self, messages: Sequence[ChatMessage], **kwargs: Any) -> ChatMessage:
        """Testing Implementation."""
        return ChatMessage(
            role=MessageRole.ASSISTANT, content=self.host + " " + self.desc
        )


class MockEmbeddingModelConnection(BaseEmbeddingModelConnection):  # noqa: D101
    api_key: str

    def embed(self, text: str, **kwargs: Any) -> list[float]:
        """Testing Implementation."""
        return [0.1234, -0.5678, 0.9012, -0.3456, 0.7890]


class MockEmbeddingModelSetup(BaseEmbeddingModelSetup):  # noqa: D101
    @property
    def model_kwargs(self) -> Dict[str, Any]:  # noqa: D102
        return {"model": self.model}


class MockVectorStore(BaseVectorStore):  # noqa: D101
    host: str
    port: int
    collection_name: str

    @property
    def store_kwargs(self) -> Dict[str, Any]:  # noqa: D102
        return {"collection_name": self.collection_name}

    def query_embedding(
        self, embedding: list[float], limit: int = 10, **kwargs: Any
    ) -> list[Document]:
        """Testing Implementation."""
        return [
            Document(
                content="Mock document content",
                metadata={"source": "test", "id": "doc1"},
                id="doc1",
            ),
            Document(
                content="Another mock document",
                metadata={"source": "test", "id": "doc2"},
                id="doc2",
            ),
        ][:limit]


class MyAgent(Agent):  # noqa: D101
    @chat_model_setup
    @staticmethod
    def mock() -> ResourceDescriptor:  # noqa: D102
        return ResourceDescriptor(
            clazz=MockChatModelImpl,
            host="8.8.8.8",
            desc="mock resource just for testing.",
            connection="mock",
        )

    @embedding_model_connection
    @staticmethod
    def mock_embedding_conn() -> ResourceDescriptor:  # noqa: D102
        return ResourceDescriptor(
            clazz=MockEmbeddingModelConnection, api_key="mock-api-key"
        )

    @embedding_model_setup
    @staticmethod
    def mock_embedding() -> ResourceDescriptor:  # noqa: D102
        return ResourceDescriptor(
            clazz=MockEmbeddingModelSetup,
            model="test-model",
            connection="mock_embedding_conn",
        )

    @vector_store
    @staticmethod
    def mock_vector_store() -> ResourceDescriptor:  # noqa: D102
        return ResourceDescriptor(
            clazz=MockVectorStore,
            embedding_model="mock_embedding",
            host="localhost",
            port=8000,
            collection_name="test_collection",
        )

    @action(InputEvent)
    @staticmethod
    def first_action(event: InputEvent, ctx: RunnerContext) -> None:  # noqa: D102
        pass

    @action(InputEvent, MyEvent)
    @staticmethod
    def second_action(event: InputEvent, ctx: RunnerContext) -> None:  # noqa: D102
        pass


@pytest.fixture(scope="module")
def agent_plan() -> AgentPlan:  # noqa: D103
    return AgentPlan.from_agent(
        MyAgent(), AgentConfiguration({"mock.key": "mock.value"})
    )


current_dir = Path(__file__).parent


def test_agent_plan_serialize(agent_plan: AgentPlan) -> None:  # noqa: D103
    json_value = agent_plan.model_dump_json(serialize_as_any=True, indent=4)
    with Path.open(Path(f"{current_dir}/resources/agent_plan.json")) as f:
        expected_json = f.read()
    actual = json.loads(json_value)
    expected = json.loads(expected_json)
    assert actual == expected


def test_agent_plan_deserialize(agent_plan: AgentPlan) -> None:  # noqa: D103
    with Path.open(Path(f"{current_dir}/resources/agent_plan.json")) as f:
        expected_json = f.read()
    deserialized_agent_plan = AgentPlan.model_validate_json(expected_json)
    assert deserialized_agent_plan == agent_plan


def test_get_resource() -> None:  # noqa: D103
    agent_plan = AgentPlan.from_agent(MyAgent(), AgentConfiguration())
    mock = agent_plan.get_resource("mock", ResourceType.CHAT_MODEL)
    assert (
        mock.chat(ChatMessage(role=MessageRole.USER, content="")).content
        == "8.8.8.8 mock resource just for testing."
    )


def test_add_action_and_resource_to_agent() -> None:  # noqa: D103
    my_agent = Agent()
    my_agent.add_action(
        name="first_action", events=[InputEvent], func=MyAgent.first_action
    )
    my_agent.add_action(
        name="second_action", events=[InputEvent, MyEvent], func=MyAgent.second_action
    )
    my_agent.add_resource(
        name="mock",
        instance=ResourceDescriptor(
            clazz=MockChatModelImpl,
            host="8.8.8.8",
            desc="mock resource just for testing.",
            connection="mock",
        ),
    )

    my_agent.add_resource(
        name="mock_embedding_conn",
        instance=ResourceDescriptor(
            clazz=MockEmbeddingModelConnection, api_key="mock-api-key"
        ),
    )
    my_agent.add_resource(
        name="mock_embedding",
        instance=ResourceDescriptor(
            clazz=MockEmbeddingModelSetup,
            model="test-model",
            connection="mock_embedding_conn",
        ),
    )
    my_agent.add_resource(
        name="mock_vector_store",
        instance=ResourceDescriptor(
            clazz=MockVectorStore,
            embedding_model="mock_embedding",
            host="localhost",
            port=8000,
            collection_name="test_collection",
        ),
    )
    agent_plan = AgentPlan.from_agent(
        my_agent, AgentConfiguration({"mock.key": "mock.value"})
    )
    json_value = agent_plan.model_dump_json(serialize_as_any=True, indent=4)
    with Path.open(Path(f"{current_dir}/resources/agent_plan.json")) as f:
        expected_json = f.read()
    actual = json.loads(json_value)
    expected = json.loads(expected_json)
    assert actual == expected
