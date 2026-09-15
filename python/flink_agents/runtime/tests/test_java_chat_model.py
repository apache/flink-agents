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
from typing import Any

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.runtime.java.java_chat_model import _to_java_chat_message


class _JavaResourceAdapter:
    def __init__(self) -> None:
        self.arguments: tuple[Any, ...] | None = None
        self.result = object()

    def fromPythonChatMessage(self, *arguments: Any) -> Any:
        self.arguments = arguments
        return self.result


def test_to_java_chat_message_extracts_java_safe_fields() -> None:
    adapter = _JavaResourceAdapter()
    message = ChatMessage(
        role=MessageRole.ASSISTANT,
        content="hello",
        tool_calls=[
            {
                "id": 7,
                "type": "function",
                "function": {"name": "lookup", "arguments": "{}"},
            }
        ],
        extra_args={"reasoning": "brief"},
    )

    result = _to_java_chat_message(adapter, message)

    assert result is adapter.result
    assert adapter.arguments == (
        "assistant",
        "hello",
        [
            {
                "id": "7",
                "type": "function",
                "function": {"name": "lookup", "arguments": "{}"},
            }
        ],
        {"reasoning": "brief"},
    )
