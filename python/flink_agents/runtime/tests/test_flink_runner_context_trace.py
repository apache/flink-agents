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

import pytest

from flink_agents.api.trace import (
    ExecutionEntityTypes,
    ExecutionProblemCategories,
    ExecutionReporter,
)
from flink_agents.runtime.flink_runner_context import FlinkRunnerContext


def test_flink_runner_context_is_execution_reporter() -> None:
    assert issubclass(FlinkRunnerContext, ExecutionReporter)


@pytest.mark.parametrize(
    ("chain_attribute", "error_type", "error_message"),
    [
        ("__cause__", "builtins.ValueError", "invalid model response"),
        ("__context__", "builtins.RuntimeError", "structured output failed"),
    ],
)
def test_failed_execution_reports_explicit_cause_only(
    chain_attribute: str, error_type: str, error_message: str
) -> None:
    java_context = MagicMock()
    ctx = FlinkRunnerContext.__new__(FlinkRunnerContext)
    ctx._j_runner_context = java_context
    reported_error = RuntimeError("structured output failed")
    setattr(reported_error, chain_attribute, ValueError("invalid model response"))

    ctx.report_execution_failed(
        "execution-1",
        ExecutionEntityTypes.PARSER,
        "structured_output",
        {},
        reported_error,
        ExecutionProblemCategories.MODEL_OUTPUT_PARSE_ERROR,
    )

    java_context.reportExecutionFailedJson.assert_called_once_with(
        "execution-1",
        ExecutionEntityTypes.PARSER,
        "structured_output",
        "{}",
        error_type,
        error_message,
        ExecutionProblemCategories.MODEL_OUTPUT_PARSE_ERROR,
    )


@pytest.mark.parametrize(
    ("python_method", "java_method"),
    [
        ("created", "Created"),
        ("started", "Started"),
        ("started_at", "StartedAt"),
        ("succeeded", "Succeeded"),
        ("succeeded_at", "SucceededAt"),
        ("failed_at", "FailedAt"),
    ],
)
def test_execution_reports_forward_identity_and_payload(
    python_method, java_method
) -> None:
    java_context = MagicMock()
    ctx = FlinkRunnerContext.__new__(FlinkRunnerContext)
    ctx._j_runner_context = java_context
    args = ["execution-1", "tool", "search", {"key": "value"}]
    expected = ["execution-1", "tool", "search", '{"key": "value"}']
    if python_method == "failed_at":
        args.extend([ValueError("boom"), "tool_call_failed"])
        expected.extend(["builtins.ValueError", "boom", "tool_call_failed"])
    if python_method.endswith("_at"):
        args.append("2026-01-01T00:00:00Z")
        expected.append("2026-01-01T00:00:00Z")
    getattr(ctx, "report_execution_" + python_method)(*args)
    getattr(
        java_context, "reportExecution" + java_method + "Json"
    ).assert_called_once_with(*expected)
