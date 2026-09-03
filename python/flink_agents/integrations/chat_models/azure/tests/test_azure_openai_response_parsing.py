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

from openai.types.chat import ChatCompletion

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.integrations.chat_models.azure.azure_openai_chat_model import (
    AzureOpenAIChatModelConnection,
)

DEPLOYMENT = "my-deployment"

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
) -> AzureOpenAIChatModelConnection:
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
        "model": DEPLOYMENT,
        "choices": [choice],
    }
    if usage is not None:
        payload["usage"] = usage

    conn = AzureOpenAIChatModelConnection(
        api_key="test-key",
        azure_endpoint="https://example.openai.azure.com",
        api_version="2024-08-01-preview",
    )
    mock_client = MagicMock()
    mock_client.chat.completions.create.return_value = ChatCompletion.construct(
        **payload
    )
    conn._client = mock_client
    return conn


def _chat(conn: AzureOpenAIChatModelConnection, **kwargs: object) -> ChatMessage:
    return conn.chat(
        [ChatMessage(role=MessageRole.USER, content="hi")], model=DEPLOYMENT, **kwargs
    )


def test_chat_records_finish_reason_in_extra_args() -> None:
    """The finish reason survives alongside the token metrics."""
    result = _chat(
        _connection("length", usage=USAGE), model_of_azure_deployment="gpt-4o"
    )

    assert result.extra_args["promptTokens"] == 1
    assert result.extra_args["finish_reason"] == "length"


def test_chat_records_finish_reason_without_usage_or_deployment_model() -> None:
    """The finish reason is captured independently of the token metrics.

    The metrics branch needs both model_of_azure_deployment and a usage block;
    neither is supplied here. promptTokens is asserted absent to confirm that
    branch did not run, so the finish reason cannot have been recorded by it.
    """
    result = _chat(_connection("tool_calls"))

    assert "promptTokens" not in result.extra_args
    assert result.extra_args["finish_reason"] == "tool_calls"


def test_chat_records_unrecognized_finish_reason_verbatim() -> None:
    """A finish reason outside the documented set is stored as received."""
    result = _chat(
        _connection("some_vendor_reason", usage=USAGE),
        model_of_azure_deployment="gpt-4o",
    )

    assert result.extra_args["finish_reason"] == "some_vendor_reason"


def test_chat_records_empty_finish_reason() -> None:
    """An empty finish reason is recorded rather than discarded."""
    # The capture turns on the value being present, not on it being non-empty,
    # so an empty reason reaches extra_args like any other string.
    result = _chat(_connection("", usage=USAGE), model_of_azure_deployment="gpt-4o")

    assert result.extra_args["finish_reason"] == ""


def test_chat_omits_finish_reason_when_response_has_none() -> None:
    """A response whose choice carries no finish reason yields no key."""
    result = _chat(
        _connection(OMITTED, usage=USAGE), model_of_azure_deployment="gpt-4o"
    )

    assert "finish_reason" not in result.extra_args
