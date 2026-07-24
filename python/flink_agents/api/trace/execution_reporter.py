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
import logging
from abc import ABC, abstractmethod
from collections.abc import Callable, Mapping
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from flink_agents.api.runner_context import RunnerContext

logger = logging.getLogger(__name__)

_EMPTY_METADATA: Mapping[str, Any] = {}


class ExecutionEntityTypes:
    """Shared entity type names for execution reports."""

    ACTION = "action"
    LLM = "llm"
    PARSER = "parser"
    TOOL = "tool"


class ExecutionProblemCategories:
    """Shared low-cardinality problem categories for failed execution reports."""

    ACTION_EXECUTION_FAILED = "action_execution_failed"
    MODEL_CALL_FAILED = "model_call_failed"
    MODEL_OUTPUT_PARSE_ERROR = "model_output_parse_error"
    TOOL_CALL_FAILED = "tool_call_failed"


class ExecutionReporter(ABC):
    """Optional capability for reporting executions nested inside an action."""

    @abstractmethod
    def report_execution_started(
        self,
        entity_type: str,
        entity_name: str,
        entity_metadata: Mapping[str, Any] | None = None,
    ) -> None:
        """Report that a logical execution started."""

    @abstractmethod
    def report_execution_succeeded(
        self,
        entity_type: str,
        entity_name: str,
        entity_metadata: Mapping[str, Any] | None = None,
    ) -> None:
        """Report that a logical execution completed successfully."""

    @abstractmethod
    def report_execution_failed(
        self,
        entity_type: str,
        entity_name: str,
        entity_metadata: Mapping[str, Any] | None,
        error: BaseException,
        problem_category: str | None = None,
    ) -> None:
        """Report that a logical execution failed."""


class ExecutionReporters:
    """Best-effort helpers for contexts that implement ExecutionReporter."""

    @staticmethod
    def started(
        ctx: "RunnerContext",
        entity_type: str,
        entity_name: str,
        entity_metadata: Mapping[str, Any] | None = None,
    ) -> None:
        """Report the start of a nested execution if the context supports it."""
        ExecutionReporters._report(
            ctx,
            lambda reporter: reporter.report_execution_started(
                entity_type, entity_name, entity_metadata or _EMPTY_METADATA
            ),
        )

    @staticmethod
    def succeeded(
        ctx: "RunnerContext",
        entity_type: str,
        entity_name: str,
        entity_metadata: Mapping[str, Any] | None = None,
    ) -> None:
        """Report successful completion if the context supports it."""
        ExecutionReporters._report(
            ctx,
            lambda reporter: reporter.report_execution_succeeded(
                entity_type, entity_name, entity_metadata or _EMPTY_METADATA
            ),
        )

    @staticmethod
    def failed(
        ctx: "RunnerContext",
        entity_type: str,
        entity_name: str,
        entity_metadata: Mapping[str, Any] | None,
        error: BaseException,
        problem_category: str | None = None,
    ) -> None:
        """Report failed completion if the context supports it."""
        ExecutionReporters._report(
            ctx,
            lambda reporter: reporter.report_execution_failed(
                entity_type,
                entity_name,
                entity_metadata or _EMPTY_METADATA,
                error,
                problem_category,
            ),
        )

    @staticmethod
    def _report(
        ctx: "RunnerContext", report: Callable[[ExecutionReporter], None]
    ) -> None:
        if not isinstance(ctx, ExecutionReporter):
            return
        try:
            report(ctx)
        except Exception:
            logger.debug("Execution reporting failed and was ignored.", exc_info=True)
