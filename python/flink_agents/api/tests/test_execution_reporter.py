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
from unittest.mock import MagicMock

from flink_agents.api.trace import (
    ExecutionEntityTypes,
    ExecutionProblemCategories,
    ExecutionReporter,
    ExecutionReporters,
)


def test_failed_reporter_uses_metadata_before_error() -> None:
    ctx = MagicMock(spec=ExecutionReporter)
    metadata = {"toolCallId": "call-1"}
    error = RuntimeError("boom")

    ExecutionReporters.failed(
        ctx,
        ExecutionEntityTypes.TOOL,
        "search",
        metadata,
        error,
        ExecutionProblemCategories.TOOL_CALL_FAILED,
    )

    ctx.report_execution_failed.assert_called_once_with(
        ExecutionEntityTypes.TOOL,
        "search",
        metadata,
        error,
        ExecutionProblemCategories.TOOL_CALL_FAILED,
    )


def test_reporters_ignore_context_without_execution_reporter() -> None:
    ctx = MagicMock()

    ExecutionReporters.started(ctx, ExecutionEntityTypes.LLM, "model")
    ExecutionReporters.succeeded(ctx, ExecutionEntityTypes.LLM, "model")
    ExecutionReporters.failed(
        ctx,
        ExecutionEntityTypes.LLM,
        "model",
        None,
        RuntimeError("boom"),
        ExecutionProblemCategories.MODEL_CALL_FAILED,
    )

    ctx.report_execution_started.assert_not_called()
    ctx.report_execution_succeeded.assert_not_called()
    ctx.report_execution_failed.assert_not_called()
