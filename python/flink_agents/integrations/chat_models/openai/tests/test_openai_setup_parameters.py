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
from openai.types.chat import ChatCompletion
from pydantic import ValidationError

from flink_agents.api.chat_message import ChatMessage
from flink_agents.integrations.chat_models.openai.openai_chat_model import (
    OpenAIChatModelConnection,
    OpenAIChatModelSetup,
)
from flink_agents.plan.function import PythonFunction
from flink_agents.plan.tools.function_tool import FunctionTool


def add(a: int, b: int) -> int:
    """Calculate the sum of a and b."""
    return a + b


def _request(setup: OpenAIChatModelSetup) -> dict:
    """Send one chat with the setup's parameters and return the request kwargs."""
    conn = OpenAIChatModelConnection(
        api_key="test-key", api_base_url="http://localhost"
    )
    client = MagicMock()
    client.chat.completions.create.return_value = ChatCompletion.construct(
        id="chatcmpl-test",
        object="chat.completion",
        created=0,
        model="gpt-4o",
        choices=[
            {
                "index": 0,
                "finish_reason": "stop",
                "message": {"role": "assistant", "content": "ok"},
            }
        ],
    )
    conn._client = client
    conn.chat(
        [ChatMessage.user("hi")],
        tools=[FunctionTool(func=PythonFunction.from_callable(add))],
        **setup.model_kwargs,
    )
    return client.chat.completions.create.call_args.kwargs


def test_strict_reaches_tool_definitions() -> None:
    request = _request(OpenAIChatModelSetup(connection="conn", strict=True))

    assert request["tools"][0]["function"]["strict"] is True
    assert "strict" not in request


def test_reasoning_effort_reaches_request() -> None:
    setup = OpenAIChatModelSetup(connection="conn", reasoning_effort="high")

    request = _request(setup)

    assert request["reasoning_effort"] == "high"


def test_defaults_send_neither_parameter() -> None:
    request = _request(OpenAIChatModelSetup(connection="conn"))

    assert "reasoning_effort" not in request
    assert "strict" not in request
    assert request["tools"][0]["function"]["strict"] is False


@pytest.mark.parametrize("kwargs", [{}, {"temperature": None}])
def test_unset_temperature_is_omitted(kwargs: dict) -> None:
    request = _request(OpenAIChatModelSetup(connection="conn", **kwargs))

    assert "temperature" not in request


@pytest.mark.parametrize(
    ("kwargs", "expected"),
    [
        ({"temperature": 0.0}, 0.0),
        ({"temperature": 0.3}, 0.3),
        ({"temperature": 2.0}, 2.0),
        ({"additional_kwargs": {"temperature": 0.0}}, 0.0),
        ({"temperature": 0.3, "additional_kwargs": {"temperature": 0.7}}, 0.7),
    ],
)
def test_explicit_temperature_reaches_request(kwargs: dict, expected: float) -> None:
    request = _request(
        OpenAIChatModelSetup(connection="conn", model="gpt-6-luna", **kwargs)
    )

    assert request["temperature"] == expected


@pytest.mark.parametrize("temperature", [-0.1, 2.1])
def test_invalid_temperature_is_rejected(temperature: float) -> None:
    with pytest.raises(ValidationError, match="temperature"):
        OpenAIChatModelSetup(connection="conn", temperature=temperature)
