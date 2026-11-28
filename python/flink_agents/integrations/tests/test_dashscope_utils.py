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
from types import SimpleNamespace
from typing import TYPE_CHECKING, Any
from unittest.mock import Mock

import httpx
import pytest
from dashscope.errors import APIConnectionError, APITimeoutError, DashScopeError

from flink_agents.api.chat_message import (
    ChatMessage,
    ImageBlock,
    UnsupportedContentBlockError,
)
from flink_agents.api.resource import ResourceDescriptor
from flink_agents.integrations import dashscope_utils
from flink_agents.integrations.chat_models.dashscope_chat_model import (
    DashScopeChatModelConnection,
)
from flink_agents.integrations.dashscope_utils import (
    media_source_url,
    merge_model_kwargs,
    usage_counters,
)
from flink_agents.integrations.embedding_models.dashscope_embedding_model import (
    DashScopeEmbeddingModelConnection,
)
from flink_agents.integrations.tests.dashscope_http import (
    record_dashscope_requests as record_dashscope_requests,
)
from flink_agents.plan.resource_provider import PythonResourceProvider
from flink_agents.runtime.resource_cache import ResourceCache

if TYPE_CHECKING:
    from dashscope import Client


def test_usage_counters_keep_scalar_fields_and_drop_bools() -> None:
    usage = SimpleNamespace(
        model_dump=lambda exclude_unset, exclude_none: {
            "total_tokens": 3,
            "cached_tokens": 0,
            "enabled": True,
            "nan_value": float("nan"),
            "inf_value": float("inf"),
            "details": {"cached": 1},
        }
    )

    assert usage_counters(usage) == {"total_tokens": 3, "cached_tokens": 0}
    assert usage_counters({"duration": 0, "ratio": "16:9"}) == {
        "duration": 0,
        "ratio": "16:9",
    }
    assert usage_counters(None) == {}


def test_merge_model_kwargs_drops_none_and_applies_additional_kwargs_overrides() -> (
    None
):
    merged = merge_model_kwargs(
        {"model": "qwen-image", "n": None, "seed": 7},
        {"seed": 42, "extra": "x"},
    )
    assert merged == {"model": "qwen-image", "seed": 42, "extra": "x"}


def test_media_source_url_enforces_allowed_schemes() -> None:
    assert (
        media_source_url(ImageBlock.from_url("image/png", "http://example.test/a.png"))
        == "http://example.test/a.png"
    )
    assert (
        media_source_url(ImageBlock.from_url("image/png", "https://example.test/a.png"))
        == "https://example.test/a.png"
    )
    assert (
        media_source_url(ImageBlock.from_base64("image/png", "YQ=="))
        == "data:image/png;base64,YQ=="
    )

    for bad_url in (
        "file:///etc/passwd",
        "/etc/passwd",
        "oss://dashscope-instant/demo.png",
    ):
        with pytest.raises(UnsupportedContentBlockError):
            media_source_url(ImageBlock.from_url("image/png", bad_url))

    assert (
        media_source_url(
            ImageBlock.from_url("image/png", "oss://dashscope-instant/demo.pdf"),
            allowed_schemes=("http", "https", "oss"),
        )
        == "oss://dashscope-instant/demo.pdf"
    )
    for local_url in ("file:///etc/passwd", "/etc/passwd"):
        with pytest.raises(UnsupportedContentBlockError):
            media_source_url(
                ImageBlock.from_url("image/png", local_url),
                allowed_schemes=("http", "https", "oss"),
            )


@pytest.mark.parametrize("managed", [True, False], ids=["resource-cache", "direct"])
@pytest.mark.parametrize(
    "connection_type",
    [
        DashScopeChatModelConnection,
        DashScopeEmbeddingModelConnection,
    ],
)
def test_dashscope_connection_lifetime(
    managed, connection_type, monkeypatch: pytest.MonkeyPatch, tmp_path, http_calls
) -> None:
    """Exercise lazy acquisition, pooling and release through the public entry points."""
    monkeypatch.setenv("DASHSCOPE_BASE_URL", "https://default.example/api/v1")
    monkeypatch.setenv("DASHSCOPE_REGION", "us-east-1")
    monkeypatch.setenv("DASHSCOPE_WORKSPACE", "ws-env")
    monkeypatch.setenv("DASHSCOPE_PROTOCOL", "openai")
    config_file = tmp_path / "config.toml"
    config_file.write_text("[invalid TOML", encoding="utf-8")
    monkeypatch.setenv("DASHSCOPE_CONFIG", str(config_file))
    clients = []
    create = dashscope_utils.create_client

    def capture_client(**options: Any) -> "Client":
        client = create(**options)
        clients.append(client)
        return client

    monkeypatch.setattr(dashscope_utils, "create_client", capture_client)
    options = {"api_key": "test-key"}
    if managed:
        options.update(base_url="https://explicit.example/api/v1", workspace="ws-FA")
        descriptor = ResourceDescriptor(
            target_module=connection_type.__module__,
            target_clazz=connection_type.__name__,
            arguments=options,
        )
        provider = PythonResourceProvider.get("connection", descriptor)
        cache = ResourceCache({provider.type: {provider.name: provider}})
        connection = cache.get_resource(provider.name, provider.type)
        assert cache.get_resource(provider.name, provider.type) is connection
        owner = cache
    else:
        connection = connection_type(**options)
        owner = connection
    assert options["api_key"] not in repr(connection)
    requests_sent, responses = http_calls
    assert clients == []
    try:
        for _ in range(2):
            if connection_type is DashScopeChatModelConnection:
                responses.append(
                    httpx.Response(
                        200,
                        json={
                            "output": {
                                "choices": [
                                    {"message": {"role": "assistant", "content": "ok"}}
                                ]
                            }
                        },
                    )
                )
                assert connection.chat([ChatMessage.user("hi")]).text == "ok"
            else:
                responses.append(
                    httpx.Response(
                        200, json={"output": {"embeddings": [{"embedding": [0.5]}]}}
                    )
                )
                assert connection.embed("hi") == [0.5]
        (client,) = clients
        assert client.config.region == "cn-beijing"
        assert client.config.protocol == "native"
        for request in requests_sent:
            assert request.url.host == (
                "explicit.example" if managed else "default.example"
            )
            assert request.headers["X-DashScope-WorkSpace"] == (
                "ws-FA" if managed else "ws-env"
            )
        assert len(requests_sent) == 2
        close_client = Mock(wraps=client.close)
        monkeypatch.setattr(client, "close", close_client)
    finally:
        owner.close()
    owner.close()
    close_client.assert_called_once_with()


