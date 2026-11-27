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
################################################################################
"""Optional runtime-managed async execution for tools with their own durability."""

from abc import ABC, abstractmethod
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timezone
from functools import wraps
from typing import Any

from flink_agents.api.runner_context import AsyncFuture, RunnerContext


@dataclass
class ToolCallContext:
    """Execution observations and cleanup for one model-issued call."""

    started_at: datetime | None = None
    finished_at: datetime | None = None
    on_cancel: Callable[[], None] | None = None

    def mark_started(self) -> None:
        """Record when this invocation starts executing."""
        self.started_at = datetime.now(timezone.utc)

    def mark_finished(self) -> None:
        """Record when this invocation produces a result."""
        self.finished_at = datetime.now(timezone.utc)

    def wrap(self, func: Callable) -> Callable:
        """Observe an ordinary callable on the thread that executes it."""

        @wraps(func)
        def observed_call(*args: Any, **kwargs: Any) -> Any:
            self.mark_started()
            try:
                return func(*args, **kwargs)
            finally:
                self.mark_finished()

        return observed_call


class AsyncTool(ABC):
    """A tool owning its async/durable execution, without another outer wrapper."""

    @abstractmethod
    async def call_async(
        self, ctx: RunnerContext, call_context: ToolCallContext, /, **kwargs: Any
    ) -> AsyncFuture:
        """Return a runtime future, also used for sequential tool execution."""
