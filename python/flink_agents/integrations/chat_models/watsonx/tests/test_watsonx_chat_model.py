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
import os
from typing import Any, Dict
from unittest.mock import MagicMock

import pytest

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.resource_context import ResourceContext
from flink_agents.integrations.chat_models.watsonx.watsonx_chat_model import (
    DEFAULT_MODEL,
    WatsonxChatModelConnection,
    WatsonxChatModelSetup,
    convert_to_watsonx_messages,
)

test_model = os.environ.get("WATSONX_CHAT_MODEL", DEFAULT_MODEL)
credentials_available = (
    "WATSONX_URL" in os.environ
    and ("WATSONX_API_KEY" in os.environ or "WATSONX_TOKEN" in os.environ)
) and ("WATSONX_PROJECT_ID" in os.environ or "WATSONX_SPACE_ID" in os.environ)


def _fake_connection(**kwargs: Any) -> WatsonxChatModelConnection:
    """Create a connection with fake credentials for offline tests."""
    return WatsonxChatModelConnection(
        name="watsonx",
        url=kwargs.pop("url", "https://us-south.ml.cloud.ibm.com"),
        api_key=kwargs.pop("api_key", "fake-key"),
        project_id=kwargs.pop("project_id", "fake-project"),
        **kwargs,
    )


@pytest.mark.integration
@pytest.mark.skipif(
    not credentials_available, reason="watsonx.ai credentials are not set"
)
def test_watsonx_chat() -> None:
    """Test basic chat functionality of WatsonxChatModelConnection."""
    connection = WatsonxChatModelConnection(name="watsonx")
    response = connection.chat(
        [ChatMessage(role=MessageRole.USER, content="Hello!")], model=test_model
    )
    assert response is not None
    assert response.content is not None
    assert response.content.strip() != ""
    assert response.role == MessageRole.ASSISTANT


def _mock_chat_response(
    message: Dict[str, Any], finish_reason: str = "stop"
) -> Dict[str, Any]:
    return {
        "id": "chatcmpl-1",
        "model_id": test_model,
        "choices": [{"index": 0, "message": message, "finish_reason": finish_reason}],
        "usage": {
            "prompt_tokens": 100,
            "completion_tokens": 50,
            "total_tokens": 150,
        },
    }


def test_watsonx_chat_mocked(monkeypatch: pytest.MonkeyPatch) -> None:
    """Test chat response handling and params passing (mock watsonx client)."""
    mock_model = MagicMock()
    mock_model.chat.return_value = _mock_chat_response(
        {"role": "assistant", "content": "Hello there!"}
    )
    monkeypatch.setattr(
        WatsonxChatModelConnection, "_get_model", lambda self, model: mock_model
    )

    connection = _fake_connection()

    def get_resource(name: str, type: ResourceType) -> Resource:
        return connection

    mock_ctx = MagicMock(spec=ResourceContext)
    mock_ctx.get_resource = get_resource

    llm = WatsonxChatModelSetup(
        name="watsonx",
        model=test_model,
        connection="watsonx",
        temperature=0.5,
        max_tokens=256,
        additional_kwargs={"top_p": 0.9},
        resource_context=mock_ctx,
    )

    llm.open()

    response = llm.chat([ChatMessage(role=MessageRole.USER, content="Hello!")])

    mock_model.chat.assert_called_once()
    call_kwargs = mock_model.chat.call_args.kwargs
    assert call_kwargs["messages"] == [{"role": "user", "content": "Hello!"}]
    assert call_kwargs["params"] == {
        "temperature": 0.5,
        "max_tokens": 256,
        "top_p": 0.9,
    }

    assert response.role == MessageRole.ASSISTANT
    assert response.content == "Hello there!"
    assert response.extra_args["model_name"] == test_model
    assert response.extra_args["promptTokens"] == 100
    assert response.extra_args["completionTokens"] == 50


