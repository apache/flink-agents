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
    """Shared entity type names for observing Actions and calls within Actions."""

    ACTION = "action"
    LLM = "llm"
    PARSER = "parser"
    TOOL = "tool"
    SUBAGENT = "subagent"


class ExecutionProblemCategories:
    """Shared low-cardinality failure categories for Actions and their calls."""

    ACTION_EXECUTION_FAILED = "action_execution_failed"
    MODEL_CALL_FAILED = "model_call_failed"
    MODEL_OUTPUT_PARSE_ERROR = "model_output_parse_error"
    TOOL_CALL_FAILED = "tool_call_failed"


class ExecutionReporter(ABC):
    """Report the creation, start, and outcome of calls within the current Action.

    A reported execution is one call, such as an LLM request, parser invocation,
    Tool call, or Subagent call. Reports about the same call must use the same
    entity type, name, and metadata. Metadata must distinguish calls with the same
    type and name that can overlap, and should remain small, structured, and
    serializable.

    Implementations decide how reports are consumed or ignored.
    """

    def report_execution_created(
        self,
        entity_type: str,
        entity_name: str,
        entity_metadata: Mapping[str, Any] | None = None,
    ) -> None:
        """Report that a call has been created but has not necessarily started.

        This optional report can describe a call prepared separately from its
        invocation. The default implementation ignores it. A later start,
        success, or failure report is not guaranteed; missing reports do not
        establish whether the call ran.
        """
        return None

    @abstractmethod
    def report_execution_started(
        self,
        entity_type: str,
        entity_name: str,
        entity_metadata: Mapping[str, Any] | None = None,
    ) -> None:
        """Report that a call made within the current Action started."""

    def report_execution_started_at(
        self,
        entity_type: str,
        entity_name: str,
        entity_metadata: Mapping[str, Any] | None,
        timestamp: str,
    ) -> None:
        """Report that a call started at the supplied timestamp.

        Implementations that do not retain the supplied timestamp may use their
        observation time.
        """
        self.report_execution_started(entity_type, entity_name, entity_metadata)

    @abstractmethod
    def report_execution_succeeded(
        self,
        entity_type: str,
        entity_name: str,
        entity_metadata: Mapping[str, Any] | None = None,
    ) -> None:
        """Report that a call completed successfully.

        The entity type, name, and metadata must match any creation or start
        report for the call.
        """

    def report_execution_succeeded_at(
        self,
        entity_type: str,
        entity_name: str,
        entity_metadata: Mapping[str, Any] | None,
        timestamp: str,
    ) -> None:
        """Report that a call completed successfully at the supplied timestamp.

        Implementations that do not retain the supplied timestamp may use their
        observation time.
        """
        self.report_execution_succeeded(entity_type, entity_name, entity_metadata)

    @abstractmethod
    def report_execution_failed(
        self,
        entity_type: str,
        entity_name: str,
        entity_metadata: Mapping[str, Any] | None,
        error: BaseException,
        problem_category: str | None = None,
    ) -> None:
        """Report that a call failed.

        The entity type, name, and metadata must match any creation or start
        report for the call. The problem category should be a stable,
        low-cardinality classification.
        """

    def report_execution_failed_at(
        self,
        entity_type: str,
        entity_name: str,
        entity_metadata: Mapping[str, Any] | None,
        error: BaseException,
        problem_category: str | None,
        timestamp: str,
    ) -> None:
        """Report that a call failed at the supplied timestamp.

        Implementations that do not retain the supplied timestamp may use their
        observation time.
        """
        self.report_execution_failed(
            entity_type,
            entity_name,
            entity_metadata,
            error,
            problem_category,
        )


class ExecutionReporters:
    """Report calls within an Action through ExecutionReporter when available.

    Contexts without this capability and failures in reporting are ignored.
    """

    @staticmethod
    def created(
        ctx: "RunnerContext",
        entity_type: str,
        entity_name: str,
        entity_metadata: Mapping[str, Any] | None = None,
    ) -> None:
        """Report that a call was created if the context supports reporting."""
        ExecutionReporters._report(
            ctx,
            lambda reporter: reporter.report_execution_created(
                entity_type, entity_name, entity_metadata or _EMPTY_METADATA
            ),
        )

    @staticmethod
    def started(
        ctx: "RunnerContext",
        entity_type: str,
        entity_name: str,
        entity_metadata: Mapping[str, Any] | None = None,
    ) -> None:
        """Report that a call started if the context supports reporting."""
        ExecutionReporters._report(
            ctx,
            lambda reporter: reporter.report_execution_started(
                entity_type, entity_name, entity_metadata or _EMPTY_METADATA
            ),
        )

    @staticmethod
    def started_at(
        ctx: "RunnerContext",
        entity_type: str,
        entity_name: str,
        entity_metadata: Mapping[str, Any] | None,
        timestamp: str,
    ) -> None:
        """Report a call's start time if the context supports reporting."""
        ExecutionReporters._report(
            ctx,
            lambda reporter: reporter.report_execution_started_at(
                entity_type,
                entity_name,
                entity_metadata or _EMPTY_METADATA,
                timestamp,
            ),
        )

    @staticmethod
    def succeeded(
        ctx: "RunnerContext",
        entity_type: str,
        entity_name: str,
        entity_metadata: Mapping[str, Any] | None = None,
    ) -> None:
        """Report that a call succeeded if the context supports reporting."""
        ExecutionReporters._report(
            ctx,
            lambda reporter: reporter.report_execution_succeeded(
                entity_type, entity_name, entity_metadata or _EMPTY_METADATA
            ),
        )

    @staticmethod
    def succeeded_at(
        ctx: "RunnerContext",
        entity_type: str,
        entity_name: str,
        entity_metadata: Mapping[str, Any] | None,
        timestamp: str,
    ) -> None:
        """Report a call's success time if the context supports reporting."""
        ExecutionReporters._report(
            ctx,
            lambda reporter: reporter.report_execution_succeeded_at(
                entity_type,
                entity_name,
                entity_metadata or _EMPTY_METADATA,
                timestamp,
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
        """Report that a call failed if the context supports reporting."""
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
    def failed_at(
        ctx: "RunnerContext",
        entity_type: str,
        entity_name: str,
        entity_metadata: Mapping[str, Any] | None,
        error: BaseException,
        problem_category: str | None,
        timestamp: str,
    ) -> None:
        """Report a call's failure time if the context supports reporting."""
        ExecutionReporters._report(
            ctx,
            lambda reporter: reporter.report_execution_failed_at(
                entity_type,
                entity_name,
                entity_metadata or _EMPTY_METADATA,
                error,
                problem_category,
                timestamp,
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
