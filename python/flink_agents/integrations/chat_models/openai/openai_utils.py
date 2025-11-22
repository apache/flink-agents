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
import json
import os
import uuid
from typing import TYPE_CHECKING, List, Sequence, Tuple

import openai
from openai.types.chat import (
    ChatCompletionMessage,
    ChatCompletionMessageParam,
    ChatCompletionMessageToolCallParam,
)

if TYPE_CHECKING:
    from openai.types.chat import (
        ChatCompletionAssistantMessageParam,
        ChatCompletionSystemMessageParam,
        ChatCompletionToolMessageParam,
        ChatCompletionUserMessageParam,
    )
    from openai.types.chat.chat_completion_message_tool_call_param import Function

from flink_agents.api.chat_message import ChatMessage, MessageRole

DEFAULT_OPENAI_API_BASE_URL = "https://api.openai.com/v1"


def resolve_openai_credentials(
    api_key: str | None = None,
    api_base_url: str | None = None,
) -> Tuple[str | None, str]:
    """Resolve OpenAI credentials.

    The order of precedence is:
    1. param
    2. env
    3. openai module
    4. default
    """
    # resolve from param or env
    api_key = _get_from_param_or_env("api_key", api_key, "OPENAI_API_KEY", "")
    api_base_url = _get_from_param_or_env(
        "api_base_url", api_base_url, "OPENAI_API_BASE_URL", ""
    )

    # resolve from openai module or default
    final_api_key = api_key or openai.api_key or ""
    final_api_base_url = api_base_url or openai.base_url or DEFAULT_OPENAI_API_BASE_URL

    return final_api_key, str(final_api_base_url)


def _get_from_param_or_env(
    param_name: str,
    value_from_args: str | None = None,
    env_var_name: str | None = None,
    default_value: str | None = None,
) -> str:
    """Get a value from a param or an environment variable.

    The order of precedence is:
    1. param
    2. env
    3. default
    """
    if value_from_args is not None:
        return value_from_args
    elif env_var_name and env_var_name in os.environ and os.environ[env_var_name]:
        return os.environ[env_var_name]
    elif default_value is not None:
        return default_value
    else:
        msg = (
            f"Did not find {param_name}, please add an environment variable"
            f" `{env_var_name}` which contains it, or pass"
            f"  `{param_name}` as a named parameter."
        )
        raise ValueError(msg)


def _convert_to_openai_tool_call(tool_call: dict) -> ChatCompletionMessageToolCallParam:
    """Convert framework tool call format to OpenAI tool call format."""
    # Use original_id if available, otherwise use id (and convert to string)
    openai_tool_call_id = tool_call.get("original_id")
    if openai_tool_call_id is None:
        tool_call_id = tool_call.get("id")
        if tool_call_id is None:
            msg = "Tool call must have either 'original_id' or 'id' field"
            raise ValueError(msg)
        openai_tool_call_id = str(tool_call_id)

    function: Function = {
        "name": tool_call["function"]["name"],
        # OpenAI expects arguments as JSON string, but our format has it as dict
        "arguments": json.dumps(tool_call["function"]["arguments"])
        if isinstance(tool_call["function"]["arguments"], dict)
        else tool_call["function"]["arguments"],
    }

    openai_tool_call: ChatCompletionMessageToolCallParam = {
        "id": openai_tool_call_id,
        "type": "function",
        "function": function,
    }
    return openai_tool_call


def convert_to_openai_messages(
    messages: Sequence[ChatMessage],
) -> List[ChatCompletionMessageParam]:
    """Convert chat messages to OpenAI messages."""
    return [convert_to_openai_message(message) for message in messages]


def convert_to_openai_message(message: ChatMessage) -> ChatCompletionMessageParam:
    """Convert a chat message to an OpenAI message.

    Converts framework ChatMessage to the appropriate OpenAI message type:
    - TOOL role -> ChatCompletionToolMessageParam
    - ASSISTANT role with tool_calls -> ChatCompletionAssistantMessageParam
    - USER role -> ChatCompletionUserMessageParam
    - SYSTEM role -> ChatCompletionSystemMessageParam
    """
    role = message.role

    # Handle SYSTEM role messages
    if role == MessageRole.SYSTEM:
        system_message: ChatCompletionSystemMessageParam = {
            "role": "system",
            "content": message.content,
        }
        system_message.update(message.extra_args)
        return system_message

    # Handle USER role messages
    elif role == MessageRole.USER:
        user_message: ChatCompletionUserMessageParam = {
            "role": "user",
            "content": message.content,
        }
        user_message.update(message.extra_args)
        return user_message
    # Handle ASSISTANT role messages

    elif role == MessageRole.ASSISTANT:
        # Assistant messages may have empty content when tool_calls are present
        content = message.content if message.content or not message.tool_calls else None
        assistant_message: ChatCompletionAssistantMessageParam = {
            "role": "assistant",
            "content": content,
        }
        if message.tool_calls:
            openai_tool_calls = [
                _convert_to_openai_tool_call(tool_call) for tool_call in message.tool_calls
            ]
            assistant_message["tool_calls"] = openai_tool_calls

        assistant_message.update(message.extra_args)
        return assistant_message

    # Handle TOOL role messages
    elif role == MessageRole.TOOL:
        tool_call_id = message.extra_args.get("external_id")
        if not tool_call_id or not isinstance(tool_call_id, str):
            msg = "Tool message must have 'external_id' as a string in extra_args"
            raise ValueError(msg)
        tool_message: ChatCompletionToolMessageParam = {
            "role": "tool",
            "content": message.content,
            "tool_call_id": tool_call_id,
        }
        return tool_message

    else:
        msg = f"Unsupported message role: {role}"
        raise ValueError(msg)


def convert_from_openai_message(message: ChatCompletionMessage) -> ChatMessage:
    """Convert an OpenAI message to a chat message."""
    tool_calls = []
    if message.tool_calls:
        # Generate internal UUID for each tool call while preserving
        # OpenAI's original ID in the original_id field for later
        # conversion back to OpenAI format
        tool_calls = [
            {
                "id": uuid.uuid4(),
                "type": tool_call.type,
                "function": {
                    "name": tool_call.function.name,
                    "arguments": json.loads(tool_call.function.arguments),
                },
                "original_id": tool_call.id
            }
            for tool_call in message.tool_calls
        ]
    return ChatMessage(
        role=MessageRole(message.role),
        content=message.content or "",
        tool_calls=tool_calls,
    )
