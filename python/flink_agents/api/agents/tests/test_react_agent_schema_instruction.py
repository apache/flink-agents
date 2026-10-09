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
"""Whether start_action describes the output schema to the model in the prompt."""

from concurrent.futures import CancelledError
from typing import Any, Dict, List, Mapping, Sequence
from unittest.mock import MagicMock

import pytest
from pydantic import BaseModel
from pyflink.common.typeinfo import RowTypeInfo, Types

from flink_agents.api.agents.react_agent import (
    _DEFAULT_CHAT_MODEL,
    _DEFAULT_SCHEMA_PROMPT,
    ReActAgent,
)
from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
    NativeStructuredOutputSupport,
)
from flink_agents.api.events.chat_event import ChatRequestEvent
from flink_agents.api.events.event import Event, InputEvent
from flink_agents.api.resource import ResourceDescriptor, ResourceType
from flink_agents.api.tools.tool import Tool

_CHAT_MODEL_CLASS = (
    "flink_agents.integrations.chat_models.ollama_chat_model.OllamaChatModelSetup"
)


class _Person(BaseModel):
    name: str


class _CapableConnection(BaseChatModelConnection):
    """Translates every BaseModel schema natively for every model, and no
    RowTypeInfo, as the connection contract prescribes.
    """

    def supports_native_structured_output(
        self,
        output_schema: OutputSchema | None,
        tools: List[Tool] | None,
        model_kwargs: Mapping[str, Any] | None,
    ) -> NativeStructuredOutputSupport:
        if output_schema is None or isinstance(
            output_schema.output_schema, RowTypeInfo
        ):
            return NativeStructuredOutputSupport.INFEASIBLE
        return NativeStructuredOutputSupport.NATIVE_RECOMMENDED

    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List[Tool] | None = None,
        output_schema: OutputSchema | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
        raise AssertionError


class _OpenedSetup(BaseChatModelSetup):
    @property
    def model_kwargs(self) -> Dict[str, Any]:
        return {"model": self.model}


def _capable_setup() -> _OpenedSetup:
    setup = _OpenedSetup(connection="c", model="m")
    setup._resolved_connection = _CapableConnection()
    return setup


def _gate(**behavior: Any) -> MagicMock:
    setup = MagicMock(spec=BaseChatModelSetup)
    setup.will_apply_native_structured_output = MagicMock(**behavior)
    return setup


class _Context:
    """Serves the agent's own prompts and action config, and a given chat model."""

    def __init__(self, agent: ReActAgent, chat_model: Any) -> None:
        self._prompts = agent._resources[ResourceType.PROMPT]
        self._config = agent._actions["start_action"][2] or {}
        self._chat_model = chat_model
        self.chat_model_lookups = 0
        self.events: List[Event] = []

    def get_resource(
        self, name: str, type: ResourceType, metric_group: Any = None
    ) -> Any:
        if type == ResourceType.PROMPT:
            return self._prompts[name]
        assert (name, type) == (_DEFAULT_CHAT_MODEL, ResourceType.CHAT_MODEL)
        self.chat_model_lookups += 1
        if isinstance(self._chat_model, Exception):
            raise self._chat_model
        return self._chat_model

    def get_action_config_value(self, key: str) -> Any:
        return self._config.get(key)

    def send_event(self, event: Event) -> None:
        self.events.append(event)


def _agent(output_schema: Any) -> ReActAgent:
    return ReActAgent(
        chat_model=ResourceDescriptor(
            clazz=_CHAT_MODEL_CLASS, connection="c", model="m"
        ),
        output_schema=output_schema,
    )


def _instruction(agent: ReActAgent) -> str:
    return agent._resources[ResourceType.PROMPT][_DEFAULT_SCHEMA_PROMPT].template


def _start(agent: ReActAgent, chat_model: Any) -> tuple[ChatRequestEvent, _Context]:
    ctx = _Context(agent, chat_model)
    ReActAgent.start_action(InputEvent(input="hi"), ctx)
    assert len(ctx.events) == 1
    return ctx.events[0], ctx


def _texts(event: ChatRequestEvent) -> List[str]:
    return [m.text for m in event.messages]


def test_row_type_info_schema_keeps_instruction_on_capable_connection() -> None:
    """No connection translates a RowTypeInfo, so it is always described in text."""
    agent = _agent(Types.ROW_NAMED(["name"], [Types.STRING()]))

    request, _ = _start(agent, _capable_setup())

    assert _texts(request) == [_instruction(agent), "hi"]


def test_natively_applied_schema_omits_instruction() -> None:
    """A schema the provider enforces is not also described in the prompt."""
    agent = _agent(_Person)
    gate = _gate(return_value=True)

    request, _ = _start(agent, gate)

    assert _texts(request) == ["hi"]
    assert request.messages[0].role == MessageRole.USER
    assert request.output_schema == OutputSchema(output_schema=_Person)
    gate.will_apply_native_structured_output.assert_called_once_with(
        OutputSchema(output_schema=_Person)
    )


def test_schema_not_applied_natively_keeps_instruction() -> None:
    """A schema left to the prompt keeps the instruction."""
    agent = _agent(_Person)

    request, _ = _start(agent, _gate(return_value=False))

    assert _texts(request) == [_instruction(agent), "hi"]
    assert request.output_schema == OutputSchema(output_schema=_Person)


def test_failing_gate_keeps_instruction() -> None:
    """A gate that raises keeps the instruction; the chat action reports the error."""
    agent = _agent(_Person)

    request, _ = _start(agent, _gate(side_effect=ValueError("NATIVE infeasible")))

    assert _texts(request) == [_instruction(agent), "hi"]


def test_failing_chat_model_lookup_keeps_instruction() -> None:
    """A chat model that cannot be resolved keeps the instruction."""
    agent = _agent(_Person)

    request, _ = _start(agent, RuntimeError("connection unavailable"))

    assert _texts(request) == [_instruction(agent), "hi"]


def test_chat_model_that_is_not_a_setup_keeps_instruction() -> None:
    """Only a chat model setup can answer whether it applies the schema natively."""
    agent = _agent(_Person)
    other = MagicMock()

    request, _ = _start(agent, other)

    assert _texts(request) == [_instruction(agent), "hi"]
    other.will_apply_native_structured_output.assert_not_called()


@pytest.mark.parametrize("interrupt", [CancelledError(), InterruptedError()])
def test_interrupted_gate_propagates(interrupt: BaseException) -> None:
    """An interrupt is not mistaken for an unanswerable gate."""
    agent = _agent(_Person)
    ctx = _Context(agent, _gate(side_effect=interrupt))

    with pytest.raises(type(interrupt)):
        ReActAgent.start_action(InputEvent(input="hi"), ctx)
    assert ctx.events == []


def test_without_schema_no_instruction_and_chat_model_not_consulted() -> None:
    """An agent with no output schema adds no instruction and asks nothing."""
    agent = _agent(None)

    request, ctx = _start(agent, _gate(return_value=True))

    assert _texts(request) == ["hi"]
    assert request.output_schema is None
    assert ctx.chat_model_lookups == 0
