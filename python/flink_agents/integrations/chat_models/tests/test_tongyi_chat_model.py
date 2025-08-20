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
from flink_agents.integrations.chat_models.tongyi_chat_model import (
    TongyiChatModelConnection,
    TongyiChatModelSetup,
)
from flink_agents.plan.tools.function_tool import FunctionTool, from_callable

test_model = os.environ.get("TONGYI_CHAT_MODEL", "qwen-plus")
api_key_available = "DASHSCOPE_API_KEY" in os.environ


@pytest.mark.skipif(not api_key_available, reason="DashScope API key is not set")
def test_tongyi_chat() -> None:
    """Test basic chat functionality of TongyiChatModelConnection."""
    connection = TongyiChatModelConnection(name="tongyi", model=test_model)
    response = connection.chat([ChatMessage(role=MessageRole.USER, content="Hello!")])
    assert response is not None
    assert response.content is not None
    assert response.content.strip() != ""
    assert response.role == MessageRole.ASSISTANT


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


def get_tool(name: str, type: ResourceType) -> FunctionTool:
    """Helper function to create a tool for testing."""
    return from_callable(name=name, func=add)


@pytest.mark.skipif(not api_key_available, reason="DashScope API key is not set")
def test_tongyi_chat_with_tools() -> None:
    """Test chat functionality with tool calling."""
    connection = TongyiChatModelConnection(name="tongyi", model=test_model)

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.TOOL:
            return get_tool(name=name, type=ResourceType.TOOL)
        else:
            return connection

    llm = TongyiChatModelSetup(
        name="tongyi", connection="tongyi", tools=["add"], get_resource=get_resource,
    )

    response = llm.chat(
        [
            ChatMessage(
                role=MessageRole.USER,
                content="Could you help me calculate the sum of 1 and 2?",
            )
        ]
    )

    tool_calls = response.tool_calls
    assert len(tool_calls) == 1
    tool_call = tool_calls[0]
    assert add(**tool_call["function"]["arguments"]) == 3
