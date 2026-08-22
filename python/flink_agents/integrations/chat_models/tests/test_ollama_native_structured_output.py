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
from typing import Any, Dict
from unittest.mock import MagicMock

import pytest
from pydantic import BaseModel
from pyflink.common.typeinfo import Types

from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.integrations.chat_models.ollama_chat_model import (
    OllamaChatModelConnection,
)


class Person(BaseModel):
    """A representative flat BaseModel output schema."""

    name: str
    age: int


class Company(BaseModel):
    """A nested output schema, whose JSON schema carries a ``$defs`` entry."""

    name: str
    owner: Person


def _connection() -> OllamaChatModelConnection:
    """A connection whose Ollama client is a mock, so no server is contacted."""
    conn = OllamaChatModelConnection(name="ollama")
    response = MagicMock()
    response.message.role = "assistant"
    response.message.content = "ok"
    response.message.tool_calls = None
    response.prompt_eval_count = 1
    response.eval_count = 2
    mock_client = MagicMock()
    mock_client.chat.return_value = response
    conn._OllamaChatModelConnection__client = mock_client
    return conn


def _chat_call_kwargs(conn: OllamaChatModelConnection) -> Dict[str, Any]:
    return conn.client.chat.call_args.kwargs


def _messages() -> list[ChatMessage]:
    return [ChatMessage(role=MessageRole.USER, content="hi")]


def test_native_applied_for_base_model() -> None:
    """A BaseModel schema reaches the request as the native ``format`` argument."""
    conn = _connection()
    conn.chat(
        _messages(), model="qwen3", output_schema=OutputSchema(output_schema=Person)
    )
    native_format = _chat_call_kwargs(conn)["format"]
    assert native_format["properties"].keys() == {"name", "age"}


def test_format_absent_without_schema() -> None:
    """A call without a schema carries no ``format``, leaving generation unconstrained."""
    conn = _connection()
    conn.chat(_messages(), model="qwen3")
    assert "format" not in _chat_call_kwargs(conn)


def test_native_not_applied_for_row_type_info() -> None:
    """A RowTypeInfo schema has no native translation and keeps the prompt fallback."""
    conn = _connection()
    row_type = Types.ROW_NAMED(["name"], [Types.STRING()])
    conn.chat(
        _messages(), model="qwen3", output_schema=OutputSchema(output_schema=row_type)
    )
    assert "format" not in _chat_call_kwargs(conn)


def test_schema_is_model_json_schema() -> None:
    """The payload is pydantic's schema verbatim, ``$defs`` and all.

    A nested schema is used because it is the shape a hand-rolled translation
    diverges on: inlining or renaming a ``$defs`` entry breaks the ``$ref`` targets
    the server resolves when it builds the grammar.
    """
    conn = _connection()
    conn.chat(
        _messages(), model="qwen3", output_schema=OutputSchema(output_schema=Company)
    )
    assert _chat_call_kwargs(conn)["format"] == Company.model_json_schema()


def test_schema_not_passed_as_sampling_option() -> None:
    """The schema never reaches ``options``, which the server reads as sampling options.

    A schema written into the forwarded kwargs would arrive there instead of in
    ``format``, so the server would apply no grammar and report no error.
    """
    conn = _connection()
    conn.chat(
        _messages(), model="qwen3", output_schema=OutputSchema(output_schema=Person)
    )
    options = _chat_call_kwargs(conn)["options"]
    assert "format" not in options
    assert Person.model_json_schema() not in options.values()


@pytest.mark.parametrize(
    "model",
    ["qwen3", "llama3.2", "gemma3:270m", "mistral", "an-unknown-model", "", None],
)
def test_supports_native_structured_output(model: str | None) -> None:
    """Capability is reported for every model, including an absent model name.

    The capability is the server's, not the model's, so there is no model name it
    can be keyed on and none it should report not-capable for.
    """
    conn = OllamaChatModelConnection(name="ollama")
    assert conn.supports_native_structured_output(model) is True


def test_schema_accepted_not_rejected() -> None:
    """A schema with no native translation is answered, not refused.

    Rejecting is what a connection without native structured output does. This one
    has it, so a schema form it cannot translate natively falls back to the prompt
    engineering the caller already applied rather than raising.
    """
    conn = _connection()
    row_type = Types.ROW_NAMED(["name"], [Types.STRING()])
    response = conn.chat(
        _messages(), model="qwen3", output_schema=OutputSchema(output_schema=row_type)
    )
    assert response.content == "ok"
