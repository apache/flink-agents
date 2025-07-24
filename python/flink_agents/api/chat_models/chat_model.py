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
from abc import ABC, abstractmethod
from typing import List, Optional, Sequence, Union

from typing_extensions import override

from flink_agents.api.chat_message import ChatMessage
from flink_agents.api.prompt import Prompt
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.tools.tool import BaseTool


class BaseChatModel(Resource, ABC):
    """Base abstract class for chat models.

    Attributes:
    ----------
    prompt : Optional[Union[Prompt, str]] = None
        Used for generating prompt filling by input messages. Could be Prompt object
        or str indicate the Prompt resource in Agent.
    tools : Optional[List[str]] = None
        Tools name can be call by the ChatModel.
    """

    prompt: Optional[Union[Prompt, str]] = None
    tools: Optional[List[str]] = None

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""
        return ResourceType.CHAT_MODEL

    @abstractmethod
    def chat(
        self,
        messages: Sequence[ChatMessage],
        chat_history: Optional[List[ChatMessage]] = None,
    ) -> ChatMessage:
        """Process a sequence of messages, and return a response.

        Parameters
        ----------
        messages : Sequence[ChatMessage]
            Sequence of chat messages.
        chat_history : Optional[List[ChatMessage]]
            History of chat conversation.

        Returns:
        -------
        ChatMessage
            Response from the ChatModel.
        """

    @abstractmethod
    def bind_tools(self, tools: Sequence[BaseTool]) -> None:
        """Bind tools to the chat model.

        Parameters
        ----------
        tools : Sequence[BaseTool]
            Sequence of tools to bind to the chat model.
        """
