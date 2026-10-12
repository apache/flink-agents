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
    ChatCompletionContentPartParam,
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

from flink_agents.api.chat_message import (
    AudioBlock,
    Base64Source,
    ChatMessage,
    ContentBlock,
    DocumentBlock,
    ImageBlock,
    MediaBlock,
    MessageRole,
    TextBlock,
    ToolCallBlock,
    UnsupportedContentBlockError,
    UrlSource,
)

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


def _convert_to_openai_tool_call(
    tool_call: ToolCallBlock,
) -> ChatCompletionMessageToolCallParam:
    """Convert a typed call using its provider-visible ID."""
    return {
        "id": tool_call.call_id,
        "type": "function",
        "function": {"name": tool_call.name, "arguments": json.dumps(tool_call.input)},
    }


_AUDIO_FORMATS = {
    "audio/wav": "wav",
    "audio/wave": "wav",
    "audio/x-wav": "wav",
    "audio/vnd.wave": "wav",
    "audio/mpeg": "mp3",
    "audio/mp3": "mp3",
}


def _unsupported(block: ContentBlock, reason: str) -> UnsupportedContentBlockError:
    return UnsupportedContentBlockError.for_block(
        "OpenAI Chat Completions", block, reason
    )


def _data_uri(block: MediaBlock) -> str:
    return f"data:{block.media_type};base64,{block.source.data}"


def _to_content_part(block: ContentBlock) -> ChatCompletionContentPartParam:
    if isinstance(block, TextBlock):
        return {"type": "text", "text": block.text}
    if isinstance(block, ImageBlock):
        url = (
            block.source.url
            if isinstance(block.source, UrlSource)
            else _data_uri(block)
        )
        return {"type": "image_url", "image_url": {"url": url}}
    if isinstance(block, AudioBlock):
        if not isinstance(block.source, Base64Source):
            raise _unsupported(block, "audio input takes base64 data, not a URL")
        audio_format = _AUDIO_FORMATS.get(
            block.media_type.split(";", 1)[0].strip().lower()
        )
        if audio_format is None:
            raise _unsupported(block, "audio input takes WAV or MP3 only")
        return {
            "type": "input_audio",
            "input_audio": {"data": block.source.data, "format": audio_format},
        }
    if isinstance(block, DocumentBlock):
        if not isinstance(block.source, Base64Source):
            raise _unsupported(block, "file input takes base64 data, not a URL")
        # OpenAI accepts PDF documents only; other types are left for the
        # server to reject.
        return {
            "type": "file",
            "file": {
                "file_data": _data_uri(block),
                "filename": block.name if block.name is not None else "document",
            },
        }
    raise _unsupported(block, "there is no content part for it")


def _user_content(message: ChatMessage) -> str | List[ChatCompletionContentPartParam]:
    """Keep plain string content for text-only messages; media switches to parts."""
    if not any(isinstance(block, MediaBlock) for block in message.blocks):
        return message.text
    return [_to_content_part(block) for block in message.blocks]


def _require_text_only(message: ChatMessage) -> None:
    blocks = (
        message.blocks[0].blocks if message.role == MessageRole.TOOL else message.blocks
    )
    for block in blocks:
        if isinstance(block, MediaBlock):
            reason = (
                f"only user messages can carry media, not {message.role.value} messages"
            )
            raise _unsupported(block, reason)


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

    Only the fields OpenAI defines for each role are sent. Entries in
    metadata are not forwarded as message fields, except for an assistant refusal.
    Tool messages use the call ID and content from their ToolResultBlock.

    Only user messages can carry media. A media block in any other role, or
    one the Chat Completions API has no content part for, raises
    UnsupportedContentBlockError.
    """
    role = message.role
    if role != MessageRole.USER:
        _require_text_only(message)

    # Handle SYSTEM role messages
    if role == MessageRole.SYSTEM:
        system_message: ChatCompletionSystemMessageParam = {
            "role": "system",
            "content": message.text,
        }
        return system_message

    # Handle USER role messages
    elif role == MessageRole.USER:
        user_message: ChatCompletionUserMessageParam = {
            "role": "user",
            "content": _user_content(message),
        }
        return user_message
    # Handle ASSISTANT role messages

    elif role == MessageRole.ASSISTANT:
        # Assistant messages may have empty content when tool_calls are present
        content = message.text if message.text or not message.tool_calls else None
        assistant_message: ChatCompletionAssistantMessageParam = {
            "role": "assistant",
            "content": content,
        }
        if message.tool_calls:
            openai_tool_calls = [
                _convert_to_openai_tool_call(tool_call)
                for tool_call in message.tool_calls
            ]
            assistant_message["tool_calls"] = openai_tool_calls

        refusal = message.metadata.get("refusal")
        if isinstance(refusal, str):
            assistant_message["refusal"] = refusal
        return assistant_message

    # Handle TOOL role messages
    elif role == MessageRole.TOOL:
        result = message.blocks[0]
        tool_message: ChatCompletionToolMessageParam = {
            "role": "tool",
            "content": "".join(
                block.text for block in result.blocks if isinstance(block, TextBlock)
            ),
            "tool_call_id": result.call_id,
        }
        return tool_message

    else:
        msg = f"Unsupported message role: {role}"
        raise ValueError(msg)


def convert_from_openai_message(message: ChatCompletionMessage) -> ChatMessage:
    """Convert generated text and tool calls, retaining refusal metadata."""
    blocks = []
    if message.content:
        blocks.append(TextBlock(text=message.content))
    blocks.extend(
        ToolCallBlock(
            call_id=call.id or str(uuid.uuid4()),
            name=call.function.name,
            input=json.loads(call.function.arguments),
        )
        for call in message.tool_calls or []
    )
    metadata = {} if message.refusal is None else {"refusal": message.refusal}
    return ChatMessage(role=MessageRole.ASSISTANT, blocks=blocks, metadata=metadata)
