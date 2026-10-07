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
from typing import Any, Callable
from unittest.mock import MagicMock

import pytest
from openai.lib._pydantic import to_strict_json_schema
from pydantic import BaseModel
from pyflink.common.typeinfo import Types

from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import NativeStructuredOutputSupport
from flink_agents.integrations.chat_models.openai.openai_chat_model import (
    OpenAIChatModelConnection,
)
from flink_agents.plan.function import PythonFunction
from flink_agents.plan.tools.function_tool import FunctionTool


class Person(BaseModel):
    """A representative BaseModel output schema."""

    name: str
    age: int


class Unrenderable(BaseModel):
    """A schema carrying a member that no JSON Schema can express."""

    cb: Callable[[int], int]


class FieldLess(BaseModel):
    """A schema declaring no fields, so it constrains nothing."""


class NestsFieldLess(BaseModel):
    """A field-less schema one level down, reached through a ``$ref``."""

    inner: FieldLess


class MapsToFieldLess(BaseModel):
    """A field-less schema reached through a map's ``additionalProperties``."""

    m: dict[str, FieldLess]


class Labelled(BaseModel):
    """A schema whose only member is a free-form map, a legitimate constraint."""

    labels: dict[str, str]


def _connection() -> OpenAIChatModelConnection:
    conn = OpenAIChatModelConnection(
        api_key="test-key", api_base_url="http://localhost"
    )
    mock_client = MagicMock()
    mock_message = MagicMock()
    mock_message.role = "assistant"
    mock_message.content = "ok"
    mock_message.tool_calls = None
    mock_message.refusal = None
    mock_client.chat.completions.create.return_value.choices = [
        MagicMock(message=mock_message)
    ]
    mock_client.chat.completions.create.return_value.usage = None
    conn._client = mock_client
    return conn


def _create_call_kwargs(conn: OpenAIChatModelConnection) -> dict[str, Any]:
    return conn.client.chat.completions.create.call_args.kwargs


def _add(a: int, b: int) -> int:
    """Add two integers.

    Parameters
    ----------
    a : int
        first
    b : int
        second

    Returns:
    -------
    int
        sum
    """
    return a + b


def test_native_applied_for_basemodel_capable_model() -> None:
    """response_format json_schema strict applied for a BaseModel on a capable model."""
    conn = _connection()
    conn.chat(
        [ChatMessage(role=MessageRole.USER, content="hi")],
        model="gpt-4o",
        output_schema=OutputSchema(output_schema=Person),
    )
    response_format = _create_call_kwargs(conn)["response_format"]
    assert response_format["type"] == "json_schema"
    assert response_format["json_schema"]["strict"] is True
    assert response_format["json_schema"]["schema"]["additionalProperties"] is False


def test_native_not_applied_for_incapable_model() -> None:
    """Native NOT applied for a BaseModel on an incapable model (prompt fallback)."""
    conn = _connection()
    conn.chat(
        [ChatMessage(role=MessageRole.USER, content="hi")],
        model="gpt-3.5-turbo",
        output_schema=OutputSchema(output_schema=Person),
    )
    assert "response_format" not in _create_call_kwargs(conn)


def test_native_not_applied_for_pre_cutoff_snapshot() -> None:
    """Native NOT applied for a pre-cutoff same-family gpt-4o snapshot.

    gpt-4o-2024-05-13 predates the Structured Outputs cutoff even though it shares the
    gpt-4o prefix; treating it as capable would fail silently at the provider.
    """
    conn = _connection()
    conn.chat(
        [ChatMessage(role=MessageRole.USER, content="hi")],
        model="gpt-4o-2024-05-13",
        output_schema=OutputSchema(output_schema=Person),
    )
    assert "response_format" not in _create_call_kwargs(conn)


def test_native_not_applied_when_schema_none() -> None:
    """Native NOT applied when no output schema is supplied."""
    conn = _connection()
    conn.chat(
        [ChatMessage(role=MessageRole.USER, content="hi")],
        model="gpt-4o",
        output_schema=None,
    )
    assert "response_format" not in _create_call_kwargs(conn)


