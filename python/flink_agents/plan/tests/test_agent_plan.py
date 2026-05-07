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
from typing import Any, ClassVar, Dict, List, Sequence

import pytest

from flink_agents.api.agents.agent import Agent
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
from flink_agents.runtime.resource_cache import ResourceCache


class AgentForTest(Agent):
    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def increment(event: Event, ctx: RunnerContext) -> None:
        value = InputEvent.from_event(event).input
        value += 1
        ctx.send_event(OutputEvent(output=value))


def test_from_agent():
    agent = AgentForTest()
    agent_plan = AgentPlan.from_agent(agent, AgentConfiguration())
    actions = agent_plan.get_actions(InputEvent.EVENT_TYPE)
    assert len(actions) == 1
    action = actions[0]
    assert action.name == "increment"
    func = action.exec
    assert isinstance(func, PythonFunction)
    assert func.module == "flink_agents.plan.tests.test_agent_plan"
    assert func.qualname == "AgentForTest.increment"
    assert action.listen_event_types == [InputEvent.EVENT_TYPE]


class InvalidAgent(Agent):
    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def invalid_signature_action(event: Event) -> None:
        pass


def test_to_agent_invalid_signature() -> None:
    agent = InvalidAgent()
    with pytest.raises(TypeError):
        AgentPlan.from_agent(agent, AgentConfiguration())


class MyEvent(Event):
    """Event for testing purposes."""

    EVENT_TYPE: ClassVar[str] = "_my_event"

    def __init__(self) -> None:
        """Create a MyEvent."""
        super().__init__(type=MyEvent.EVENT_TYPE)


class MockChatModelImpl(BaseChatModelSetup):
    host: str
    desc: str

    def open(self) -> None:
        """Do nothing."""

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        return {}

    @classmethod
    def resource_type(cls) -> ResourceType:
        return ResourceType.CHAT_MODEL

    def chat(self, messages: Sequence[ChatMessage], **kwargs: Any) -> ChatMessage:
        """Testing Implementation."""
        return ChatMessage(
            role=MessageRole.ASSISTANT, content=self.host + " " + self.desc
        )


class MockEmbeddingModelConnection(BaseEmbeddingModelConnection):
    api_key: str

    def embed(self, text: str | Sequence[str], **kwargs: Any) -> list[float]:
        """Testing Implementation."""
        if isinstance(text, str):
            return [0.1234, -0.5678, 0.9012, -0.3456, 0.7890]
        return [[0.1234, -0.5678, 0.9012, -0.3456, 0.7890]]


class MockEmbeddingModelSetup(BaseEmbeddingModelSetup):
    @property
    def model_kwargs(self) -> Dict[str, Any]:
        return {"model": self.model}


class MockVectorStore(BaseVectorStore):
    host: str
    port: int
    collection_name: str

    @property
    def store_kwargs(self) -> Dict[str, Any]:
        return {"collection_name": self.collection_name}

    def get(
        self,
        ids: str | List[str] | None = None,
        collection_name: str | None = None,
        filters: Dict[str, Any] | None = None,
        limit: int | None = 100,
        **kwargs: Any,
    ) -> List[Document]:
        """For Testing."""

    def delete(
        self,
        ids: str | List[str] | None = None,
        collection_name: str | None = None,
        filters: Dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> None:
        """For Testing."""

    def _add_embedding(
        self,
        *,
        documents: List[Document],
        collection_name: str | None = None,
        **kwargs: Any,
    ) -> List[str]:
        """For Testing."""

    def _update_embedding(
        self,
        *,
        documents: List[Document],
        collection_name: str | None = None,
        **kwargs: Any,
    ) -> None:
        """For Testing."""

    def _query_embedding(
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


class MyAgent(Agent):
    @chat_model_setup
    @staticmethod
    def mock() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=f"{MockChatModelImpl.__module__}.{MockChatModelImpl.__name__}",
            host="8.8.8.8",
            desc="mock resource just for testing.",
            connection="mock",
        )

    @embedding_model_connection
    @staticmethod
    def mock_embedding_conn() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=f"{MockEmbeddingModelConnection.__module__}.{MockEmbeddingModelConnection.__name__}",
            api_key="mock-api-key",
        )

    @embedding_model_setup
    @staticmethod
    def mock_embedding() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=f"{MockEmbeddingModelSetup.__module__}.{MockEmbeddingModelSetup.__name__}",
            model="test-model",
            connection="mock_embedding_conn",
        )

    @vector_store
    @staticmethod
    def mock_vector_store() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=f"{MockVectorStore.__module__}.{MockVectorStore.__name__}",
            embedding_model="mock_embedding",
            host="localhost",
            port=8000,
            collection_name="test_collection",
        )

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def first_action(event: Event, ctx: RunnerContext) -> None:
        pass

    @action("_input_event", "_my_event")
    @staticmethod
    def second_action(event: Event, ctx: RunnerContext) -> None:
        pass


