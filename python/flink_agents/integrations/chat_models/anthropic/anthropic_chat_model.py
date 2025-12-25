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
import uuid
from typing import Any, Dict, List, Sequence

from anthropic import Anthropic
from anthropic._types import NOT_GIVEN
from anthropic.types import MessageParam, TextBlockParam, ToolParam
from pydantic import Field, PrivateAttr

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
)
from flink_agents.api.tools.tool import Tool, ToolMetadata


def to_anthropic_tool(*, metadata: ToolMetadata, skip_length_check: bool = False) -> ToolParam:
    """Convert to Anthropic tool: https://docs.anthropic.com/en/api/messages#body-tools."""
    if not skip_length_check and len(metadata.description) > 1024:
        msg = (
            "Tool description exceeds maximum length of 1024 characters. "
            "Please shorten your description or move it to the prompt."
        )
        raise ValueError(msg)
    return {
        "name": metadata.name,
        "description": metadata.description,
        "input_schema": metadata.get_parameters_dict()
    }


def convert_to_anthropic_message(message: ChatMessage) -> MessageParam:
    """Convert ChatMessage to Anthropic MessageParam format."""
    if message.role == MessageRole.TOOL:
        return {
            "role": MessageRole.USER.value,
            "content": [
                {
                    "type": "tool_result",
                    "tool_use_id": message.extra_args.get("external_id"),
                    "content": message.content,
                }
            ],
        }
    elif message.role == MessageRole.ASSISTANT:
        # Use original Anthropic content blocks if available for context
        anthropic_content_blocks = message.extra_args.get("anthropic_content_blocks")
        content = anthropic_content_blocks if anthropic_content_blocks is not None else message.content
        return {
            "role": message.role.value,
            "content": content,  # type: ignore
        }
    else:
        return {
            "role": message.role.value,
            "content": message.content,
        }


def convert_to_anthropic_messages(messages: Sequence[ChatMessage]) -> List[MessageParam]:
    """Convert user/assistant messages to Anthropic input messages.

    See: https://docs.anthropic.com/en/api/messages#body-messages
    """
    return [convert_to_anthropic_message(message) for message in messages if
            message.role in [MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL]]


def convert_to_anthropic_system_prompts(messages: Sequence[ChatMessage]) -> List[TextBlockParam]:
    """Convert system messages to Anthropic system prompts.

    See: https://docs.anthropic.com/en/api/messages#body-system
    """
    system_messages = [message for message in messages if message.role == MessageRole.SYSTEM]
    return [
        TextBlockParam(
            type="text",
            text=message.content
        )
        for message in system_messages
    ]


