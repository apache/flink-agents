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
from typing import Annotated, Any

from pydantic import BaseModel, ConfigDict, Field, StrictStr, field_validator

from flink_agents.api.chat_message import (
    ChatMessage,
    MessageRole,
    ToolCallBlock,
)

TokenCount = Annotated[int, Field(strict=True, ge=0, le=2**63 - 1)]


class TokenUsage(BaseModel):
    """Per-call usage; None means unknown and zero is a measured value."""

    model_config = ConfigDict(extra="forbid", frozen=True, validate_default=True)
    prompt_tokens: TokenCount | None = None
    completion_tokens: TokenCount | None = None
    prompt_token_details: dict[str, TokenCount] = Field(default_factory=dict)
    completion_token_details: dict[str, TokenCount] = Field(default_factory=dict)


class ChatResult(BaseModel):
    """The result of a large language model (LLM) invocation.

    Contains the generated assistant message, along with optional model information,
    a response ID, token usage, a finish reason, and metadata about the invocation.
    """

    model_config = ConfigDict(extra="forbid", frozen=True, validate_default=True)
    message: ChatMessage
    model: str | None = None
    response_id: str | None = None
    usage: TokenUsage | None = None
    finish_reason: StrictStr | None = None
    metadata: dict[str, Any] = Field(default_factory=dict, repr=False)

    @field_validator("message")
    @classmethod
    def validate_message(cls, message: ChatMessage) -> ChatMessage:
        """Require an assistant message as the model output."""
        if message.role != MessageRole.ASSISTANT:
            msg = "ChatResult requires an ASSISTANT message"
            raise ValueError(msg)
        return message

    @property
    def text(self) -> str:
        """The text of the generated assistant message."""
        return self.message.text

    @property
    def tool_calls(self) -> tuple[ToolCallBlock, ...]:
        """The tool calls in the generated assistant message."""
        return self.message.tool_calls
