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
import math
import os
from unittest.mock import MagicMock

import httpx
import pytest
from pydantic import ValidationError

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.resource_context import ResourceContext
from flink_agents.integrations.chat_models.openai.openai_chat_model import (
    DEFAULT_OPENAI_MODEL,
    MAX_OPENAI_RETRIES,
    MAX_OPENAI_TIMEOUT_SECONDS,
    OpenAIChatModelConnection,
    OpenAIChatModelSetup,
)
from flink_agents.plan.function import PythonFunction
from flink_agents.plan.tools.function_tool import FunctionTool

pytestmark = pytest.mark.integration

test_model = os.environ.get("TEST_MODEL")
api_key = os.environ.get("TEST_API_KEY")
api_base_url = os.environ.get("TEST_API_BASE_URL")


@pytest.mark.skipif(api_key is None, reason="TEST_API_KEY is not set")
def test_openai_chat_model() -> None:
    connection = OpenAIChatModelConnection(api_key=api_key, api_base_url=api_base_url)

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.CHAT_MODEL_CONNECTION:
            return connection
        else:
            return get_resource(name, ResourceType.TOOL)

    mock_ctx = MagicMock(spec=ResourceContext)
    mock_ctx.get_resource = get_resource

    chat_model = OpenAIChatModelSetup(
        model=test_model, connection="openai", resource_context=mock_ctx
    )
    response = chat_model.chat([ChatMessage.of(MessageRole.USER, "Hello!")])
    assert response is not None
    assert str(response).strip() != ""


def add(a: int, b: int) -> int:
    """Calculate the sum of a and b.

    Parameters
    ----------
    a : int
        The first operand
    b : int
        The second operand

    Returns:
    -------
    int:
        The sum of a and b
    """
    return a + b


@pytest.mark.skipif(api_key is None, reason="TEST_API_KEY is not set")
def test_openai_chat_with_tools() -> None:
    connection = OpenAIChatModelConnection(api_key=api_key, api_base_url=api_base_url)

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.CHAT_MODEL_CONNECTION:
            return connection
        else:
            return FunctionTool(func=PythonFunction.from_callable(add))

    mock_ctx = MagicMock(spec=ResourceContext)
    mock_ctx.get_resource = get_resource

    chat_model = OpenAIChatModelSetup(
        model=test_model,
        connection="openai",
        tools=["add"],
        resource_context=mock_ctx,
    )
    response = chat_model.chat(
        [ChatMessage.of(MessageRole.USER, "What is 377 + 688?")]
    )
    tool_calls = response.tool_calls
    assert len(tool_calls) == 1
    tool_call = tool_calls[0]
    assert add(**tool_call["function"]["arguments"]) == 1065


def test_model_field_roundtrip() -> None:
    """Verify `model` is preserved through pydantic dump/validate round-trip."""
    setup = OpenAIChatModelSetup(connection="conn", model="test-model")
    restored = OpenAIChatModelSetup.model_validate(setup.model_dump())
    assert restored.model == "test-model"


def test_default_model_when_omitted() -> None:
    """Verify per-integration default applies when `model` is omitted from __init__."""
    setup = OpenAIChatModelSetup(connection="conn")
    assert setup.model == DEFAULT_OPENAI_MODEL


def test_connection_default_timeout_and_max_retries() -> None:
    """Pin canonical connection defaults to prevent silent drift."""
    conn = OpenAIChatModelConnection(api_key="fake", api_base_url="http://localhost")
    assert conn.timeout == 60.0
    assert conn.max_retries == 3


def test_zero_timeout_disables_client_timeout() -> None:
    """Keep zero-timeout semantics aligned with the Java OpenAI SDK."""
    conn = OpenAIChatModelConnection(
        api_key="fake", api_base_url="http://localhost", timeout=0
    )

    assert conn.client.timeout is None
    # openai>=3 wraps timeouts in its own Timeout class, so compare
    # components instead of instances.
    transport_timeout = conn.client._client.timeout
    assert transport_timeout.connect is None
    assert transport_timeout.read is None
    assert transport_timeout.write is None
    assert transport_timeout.pool is None


def test_zero_timeout_disables_custom_http_client_timeout() -> None:
    http_client = httpx.Client(timeout=10.0)
    conn = OpenAIChatModelConnection(
        api_key="fake",
        api_base_url="http://localhost",
        timeout=0,
        http_client=http_client,
    )

    assert conn.client.timeout is None
    assert http_client.timeout == httpx.Timeout(None)

    http_client.close()


@pytest.mark.parametrize("timeout", [math.nan, math.inf])
def test_connection_rejects_non_finite_timeout(timeout: float) -> None:
    with pytest.raises(ValidationError, match="finite"):
        OpenAIChatModelConnection(
            api_key="fake",
            api_base_url="http://localhost",
            timeout=timeout,
        )


@pytest.mark.parametrize(
    ("argument", "value"),
    [
        ("timeout", MAX_OPENAI_TIMEOUT_SECONDS + 0.001),
        ("max_retries", MAX_OPENAI_RETRIES + 1),
    ],
)
def test_connection_rejects_values_beyond_java_sdk_limits(
    argument: str, value: float | int
) -> None:
    with pytest.raises(ValidationError, match="less than or equal"):
        OpenAIChatModelConnection(
            api_key="fake",
            api_base_url="http://localhost",
            **{argument: value},
        )