def test_native_not_applied_for_row_type_info() -> None:
    """Native NOT applied for a RowTypeInfo schema (BaseModel-only scope)."""
    conn = _connection()
    row_type = Types.ROW_NAMED(["name"], [Types.STRING()])
    conn.chat(
        [ChatMessage(role=MessageRole.USER, content="hi")],
        model="gpt-4o",
        output_schema=OutputSchema(output_schema=row_type),
    )
    assert "response_format" not in _create_call_kwargs(conn)


def test_native_applied_even_when_tools_bound() -> None:
    """Native applied for a BaseModel even when tools are bound (no empty-tools gate)."""
    conn = _connection()
    tool = FunctionTool(func=PythonFunction.from_callable(_add))
    conn.chat(
        [ChatMessage(role=MessageRole.USER, content="hi")],
        tools=[tool],
        model="gpt-4o",
        output_schema=OutputSchema(output_schema=Person),
    )
    assert "response_format" in _create_call_kwargs(conn)


@pytest.mark.parametrize(
    "model",
    [
        "gpt-4o",
        "gpt-4o-2024-08-06",
        "gpt-4o-2024-11-20",
        "gpt-4o-mini",
        "gpt-4o-mini-2024-07-18",
        "gpt-4o-search-preview",
        "gpt-4o-search-preview-2025-03-11",
        "gpt-4o-mini-search-preview",
        "gpt-4.1",
        "gpt-4.1-mini",
        "gpt-5",
        "gpt-5-mini",
        "gpt-5-chat-latest",
        "o1",
        "o1-2024-12-17",
        "o3",
        "o3-mini",
        "o4-mini",
    ],
)
def test_query_recommends_native_for_capable_models(model: str) -> None:
    """A translatable schema on a documented capable model is recommended native."""
    assert (
        _connection().supports_native_structured_output(
            OutputSchema(output_schema=Person), [], {"model": model}
        )
        is NativeStructuredOutputSupport.NATIVE_RECOMMENDED
    )


@pytest.mark.parametrize(
    "model",
    [
        "gpt-3.5-turbo",
        "gpt-4",
        "gpt-4-turbo",
        "gpt-4o-2024-05-13",
        "gpt-4o-audio-preview",
        "gpt-4o-mini-audio-preview",
        "gpt-4o-mini-realtime-preview",
        "gpt-4o-mini-tts",
        "gpt-4o-mini-transcribe",
        "o1-mini",
        "some-unknown-model",
        "",
        None,
    ],
)
def test_query_reports_incapable_models_feasible(model: str | None) -> None:
    """Modality variants, incapable, unknown and empty models stay merely feasible.

    The schema is translatable, so the request is not infeasible: capability is
    advisory and is kept out of the binding half of the answer.
    """
    assert (
        _connection().supports_native_structured_output(
            OutputSchema(output_schema=Person), [], {"model": model}
        )
        is NativeStructuredOutputSupport.FEASIBLE
    )


def _chat_with_schema(conn: OpenAIChatModelConnection, schema: Any) -> None:
    conn.chat(
        [ChatMessage(role=MessageRole.USER, content="hi")],
        model="gpt-4o",
        output_schema=OutputSchema(output_schema=schema),
    )


def test_unrenderable_schema_raises_naming_the_model() -> None:
    """A schema that cannot be rendered fails here rather than at the provider."""
    with pytest.raises(TypeError, match="Unrenderable cannot be rendered"):
        _chat_with_schema(_connection(), Unrenderable)


