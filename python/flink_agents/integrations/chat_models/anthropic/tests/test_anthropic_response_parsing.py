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
from flink_agents.api.tools.tool import Tool, ToolMetadata, ToolType
from flink_agents.integrations.chat_models.anthropic.anthropic_chat_model import (
    AnthropicChatModelConnection,
    AnthropicChatModelSetup,
    _supports_json_prefill,
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


def test_plain_text_response_keeps_token_usage() -> None:
    # Token usage must survive on non-tool_use responses too: the common
    # end_turn path previously dropped extra_args entirely, so promptTokens /
    # completionTokens never reached the token metrics recording.
    message = Message(
        id="m",
        model="claude",
        role="assistant",
        type="message",
        stop_reason="end_turn",
        content=[TextBlock(type="text", text="Hello!")],
        usage=Usage(input_tokens=7, output_tokens=3),
    )
    response = _connection_returning(message).chat(
        [ChatMessage(role=MessageRole.USER, content="hi")],
        model="claude-sonnet-4-5",
    )
    assert response.extra_args["model_name"] == "claude-sonnet-4-5"
    assert response.extra_args["promptTokens"] == 7
    assert response.extra_args["completionTokens"] == 3


def test_tool_use_response_keeps_token_usage() -> None:
    # Regression guard for the tool_use path, which already carried usage.
    message = Message(
        id="m",
        model="claude",
        role="assistant",
        type="message",
        stop_reason="tool_use",
        content=[
            ToolUseBlock(type="tool_use", id="t1", name="add", input={"a": 1, "b": 2})
        ],
        usage=Usage(input_tokens=7, output_tokens=3),
    )
    response = _connection_returning(message).chat(
        [ChatMessage(role=MessageRole.USER, content="add 1 and 2")],
        model="claude-sonnet-4-5",
    )
    assert response.extra_args["promptTokens"] == 7
    assert response.extra_args["completionTokens"] == 3


# ---------------------------------------------------------------------------------
# Native structured output
# ---------------------------------------------------------------------------------


class _Answer(BaseModel):
    """A representative BaseModel output schema."""

    verdict: str


# A model the provider documents native structured-output support for.
#
# Deliberately a 4.5-generation name, which is the only generation that is both
# structured-output capable and still accepts a JSON prefill. The prefill tests below
# assert that an output_config suppresses the prefill; on a 4.6-or-later name the
# prefill capability guard would suppress it as well, so those assertions would hold
# even with the output_config suppression removed.
_CAPABLE_MODEL = "claude-sonnet-4-5"

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


@pytest.mark.parametrize("model", ["claude-sonnet-4-5", "claude-opus-4-6"])
def test_native_output_config_applied_on_capable_model(model) -> None:
    # One name from each way the capability check can match: a 4.5-generation alias
    # reached by prefix, and a 4.6 name reached by exact match. The chat path consults
    # the check as a whole, so covering only one branch would let it be narrowed to
    # that branch while silently dropping native structured output for every model on
    # the other.
    output_config = _request_kwargs(
        model=model, output_schema=OutputSchema(output_schema=_Answer)
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


def test_alias_prefix_does_not_match_longer_minor_version() -> None:
    # A dated snapshot continues the alias with a "-" separator. A name that extends
    # the alias without one is a different minor version, whose capability is not the
    # alias's to answer for.
    predicate = _connection().supports_native_structured_output
    assert predicate("claude-sonnet-4-50") is False


def test_capability_reads_no_instance_state() -> None:
    # __new__ skips __init__, so no field is set and no client exists. A predicate
    # reading instance state would raise here instead of answering for its argument.
    bare = AnthropicChatModelConnection.__new__(AnthropicChatModelConnection)

    assert bare.supports_native_structured_output(_CAPABLE_MODEL) is True
    assert bare.supports_native_structured_output(_INCAPABLE_MODEL) is False


# ---------------------------------------------------------------------------------
# JSON prefill
# ---------------------------------------------------------------------------------

# The continuation an assistant returns after a "{" prefill, and the document it
# completes.
_CONTINUATION = '"verdict": "ok"}'
_COMPLETED = "{" + _CONTINUATION

# The models the provider documents as rejecting assistant-message prefilling, in the
# order the connection lists them. Mirroring that order keeps the two lists comparable
# side by side, so a name added to one and not the other stands out.
_PREFILL_UNSUPPORTED = [
    "claude-opus-4-6",
    "claude-opus-4-7",
    "claude-opus-4-8",
    "claude-opus-5",
    "claude-sonnet-4-6",
    "claude-sonnet-5",
    "claude-fable-5",
    "claude-mythos-5",
    "claude-mythos-preview",
]

# Names that accept a prefill. The three 4.5-generation names are the load-bearing
# ones: they are documented as structured-output capable, so folding the two rules onto
# one list would silently withdraw the prefill from exactly these models.
# claude-sonnet-4-5-20250929 is the dated snapshot behind one of those aliases, and
# claude-3-5-sonnet-latest stands for every name the list does not mention, which keeps
# the prefill because only the listed names withdraw it.
_PREFILL_SUPPORTED = [
    "claude-opus-4-5",
    "claude-sonnet-4-5",
    "claude-haiku-4-5",
    "claude-sonnet-4-5-20250929",
    "claude-sonnet-4-20250514",
    "claude-3-5-sonnet-latest",
    "",
    None,
]


class _AddArgs(BaseModel):
    a: int


class _StubTool(Tool):
    """Minimal tool stub; only its presence in the tools list matters."""

    @classmethod
    def tool_type(cls) -> ToolType:
        return ToolType.FUNCTION

    def call(self, *args: Any, **kwargs: Any) -> None:
        return None


def _prefill_outcome(**chat_kwargs: Any) -> tuple:
    """Whether the request carried the prefill, and the content the response yielded.

    The two have to agree: a response reconstructed on any other signal than the
    decision the request was built with either prepends a stray "{" or drops a
    required one, and the response itself gives no sign of either.
    """
    message = Message(
        id="m",
        model="claude",
        role="assistant",
        type="message",
        stop_reason="end_turn",
        content=[TextBlock(type="text", text=_CONTINUATION)],
        usage=_usage(),
    )
    connection = _connection_returning(message)
    response = connection.chat(
        [ChatMessage(role=MessageRole.USER, content="hi")], **chat_kwargs
    )
    sent = connection.client.messages.create.call_args.kwargs["messages"]
    return sent[-1] == {"role": "assistant", "content": "{"}, response.content


def test_json_prefill_not_applied_by_default() -> None:
    # The parameter is opt-in: it steers the model with a technique several models
    # reject outright, so a caller that does not ask for it must not get it.
    assert _prefill_outcome(model=_INCAPABLE_MODEL) == (False, _CONTINUATION)


def test_json_prefill_not_applied_when_explicitly_false() -> None:
    # The setup emits json_prefill on every call, so the key is always present and only
    # its value separates opt-in from opt-out. Detecting the parameter by presence
    # rather than by value would re-enable the prefill for every request.
    assert _prefill_outcome(model=_INCAPABLE_MODEL, json_prefill=False) == (
        False,
        _CONTINUATION,
    )


def test_json_prefill_applied_when_requested() -> None:
    # An empty tools list, rather than no tools argument: a request configured with no
    # tools still reaches the decision carrying a list, and only an emptiness test
    # rather than a None test lets the prefill through there.
    assert _prefill_outcome(model=_INCAPABLE_MODEL, json_prefill=True, tools=[]) == (
        True,
        _COMPLETED,
    )


def test_json_prefill_suppressed_by_tools() -> None:
    # The prefill forces JSON text where the model would otherwise emit tool_use blocks.
    tool = _StubTool(
        name="add",
        metadata=ToolMetadata(name="add", description="adds", args_schema=_AddArgs),
    )

    assert _prefill_outcome(
        model=_INCAPABLE_MODEL, json_prefill=True, tools=[tool]
    ) == (False, _CONTINUATION)


def test_json_prefill_suppressed_by_caller_output_config() -> None:
    assert _prefill_outcome(
        model=_INCAPABLE_MODEL,
        json_prefill=True,
        output_config={"format": {"type": "json_schema", "schema": {"type": "object"}}},
    ) == (False, _CONTINUATION)


def test_json_prefill_suppressed_by_derived_output_config() -> None:
    # The schema reaches the request as an output_config of the framework's own making,
    # which the provider documents as incompatible with prefilling just the same. The
    # model accepts prefilling, so the output_config is the only thing suppressing it.
    assert _prefill_outcome(
        model=_CAPABLE_MODEL,
        json_prefill=True,
        output_schema=OutputSchema(output_schema=_Answer),
    ) == (False, _CONTINUATION)


def test_json_prefill_applied_when_schema_falls_back() -> None:
    # Suppression keys on whether the schema reached the request, not on whether one was
    # supplied. Keying it on the schema would strip the prefill the prompt-engineering
    # fallback depends on, which is the case the prefill mainly exists for.
    assert _prefill_outcome(
        model=_INCAPABLE_MODEL,
        json_prefill=True,
        output_schema=OutputSchema(output_schema=_Answer),
    ) == (True, _COMPLETED)


def test_json_prefill_suppressed_on_prefill_unsupported_model() -> None:
    assert _prefill_outcome(model="claude-opus-4-6", json_prefill=True) == (
        False,
        _CONTINUATION,
    )


def test_json_prefill_applied_on_structured_output_capable_model() -> None:
    # The two capability rules draw different lines, and this model sits between them:
    # the provider documents structured-output support from the 4.5 generation on but
    # withdraws prefilling only from 4.6 on. Deriving the prefill rule from the
    # structured-output allowlists would strip the prefill here, where the provider
    # still accepts it.
    assert _connection().supports_native_structured_output("claude-sonnet-4-5") is True

    assert _prefill_outcome(model="claude-sonnet-4-5", json_prefill=True) == (
        True,
        _COMPLETED,
    )


@pytest.mark.parametrize("json_prefill", [True, False])
def test_json_prefill_is_not_forwarded_to_the_provider(json_prefill) -> None:
    # It is a framework parameter, so the SDK would reject it as an unknown request
    # field whichever way the decision went.
    assert "json_prefill" not in _request_kwargs(
        model=_INCAPABLE_MODEL, json_prefill=json_prefill
    )


@pytest.mark.parametrize("model", _PREFILL_UNSUPPORTED)
def test_prefill_predicate_rejects_unsupported_models(model) -> None:
    assert _supports_json_prefill(model) is False


@pytest.mark.parametrize("model", _PREFILL_SUPPORTED)
def test_prefill_predicate_accepts_every_other_model(model) -> None:
    assert _supports_json_prefill(model) is True


# ---------------------------------------------------------------------------------
# Setup parameters
# ---------------------------------------------------------------------------------


def test_setup_defaults_json_prefill_to_false() -> None:
    assert (
        AnthropicChatModelSetup(connection="conn").model_kwargs["json_prefill"] is False
    )


def test_setup_honors_explicit_json_prefill() -> None:
    # Pins that the argument is read rather than the default being emitted
    # unconditionally.
    setup = AnthropicChatModelSetup(connection="conn", json_prefill=True)
    assert setup.model_kwargs["json_prefill"] is True
