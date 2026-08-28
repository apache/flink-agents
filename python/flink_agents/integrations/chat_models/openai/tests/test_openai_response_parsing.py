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
from unittest.mock import MagicMock

import pytest
from openai.types.chat import ChatCompletion, ChatCompletionMessage

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.integrations.chat_models.openai.openai_chat_model import (
    OpenAIChatModelConnection,
)
from flink_agents.integrations.chat_models.openai.openai_utils import (
    convert_from_openai_message,
    convert_to_openai_message,
)


@pytest.mark.parametrize("refusal", ["I cannot help with that", ""])
def test_refusal_is_preserved_in_extra_args(refusal: str) -> None:
    """A provider refusal reaches the caller through extra_args."""
    # A refused response carries no content, so the reason is the only thing that
    # distinguishes it from a genuinely empty completion. An empty reason is still
    # a refusal, which a truthiness guard would silently drop. Callers hand in an
    # extra_args already holding token metrics, so recording the reason must add to
    # that dict rather than replace it. The reason belongs in extra_args alone:
    # folding it into content would make a refusal read as an ordinary answer.
    message = ChatCompletionMessage(role="assistant", content=None, refusal=refusal)

    result = convert_from_openai_message(message, {"promptTokens": 3})

    assert result.extra_args["refusal"] == refusal
    assert result.extra_args["promptTokens"] == 3
    assert result.text == ""


def test_no_refusal_key_when_refusal_absent() -> None:
    """A response that was not refused leaves no refusal key behind."""
    # Absence of the key, not a falsy value, is what marks a response that was
    # never refused.
    message = ChatCompletionMessage(role="assistant", content="ok", refusal=None)

    result = convert_from_openai_message(message, {})

    assert "refusal" not in result.extra_args


@pytest.mark.parametrize(
    "role", [MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT]
)
def test_convert_to_openai_message_omits_response_metadata(
    role: MessageRole,
) -> None:
    """Completion metadata held in extra_args never reaches an outbound param."""
    message = ChatMessage(
        role=role,
        content="hello",
        extra_args={
            "model_name": "gpt-4o",
            "promptTokens": 3,
            "completionTokens": 5,
        },
    )

    param = convert_to_openai_message(message)

    assert param == {"role": role.value, "content": "hello"}


@pytest.mark.parametrize("refusal", ["I cannot help with that", ""])
def test_convert_to_openai_message_forwards_string_refusal(refusal: str) -> None:
    """A string refusal on an assistant message is sent to the provider."""
    # The outbound guard is a type check rather than a truthiness check, so an
    # empty reason forwards like any other.
    message = ChatMessage(
        role=MessageRole.ASSISTANT,
        content="",
        extra_args={"refusal": refusal},
    )

    param = convert_to_openai_message(message)

    assert param == {"role": "assistant", "content": "", "refusal": refusal}


@pytest.mark.parametrize("refusal", [{"reason": "policy"}, 123, True])
def test_convert_to_openai_message_omits_non_string_refusal(
    refusal: object,
) -> None:
    """Only a string refusal is forwarded; a value of any other type is dropped."""
    message = ChatMessage(
        role=MessageRole.ASSISTANT,
        content="",
        extra_args={"refusal": refusal},
    )

    param = convert_to_openai_message(message)

    assert param == {"role": "assistant", "content": ""}


def test_convert_to_openai_message_assistant_tool_calls() -> None:
    """An assistant message requesting tool calls sends them with a null content."""
    message = ChatMessage(
        role=MessageRole.ASSISTANT,
        content="",
        tool_calls=[
            {
                "original_id": "call_abc",
                "function": {"name": "get_weather", "arguments": {"city": "Berlin"}},
            }
        ],
        extra_args={"model_name": "gpt-4o", "promptTokens": 3},
    )

    param = convert_to_openai_message(message)

    assert set(param) == {"role", "content", "tool_calls"}
    assert param["role"] == "assistant"
    assert param["content"] is None


