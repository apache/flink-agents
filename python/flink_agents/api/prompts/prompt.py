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
from typing import List, Sequence, Union

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.prompts.utils import format_string
from flink_agents.api.resource import ResourceType, SerializableResource


class Prompt(SerializableResource):
    """Prompt for a language model.

    Attributes:
    ----------
    template : Union[Sequence[ChatMessage], str]
        The prompt template.
    """

    template: Union[Sequence[ChatMessage], str]

    @staticmethod
    def from_messages(name: str, messages: Sequence[ChatMessage]) -> "Prompt":
        """Create prompt from sequence of ChatMessage."""
        return Prompt(name=name, template=messages)

    @staticmethod
    def from_text(name: str, text: str) -> "Prompt":
        """Create prompt from text string."""
        return Prompt(name=name, template=text)

    @classmethod
    def resource_type(cls) -> ResourceType:
        """Get the resource type."""
        return ResourceType.PROMPT

    def format_string(self, **kwargs: str) -> str:
        """Generate text string from template with input arguments."""
        if isinstance(self.template, str):
            return format_string(self.template, **kwargs)
        else:
            msgs = []
            for m in self.template:
                msg = f"{m.role.value}: {format_string(m.content, **kwargs)}"
                if m.extra_args is not None and len(m.extra_args) > 0:
                    msg += f"{m.extra_args}"
                msgs.append(msg)
            return "\n".join(msgs)

    def format_messages(
        self, role: MessageRole = MessageRole.SYSTEM, **kwargs: str
    ) -> List[ChatMessage]:
        """Generate list of ChatMessage from template with input arguments."""
        if isinstance(self.template, str):
            return [
                ChatMessage(role=role, content=format_string(self.template, **kwargs))
            ]
        else:
            msgs = []
            for m in self.template:
                msg = ChatMessage(
                    role=m.role, content=format_string(m.content, **kwargs)
                )
                msgs.append(msg)
            return msgs
