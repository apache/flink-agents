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
from anthropic.types import Message, TextBlock, ToolUseBlock, Usage
from pydantic import BaseModel
from pyflink.common.typeinfo import Types

from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.integrations.chat_models.anthropic.anthropic_chat_model import (
    AnthropicChatModelConnection,
)


def _connection() -> AnthropicChatModelConnection:
    return AnthropicChatModelConnection(name="test", api_key="dummy")


def _connection_returning(message: Message) -> AnthropicChatModelConnection:
    connection = _connection()
    client = MagicMock()
    client.messages.create.return_value = message
    connection._client = client
    return connection


def _usage() -> Usage:
    return Usage(input_tokens=1, output_tokens=1)


def test_tool_use_response_without_leading_text() -> None:
    # When the model calls a tool it commonly returns only a tool_use block, so
    # content[0] is not a text block. Parsing must not assume content[0].text.
    message = Message(
        id="m",
        model="claude",
        role="assistant",
        type="message",
        stop_reason="tool_use",
        content=[
            ToolUseBlock(type="tool_use", id="t1", name="add", input={"a": 1, "b": 2})
        ],
        usage=_usage(),
    )
    response = _connection_returning(message).chat(
        [ChatMessage(role=MessageRole.USER, content="add 1 and 2")]
    )
    assert response.content == ""
    assert len(response.tool_calls) == 1
    assert response.tool_calls[0]["function"]["name"] == "add"


def test_tool_use_response_keeps_leading_text() -> None:
    # A tool_use response may be preceded by a text block; that text is kept.
    message = Message(
        id="m",
        model="claude",
        role="assistant",
        type="message",
        stop_reason="tool_use",
        content=[
            TextBlock(type="text", text="Let me add those."),
            ToolUseBlock(type="tool_use", id="t1", name="add", input={"a": 1, "b": 2}),
        ],
        usage=_usage(),
    )
    response = _connection_returning(message).chat(
        [ChatMessage(role=MessageRole.USER, content="add 1 and 2")]
    )
    assert response.content == "Let me add those."
    assert len(response.tool_calls) == 1


def test_plain_text_response() -> None:
    message = Message(
        id="m",
        model="claude",
        role="assistant",
        type="message",
        stop_reason="end_turn",
        content=[TextBlock(type="text", text="Hello!")],
        usage=_usage(),
    )
    response = _connection_returning(message).chat(
        [ChatMessage(role=MessageRole.USER, content="hi")]
    )
    assert response.content == "Hello!"


# ---------------------------------------------------------------------------------
# Native structured output
# ---------------------------------------------------------------------------------


class _Answer(BaseModel):
    """A representative BaseModel output schema."""

    verdict: str


# A model the provider documents native structured-output support for.
_CAPABLE_MODEL = "claude-opus-4-6"

# The default model this integration ships with, which predates the cutoff.
_INCAPABLE_MODEL = "claude-sonnet-4-20250514"

# The models the provider documents native structured-output support for, in the order
# the connection lists them: the exact-matched names first, then the prefix-matched
# aliases. The names are written out here rather than read from the connection, so that
# a name mistyped there is a disagreement between two lists rather than a value both
# sides share.
_CAPABLE_MODELS = [
    "claude-opus-4-6",
    "claude-opus-4-7",
    "claude-opus-4-8",
    "claude-opus-5",
    "claude-sonnet-4-6",
    "claude-sonnet-5",
    "claude-fable-5",
    "claude-mythos-5",
    "claude-mythos-preview",
    "claude-opus-4-5",
    "claude-sonnet-4-5",
    "claude-haiku-4-5",
]

# Names that must not be treated as capable. claude-opus-4-1-20250805 and claude-opus-4
# are the reason the alias prefixes retain their minor version: truncating
# claude-opus-4-5 to claude-opus-4 would admit both.
_INCAPABLE_MODELS = [
    "claude-opus-4-1-20250805",
    "claude-opus-4",
    "claude-sonnet-4-20250514",
    "claude-3-5-sonnet-latest",
    "",
    None,
]


def _request_kwargs(**chat_kwargs: Any) -> Dict[str, Any]:
    """The keyword arguments the connection passed to ``messages.create``."""
    message = Message(
        id="m",
        model="claude",
        role="assistant",
        type="message",
        stop_reason="end_turn",
        content=[TextBlock(type="text", text='{"verdict": "ok"}')],
        usage=_usage(),
    )
    connection = _connection_returning(message)
    connection.chat([ChatMessage(role=MessageRole.USER, content="hi")], **chat_kwargs)
    return connection.client.messages.create.call_args.kwargs


def test_native_output_config_applied_on_capable_model() -> None:
    output_config = _request_kwargs(
        model=_CAPABLE_MODEL, output_schema=OutputSchema(output_schema=_Answer)
    )["output_config"]

    # Asserting the property name rather than mere presence: a config derived from the
    # wrong schema, or from an empty placeholder, would also be present.
    assert output_config["format"]["type"] == "json_schema"
    assert set(output_config["format"]["schema"]["properties"]) == {"verdict"}


def test_native_output_config_not_applied_on_incapable_model() -> None:
    assert "output_config" not in _request_kwargs(
        model=_INCAPABLE_MODEL, output_schema=OutputSchema(output_schema=_Answer)
    )


def test_native_output_config_not_applied_without_schema() -> None:
    assert "output_config" not in _request_kwargs(
        model=_CAPABLE_MODEL, output_schema=None
    )


def test_native_output_config_not_applied_for_row_type_info() -> None:
    # A RowTypeInfo schema has no native translation and must keep the
    # prompt-engineering fallback rather than failing.
    row_type = Types.ROW_NAMED(["verdict"], [Types.STRING()])
    assert "output_config" not in _request_kwargs(
        model=_CAPABLE_MODEL, output_schema=OutputSchema(output_schema=row_type)
    )


def test_caller_output_config_wins_over_schema() -> None:
    # Only one channel carries output_config into the request, so a derived config
    # would replace the caller's outright and report nothing. The caller's value is
    # kept and the schema stays on the prompt-engineering fallback.
    caller_config = {"format": {"type": "json_schema", "schema": {"type": "object"}}}

    sent = _request_kwargs(
        model=_CAPABLE_MODEL,
        output_schema=OutputSchema(output_schema=_Answer),
        output_config=caller_config,
    )["output_config"]

    assert sent == caller_config


@pytest.mark.parametrize("model", _CAPABLE_MODELS)
def test_capability_predicate_accepts_capable_models(model) -> None:
    assert _connection().supports_native_structured_output(model) is True


@pytest.mark.parametrize("model", _INCAPABLE_MODELS)
def test_capability_predicate_rejects_incapable_models(model) -> None:
    assert _connection().supports_native_structured_output(model) is False


def test_alias_prefix_matches_dated_snapshot() -> None:
    # The three 4.5-generation names are aliases, so a request may carry the dated
    # snapshot instead. Turning the prefixes into exact matches would still satisfy
    # the capable-models test above.
    predicate = _connection().supports_native_structured_output
    assert predicate("claude-sonnet-4-5-20250929") is True


def test_capability_reads_no_instance_state() -> None:
    # __new__ skips __init__, so no field is set and no client exists. A predicate
    # reading instance state would raise here instead of answering for its argument.
    bare = AnthropicChatModelConnection.__new__(AnthropicChatModelConnection)

    assert bare.supports_native_structured_output(_CAPABLE_MODEL) is True
    assert bare.supports_native_structured_output(_INCAPABLE_MODEL) is False
