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
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class ToolResponse:
    """Represents the result and status of one Python tool execution.

    Python tools may continue returning raw values, which the runtime treats as
    successful results. Return ``ToolResponse.error(...)`` when a tool call
    completed normally but the tool operation itself failed.
    """

    result: Any = None
    error_message: str | None = None
    execution_time_ms: int = 0
    tool_name: str | None = None

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
