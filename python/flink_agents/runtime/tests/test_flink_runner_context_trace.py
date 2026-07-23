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
#  Unless required by applicable law or agreed to in writing,
#  software distributed under the License is distributed on an
#  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
#  KIND, either express or implied.  See the License for the
#  specific language governing permissions and limitations
#  under the License.
#################################################################################
from unittest.mock import MagicMock

from flink_agents.api.trace import (
    ExecutionEntityTypes,
    ExecutionProblemCategories,
    ExecutionReporter,
)
from flink_agents.runtime.flink_runner_context import FlinkRunnerContext


def test_flink_runner_context_is_execution_reporter() -> None:
    assert issubclass(FlinkRunnerContext, ExecutionReporter)


def test_failed_execution_reports_deepest_cause() -> None:
    java_context = MagicMock()
    ctx = FlinkRunnerContext.__new__(FlinkRunnerContext)
    ctx._j_runner_context = java_context
    root_error = ValueError("invalid model response")
    wrapped_error = RuntimeError("structured output failed")
    wrapped_error.__cause__ = root_error

    ctx.report_execution_failed(
        ExecutionEntityTypes.PARSER,
        "structured_output",
        {},
        wrapped_error,
        ExecutionProblemCategories.MODEL_OUTPUT_PARSE_ERROR,
    )

    java_context.reportExecutionFailedJson.assert_called_once_with(
        ExecutionEntityTypes.PARSER,
        "structured_output",
        "{}",
        "builtins.ValueError",
        "invalid model response",
        ExecutionProblemCategories.MODEL_OUTPUT_PARSE_ERROR,
    )
