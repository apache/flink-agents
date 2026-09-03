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
"""Covers how a setup declares its AGENT resources to a chat model."""

from typing import Any, Dict, List, Sequence

import pytest
from pydantic import BaseModel, Field

from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
)
from flink_agents.api.chat_models.subagent_tool import SubagentTool
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.resource_context import ResourceContext
from flink_agents.api.subagent import SubagentSetup
from flink_agents.api.tools.tool import Tool, ToolMetadata, ToolType

CUSTOM_SCHEMA = '{"type":"object","properties":{"path":{"type":"string"}}}'


class _RecordingConnection(BaseChatModelConnection):
    """Connection that captures what the setup hands to the model."""

    captured_messages: List[ChatMessage] = Field(default_factory=list)
    captured_tools: List[Tool] = Field(default_factory=list)

    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List[Tool] | None = None,
        output_schema: OutputSchema | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
        """Record the request and answer with a fixed message."""
        self.captured_messages = list(messages)
        self.captured_tools = list(tools or [])
        return ChatMessage(role=MessageRole.ASSISTANT, content="ok")


class _StubSetup(BaseChatModelSetup):
    """Setup whose model parameters are irrelevant to these tests."""

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return no model settings."""
        return {}


class _EmptyArgs(BaseModel):
    """Argument model of the stub tool."""


class _StubTool(Tool):
    """A plain tool, standing in for anything declared under ``tools``."""

    @classmethod
    def tool_type(cls) -> ToolType:
        """Return the function tool type."""
        return ToolType.FUNCTION

    def call(self, *args: Any, **kwargs: Any) -> Any:
        """Never called by these tests."""
        raise NotImplementedError

    @staticmethod
    def of(name: str) -> "_StubTool":
        """Build a stub tool under the given name."""
        return _StubTool(
            metadata=ToolMetadata(name=name, description="stub", args_schema=_EmptyArgs)
        )


class _StubSubagentSetup(SubagentSetup):
    """Metadata-carrying sub-agent double: declaring it is all these tests exercise."""

    async def submit(
        self,
        ctx: Any,
        prompt: Any,
        session_id: str | None = None,
        call_id: str | None = None,
    ) -> Any:
        """Never invoked: these tests stop at declaration."""
        raise NotImplementedError


class _Review(BaseModel):
    """Arguments of the typed double below."""

    path: str
    lines: int = 0


class _TypedStubSubagentSetup(_StubSubagentSetup):
    """Types its arguments, so the schema it is declared with is derived."""

    @classmethod
    def input_type(cls) -> type:
        """Return the declared argument type."""
        return _Review


class _StubResourceContext(ResourceContext):
    """Resource context backed by a plain name-keyed store.

    ``agent_store`` holds AGENT-type overrides, so one name can carry a tool
    and a sub-agent at the same time; an AGENT lookup falls back to the plain
    store when there is no override.
    """

    def __init__(
        self,
        store: Dict[str, Resource],
        agent_store: Dict[str, Resource] | None = None,
    ) -> None:
        self._store = store
        self._agent_store = agent_store or {}

    def get_resource(self, name: str, resource_type: ResourceType) -> Resource:
        """Return the stored resource, honoring the AGENT overrides."""
        if resource_type == ResourceType.AGENT and name in self._agent_store:
            return self._agent_store[name]
        if name not in self._store:
            msg = f"No such resource: {name}"
            raise KeyError(msg)
        return self._store[name]

    def generate_available_skills_prompt(self, *skill_names: str) -> str:
        """Return a recognizable stand-in for the skill listing."""
        return f"<available_skills>{list(skill_names)}</available_skills>"

    def get_skill_dirs(self, *skill_names: str) -> List[str]:
        """Return no skill directories."""
        return []


def _build(
    store: Dict[str, Resource],
    agent_store: Dict[str, Resource] | None = None,
    **setup_args: Any,
) -> tuple[_StubSetup, _RecordingConnection]:
    connection = _RecordingConnection()
    store["conn"] = connection
    setup = _StubSetup(
        connection="conn",
        model="m",
        resource_context=_StubResourceContext(store, agent_store),
        **setup_args,
    )
    return setup, connection


def test_declared_subagents_reach_the_model_as_callables_after_the_tools() -> None:
    """Sub-agents are declared to the model after the plain tools."""
    store: Dict[str, Resource] = {
        "lookup": _StubTool.of("lookup"),
        "reviewer": _StubSubagentSetup(
            description="Reviews a file.", input_schema=CUSTOM_SCHEMA
        ),
    }
    setup, connection = _build(store, subagents=["reviewer"], tools=["lookup"])

    setup.open()
    setup.chat([])

    assert [tool.metadata.name for tool in connection.captured_tools] == [
        "lookup",
        "subagent_reviewer",
    ]
    delegated = connection.captured_tools[1]
    assert isinstance(delegated, SubagentTool)
    assert delegated.metadata.description == "Reviews a file. This is subagent."
    assert delegated.metadata.get_parameters_dict()["properties"] == {
        "path": {"title": "Path", "type": "string"}
    }


def test_an_undescribed_subagent_is_still_delegable() -> None:
    """Without a description, a generic delegation description is declared."""
    store: Dict[str, Resource] = {
        "reviewer": _StubSubagentSetup(input_schema=CUSTOM_SCHEMA)
    }
    setup, _ = _build(store, subagents=["reviewer"])

    setup.open()

    tool = setup.tools[0]
    assert tool.metadata.name == "subagent_reviewer"
    assert tool.metadata.description == (
        "Delegate a standalone task to sub-agent reviewer This is subagent."
    )
    assert tool.metadata.get_parameters_dict()["properties"] == {
        "path": {"title": "Path", "type": "string"}
    }


def test_a_typed_subagent_is_declared_with_the_derived_schema() -> None:
    """A declared argument type is rendered, so it need not be written out."""
    store: Dict[str, Resource] = {
        "reviewer": _TypedStubSubagentSetup(description="Reviews a file.")
    }
    setup, _ = _build(store, subagents=["reviewer"])

    setup.open()

    assert setup.tools[0].metadata.get_parameters_dict()["properties"] == {
        "path": {"title": "Path", "type": "string"},
        "lines": {"default": 0, "title": "Lines", "type": "integer"},
    }


def test_a_subagent_that_states_no_input_shape_is_not_offered_to_the_model() -> None:
    """A sub-agent that states no shape leaves the model nothing to build a call
    from, so it is dropped rather than declared as a callable it could only
    misuse.
    """
    store: Dict[str, Resource] = {
        "reviewer": _StubSubagentSetup(description="Reviews a file.")
    }
    setup, _ = _build(store, subagents=["reviewer"])

    setup.open()

    assert setup.tools == []


def test_a_subagent_without_an_input_shape_does_not_stop_the_others() -> None:
    """Dropping one is not dropping the rest: the other callables stay usable."""
    store: Dict[str, Resource] = {
        "opaque": _StubSubagentSetup(description="Reviews a file."),
        "coder": _StubSubagentSetup(
            description="Writes a patch.", input_schema=CUSTOM_SCHEMA
        ),
    }
    setup, _ = _build(store, subagents=["opaque", "coder"])

    setup.open()

    assert [tool.metadata.name for tool in setup.tools] == ["subagent_coder"]


def test_a_tool_and_a_subagent_may_share_a_name() -> None:
    """The reserved prefix keeps the two namespaces apart, so one name may serve
    both.
    """
    store: Dict[str, Resource] = {"reviewer": _StubTool.of("reviewer")}
    agent_store: Dict[str, Resource] = {
        "reviewer": _StubSubagentSetup(
            description="Reviews a file.", input_schema=CUSTOM_SCHEMA
        )
    }
    setup, _ = _build(store, agent_store, tools=["reviewer"], subagents=["reviewer"])

    setup.open()

    assert [tool.metadata.name for tool in setup.tools] == [
        "reviewer",
        "subagent_reviewer",
    ]


def test_a_repeated_tool_name_is_rejected() -> None:
    """The same tool declared twice would be declared twice to the model."""
    store: Dict[str, Resource] = {"lookup": _StubTool.of("lookup")}
    setup, _ = _build(store, tools=["lookup", "lookup"])

    with pytest.raises(ValueError, match="Duplicate callable name: lookup"):
        setup.open()


def test_a_repeated_subagent_name_is_rejected() -> None:
    """The same sub-agent declared twice would be declared twice to the model."""
    store: Dict[str, Resource] = {
        "reviewer": _StubSubagentSetup(
            description="Reviews a file.", input_schema=CUSTOM_SCHEMA
        )
    }
    setup, _ = _build(store, subagents=["reviewer", "reviewer"])

    with pytest.raises(ValueError, match="Duplicate callable name: subagent_reviewer"):
        setup.open()


def test_a_subagent_without_a_setup_is_rejected() -> None:
    """An AGENT resource that is not a SubagentSetup carries no schema to declare.

    A sub-agent owned by the other language resolves to a bridge handle here, so
    declaring it must fail loudly rather than leave the model with a callable it
    cannot call.
    """
    store: Dict[str, Resource] = {"reviewer": _StubTool.of("reviewer")}
    setup, _ = _build(store, subagents=["reviewer"])

    with pytest.raises(TypeError, match="must resolve to a SubagentSetup"):
        setup.open()


def test_reopening_does_not_duplicate_the_callables() -> None:
    """A second open() rebuilds the callables instead of appending to them."""
    store: Dict[str, Resource] = {
        "lookup": _StubTool.of("lookup"),
        "reviewer": _StubSubagentSetup(
            description="Reviews a file.", input_schema=CUSTOM_SCHEMA
        ),
    }
    setup, _ = _build(store, tools=["lookup"], subagents=["reviewer"])

    setup.open()
    setup.open()

    assert len(setup.tools) == 2


def test_declared_subagents_inject_no_listing_message() -> None:
    """Delegation is offered through the callables alone: no listing message is
    injected.
    """
    store: Dict[str, Resource] = {
        "reviewer": _StubSubagentSetup(
            description="Reviews a file.", input_schema=CUSTOM_SCHEMA
        ),
        "coder": _StubSubagentSetup(
            description="Writes a patch.", input_schema=CUSTOM_SCHEMA
        ),
    }
    setup, connection = _build(store, subagents=["reviewer", "coder"])
    setup.open()

    setup.chat(
        [
            ChatMessage(role=MessageRole.SYSTEM, content="You are helpful."),
            ChatMessage(role=MessageRole.USER, content="review it"),
        ]
    )

    assert len(connection.captured_messages) == 2
    assert connection.captured_messages[0].content == "You are helpful."
    assert len(connection.captured_tools) == 2


def test_only_the_skill_listing_is_injected_when_subagents_are_declared() -> None:
    """With sub-agents declared, the skill listing is still the only injection."""
    store: Dict[str, Resource] = {
        "reviewer": _StubSubagentSetup(
            description="Reviews a file.", input_schema=CUSTOM_SCHEMA
        ),
        "load_skill": _StubTool.of("load_skill"),
        "bash": _StubTool.of("bash"),
    }
    setup, connection = _build(store, subagents=["reviewer"], skills=["github"])
    setup.open()

    setup.chat([ChatMessage(role=MessageRole.USER, content="review it")])

    assert len(connection.captured_messages) == 2
    assert connection.captured_messages[0].content.startswith("<available_skills>")
    assert connection.captured_messages[1].content == "review it"


def test_a_setup_without_subagents_injects_nothing() -> None:
    """Without declared sub-agents, neither the tools nor the messages change."""
    setup, connection = _build({})
    setup.open()

    setup.chat([ChatMessage(role=MessageRole.USER, content="hi")])

    assert len(connection.captured_messages) == 1
    assert connection.captured_tools == []