def test_watsonx_tool_call_response_mocked(monkeypatch: pytest.MonkeyPatch) -> None:
    """Test that tool call responses are converted to the framework format."""
    mock_model = MagicMock()
    mock_model.chat.return_value = _mock_chat_response(
        {
            "role": "assistant",
            "tool_calls": [
                {
                    "id": "call_abc123",
                    "type": "function",
                    "function": {"name": "add", "arguments": '{"a": 1, "b": 2}'},
                }
            ],
        }
    )
    monkeypatch.setattr(
        WatsonxChatModelConnection, "_get_model", lambda self, model: mock_model
    )

    connection = _fake_connection()
    response = connection.chat(
        [ChatMessage(role=MessageRole.USER, content="What is 1 + 2?")],
        model=test_model,
    )

    assert len(response.tool_calls) == 1
    tool_call = response.tool_calls[0]
    assert tool_call["function"]["name"] == "add"
    assert tool_call["function"]["arguments"] == {"a": 1, "b": 2}
    assert tool_call["original_id"] == "call_abc123"


def test_chat_retries_transient_failures(monkeypatch: pytest.MonkeyPatch) -> None:
    """Transient HTTP failures are retried up to max_retries, then succeed."""
    import httpx
    from ibm_watsonx_ai.wml_client_error import ApiRequestFailure

    request = httpx.Request("POST", "https://test.invalid/ml/v1/text/chat")
    rate_limited = ApiRequestFailure(
        "rate limited",
        httpx.Response(
            429,
            text="too many requests",
            headers={"Retry-After": "5"},
            request=request,
        ),
    )
    mock_model = MagicMock()
    mock_model.chat.side_effect = [
        rate_limited,
        rate_limited,
        _mock_chat_response({"role": "assistant", "content": "Recovered!"}),
    ]
    monkeypatch.setattr(
        WatsonxChatModelConnection, "_get_model", lambda self, model: mock_model
    )
    sleep = MagicMock()
    monkeypatch.setattr(
        "flink_agents.integrations.chat_models.watsonx.watsonx_chat_model.time.sleep",
        sleep,
    )

    connection = _fake_connection(max_retries=3)
    response = connection.chat(
        [ChatMessage(role=MessageRole.USER, content="Hello!")], model=test_model
    )

    assert response.content == "Recovered!"
    assert mock_model.chat.call_count == 3
    assert [call.args[0] for call in sleep.call_args_list] == [5, 5]

    # Non-retryable failures propagate immediately.
    unauthorized = ApiRequestFailure(
        "bad key", httpx.Response(401, text="unauthorized", request=request)
    )
    mock_model.chat.side_effect = [unauthorized]
    mock_model.chat.reset_mock()
    with pytest.raises(ApiRequestFailure):
        connection.chat(
            [ChatMessage(role=MessageRole.USER, content="Hello!")], model=test_model
        )
    assert mock_model.chat.call_count == 1


def test_connection_close() -> None:
    """close() releases the HTTP client and cached models without errors."""
    connection = _fake_connection()
    connection.close()  # closing before any request is a no-op

    connection._http_client = MagicMock()
    http_client = connection._http_client
    connection.close()
    http_client.close.assert_called_once()
    assert connection._http_client is None
    assert connection._models == {}


def test_parse_tool_arguments_messy_formats() -> None:
    """Tool arguments in messy model-emitted formats are parsed into a dict."""
    from flink_agents.integrations.chat_models.watsonx.watsonx_chat_model import (
        _parse_tool_arguments,
    )

    assert _parse_tool_arguments('{"a": 1, "b": 2}') == {"a": 1, "b": 2}
    # double-encoded JSON string
    assert _parse_tool_arguments('"{\\"a\\": 1}"') == {"a": 1}
    # single-quoted / Python-literal style dict
    assert _parse_tool_arguments("{'a': 17, 'b': 25}") == {"a": 17, "b": 25}
    # already a dict
    assert _parse_tool_arguments({"a": 1}) == {"a": 1}
    # missing or empty -> empty dict
    assert _parse_tool_arguments(None) == {}
    assert _parse_tool_arguments("") == {}
    # garbage -> descriptive error carrying the raw value
    with pytest.raises(TypeError, match="not json at all"):
        _parse_tool_arguments("not json at all")


