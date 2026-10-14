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
from unittest.mock import MagicMock, call

import pytest

from flink_agents.api.trace import (
    ExecutionEntityTypes,
    ExecutionProblemCategories,
    ExecutionReporter,
    ExecutionReporters,
)


def test_reporters_ignore_context_without_execution_reporter() -> None:
    ctx = MagicMock()

    ExecutionReporters.succeeded(ctx, "execution-1", ExecutionEntityTypes.LLM, "model")
    ExecutionReporters.failed(
        ctx,
        "execution-1",
        ExecutionEntityTypes.LLM,
        "model",
        None,
        RuntimeError("boom"),
        ExecutionProblemCategories.MODEL_CALL_FAILED,
    )

    ctx.report_execution_succeeded.assert_not_called()
    ctx.report_execution_failed.assert_not_called()


@pytest.mark.parametrize("method", ["created", "started", "started_at"])
@pytest.mark.parametrize("reporting", ["available", "unavailable", "failing"])
def test_initial_reports_return_distinct_ids_independently_of_reporting(
    method: str, reporting: str
) -> None:
    ctx = MagicMock(spec=None if reporting == "unavailable" else ExecutionReporter)
    reporter = getattr(ctx, "report_execution_" + method)
    if reporting == "failing":
        reporter.side_effect = RuntimeError("report failed")
    args = [ExecutionEntityTypes.TOOL, "search", {"toolCallId": "call-1"}]
    if method == "started_at":
        args.append("2026-01-01T00:00:00Z")
    report = getattr(ExecutionReporters, method)
    identities = [report(ctx, *args), report(ctx, *args, execution_id=None)]
    assert identities[0] != identities[1]
    assert all(
        isinstance(identity, str) and identity.strip() for identity in identities
    )
    if reporting == "unavailable":
        reporter.assert_not_called()
    else:
        assert reporter.call_args_list == [
            call(identity, *args) for identity in identities
        ]


@pytest.mark.parametrize(
    "method",
    [
        "created",
        "started",
        "started_at",
        "succeeded",
        "succeeded_at",
        "failed",
        "failed_at",
    ],
)
def test_reporters_forward_arguments_and_ignore_reporting_failures(method: str) -> None:
    ctx = MagicMock(spec=ExecutionReporter)
    reporter = getattr(ctx, "report_execution_" + method)
    reporter.side_effect = [None, RuntimeError("report failed")]
    args = [
        "",
        ExecutionEntityTypes.TOOL,
        "search",
        {"toolCallId": "call-1"},
    ]
    if method.startswith("failed"):
        args.extend(
            [ValueError("business failed"), ExecutionProblemCategories.TOOL_CALL_FAILED]
        )
    if method.endswith("_at"):
        args.append("2026-01-01T00:00:00Z")
    initial_report = method in {"created", "started", "started_at"}
    helper_args = args[1:] if initial_report else args
    helper_kwargs = {"execution_id": args[0]} if initial_report else {}
    for _ in range(2):
        result = getattr(ExecutionReporters, method)(ctx, *helper_args, **helper_kwargs)
        if initial_report:
            assert result == args[0]
    assert reporter.call_args_list == [call(*args), call(*args)]


def test_reports_keep_identity_with_changed_metadata_or_repeated_reports() -> None:
    ctx = MagicMock(spec=ExecutionReporter)
    identities = []
    for _ in range(2):
        execution_id = ExecutionReporters.created(ctx, "custom", "same", {})
        identities.append(execution_id)
        assert (
            ExecutionReporters.started(
                ctx, "custom", "same", {"stage": "started"}, execution_id=execution_id
            )
            == execution_id
        )
        ExecutionReporters.succeeded(
            ctx, execution_id, "custom", "same", {"result": "done"}
        )
        ExecutionReporters.succeeded(
            ctx, execution_id, "custom", "same", {"result": "done"}
        )
    assert [
        call.args[0] for call in ctx.report_execution_started.call_args_list
    ] == identities
    assert [call.args[0] for call in ctx.report_execution_succeeded.call_args_list] == [
        identity for identity in identities for _ in range(2)
    ]