@pytest.fixture(scope="module")
def agent_plan() -> AgentPlan:
    return AgentPlan.from_agent(
        MyAgent(), AgentConfiguration({"mock.key": "mock.value"})
    )


current_dir = Path(__file__).parent


def test_agent_plan_serialize(agent_plan: AgentPlan) -> None:
    json_value = agent_plan.model_dump_json(serialize_as_any=True, indent=4)
    with Path.open(Path(f"{current_dir}/resources/agent_plan.json")) as f:
        expected_json = f.read()
    actual = json.loads(json_value)
    expected = json.loads(expected_json)
    assert actual == expected


def test_agent_plan_deserialize(agent_plan: AgentPlan) -> None:
    with Path.open(Path(f"{current_dir}/resources/agent_plan.json")) as f:
        expected_json = f.read()
    deserialized_agent_plan = AgentPlan.model_validate_json(expected_json)
    assert deserialized_agent_plan == agent_plan


def test_get_resource() -> None:
    agent_plan = AgentPlan.from_agent(MyAgent(), AgentConfiguration())
    cache = ResourceCache(agent_plan.resource_providers, agent_plan.config)
    mock = cache.get_resource("mock", ResourceType.CHAT_MODEL)
    assert (
        mock.chat(ChatMessage(role=MessageRole.USER, content="")).content
        == "8.8.8.8 mock resource just for testing."
    )


def test_add_action_and_resource_to_agent() -> None:
    my_agent = Agent()
    my_agent.add_action(
        name="first_action", events=["_input_event"], func=MyAgent.first_action
    )
    my_agent.add_action(
        name="second_action", events=["_input_event", "_my_event"], func=MyAgent.second_action
    )
    my_agent.add_resource(
        name="mock",
        resource_type=ResourceType.CHAT_MODEL,
        instance=ResourceDescriptor(
            clazz=f"{MockChatModelImpl.__module__}.{MockChatModelImpl.__name__}",
            host="8.8.8.8",
            desc="mock resource just for testing.",
            connection="mock",
        ),
    )

    my_agent.add_resource(
        name="mock_embedding_conn",
        resource_type=ResourceType.EMBEDDING_MODEL_CONNECTION,
        instance=ResourceDescriptor(
            clazz=f"{MockEmbeddingModelConnection.__module__}.{MockEmbeddingModelConnection.__name__}",
            api_key="mock-api-key",
        ),
    )
    my_agent.add_resource(
        name="mock_embedding",
        resource_type=ResourceType.EMBEDDING_MODEL,
        instance=ResourceDescriptor(
            clazz=f"{MockEmbeddingModelSetup.__module__}.{MockEmbeddingModelSetup.__name__}",
            model="test-model",
            connection="mock_embedding_conn",
        ),
    )
    my_agent.add_resource(
        name="mock_vector_store",
        resource_type=ResourceType.VECTOR_STORE,
        instance=ResourceDescriptor(
            clazz=f"{MockVectorStore.__module__}.{MockVectorStore.__name__}",
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


# ── String identifier tests ──────────────────────────────────────────────


class StringIdAgent(Agent):
    """Agent with actions listening to string identifiers."""

    @action("CustomEvent")
    @staticmethod
    def handle_custom(event: Event, ctx: RunnerContext) -> None:
        ctx.send_event(OutputEvent(output=event.get_attr("msg")))


def test_from_agent_with_string_identifier() -> None:
    """Test that AgentPlan correctly handles string identifiers."""
    agent = StringIdAgent()
    agent_plan = AgentPlan.from_agent(agent, AgentConfiguration())

    # The string identifier should be preserved as-is
    actions = agent_plan.get_actions("CustomEvent")
    assert len(actions) == 1
    assert actions[0].name == "handle_custom"
    assert "CustomEvent" in actions[0].listen_event_types

    # Verify serialization roundtrip preserves the string identifier
    json_str = agent_plan.model_dump_json(serialize_as_any=True)
    restored = AgentPlan.model_validate_json(json_str)
    assert restored.get_actions("CustomEvent")[0].name == "handle_custom"
