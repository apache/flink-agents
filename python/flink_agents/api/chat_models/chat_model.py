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
from typing import Any, ClassVar, Dict, List, Mapping, Sequence, Tuple, cast

from pydantic import Field, PrivateAttr
from typing_extensions import override

from flink_agents.api.chat_message import (
    ChatMessage,
    MessageRole,
    find_first_system_message,
)
from flink_agents.api.metric_group import MetricGroup
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.skills import BASH_TOOL, LOAD_SKILL_TOOL
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

    DEFAULT_REASONING_PATTERNS: ClassVar[Tuple[re.Pattern[str], ...]] = (
        re.compile(r"<think>(.*?)</think>", re.DOTALL | re.IGNORECASE),
        re.compile(r"<analysis>(.*?)</analysis>", re.DOTALL | re.IGNORECASE),
        re.compile(r"<reasoning>(.*?)</reasoning>", re.DOTALL | re.IGNORECASE),
        re.compile(
            r"```(?:think|reasoning|thought)\s*\n(.*?)\n```", re.DOTALL | re.IGNORECASE
        ),
        re.compile(
            r"(?:^|\n)Reasoning:\s*(.*?)(?:\n{2,}|$)", re.DOTALL | re.IGNORECASE
        ),
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
    - Connection to chat model service (connection)
    - Model name (model)
    - Prompt templates (prompt)
    - Available tools (tools)
    - Generation parameters (temperature, max_tokens, etc.)
    - Context management

    Internally calls ChatModelConnection to perform actual communication with llm.

    Different chat model setups can share the same chat model connection and contains
    different chat configurations.
    """

    connection: str = Field(description="The referenced connection name.")
    model: str = Field(description="Name of the chat model to use.")
    _resolved_connection: BaseChatModelConnection | None = PrivateAttr(default=None)
    prompt: Prompt | str | None = None
    tools: List[str] | List[Tool] = Field(default_factory=list)
    skills: List[str] | None = None
    skill_discovery_prompt: str | None = None
    allowed_commands: List[str] = Field(default_factory=list)
    allowed_script_dirs: List[str] = Field(default_factory=list)

    @property
    @abstractmethod
    def model_kwargs(self) -> Dict[str, Any]:
        """Return chat model settings."""

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""
        return ResourceType.CHAT_MODEL

    @override
    def open(self) -> None:
        self._resolved_connection = cast(
            "BaseChatModelConnection",
            self.resource_context.get_resource(
                self.connection, ResourceType.CHAT_MODEL_CONNECTION
            ),
        )
        if self.prompt is not None:
            if isinstance(self.prompt, str):
                # Get prompt resource if it's a string
                self.prompt = cast(
                    "Prompt",
                    self.resource_context.get_resource(
                        self.prompt, ResourceType.PROMPT
                    ),
                )
        if self.skills is not None:
            self.skill_discovery_prompt = (
                self.resource_context.generate_available_skills_prompt(*self.skills)
            )
            self.tools.extend([LOAD_SKILL_TOOL, BASH_TOOL])

        if len(self.tools) > 0:
            self.tools = [
                cast(
                    "Tool",
                    self.resource_context.get_resource(tool_name, ResourceType.TOOL),
                )
                for tool_name in self.tools
            ]

    def chat(
        self,
        messages: Sequence[ChatMessage],
        prompt_args: Mapping[str, Any] | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
        """Execute chat conversation.

        1. Apply prompt template (if any), filled from ``prompt_args``
        2. Bind tools (if any)
        3. Call ChatModelConnection to perform actual communication
        4. Process response

        Parameters
        ----------
        messages : Sequence[ChatMessage]
            Input message sequence
        prompt_args : Mapping[str, Any] | None
            Variables used to fill the prompt template, if a prompt resource is
            configured. Values are stringified via ``str()`` to match the
            ``Prompt.format_messages`` contract.
        **kwargs : Any
            Additional parameters passed to the model service

        Returns:
        -------
        ChatMessage
            Model response message
        """
        # Apply prompt template
        if self.prompt is not None:
            str_prompt_args: Dict[str, str] = (
                {k: str(v) for k, v in prompt_args.items()} if prompt_args else {}
            )
            prompt_messages = self._get_prompt().format_messages(**str_prompt_args)

            # append meaningful messages
            for msg in messages:
                if (
                    msg.content is not None and msg.content != ""
                ) or msg.role == MessageRole.ASSISTANT:
                    prompt_messages.append(msg)
            messages = prompt_messages

        if self.skills is not None:
            index = find_first_system_message(messages)
            messages = (
                messages[: index + 1]
                + [
                    ChatMessage(
                        role=MessageRole.SYSTEM, content=self.skill_discovery_prompt
                    )
                ]
                + messages[index + 1 :]
            )

        # Call chat model connection to execute chat
        merged_kwargs = self.model_kwargs.copy()
        merged_kwargs.update(kwargs)
        return self._get_connection().chat(
            messages, tools=self._get_tools(), **merged_kwargs
        )

    def _record_token_metrics(
        self,
        model_name: str,
        prompt_tokens: int,
        completion_tokens: int,
        metric_group: MetricGroup | None,
    ) -> None:
        """Record token usage metrics for the given model.

        Parameters
        ----------
        model_name : str
            The name of the model used
        prompt_tokens : int
            The number of prompt tokens
        completion_tokens : int
            The number of completion tokens
        metric_group : MetricGroup | None
            The metric group captured when the request was initiated. If None, token
            metrics are skipped.
        """
        if metric_group is None:
            return

        model_group = metric_group.get_sub_group("model", model_name)
        model_group.get_counter("promptTokens").inc(prompt_tokens)
        model_group.get_counter("completionTokens").inc(completion_tokens)

    def _get_connection(self) -> BaseChatModelConnection:
        if self._resolved_connection is None:
            err_msg = (
                f"Connection '{self.connection}' has not been resolved. "
                "Ensure open() is called before using the connection."
            )
            raise TypeError(err_msg)
        return self._resolved_connection

    def _get_prompt(self) -> Prompt:
        if not isinstance(self.prompt, Prompt):
            err_msg = f"Expect Prompt, but is {self.prompt.__class__.__name__}"
            raise TypeError(err_msg)
        return self.prompt

    def _get_tools(self) -> List[Tool]:
        for tool in self.tools:
            if not isinstance(tool, Tool):
                err_msg = f"Expect Tool, but is {tool.__class__.__name__}"
                raise TypeError(err_msg)
        return self.tools
