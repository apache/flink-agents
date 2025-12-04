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

from pemja import findClass
from pydantic import Field
from typing_extensions import override

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.tools.tool import Tool, ToolType


class JavaTool(Tool):
    """Java Tool that carries tool metadata and can be recognized by PythonChatModel."""

    @classmethod
    @override
    def tool_type(cls) -> ToolType:
        """Get the tool type."""
        return ToolType.REMOTE_FUNCTION

    @override
    def call(self, *args: Any, **kwargs: Any) -> Any:
        err_msg = "Java tool is defined in Java and needs to be executed through the Java runtime."
        raise NotImplementedError(err_msg)

class JavaPrompt(Prompt):
    """Python wrapper for Java's Prompt."""

    j_prompt: Any= Field(exclude=True)

    @override
    def format_string(self, **kwargs: str) -> str:
        return self.j_prompt.formatString(kwargs)

    @override
    def format_messages(
        self, role: MessageRole = MessageRole.SYSTEM, **kwargs: str
    ) -> List[ChatMessage]:
        j_MessageRole = findClass("org.apache.flink.agents.api.chat.messages.MessageRole")
        j_chat_messages = self.j_prompt.formatMessages(j_MessageRole.fromValue(role.value), kwargs)
        chatMessages = [ChatMessage(role=MessageRole(j_chat_message.getRole().getValue()),
                                            content=j_chat_message.getContent(),
                                            tool_calls= j_chat_message.getToolCalls(),
                                            extra_args=j_chat_message.getExtraArgs()) for j_chat_message in j_chat_messages]
        return chatMessages

class JavaGetResourceWrapper:
    """Python wrapper for Java ResourceAdapter."""

    def __init__(self, j_resource_adapter: Any) -> None:
        """Initialize with a Java ResourceAdapter."""
        self._j_resource_adapter = j_resource_adapter


    def get_resource(self, name: str, type: ResourceType) -> Resource:
        """Get a resource by name and type."""
        return self._j_resource_adapter.getResource(name, type.value)
