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
from typing import Any
from unittest.mock import MagicMock

import pytest
from pydantic import BaseModel
from pyflink.common.typeinfo import Types

from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.integrations.chat_models.openai.openai_chat_model import (
    OpenAIChatModelConnection,
)
from flink_agents.plan.function import PythonFunction
from flink_agents.plan.tools.function_tool import FunctionTool


class Person(BaseModel):
    """A representative BaseModel output schema."""

    name: str
    age: int


def _connection() -> OpenAIChatModelConnection:
    conn = OpenAIChatModelConnection(
        name="openai", api_key="test-key", api_base_url="http://localhost"
    )
    mock_client = MagicMock()
    mock_message = MagicMock()
    mock_message.role = "assistant"
    mock_message.content = "ok"
    mock_message.tool_calls = None
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
    ],
)
def test_capability_predicate_accepts_capable_models(model: str) -> None:
    """The capability predicate accepts the documented capable models."""
    assert _connection().supports_native_structured_output(model) is True


@pytest.mark.parametrize(
    "model",
    [
        "gpt-3.5-turbo",
        "gpt-4-turbo",
        "gpt-4o-2024-05-13",
        "some-unknown-model",
        None,
    ],
)
def test_capability_predicate_rejects_incapable_models(model: str | None) -> None:
    """The capability predicate rejects incapable, pre-cutoff, unknown, and None models."""
    assert _connection().supports_native_structured_output(model) is False