class AnthropicChatModelConnection(BaseChatModelConnection):
    """Manages the connection to the Anthropic AI models for chat interactions.

    Attributes:
    ----------
    api_key : str
        The Anthropic API key.
    max_retries : int
        The number of times to retry the API call upon failure.
    timeout : float
        The number of seconds to wait for an API call before it times out.
    reuse_client : bool
        Whether to reuse the Anthropic client between requests.
    """

    api_key: str = Field(default=None, description="The Anthropic API key.")

    max_retries: int = Field(
        default=3,
        description="The number of times to retry the API call upon failure.",
        ge=0,
    )
    timeout: float = Field(
        default=60.0,
        description="The number of seconds to wait for an API call before it times out.",
        ge=0,
    )

    def __init__(
            self,
            api_key: str | None = None,
            max_retries: int = 3,
            timeout: float = 60.0,
            **kwargs: Any,
    ) -> None:
        """Initialize the Anthropic chat model connection."""
        super().__init__(
            api_key=api_key,
            max_retries=max_retries,
            timeout=timeout,
            **kwargs,
        )

    _client: Anthropic | None = PrivateAttr(default=None)

    @property
    def client(self) -> Anthropic:
        """Get or create the Anthropic client instance."""
        if self._client is None:
            self._client = Anthropic(api_key=self.api_key, max_retries=self.max_retries, timeout=self.timeout)
        return self._client

    def chat(self, messages: Sequence[ChatMessage], tools: List[Tool] | None = None,
             **kwargs: Any) -> ChatMessage:
        """Direct communication with Anthropic model service for chat conversation."""
        anthropic_tools = None
        if tools is not None:
            anthropic_tools = [to_anthropic_tool(metadata=tool.metadata) for tool in tools]

        anthropic_system = convert_to_anthropic_system_prompts(messages)
        anthropic_messages = convert_to_anthropic_messages(messages)

        message = self.client.messages.create(
            messages=anthropic_messages,
            tools=anthropic_tools or NOT_GIVEN,
            system=anthropic_system or NOT_GIVEN,
            **kwargs,
        )

        # Record token metrics if model name and usage are available
        model_name = kwargs.get("model")
        if model_name and message.usage:
            self._record_token_metrics(
                model_name,
                message.usage.input_tokens,
                message.usage.output_tokens,
            )

        if message.stop_reason == "tool_use":
            tool_calls = [
                {
                    "id": uuid.uuid4(),
                    "type": "function",
                    "function": {
                        "name": content_block.name,
                        "arguments": content_block.input,
                    },
                    "original_id": content_block.id,
                }
                for content_block in message.content
                if content_block.type == 'tool_use'
            ]

            return ChatMessage(
                role=MessageRole(message.role),
                content=message.content[0].text,
                tool_calls=tool_calls,
                extra_args={
                    "anthropic_content_blocks": message.content
                }

            )
        else:
            # TODO: handle other stop_reason values according to Anthropic API:
            #  https://docs.anthropic.com/en/api/messages#response-stop-reason
            return ChatMessage(
                role=MessageRole(message.role),
                content=message.content[0].text,
            )


DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-20250514"
DEFAULT_MAX_TOKENS = 1024
DEFAULT_TEMPERATURE = 0.1


class AnthropicChatModelSetup(BaseChatModelSetup):
    """The settings for Anthropic Chat Model.

    Attributes:
    ----------
    connection : str
        Name of the referenced connection. (Inherited from BaseChatModelSetup)
    prompt : Optional[Union[Prompt, str]
        Prompt template or string for the model. (Inherited from BaseChatModelSetup)
    tools : Optional[List[str]]
        List of available tools to use in the chat. (Inherited from BaseChatModelSetup)
    model : str
        Specifies the Anthropic model to use. Defaults to claude-sonnet-4-20250514.
    max_tokens: int
        The maximum number of tokens to generate before stopping. Defaults to 1024.
    temperature : float
        Amount of randomness injected into the response.
    """

    model: str = Field(
        default=DEFAULT_ANTHROPIC_MODEL, description="Specifies the Anthropic model to use. Defaults to "
                                                     "claude-sonnet-4-20250514."
    )
    max_tokens: int = Field(
        default=DEFAULT_MAX_TOKENS,
        description="The maximum number of tokens to generate before stopping. Defaults to 1024.",
        ge=1,
    )
    temperature: float = Field(
        default=DEFAULT_TEMPERATURE,
        description="Amount of randomness injected into the response. Defaults to 0.1",
        ge=0.0,
        le=1.0,
    )

    def __init__(
            self,
            connection: str,
            model: str = DEFAULT_ANTHROPIC_MODEL,
            max_tokens: int = DEFAULT_MAX_TOKENS,
            temperature: float = DEFAULT_TEMPERATURE,
            **kwargs: Any,
    ) -> None:
        """Init method."""
        super().__init__(
            connection=connection,
            model=model,
            max_tokens=max_tokens,
            temperature=temperature,
            **kwargs,
        )

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Get model-specific keyword arguments."""
        return {"model": self.model, "max_tokens": self.max_tokens, "temperature": self.temperature}
