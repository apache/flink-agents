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
from enum import Enum
from typing import Any, Dict, List

from pydantic import BaseModel, Field


class MessageRole(str, Enum):
    """Message role.

    Attributes:
    ----------
    SYSTEM : str
        Used to tell the chat model how to behave and provide additional context.
    USER : str
        Represents input from a user interacting with the model.
    ASSISTANT : str
        Represents a response from the model, which can include text or a
        request to invoke tools.
    TOOL : str
        A message used to pass the results of a tools invocation back to the model.
    """

    SYSTEM = "system"
    USER = "user"
    ASSISTANT = "assistant"
    TOOL = "tool"


class ChatMessage(BaseModel):
    """Chat message.

    ChatMessages are the inputs and outputs of ChatModels.

    Attributes:
    ----------
    role : MessageRole
        The message productor or purpose.
    content : str
        The content of the message.
    tool_calls: List[Dict[str, Any]]
        The tools call information.
    extra_args : dict[str, Any]
        Additional information about the message.
    """

    role: MessageRole = MessageRole.USER
    content: str = Field(default_factory=str)
    tool_calls: List[Dict[str, Any]] = Field(default_factory=list)
    extra_args: Dict[str, Any] = Field(default_factory=dict)

    def __str__(self) -> str:
        return f"{self.role.value}: {self.content}"
