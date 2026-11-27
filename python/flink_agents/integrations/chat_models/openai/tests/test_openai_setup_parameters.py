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
from typing import Any, List
from unittest.mock import MagicMock

import pytest
from openai import NOT_GIVEN
from openai.types.chat import ChatCompletion
from pydantic import ValidationError

from flink_agents.api.chat_message import ChatMessage
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.resource_context import ResourceContext
from flink_agents.api.tools.tool import Tool
from flink_agents.integrations.chat_models.openai.openai_chat_model import (
    OpenAIChatModelConnection,
    OpenAIChatModelSetup,
)
from flink_agents.plan.function import PythonFunction
from flink_agents.plan.tools.function_tool import FunctionTool


def add(a: int, b: int) -> int:
    """Calculate the sum of a and b."""
    return a + b


def delegate(task: str) -> str:
    """Hand a task to a sub-agent."""
    return task


def _recording_connection() -> OpenAIChatModelConnection:
    """A connection whose client records the request and answers "ok"."""
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
    return conn


def _sent(conn: OpenAIChatModelConnection) -> dict:
    """The keyword arguments of the last request the connection sent."""
    return conn._client.chat.completions.create.call_args.kwargs


def _request(setup: OpenAIChatModelSetup) -> dict:
    """Send one chat with the setup's parameters and return the request kwargs."""
    conn = _recording_connection()
    conn.chat(
        [ChatMessage.user("hi")],
        tools=[FunctionTool(func=PythonFunction.from_callable(add))],
        **setup.model_kwargs,
    )
    return _sent(conn)


class _Context(ResourceContext):
    """Resolves the connection by type, every tool name to ``add`` and every
    sub-agent to ``delegate``.
    """

    def __init__(self, connection: OpenAIChatModelConnection | None = None) -> None:
        self._connection = connection

    def get_resource(self, name: str, resource_type: ResourceType) -> Resource:
        if resource_type == ResourceType.CHAT_MODEL_CONNECTION:
            return self._connection
        return FunctionTool(func=PythonFunction.from_callable(add))

    def get_subagent_tool(self, name: str) -> Tool:
        return FunctionTool(func=PythonFunction.from_callable(delegate))

    def generate_available_skills_prompt(self, *skill_names: str) -> str:
        return ""

    def get_skill_dirs(self, *skill_names: str) -> List[str]:
        return []


def _opened(**setup_args: Any) -> OpenAIChatModelSetup:
    """An opened setup whose connection is never called."""
    setup = OpenAIChatModelSetup(
        connection="conn", resource_context=_Context(), **setup_args
    )
    setup.open()
    return setup


def _opened_request(**setup_args: Any) -> dict:
    """Open a setup, send one chat through it and return the request kwargs."""
    conn = _recording_connection()
    setup = OpenAIChatModelSetup(
        connection="conn", resource_context=_Context(conn), **setup_args
    )
    setup.open()
    setup.chat([ChatMessage.user("hi")])
    return _sent(conn)


def test_strict_reaches_tool_definitions() -> None:
    request = _request(OpenAIChatModelSetup(connection="conn", strict=True))

    assert request["tools"][0]["function"]["strict"] is True
    assert "strict" not in request


@pytest.mark.parametrize("effort", ["none", "low", "medium", "high"])
def test_reasoning_effort_reaches_request(effort: str) -> None:
    setup = OpenAIChatModelSetup(connection="conn", reasoning_effort=effort)

    request = _request(setup)

    assert request["reasoning_effort"] == effort


@pytest.mark.parametrize("effort", ["minimal", "xhigh", "None", ""])
def test_unknown_reasoning_effort_rejected(effort: str) -> None:
    with pytest.raises(ValidationError, match="reasoning_effort"):
        OpenAIChatModelSetup(connection="conn", reasoning_effort=effort)


def test_defaults_send_neither_parameter() -> None:
    request = _request(OpenAIChatModelSetup(connection="conn"))

    assert "reasoning_effort" not in request
    assert "strict" not in request
    assert request["tools"][0]["function"]["strict"] is False


def test_default_model_with_tool_sends_none() -> None:
    request = _opened_request(tools=["add"])

    assert request["model"] == "gpt-6-luna"
    assert len(request["tools"]) == 1
    assert request["reasoning_effort"] == "none"


@pytest.mark.parametrize("model", ["gpt-6-luna", "gpt-6-sol"])
def test_known_model_with_tool_sends_none(model: str) -> None:
    request = _opened_request(model=model, tools=["add"])

    assert request["reasoning_effort"] == "none"


def test_skill_and_subagent_tools_count() -> None:
    with_skill = _opened(skills=["my-skill"])
    with_subagent = _opened(subagents=["child"])

    assert len(with_skill.tools) == 1
    assert with_skill.model_kwargs["reasoning_effort"] == "none"
    assert len(with_subagent.tools) == 1
    assert with_subagent.model_kwargs["reasoning_effort"] == "none"


@pytest.mark.parametrize("model", ["gpt-6-luna", "gpt-6-sol"])
def test_no_default_without_tools(model: str) -> None:
    request = _opened_request(model=model)

    assert request["tools"] is NOT_GIVEN
    assert "reasoning_effort" not in request


@pytest.mark.parametrize(
    "model",
    [
        "gpt-6.2-astra",
        "gpt-6.2-sol",
        "gpt-6.1-sol",
        "gpt-6.1-terra",
        "gpt-6-astra",
        "gpt-5.6-luna",
        "gpt-5.5",
        "gpt-4o-mini",
    ],
)
def test_no_default_for_other_models(model: str) -> None:
    request = _opened_request(model=model, tools=["add"])

    assert len(request["tools"]) == 1
    assert "reasoning_effort" not in request


@pytest.mark.parametrize("effort", ["low", "medium", "high"])
def test_configured_reasoning_effort_kept(effort: str) -> None:
    request = _opened_request(tools=["add"], reasoning_effort=effort)

    assert request["reasoning_effort"] == effort


def test_reasoning_effort_in_additional_kwargs_kept() -> None:
    setup = _opened(tools=["add"], additional_kwargs={"reasoning_effort": "high"})

    assert setup.model_kwargs["reasoning_effort"] == "high"
    assert (
        _opened_request(tools=["add"], additional_kwargs={"reasoning_effort": "high"})[
            "reasoning_effort"
        ]
        == "high"
    )


def test_no_default_before_open() -> None:
    setup = OpenAIChatModelSetup(connection="conn", tools=["add"])

    assert "reasoning_effort" not in setup.model_kwargs


def test_second_open_keeps_the_default_out_of_the_dump() -> None:
    setup = _opened(tools=["add"])
    setup.open()

    assert len(setup.tools) == 1
    assert setup.model_kwargs["reasoning_effort"] == "none"
    assert setup.reasoning_effort is None
    assert setup.model_dump()["reasoning_effort"] is None
