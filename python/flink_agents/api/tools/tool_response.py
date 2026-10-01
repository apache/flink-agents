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
from typing import Any

from pydantic import (
    BaseModel,
    ConfigDict,
    field_validator,
    model_serializer,
    model_validator,
)

from flink_agents.api.chat_message import ContentBlock, TextBlock, ToolResultBlock


class ToolResponse(BaseModel):
    """Represents the result and status of one Python tool execution.

    Python tools may continue returning raw values, which the runtime treats as
    successful results. Return ``ToolResponse.error(...)`` when a tool call
    completed normally but the tool operation itself failed.
    """

    model_config = ConfigDict(frozen=True, extra="forbid")
    result: Any = None
    error_message: str | None = None
    execution_time_ms: int = 0
    tool_name: str | None = None

    blocks: tuple[ContentBlock, ...] | None = None

    @field_validator("blocks")
    @classmethod
    def validate_blocks(cls, value: tuple | None) -> tuple | None:
        """Validate explicit model-facing content even before execution finishes."""
        if value is not None:
            ToolResultBlock(call_id="validation", blocks=value)
        return value

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
            "result": self.result,
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
        if self.blocks is not None:
            return ToolResultBlock(call_id=call_id, blocks=self.blocks)
        text = (
            self.result
            if isinstance(self.result, str)
            else json.dumps(self.result, ensure_ascii=False, allow_nan=False)
        )
        return ToolResultBlock(call_id=call_id, blocks=[TextBlock(text=text)])

    @classmethod
    def success(
        cls,
        result: Any,
        execution_time_ms: int = 0,
        tool_name: str | None = None,
    ) -> "ToolResponse":
        """Create a successful tool response."""
        return cls(
            result=result,
            execution_time_ms=execution_time_ms,
            tool_name=tool_name,
        )

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
        return str(self.result) if self.is_success() else str(self.error_message)
