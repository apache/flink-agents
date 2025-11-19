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
import re
from abc import ABC, abstractmethod
from typing import Any, ClassVar, Dict, List, Sequence, Tuple

from pydantic import Field
from typing_extensions import override

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.tools.tool import Tool


class BaseChatModelConnection(Resource, ABC):
    """Base abstract class for chat model connection.

    Responsible for managing model service connection configurations, such as:
    - Service address (base_url)
    - API key (api_key)
    - Connection timeout (timeout)
    - Model name (model_name)
    - Authentication information, etc.

    Provides the basic chat interface for direct communication with model services.

    One connection can be shared in multiple chat model setup.
    """

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""
        return ResourceType.CHAT_MODEL_CONNECTION

    DEFAULT_REASONING_PATTERNS: ClassVar[Tuple[re.Pattern[str],...]] = (
        re.compile(r"<think>(.*?)</think>", re.DOTALL | re.IGNORECASE),
        re.compile(r"<analysis>(.*?)</analysis>", re.DOTALL | re.IGNORECASE),
        re.compile(r"<reasoning>(.*?)</reasoning>", re.DOTALL | re.IGNORECASE),
        re.compile(r"```(?:think|reasoning|thought)\s*\n(.*?)\n```", re.DOTALL | re.IGNORECASE),
        re.compile(r"(?:^|\n)Reasoning:\s*(.*?)(?:\n{2,}|$)", re.DOTALL | re.IGNORECASE),
    )

    @staticmethod
    def _extract_reasoning(
        content: str,
        patterns: List[re.Pattern[str]] = DEFAULT_REASONING_PATTERNS,
    ) -> Tuple[str, str | None]:
        """Extract content within <think></think> tags and clean the remaining content.

        Parameters
        ----------
        content: str
          Original content text

        Returns:
        -------
        Tuple[str, Optional[str]]
          The cleaned content and the reasoning part.
        """
        if not content:
            return "", None

        reasoning_chunks: List[str] = []
        cleaned = content

        for pat in patterns:
            matches = pat.findall(cleaned)
            if matches:
                reasoning_chunks.extend(m.strip() for m in matches if m.strip())
                cleaned = pat.sub("", cleaned)

        reasoning = "\n\n".join(reasoning_chunks) if reasoning_chunks else None
        cleaned = re.sub(r"\n{3,}", "\n\n", cleaned)
        cleaned = re.sub(r" {2,}", " ", cleaned)
        cleaned = cleaned.strip()
        return cleaned, reasoning

    @abstractmethod
    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List[Tool] | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
        """Direct communication with model service for chat conversation.

        Parameters
        ----------
        messages : Sequence[ChatMessage]
            Input message sequence
        tools : Optional[List]
            List of tools that can be called by the model
        **kwargs : Any
            Additional parameters passed to the model service (e.g., temperature,
            max_tokens, etc.)

        Returns:
        -------
        ChatMessage
            Model response message
        """


class BaseChatModelSetup(Resource):
    """Base abstract class for chat model setup.

    Responsible for managing chat configurations, such as:
    - Prompt templates (prompt)
    - Available tools (tools)
    - Generation parameters (temperature, max_tokens, etc.)
    - Context management

    Internally calls ChatModelConnection to perform actual communication with llm.

    Different chat model setups can share the same chat model connection and contains
    different chat configurations.
    """

    connection: str = Field(description="Name of the referenced connection.")
    prompt: Prompt | str | None = None
    tools: List[str] | None = None

    @property
    @abstractmethod
    def model_kwargs(self) -> Dict[str, Any]:
        """Return chat model settings."""

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""
        return ResourceType.CHAT_MODEL

    def chat(self, messages: Sequence[ChatMessage], **kwargs: Any) -> ChatMessage:
        """Execute chat conversation.

        1. Apply prompt template (if any)
        2. Bind tools (if any)
        3. Call ChatModelConnection to perform actual communication
        4. Process response

        Parameters
        ----------
        messages : Sequence[ChatMessage]
            Input message sequence
        **kwargs : Any
            Additional parameters passed to the model service

        Returns:
        -------
        ChatMessage
            Model response message
        """
        # Get model connection
        connection = self.get_resource(
            self.connection, ResourceType.CHAT_MODEL_CONNECTION
        )

        # Apply prompt template
        if self.prompt is not None:
            if isinstance(self.prompt, str):
                # Get prompt resource if it's a string
                prompt = self.get_resource(self.prompt, ResourceType.PROMPT)
            else:
                prompt = self.prompt

            input_variable = {}

            # fill the prompt template
            for msg in messages:
                # Convert Any values to str to match format_messages signature
                str_extra_args = {k: str(v) for k, v in msg.extra_args.items()}
                input_variable.update(str_extra_args)
            prompt_messages = prompt.format_messages(**input_variable)

            # append meaningful messages
            for msg in messages:
                if (msg.content is not None and msg.content != "") or msg.role == MessageRole.ASSISTANT:
                    prompt_messages.append(msg)
            messages = prompt_messages
        # Bind tools
        tools = None
        if self.tools is not None:
            tools = [
                self.get_resource(tool_name, ResourceType.TOOL)
                for tool_name in self.tools
            ]

        # Call chat model connection to execute chat
        merged_kwargs = self.model_kwargs.copy()
        merged_kwargs.update(kwargs)
        return connection.chat(messages, tools=tools, **merged_kwargs)
