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

import pytest
from pydantic import ValidationError

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.resource_context import ResourceContext
from flink_agents.integrations.chat_models.azure.azure_openai_chat_model import (
    MAX_OPENAI_RETRIES,
    MAX_OPENAI_TIMEOUT_SECONDS,
    AzureOpenAIChatModelConnection,
    AzureOpenAIChatModelSetup,
)
from flink_agents.plan.function import PythonFunction
from flink_agents.plan.tools.function_tool import FunctionTool

pytestmark = pytest.mark.integration

test_deployment = os.environ.get("TEST_AZURE_DEPLOYMENT")
api_key = os.environ.get("AZURE_OPENAI_API_KEY")
azure_endpoint = os.environ.get("AZURE_OPENAI_ENDPOINT")
api_version = os.environ.get("AZURE_OPENAI_API_VERSION")


@pytest.mark.skipif(api_key is None, reason="AZURE_OPENAI_API_KEY is not set")
def test_azure_openai_chat_model() -> None:
    connection = AzureOpenAIChatModelConnection(
        api_key=api_key,
        azure_endpoint=azure_endpoint,
        api_version=api_version,
    )

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.CHAT_MODEL_CONNECTION:
            return connection
        else:
            return get_resource(name, ResourceType.TOOL)

    mock_ctx = MagicMock(spec=ResourceContext)
    mock_ctx.get_resource = get_resource

    chat_model = AzureOpenAIChatModelSetup(
        model=test_deployment,
        connection="azure_openai",
        resource_context=mock_ctx,
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


@pytest.mark.skipif(api_key is None, reason="AZURE_OPENAI_API_KEY is not set")
def test_azure_openai_chat_with_tools() -> None:
    connection = AzureOpenAIChatModelConnection(
        api_key=api_key,
        azure_endpoint=azure_endpoint,
        api_version=api_version,
    )

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.CHAT_MODEL_CONNECTION:
            return connection
        else:
            return FunctionTool(func=PythonFunction.from_callable(add))

    mock_ctx = MagicMock(spec=ResourceContext)
    mock_ctx.get_resource = get_resource

    chat_model = AzureOpenAIChatModelSetup(
        model=test_deployment,
        connection="azure_openai",
        tools=["add"],
        resource_context=mock_ctx,
    )
    response = chat_model.chat(
        [
            ChatMessage.of(MessageRole.USER, "You MUST use the add tool to calculate: What is 377 + 688?",
            )
        ]
    )
    tool_calls = response.tool_calls
    assert len(tool_calls) == 1
    tool_call = tool_calls[0]
    assert add(**tool_call["function"]["arguments"]) == 1065


def test_model_field_roundtrip() -> None:
    """Verify `model` is preserved through pydantic dump/validate round-trip."""
    setup = AzureOpenAIChatModelSetup(connection="conn", model="test-deployment")
    restored = AzureOpenAIChatModelSetup.model_validate(setup.model_dump())
    assert restored.model == "test-deployment"


def test_zero_timeout_disables_client_timeout() -> None:
    """Keep zero-timeout semantics aligned with the Java OpenAI SDK."""
    conn = AzureOpenAIChatModelConnection(
        api_key="fake-key",
        azure_endpoint="https://example.openai.azure.com",
        api_version="2024-02-01",
        timeout=0,
    )

    assert conn.client.timeout is None
    # openai>=3 wraps timeouts in its own Timeout class, so compare
    # components instead of instances.
    transport_timeout = conn.client._client.timeout
    assert transport_timeout.connect is None
    assert transport_timeout.read is None
    assert transport_timeout.write is None
    assert transport_timeout.pool is None


@pytest.mark.parametrize("timeout", [math.nan, math.inf])
def test_connection_rejects_non_finite_timeout(timeout: float) -> None:
    with pytest.raises(ValidationError, match="finite"):
        AzureOpenAIChatModelConnection(
            api_key="fake-key",
            azure_endpoint="https://example.openai.azure.com",
            api_version="2024-02-01",
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
        AzureOpenAIChatModelConnection(
            api_key="fake-key",
            azure_endpoint="https://example.openai.azure.com",
            api_version="2024-02-01",
            **{argument: value},
        )


def test_model_kwargs_nests_additional_kwargs() -> None:
    """`additional_kwargs` is nested under its own key, not flattened.

    Flattening would allow a colliding key (e.g. `temperature`) in
    `additional_kwargs` to silently overwrite the field-validated value.
    """
    setup = AzureOpenAIChatModelSetup(
        connection="conn",
        model="my-deployment",
        additional_kwargs={"seed": 42, "user": "user-123"},
    )
    kwargs = setup.model_kwargs
    assert kwargs["model"] == "my-deployment"
    assert kwargs["additional_kwargs"] == {"seed": 42, "user": "user-123"}
    assert "seed" not in kwargs
    assert "user" not in kwargs


def test_chat_rejects_reserved_key_in_additional_kwargs() -> None:
    """`additional_kwargs` containing a reserved typed key must raise.

    Without this check, `**kwargs, **additional_kwargs` would raise an opaque
    TypeError, and (worse) leaves the door open for callers to bypass the
    field-level validation on `temperature`, `max_tokens`, etc.
    """
    connection = AzureOpenAIChatModelConnection(
        api_key="fake-key",
        azure_endpoint="https://example.openai.azure.com",
        api_version="2024-02-01",
    )
    with pytest.raises(ValueError, match="additional_kwargs"):
        connection.chat(
            messages=[ChatMessage.of(MessageRole.USER, "hi")],
            model="my-deployment",
            temperature=0.3,
            additional_kwargs={"temperature": 5.0},
        )
