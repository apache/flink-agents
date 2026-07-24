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
from typing import Any, Dict, List, Sequence

import pytest
from pydantic import BaseModel, Field, ValidationError

from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
    StructuredOutputStrategy,
)
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.tools.tool import Tool


class _MinimalChatModelSetup(BaseChatModelSetup):
    """Minimal subclass that omits the `model` field declaration.

    Used to assert the `model` field is inherited from `BaseChatModelSetup`.
    """

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return chat model settings derived from the inherited `model` field."""
        return {"model": self.model}


class _Answer(BaseModel):
    """A representative BaseModel output schema."""

    text: str


class _RecordingConnection(BaseChatModelConnection):
    """Connection that captures the messages and kwargs it receives for inspection."""

    captured_messages: List[ChatMessage] = Field(default_factory=list)
    captured_kwargs: Dict[str, Any] = Field(default_factory=dict)
    captured_output_schema: OutputSchema | None = None

    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List[Tool] | None = None,
        output_schema: OutputSchema | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
        self.captured_messages = list(messages)
        self.captured_kwargs = dict(kwargs)
        self.captured_output_schema = output_schema
        return ChatMessage(role=MessageRole.ASSISTANT, content="ok")


class _RecordingChatModelSetup(BaseChatModelSetup):
    """Subclass that lets tests inject a connection without calling open()."""

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        return {}


def _build_setup(
    prompt: Prompt,
) -> tuple[_RecordingChatModelSetup, _RecordingConnection]:
    setup = _RecordingChatModelSetup(connection="c", model="m", prompt=prompt)
    connection = _RecordingConnection()
    setup._resolved_connection = connection
    return setup, connection


def test_inherits_model_field_from_base() -> None:
    """A subclass that omits `model` still exposes it via inheritance."""
    setup = _MinimalChatModelSetup(connection="c", model="m1")
    assert setup.model == "m1"


def test_missing_model_raises_validation_error() -> None:
    """Constructing without `model` must raise a Pydantic ValidationError."""
    with pytest.raises(ValidationError):
        _MinimalChatModelSetup(connection="c")


def test_chat_fills_template_from_prompt_args_parameter() -> None:
    """chat() fills the prompt template from the `prompt_args` parameter."""
    prompt = Prompt.from_text(text="Task: {key}")
    setup, connection = _build_setup(prompt)

    setup.chat([], prompt_args={"key": "value"})

    assert len(connection.captured_messages) == 1
    assert connection.captured_messages[0].content == "Task: value"


def test_chat_does_not_read_template_vars_from_extra_args() -> None:
    """chat() must not read template variables from ChatMessage.extra_args."""
    prompt = Prompt.from_text(text="Task: {key}")
    setup, connection = _build_setup(prompt)

    user_message = ChatMessage(
        role=MessageRole.USER, content="hello", extra_args={"key": "value"}
    )
    setup.chat([user_message], prompt_args={})

    assert len(connection.captured_messages) == 2
    assert connection.captured_messages[0].content == "Task: {key}"
    assert connection.captured_messages[1].content == "hello"


def test_chat_refills_template_on_subsequent_invocations() -> None:
    """Each chat() invocation must re-fill the prompt template from the args."""
    prompt = Prompt.from_text(text="Task: {key}")
    setup, connection = _build_setup(prompt)

    setup.chat([], prompt_args={"key": "v1"})
    assert len(connection.captured_messages) == 1
    assert connection.captured_messages[0].content == "Task: v1"

    tool_response = ChatMessage(role=MessageRole.TOOL, content="tool result")
    setup.chat([tool_response], prompt_args={"key": "v1"})
    assert len(connection.captured_messages) == 2
    assert connection.captured_messages[0].content == "Task: v1"
    assert connection.captured_messages[1].content == "tool result"


def test_default_capability_predicate_is_false() -> None:
    """A connection reports no native structured output for any model by default."""
    connection = _RecordingConnection()

    assert connection.supports_native_structured_output("gpt-4o") is False
    assert connection.supports_native_structured_output("gpt-3.5-turbo") is False
    assert connection.supports_native_structured_output(None) is False


def test_output_schema_guard_rejects_a_schema() -> None:
    """The guard refuses a schema a connection cannot translate natively.

    Dropping it instead would return an unconstrained response that the caller has no
    way to tell apart from a schema-conforming one.
    """
    connection = _RecordingConnection()
    schema = OutputSchema(output_schema=_Answer)

    with pytest.raises(NotImplementedError, match="_RecordingConnection"):
        connection._reject_unsupported_output_schema(schema)


def test_output_schema_guard_passes_through_none() -> None:
    """A caller on the prompt-engineering fallback passes None and is let through."""
    connection = _RecordingConnection()

    assert connection._reject_unsupported_output_schema(None) is None


def test_setup_routes_output_schema_through_to_connection() -> None:
    """chat() forwards a caller's ``output_schema`` on to the connection intact.

    The setup filters what reaches the connection, so a schema it dropped or consumed
    would leave the connection unable to apply one at all. That the schema cannot land
    in ``**kwargs`` is a separate, tree-wide invariant covered by the connection
    signature guard.
    """
    setup = _RecordingChatModelSetup(connection="c", model="m")
    connection = _RecordingConnection()
    setup._resolved_connection = connection

    schema = OutputSchema(output_schema=_Answer)
    setup.chat([], output_schema=schema)

    assert connection.captured_output_schema is schema


def test_structured_output_strategy_defaults_to_auto() -> None:
    """The setup policy defaults to AUTO when unset."""
    setup = _RecordingChatModelSetup(connection="c", model="m")

    assert setup.structured_output_strategy is StructuredOutputStrategy.AUTO


@pytest.mark.parametrize("raw", ["NATIVE", "native", "Native"])
def test_structured_output_strategy_coerces_name_and_value_case_insensitively(
    raw: str,
) -> None:
    """The policy coerces from either its name or its value, in any case.

    Java serializes this enum as its name ("NATIVE") and its own resolver accepts any
    case, so a Python side that only accepted the lowercase value would reject what
    Java sends.
    """
    setup = _RecordingChatModelSetup(
        connection="c", model="m", structured_output_strategy=raw
    )

    assert setup.structured_output_strategy is StructuredOutputStrategy.NATIVE


def test_auto_strategy_resolves_to_native_only_when_capable() -> None:
    """AUTO defers to the model's capability."""
    assert StructuredOutputStrategy.AUTO.resolves_to_native(True) is True
    assert StructuredOutputStrategy.AUTO.resolves_to_native(False) is False


def test_native_strategy_forces_native_regardless_of_capability() -> None:
    """NATIVE resolves to native even when the model is not capable."""
    assert StructuredOutputStrategy.NATIVE.resolves_to_native(False) is True


def test_prompt_strategy_never_resolves_to_native() -> None:
    """PROMPT never resolves to native even when the model is capable."""
    assert StructuredOutputStrategy.PROMPT.resolves_to_native(True) is False