@pytest.mark.parametrize("schema", [FieldLess, NestsFieldLess, MapsToFieldLess])
def test_field_less_schema_is_accepted_and_sent_whole(schema: type[BaseModel]) -> None:
    """A schema declaring no fields renders, so the provider decides on it, not us.

    The document reaches the request exactly as rendered rather than being refused
    here. The nested cases carry the field-less model below the root, so the
    assertion covers the whole document rather than only its top level.
    """
    conn = _connection()
    _chat_with_schema(conn, schema)
    response_format = _create_call_kwargs(conn)["response_format"]
    assert response_format["json_schema"]["schema"] == to_strict_json_schema(schema)


def test_map_member_schema_is_accepted_and_sent_whole() -> None:
    """A free-form map is a legitimate constraint and reaches the request intact."""
    conn = _connection()
    _chat_with_schema(conn, Labelled)
    response_format = _create_call_kwargs(conn)["response_format"]
    assert response_format["json_schema"]["schema"] == to_strict_json_schema(Labelled)


def _judging_connection() -> tuple[OpenAIChatModelConnection, list[str | None]]:
    """A connection recording every model its request path judges for capability.

    Subclassing keeps the predicate itself under test rather than standing a stub in
    for it: the override notes what it was asked about and delegates to the real one.
    """
    judged: list[str | None] = []

    class _JudgingConnection(OpenAIChatModelConnection):
        def _model_supports_native_structured_output(
            self, effective_model: str | None
        ) -> bool:
            judged.append(effective_model)
            return super()._model_supports_native_structured_output(effective_model)

    conn = _JudgingConnection(api_key="test-key", api_base_url="http://localhost")
    mock_client = MagicMock()
    mock_message = MagicMock()
    mock_message.role = "assistant"
    mock_message.content = "ok"
    mock_message.tool_calls = None
    mock_message.refusal = None
    mock_client.chat.completions.create.return_value.choices = [
        MagicMock(message=mock_message)
    ]
    mock_client.chat.completions.create.return_value.usage = None
    conn._client = mock_client
    return conn, judged


@pytest.mark.parametrize(
    "model_kwargs",
    [{"model": "gpt-4o-mini"}, {"model": "an-unknown-model"}, {"model": ""}, {}],
    ids=["capable", "unknown", "blank", "absent"],
)
def test_query_judges_the_model_the_request_judges(
    model_kwargs: dict[str, Any],
) -> None:
    """The query asks about exactly the model the request path asks about.

    This connection reads the parameter without a fallback. Pinning the query against
    what the builder judges is what would catch a fallback being added to one of the
    two without the other.
    """
    conn, judged = _judging_connection()
    schema = OutputSchema(output_schema=Person)

    conn.supports_native_structured_output(schema, [], model_kwargs)
    conn.chat(
        [ChatMessage(role=MessageRole.USER, content="hi")],
        output_schema=schema,
        **model_kwargs,
    )

    assert len(judged) == 2
    assert judged[0] == judged[1]


def test_query_agrees_with_the_native_branch() -> None:
    """The answer matches whether the request ends up carrying a response_format.

    Comparing the answer against what the request carries, rather than against a
    literal, is what keeps the query and the branch from drifting in step. The model is
    capable in every case, so only feasibility varies.
    """
    conn = _connection()
    tool = FunctionTool(func=PythonFunction.from_callable(_add))
    row_type = Types.ROW_NAMED(["name"], [Types.STRING()])
    model_kwargs = {"model": "gpt-4o"}

    for schema in (
        OutputSchema(output_schema=Person),
        OutputSchema(output_schema=row_type),
        None,
    ):
        for tools in (None, [], [tool]):
            support = conn.supports_native_structured_output(
                schema, tools, model_kwargs
            )

            conn.chat(
                [ChatMessage(role=MessageRole.USER, content="hi")],
                tools=tools,
                output_schema=schema,
                **model_kwargs,
            )

            carried = "response_format" in _create_call_kwargs(conn)
            expected = (
                NativeStructuredOutputSupport.NATIVE_RECOMMENDED
                if carried
                else NativeStructuredOutputSupport.INFEASIBLE
            )
            assert support is expected, f"schema {schema}, tools {tools}"
