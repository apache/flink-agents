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
from typing import Any

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    model_serializer,
    model_validator,
)

from flink_agents.api.chat_message import DataContentBlock, TextBlock, ToolResultBlock


class ToolResponse(BaseModel):
    """Represents the result and status of one Python tool execution.

    Ordinary tool return values become successful text results. Strings are used
    directly; other values use JSON when possible, falling back to their string
    representation. ``blocks`` is the ordered model-facing text/media content.
    ``metadata`` holds application data
    and is not copied into chat messages. Media requires provider/model support.
    Return ``ToolResponse.error(...)`` when the tool operation itself failed.
    """

    model_config = ConfigDict(frozen=True, extra="forbid")
    blocks: tuple[DataContentBlock, ...] = ()
    metadata: dict[str, Any] = Field(default_factory=dict)
    error_message: str | None = None
    execution_time_ms: int = 0
    tool_name: str | None = None

    @model_validator(mode="before")
    @classmethod
    def from_wire(cls, value: Any) -> Any:
        """Read Java/Python execution results using one wire shape."""
        if isinstance(value, dict) and "success" in value:
            value = dict(value)
            success = value.pop("success")
            error = value.pop("error", None)
            if success != (error is None):
                msg = "ToolResponse success and error disagree"
                raise ValueError(msg)
            value["error_message"] = error
        return value

    @model_serializer
    def to_wire(self) -> dict:
        """Serialize execution data separately from its model-facing projection."""
        return {
            "metadata": self.metadata,
            "success": self.is_success(),
            "error": self.error_message,
            "execution_time_ms": self.execution_time_ms,
            "tool_name": self.tool_name,
            "blocks": self.blocks,
        }

    def to_result_block(self, call_id: str) -> ToolResultBlock:
        """Project a tool execution into model-facing content."""
        if self.is_error():
            return ToolResultBlock(
                call_id=call_id,
                blocks=[TextBlock(text=self.error_message)],
                is_error=True,
            )
        return ToolResultBlock(call_id=call_id, blocks=self.blocks)

    def get_text(self) -> str:
        """Concatenate text blocks in order, excluding media and metadata."""
        return "".join(b.text for b in self.blocks if isinstance(b, TextBlock))

    @classmethod
    def success(
        cls,
        blocks: tuple[DataContentBlock, ...] | list[DataContentBlock],
        execution_time_ms: int = 0,
        tool_name: str | None = None,
    ) -> "ToolResponse":
        """Create a successful response with ordered text and media blocks."""
        return cls(
            blocks=blocks,
            execution_time_ms=execution_time_ms,
            tool_name=tool_name,
        )

    @classmethod
    def text(
        cls,
        text: str,
        execution_time_ms: int = 0,
        tool_name: str | None = None,
    ) -> "ToolResponse":
        """Create a successful response containing one text block."""
        return cls.success([TextBlock(text=text)], execution_time_ms, tool_name)

    @classmethod
    def error(
        cls,
        error: str,
        execution_time_ms: int = 0,
        tool_name: str | None = None,
    ) -> "ToolResponse":
        """Create a failed tool response."""
        if error is None:
            msg = "error cannot be None"
            raise ValueError(msg)
        return cls(
            error_message=error,
            execution_time_ms=execution_time_ms,
            tool_name=tool_name,
        )

    def is_success(self) -> bool:
        """Return whether the tool operation succeeded."""
        return self.error_message is None

    def is_error(self) -> bool:
        """Return whether the tool operation failed."""
        return not self.is_success()

    def __str__(self) -> str:
        return self.get_text() if self.is_success() else str(self.error_message)