@pytest.mark.parametrize(
    ("error", "expected"),
    [
        (httpx.ConnectError("connection refused"), APIConnectionError),
        (httpx.ReadTimeout("read timed out"), APITimeoutError),
        (httpx.ConnectTimeout("connect timed out"), APITimeoutError),
    ],
)
@pytest.mark.parametrize(
    "connection_type", [DashScopeChatModelConnection, DashScopeEmbeddingModelConnection]
)
def test_dashscope_native_http_failure_is_not_retried(
    error, expected, connection_type, monkeypatch: pytest.MonkeyPatch
) -> None:
    send = Mock(side_effect=error)
    monkeypatch.setattr(httpx.HTTPTransport, "handle_request", send)
    connection = connection_type(api_key="test-key", workspace="ws-FA")
    try:
        if isinstance(connection, DashScopeChatModelConnection):
            invoke, payload = connection.chat, [ChatMessage.user("hi")]
        else:
            invoke, payload = connection.embed, "hi"
        with pytest.raises(expected) as exc_info:
            invoke(payload)
        send.assert_called_once()
        assert exc_info.value.__cause__ is error
    finally:
        connection.close()


@pytest.mark.parametrize(
    "connection_type", [DashScopeChatModelConnection, DashScopeEmbeddingModelConnection]
)
def test_dashscope_unknown_service_error_preserves_message_and_cause(
    connection_type, http_calls
) -> None:
    requests_sent, responses = http_calls
    responses.append(
        httpx.Response(
            200,
            json={
                "code": "AllocateQuotaFailed",
                "message": "Quota unavailable",
                "request_id": "req-1",
            },
        )
    )
    connection = connection_type(api_key="test-key")
    try:
        if isinstance(connection, DashScopeChatModelConnection):
            invoke, payload = connection.chat, [ChatMessage.user("Hello")]
        else:
            invoke, payload = connection.embed, "Hello"
        with pytest.raises(RuntimeError, match="Quota unavailable") as err:
            invoke(payload)
        assert isinstance(err.value.__cause__, DashScopeError)
        assert err.value.__cause__.code == "AllocateQuotaFailed"
        assert err.value.__cause__.request_id == "req-1"
        assert len(requests_sent) == 1
    finally:
        connection.close()


@pytest.mark.parametrize(
    "connection_type", [DashScopeChatModelConnection, DashScopeEmbeddingModelConnection]
)
def test_dashscope_credentials_and_positional_timeout(
    connection_type, monkeypatch
) -> None:
    monkeypatch.setenv("DASHSCOPE_API_KEY", "from-env")
    assert connection_type().api_key == "from-env"
    connection = connection_type("explicit-key", 3.5)
    assert connection.api_key == "explicit-key"
    assert connection.request_timeout == 3.5


@pytest.mark.parametrize(
    "connection_type", [DashScopeChatModelConnection, DashScopeEmbeddingModelConnection]
)
@pytest.mark.parametrize("timeout", [0, -1.0, float("inf"), float("nan")])
def test_invalid_request_timeout_fails_before_request(
    connection_type, timeout, http_calls
) -> None:
    with pytest.raises(ValueError, match="request_timeout"):
        connection_type("test-key", timeout)
    connection = connection_type("test-key")
    if isinstance(connection, DashScopeChatModelConnection):
        invoke, payload = connection.chat, [ChatMessage.user("Hello")]
    else:
        invoke, payload = connection.embed, "Hello"
    with pytest.raises(ValueError, match="request_timeout"):
        invoke(payload, request_timeout=timeout)
    assert not http_calls[0]