def test_convert_to_openai_message_tool_role_unchanged() -> None:
    """A tool result carries its call id and nothing else from extra_args."""
    message = ChatMessage(
        role=MessageRole.TOOL,
        content="42",
        extra_args={"external_id": "call_abc", "promptTokens": 7},
    )

    param = convert_to_openai_message(message)

    assert param == {"role": "tool", "content": "42", "tool_call_id": "call_abc"}


ASSISTANT_MESSAGE = {
    "role": "assistant",
    "content": "ok",
    "tool_calls": None,
    "refusal": None,
}
USAGE = {"prompt_tokens": 1, "completion_tokens": 2, "total_tokens": 3}

OMITTED = object()
"""Sentinel for a response whose choice carries no finish_reason field at all,
which is distinct from one carrying an explicit null."""


def _connection(
    finish_reason: object, usage: dict | None = None
) -> OpenAIChatModelConnection:
    """Build a connection whose stubbed transport returns one real completion.

    The transport is a mock but the payload is a genuine ``ChatCompletion``, so
    the assertions exercise the SDK's own attribute access rather than values a
    mock was told to return.

    Parameters
    ----------
    finish_reason : object
        Value for the choice's ``finish_reason``. Pass ``OMITTED`` to leave the
        field out of the payload entirely. Required, so a caller cannot omit it
        by accident and assert against an unintended shape.
    usage : dict | None
        Token usage block, or None to build a response carrying none.
    """
    choice: dict = {"index": 0, "message": ASSISTANT_MESSAGE}
    if finish_reason is not OMITTED:
        choice["finish_reason"] = finish_reason
    payload: dict = {
        "id": "chatcmpl-test",
        "object": "chat.completion",
        "created": 0,
        "model": "gpt-4o",
        "choices": [choice],
    }
    if usage is not None:
        payload["usage"] = usage

    conn = OpenAIChatModelConnection(
        api_key="test-key", api_base_url="http://localhost"
    )
    mock_client = MagicMock()
    mock_client.chat.completions.create.return_value = ChatCompletion.construct(
        **payload
    )
    conn._client = mock_client
    return conn


def _chat(conn: OpenAIChatModelConnection) -> ChatMessage:
    return conn.chat([ChatMessage(role=MessageRole.USER, content="hi")], model="gpt-4o")


def test_chat_records_finish_reason_in_extra_args() -> None:
    """The finish reason survives alongside the token metrics."""
    result = _chat(_connection("length", usage=USAGE))

    assert result.extra_args["promptTokens"] == 1
    assert result.extra_args["finish_reason"] == "length"


def test_chat_records_finish_reason_when_usage_is_absent() -> None:
    """The finish reason is captured independently of the token metrics.

    promptTokens is asserted absent to confirm the metrics branch did not run,
    so the finish reason cannot have been recorded by it.
    """
    result = _chat(_connection("tool_calls"))

    assert "promptTokens" not in result.extra_args
    assert result.extra_args["finish_reason"] == "tool_calls"


def test_chat_records_unrecognized_finish_reason_verbatim() -> None:
    """A finish reason outside the documented set is stored as received."""
    result = _chat(_connection("some_vendor_reason", usage=USAGE))

    assert result.extra_args["finish_reason"] == "some_vendor_reason"


def test_chat_records_empty_finish_reason() -> None:
    """An empty finish reason is recorded rather than discarded."""
    # The capture turns on the value being present, not on it being non-empty,
    # so an empty reason reaches extra_args like any other string.
    result = _chat(_connection("", usage=USAGE))

    assert result.extra_args["finish_reason"] == ""


def test_chat_omits_finish_reason_when_response_has_none() -> None:
    """A response whose choice carries no finish reason yields no key."""
    result = _chat(_connection(OMITTED, usage=USAGE))

    assert "finish_reason" not in result.extra_args
