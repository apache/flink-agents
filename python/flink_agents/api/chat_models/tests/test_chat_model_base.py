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
from pyflink.common.typeinfo import BasicTypeInfo, RowTypeInfo

from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
    NativeStructuredOutputSupport,
    StructuredOutputStrategy,
)
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.tools.tool import Tool, ToolType


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


class _StubTool(Tool):
    """Minimal tool stub; only its presence in the tools list matters."""

    @classmethod
    def tool_type(cls) -> ToolType:
        return ToolType.FUNCTION

    def call(self, *args: Any, **kwargs: Any) -> None:
        return None


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


def test_structured_output_strategy_normalizes_explicit_none_to_auto() -> None:
    """An explicitly null policy resolves to AUTO instead of being rejected.

    Java cannot distinguish a configuration that carries the key as null from one
    that omits it, and resolves both to AUTO, so a null arriving on Python must not
    fail validation or leave the attribute None.
    """
    setup = _RecordingChatModelSetup(
        connection="c", model="m", structured_output_strategy=None
    )

    assert setup.structured_output_strategy is StructuredOutputStrategy.AUTO


@pytest.mark.parametrize("raw", ["bogus", ""])
def test_structured_output_strategy_rejects_unrecognized_value(raw: str) -> None:
    """Only null normalizes to AUTO; every other unrecognized value still raises.

    An empty string reaches this field in practice from an empty YAML scalar or an
    unset environment substitution, and it must not be mistaken for an omitted value:
    normalizing on falsiness rather than on null would accept it as AUTO. A non-empty
    unrecognized name survives that same falsiness check, so it takes `"bogus"` to
    catch a resolver that coerces any unknown string to AUTO.
    """
    with pytest.raises(ValidationError):
        _RecordingChatModelSetup(
            connection="c", model="m", structured_output_strategy=raw
        )


@pytest.mark.parametrize(
    ("strategy", "support", "expected"),
    [
        (
            StructuredOutputStrategy.AUTO,
            NativeStructuredOutputSupport.INFEASIBLE,
            False,
        ),
        (StructuredOutputStrategy.AUTO, NativeStructuredOutputSupport.FEASIBLE, False),
        (
            StructuredOutputStrategy.AUTO,
            NativeStructuredOutputSupport.NATIVE_RECOMMENDED,
            True,
        ),
        (StructuredOutputStrategy.NATIVE, NativeStructuredOutputSupport.FEASIBLE, True),
        (
            StructuredOutputStrategy.NATIVE,
            NativeStructuredOutputSupport.NATIVE_RECOMMENDED,
            True,
        ),
        (
            StructuredOutputStrategy.PROMPT,
            NativeStructuredOutputSupport.INFEASIBLE,
            False,
        ),
        (
            StructuredOutputStrategy.PROMPT,
            NativeStructuredOutputSupport.FEASIBLE,
            False,
        ),
        (
            StructuredOutputStrategy.PROMPT,
            NativeStructuredOutputSupport.NATIVE_RECOMMENDED,
            False,
        ),
    ],
)
def test_strategy_resolves_against_connection_support(
    strategy: StructuredOutputStrategy,
    support: NativeStructuredOutputSupport,
    expected: bool,
) -> None:
    """AUTO goes native only when recommended, NATIVE whenever feasible, PROMPT never."""
    assert strategy.resolves_to_native(support) is expected


def test_native_strategy_raises_on_an_infeasible_request() -> None:
    """A forced native request with no native form fails rather than degrading."""
    with pytest.raises(ValueError, match="NATIVE"):
        StructuredOutputStrategy.NATIVE.resolves_to_native(
            NativeStructuredOutputSupport.INFEASIBLE
        )


def test_connection_rejects_unrecognized_constructor_argument() -> None:
    """An unknown/misspelled constructor argument must raise, not be dropped.

    Regression test: BaseChatModelConnection previously inherited pydantic's
    default extra="ignore" behavior, so a caller who mistyped a config key (or
    passed a key that only exists on a different language's implementation)
    saw no error and no effect -- the value was silently discarded.
    """
    with pytest.raises(ValidationError, match="not_a_real_field"):
        _RecordingConnection(not_a_real_field="oops")


def test_setup_rejects_unrecognized_constructor_argument() -> None:
    """Same guarantee as the connection, for BaseChatModelSetup."""
    with pytest.raises(ValidationError, match="not_a_real_field"):
        _RecordingChatModelSetup(connection="c", model="m", not_a_real_field="oops")


def test_default_query_is_infeasible() -> None:
    """A connection reports no schema applicable to any request by default."""
    connection = _RecordingConnection()
    model_kwargs = {"model": "gpt-4o"}

    # Both forms an OutputSchema wraps: a BaseModel subclass, which a connection with
    # a native branch could translate, and a RowTypeInfo, which none translates.
    assert (
        connection.supports_native_structured_output(
            OutputSchema(output_schema=_Answer), [], model_kwargs
        )
        is NativeStructuredOutputSupport.INFEASIBLE
    )
    assert (
        connection.supports_native_structured_output(
            OutputSchema(
                output_schema=RowTypeInfo(
                    field_types=[BasicTypeInfo.STRING_TYPE_INFO()],
                    field_names=["name"],
                )
            ),
            [],
            model_kwargs,
        )
        is NativeStructuredOutputSupport.INFEASIBLE
    )


def test_query_accepts_a_missing_schema_tools_and_kwargs() -> None:
    """A missing schema, missing tools or missing parameters must not raise.

    Each is an ordinary request to answer about rather than a misuse: an unconstrained
    request carries no schema, a request binding no tools may reach a builder as None
    rather than as an empty list, and a builder handed no parameters asks with the same
    None it was handed.
    """
    connection = _RecordingConnection()

    assert (
        connection.supports_native_structured_output(None, None, None)
        is NativeStructuredOutputSupport.INFEASIBLE
    )
    assert (
        connection.supports_native_structured_output(
            OutputSchema(output_schema=_Answer), None, None
        )
        is NativeStructuredOutputSupport.INFEASIBLE
    )


def test_query_does_not_consume_the_model_kwargs() -> None:
    """Answering leaves the mapping able to build the request it answered about.

    An override copying a request builder's ``pop`` idiom would hand that builder a
    mapping with the key already removed, so the contract is pinned on the default too.
    """
    connection = _RecordingConnection()
    model_kwargs = {"model": "gpt-4o", "temperature": 0.5}

    connection.supports_native_structured_output(
        OutputSchema(output_schema=_Answer), [], model_kwargs
    )

    assert model_kwargs == {"model": "gpt-4o", "temperature": 0.5}


def test_query_does_not_consume_the_tools() -> None:
    """The same tools go on to bind the request the answer was about.

    A connection whose native branch turns on whether any tool is bound would answer
    about one request and build another if answering emptied the list.
    """
    connection = _RecordingConnection()
    tool = _StubTool()
    tools = [tool]

    connection.supports_native_structured_output(
        OutputSchema(output_schema=_Answer), tools, {"model": "gpt-4o"}
    )

    assert tools == [tool]
