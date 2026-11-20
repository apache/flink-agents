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
"""The number of input tokens a chat request is expected to use."""

import json
from dataclasses import dataclass
from typing import Any, Sequence

from flink_agents.api.chat_message import (
    ChatMessage,
    ReasoningBlock,
    TextBlock,
    ToolCallBlock,
    ToolResultBlock,
)
from flink_agents.api.tools.tool import Tool

# Fixed cost of one message: its role and the framing around its content.
_MESSAGE_OVERHEAD_TOKENS = 4
# Fixed cost of one tool definition, beside its name, description and input schema.
_TOOL_OVERHEAD_TOKENS = 8
# Flat cost of one image, audio, video or document block, whatever its size.
_MEDIA_BLOCK_TOKENS = 1024
_UNITS_PER_TOKEN = 4
_NON_ASCII_UNITS = 4


@dataclass(frozen=True)
class TokenEstimate:
    """The expected input tokens of a chat request, and how they were counted.

    Attributes:
    ----------
    tokens : int
        The number of input tokens.
    is_approximate : bool
        Whether the count was derived from the size of the request, as opposed to
        the provider's own tokenization of it.
    """

    tokens: int
    is_approximate: bool

    def __post_init__(self) -> None:
        """Reject a negative count."""
        if self.tokens < 0:
            msg = f"tokens must not be negative, got {self.tokens}"
            raise ValueError(msg)

    @classmethod
    def exact(cls, tokens: int) -> "TokenEstimate":
        """A count made with the provider's own tokenization of the request."""
        return cls(tokens=tokens, is_approximate=False)

    @classmethod
    def approximate(cls, tokens: int) -> "TokenEstimate":
        """A count derived from the size of the request."""
        return cls(tokens=tokens, is_approximate=True)

    def __str__(self) -> str:
        return f"{'~' if self.is_approximate else ''}{self.tokens} tokens"


def approximate_token_estimate(
    messages: Sequence[ChatMessage], tools: Sequence[Tool]
) -> TokenEstimate:
    """Approximate the input tokens of a request from its size.

    For connections whose provider offers no way to count tokens. The approximation
    is the same in Java and Python. Text costs one token per four ASCII characters
    and one token per other character. Every message, tool definition and media block
    adds a fixed cost. Tool call arguments are measured by their structure, so the
    result does not depend on how a dict is rendered as text; the input schema of a
    tool is measured as compact JSON. Real token counts differ by model; the estimate
    is reported as approximate.

    Args:
        messages: The messages as they would be sent.
        tools: The tools as they would be bound to the request.
    """
    tokens = 0
    units = 0
    for message in messages:
        tokens += _MESSAGE_OVERHEAD_TOKENS
        for block in message.blocks:
            if isinstance(block, TextBlock | ReasoningBlock):
                units += _text_units(block.text)
            elif isinstance(block, ToolCallBlock):
                units += _text_units(block.name) + _value_units(block.input)
            elif isinstance(block, ToolResultBlock):
                for nested in block.blocks:
                    if isinstance(nested, TextBlock):
                        units += _text_units(nested.text)
                    else:
                        tokens += _MEDIA_BLOCK_TOKENS
            else:
                tokens += _MEDIA_BLOCK_TOKENS
    for tool in tools:
        tokens += _TOOL_OVERHEAD_TOKENS
        metadata = tool.metadata
        units += _text_units(tool.name)
        if metadata is not None:
            units += _text_units(metadata.description) + _text_units(
                json.dumps(
                    metadata.args_schema, separators=(",", ":"), ensure_ascii=False
                )
            )
    return TokenEstimate.approximate(tokens + -(-units // _UNITS_PER_TOKEN))


def _text_units(text: str | None) -> int:
    """One unit per ASCII character and four per other character."""
    if not text:
        return 0
    if text.isascii():
        return len(text)
    ascii_chars = sum(1 for char in text if ord(char) < 128)
    return ascii_chars + (len(text) - ascii_chars) * _NON_ASCII_UNITS


def _value_units(value: Any) -> int:
    """The size of a tool call argument, by its structure."""
    if value is None:
        return 4
    if isinstance(value, bool):
        return 5
    if isinstance(value, int | float):
        return 8
    if isinstance(value, str):
        return 2 + _text_units(value)
    if isinstance(value, dict):
        return 2 + sum(
            _text_units(str(key)) + 4 + _value_units(item) + 1
            for key, item in value.items()
        )
    if isinstance(value, list | tuple | set | frozenset):
        return 2 + sum(_value_units(item) + 1 for item in value)
    return 2 + _text_units(str(value))
