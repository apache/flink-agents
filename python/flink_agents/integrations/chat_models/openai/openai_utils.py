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
from typing import List, Optional, Sequence, Tuple, cast

import openai
from openai.types.chat import ChatCompletionMessage, ChatCompletionMessageParam

from flink_agents.api.chat_message import ChatMessage, MessageRole

DEFAULT_OPENAI_API_BASE_URL = "https://api.openai.com/v1"


def resolve_openai_credentials(
    api_key: Optional[str] = None,
    api_base_url: Optional[str] = None,
) -> Tuple[Optional[str], str]:
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
    value_from_args: Optional[str] = None,
    env_var_name: Optional[str] = None,
    default_value: Optional[str] = None,
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


def convert_to_openai_messages(
    messages: Sequence[ChatMessage],
) -> List[ChatCompletionMessageParam]:
    """Convert chat messages to OpenAI messages."""
    return [convert_to_openai_message(message) for message in messages]


def convert_to_openai_message(message: ChatMessage) -> ChatCompletionMessageParam:
    """Convert a chat message to an OpenAI message."""
    context_txt = message.content
    context_txt = (
        None
        if context_txt == ""
        and message.role == MessageRole.ASSISTANT
        and len(message.tool_calls) > 0
        else context_txt
    )
    if len(message.tool_calls) > 0:
        openai_message = {
            "role": message.role.value,
            "content": context_txt,
            "tool_calls": message.tool_calls,
        }
    else:
        openai_message = {"role": message.role.value, "content": context_txt}
    openai_message.update(message.extra_args)
    return cast("ChatCompletionMessageParam", openai_message)


def convert_from_openai_message(message: ChatCompletionMessage) -> ChatMessage:
    """Convert an OpenAI message to a chat message."""
    tool_calls = []
    if message.tool_calls:
        tool_calls = [
            {
                "id": tool_call.id,
                "type": tool_call.type,
                "function": {
                    "name": tool_call.function.name,
                    "arguments": json.loads(tool_call.function.arguments),
                },
            }
            for tool_call in message.tool_calls
        ]
    return ChatMessage(
        role=MessageRole(message.role),
        content=message.content or "",
        tool_calls=tool_calls,
    )
