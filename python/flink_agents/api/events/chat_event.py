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
from typing import List
from uuid import UUID

from flink_agents.api.chat_message import ChatMessage
from flink_agents.api.events.event import Event


class ChatRequestEvent(Event):
    """Event representing a request to chat model.

    Attributes:
    ----------
    model : str
        The name of the chat model to be chatted with.
    messages : List[ChatMessage]
        The input to the chat model.
    """

    model: str
    messages: List[ChatMessage]


class ChatResponseEvent(Event):
    """Event representing a response from chat model.

    Attributes:
    ----------
    request_id : UUID
        The id of the request event.
    response : ChatMessage
        The response from the chat model.
    """

    request_id: UUID
    response: ChatMessage
