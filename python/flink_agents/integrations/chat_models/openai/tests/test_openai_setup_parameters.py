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
