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

import pytest

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.integrations.chat_models.openai.openai_chat_model import (
    OpenAIChatModelConnection,
    OpenAIChatModelSetup,
)
from flink_agents.plan.tools.function_tool import from_callable

test_model = os.environ.get("TEST_MODEL")
api_key = os.environ.get("TEST_API_KEY")
api_base_url = os.environ.get("TEST_API_BASE_URL")


@pytest.mark.skipif(api_key is None, reason="TEST_API_KEY is not set")
def test_openai_chat_model() -> None:  # noqa: D103
    connection = OpenAIChatModelConnection(
        name="openai", api_key=api_key, api_base_url=api_base_url
    )

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.CHAT_MODEL_CONNECTION:
            return connection
        else:
            return get_resource(name, ResourceType.TOOL)

    chat_model = OpenAIChatModelSetup(
        name="openai", model=test_model, connection="openai", get_resource=get_resource
    )
    response = chat_model.chat([ChatMessage(role=MessageRole.USER, content="Hello!")])
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
def test_openai_chat_with_tools() -> None:  # noqa : D103
    connection = OpenAIChatModelConnection(
        name="openai", api_key=api_key, api_base_url=api_base_url
    )

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.CHAT_MODEL_CONNECTION:
            return connection
        else:
            return from_callable(name=name, func=add)

    chat_model = OpenAIChatModelSetup(
        name="openai",
        model=test_model,
        connection="openai",
        tools=["add"],
        get_resource=get_resource,
    )
    response = chat_model.chat(
        [ChatMessage(role=MessageRole.USER, content="What is 377 + 688?")]
    )
    tool_calls = response.tool_calls
    assert len(tool_calls) == 1
    tool_call = tool_calls[0]
    assert add(**tool_call["function"]["arguments"]) == 1065