def test_convert_to_watsonx_messages_round_trip() -> None:
    """Test conversion of assistant tool calls and tool results to watsonx format."""
    messages = [
        ChatMessage(role=MessageRole.SYSTEM, content="You are helpful."),
        ChatMessage(role=MessageRole.USER, content="What is 1 + 2?"),
        ChatMessage(
            role=MessageRole.ASSISTANT,
            tool_calls=[
                {
                    "id": "internal-id",
                    "type": "function",
                    "function": {"name": "add", "arguments": {"a": 1, "b": 2}},
                    "original_id": "call_abc123",
                }
            ],
        ),
        ChatMessage(
            role=MessageRole.TOOL,
            content="3",
            extra_args={"external_id": "call_abc123"},
        ),
    ]

    converted = convert_to_watsonx_messages(messages)

    assert converted[0] == {"role": "system", "content": "You are helpful."}
    assert converted[1] == {"role": "user", "content": "What is 1 + 2?"}
    assert converted[2] == {
        "role": "assistant",
        "tool_calls": [
            {
                "id": "call_abc123",
                "type": "function",
                "function": {"name": "add", "arguments": '{"a": 1, "b": 2}'},
            }
        ],
    }
    assert converted[3] == {
        "role": "tool",
        "content": "3",
        "tool_call_id": "call_abc123",
    }


def test_configuration_contract(monkeypatch: pytest.MonkeyPatch) -> None:
    """Validate required connection fields, scoping, timeout, and defaults."""
    for var in (
        "WATSONX_URL",
        "WATSONX_API_KEY",
        "WATSONX_TOKEN",
        "WATSONX_PROJECT_ID",
        "WATSONX_SPACE_ID",
    ):
        monkeypatch.delenv(var, raising=False)

    with pytest.raises(ValueError, match="url"):
        WatsonxChatModelConnection(name="watsonx")

    with pytest.raises(ValueError, match="credentials"):
        WatsonxChatModelConnection(
            name="watsonx", url="https://us-south.ml.cloud.ibm.com"
        )

    with pytest.raises(ValueError, match="project or space"):
        WatsonxChatModelConnection(
            name="watsonx",
            url="https://us-south.ml.cloud.ibm.com",
            api_key="fake-key",
        )

    connection = WatsonxChatModelConnection(
        name="watsonx",
        url=" https://us-south.ml.cloud.ibm.com ",
        api_key=" fake-key ",
        space_id=" fake-space ",
    )

    assert connection.url == "https://us-south.ml.cloud.ibm.com"
    assert connection.api_key == "fake-key"
    assert connection.project_id is None
    assert connection.space_id == "fake-space"

    with pytest.raises(ValueError, match=r"cannot both be provided.*exactly one"):
        WatsonxChatModelConnection(
            name="watsonx",
            url="https://us-south.ml.cloud.ibm.com",
            api_key="fake-key",
            project_id="fake-project",
            space_id="fake-space",
        )

    with pytest.raises(ValueError, match=r"api_key and token.*exactly one"):
        WatsonxChatModelConnection(
            name="watsonx",
            url=" https://us-south.ml.cloud.ibm.com ",
            api_key=" fake-key ",
            token=" fake-token ",
            project_id=" fake-project ",
        )

    for request_timeout in (0, -1, float("nan"), float("inf")):
        with pytest.raises(ValueError, match="request_timeout"):
            _fake_connection(request_timeout=request_timeout)

    assert WatsonxChatModelSetup(connection="conn").model == DEFAULT_MODEL

    with pytest.raises(ValueError, match="additional_kwargs"):
        _fake_connection().chat(
            [ChatMessage(role=MessageRole.USER, content="Hello!")],
            model=test_model,
            additional_kwargs={"temperature": 5.0},
        )
