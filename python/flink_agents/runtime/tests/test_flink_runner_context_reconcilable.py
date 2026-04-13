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
import asyncio
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from typing import Any, Callable

import cloudpickle
import pytest

from flink_agents.runtime.durable_execution import (
    _compute_args_digest,
    _compute_function_id,
)
from flink_agents.runtime.flink_runner_context import FlinkRunnerContext


@dataclass
class _StoredCallResult:
    function_id: str
    args_digest: str
    status: str
    result_payload: bytes | None = None
    exception_payload: bytes | None = None


class _FakeJavaRunnerContext:
    def __init__(self) -> None:
        self.call_results: list[_StoredCallResult] = []
        self.current_call_index = 0
        self.operations: list[str] = []

    def getCurrentCallResultFields(self) -> list[Any] | None:
        self.operations.append("peek")
        if self.current_call_index < len(self.call_results):
            current = self.call_results[self.current_call_index]
            return [
                current.function_id,
                current.args_digest,
                current.status,
                current.result_payload,
                current.exception_payload,
            ]
        return None

    def matchNextOrClearSubsequentCallResult(
        self, function_id: str, args_digest: str
    ) -> list[Any] | None:
        self.operations.append("match")
        if self.current_call_index < len(self.call_results):
            current = self.call_results[self.current_call_index]
            if (
                current.function_id == function_id
                and current.args_digest == args_digest
            ):
                self.current_call_index += 1
                return [True, current.result_payload, current.exception_payload]
            self.call_results = self.call_results[: self.current_call_index]
        return None

    def recordCallCompletion(
        self,
        function_id: str,
        args_digest: str,
        result_payload: bytes | None,
        exception_payload: bytes | None,
    ) -> None:
        self.operations.append("record")
        status = "FAILED" if exception_payload is not None else "SUCCEEDED"
        self.call_results.append(
            _StoredCallResult(
                function_id=function_id,
                args_digest=args_digest,
                status=status,
                result_payload=result_payload,
                exception_payload=exception_payload,
            )
        )
        self.current_call_index += 1

    def appendPendingCall(self, function_id: str, args_digest: str) -> None:
        self.operations.append("append_pending")
        self.call_results.append(
            _StoredCallResult(
                function_id=function_id,
                args_digest=args_digest,
                status="PENDING",
            )
        )

    def finalizeCurrentCall(
        self,
        function_id: str,
        args_digest: str,
        result_payload: bytes | None,
        exception_payload: bytes | None,
    ) -> None:
        self.operations.append("finalize")
        current = self.call_results[self.current_call_index]
        assert current.status == "PENDING"
        assert current.function_id == function_id
        assert current.args_digest == args_digest
        self.call_results[self.current_call_index] = _StoredCallResult(
            function_id=function_id,
            args_digest=args_digest,
            status="FAILED" if exception_payload is not None else "SUCCEEDED",
            result_payload=result_payload,
            exception_payload=exception_payload,
        )
        self.current_call_index += 1

    def clearCallResultsFromCurrentIndexAndPersist(self) -> None:
        self.operations.append("clear")
        self.call_results = self.call_results[: self.current_call_index]


def _create_runner_context(
    j_runner_context: _FakeJavaRunnerContext,
) -> FlinkRunnerContext:
    ctx = FlinkRunnerContext.__new__(FlinkRunnerContext)
    ctx._j_runner_context = j_runner_context
    ctx.executor = ThreadPoolExecutor(max_workers=1)
    ctx._FlinkRunnerContext__agent_plan = None
    ctx._FlinkRunnerContext__ltm = None
    return ctx


def _close_runner_context(ctx: FlinkRunnerContext) -> None:
    ctx.executor.shutdown(wait=True)


def _run_async(result: Any) -> object:
    async def _await_result() -> Any:
        return await result

    return asyncio.run(_await_result())


def _preload_pending(
    j_runner_context: _FakeJavaRunnerContext,
    func: Callable[..., Any],
    *args: Any,
    **kwargs: Any,
) -> None:
    j_runner_context.call_results.append(
        _StoredCallResult(
            function_id=_compute_function_id(func),
            args_digest=_compute_args_digest(args, kwargs),
            status="PENDING",
        )
    )


def _call_value(value: str) -> str:
    return f"call:{value}"


def test_flink_runner_context_sync_with_reconciler_executes_original_call() -> None:
    """Start a new durable call when no pending state exists."""
    j_runner_context = _FakeJavaRunnerContext()
    ctx = _create_runner_context(j_runner_context)
    reconciler_called = False

    def reconciler() -> str:
        nonlocal reconciler_called
        reconciler_called = True
        return "reconciled:order-1"

    try:
        result = ctx.durable_execute(_call_value, "order-1", reconciler=reconciler)
    finally:
        _close_runner_context(ctx)

    assert result == "call:order-1"
    assert reconciler_called is False
    assert j_runner_context.operations == ["peek", "append_pending", "finalize"]
    assert j_runner_context.call_results[0].status == "SUCCEEDED"


def test_flink_runner_context_sync_reconciler_success() -> None:
    """Persist a recovered success without re-executing the original call."""
    j_runner_context = _FakeJavaRunnerContext()
    call_count = 0

    def tracked_call(value: str) -> str:
        nonlocal call_count
        call_count += 1
        return _call_value(value)

    _preload_pending(j_runner_context, tracked_call, "order-1")
    ctx = _create_runner_context(j_runner_context)

    try:
        result = ctx.durable_execute(
            tracked_call,
            "order-1",
            reconciler=lambda: "reconciled:order-1",
        )
    finally:
        _close_runner_context(ctx)

    assert result == "reconciled:order-1"
    assert call_count == 0
    assert j_runner_context.operations == ["peek", "finalize"]
    assert cloudpickle.loads(j_runner_context.call_results[0].result_payload) == (
        "reconciled:order-1"
    )


def test_flink_runner_context_sync_reconciler_exception_persists_failure() -> None:
    """Persist a recovered failure from the reconciler and re-raise it."""
    j_runner_context = _FakeJavaRunnerContext()
    call_count = 0

    def tracked_call(value: str) -> str:
        nonlocal call_count
        call_count += 1
        return _call_value(value)

    _preload_pending(j_runner_context, tracked_call, "order-1")
    ctx = _create_runner_context(j_runner_context)

    def reconciler() -> str:
        error_message = "failed:order-1"
        raise ValueError(error_message)

    try:
        with pytest.raises(ValueError, match="failed:order-1"):
            ctx.durable_execute(tracked_call, "order-1", reconciler=reconciler)
    finally:
        _close_runner_context(ctx)

    assert call_count == 0
    assert j_runner_context.operations == ["peek", "finalize"]
    assert j_runner_context.call_results[0].status == "FAILED"
    persisted_exception = cloudpickle.loads(
        j_runner_context.call_results[0].exception_payload
    )
    assert isinstance(persisted_exception, ValueError)
    assert str(persisted_exception) == "failed:order-1"
    assert j_runner_context.current_call_index == 1


def test_flink_runner_context_sync_reconciler_mismatch_clears_and_executes() -> None:
    """Clear mismatched persisted state before executing the original call."""
    j_runner_context = _FakeJavaRunnerContext()
    stale_result_payload = cloudpickle.dumps("stale")
    j_runner_context.call_results.extend(
        [
            _StoredCallResult(
                function_id=_compute_function_id(_call_value),
                args_digest=_compute_args_digest(("other-order",), {}),
                status="PENDING",
            ),
            _StoredCallResult(
                function_id="stale.function",
                args_digest="stale-args",
                status="SUCCEEDED",
                result_payload=stale_result_payload,
            ),
        ]
    )
    ctx = _create_runner_context(j_runner_context)
    reconciler_called = False

    def reconciler() -> str:
        nonlocal reconciler_called
        reconciler_called = True
        return "reconciled:order-1"

    try:
        result = ctx.durable_execute(_call_value, "order-1", reconciler=reconciler)
    finally:
        _close_runner_context(ctx)

    assert result == "call:order-1"
    assert reconciler_called is False
    assert j_runner_context.operations == ["peek", "clear", "append_pending", "finalize"]
    assert len(j_runner_context.call_results) == 1
    assert j_runner_context.call_results[0].function_id == _compute_function_id(_call_value)
    assert j_runner_context.call_results[0].args_digest == _compute_args_digest(
        ("order-1",), {}
    )
    assert j_runner_context.call_results[0].status == "SUCCEEDED"


def test_flink_runner_context_async_writes_pending_on_await() -> None:
    """Defer pending-state writes for async execution until await time."""
    j_runner_context = _FakeJavaRunnerContext()
    ctx = _create_runner_context(j_runner_context)
    reconciler_called = False

    def reconciler() -> str:
        nonlocal reconciler_called
        reconciler_called = True
        return "reconciled:order-1"

    try:
        async_result = ctx.durable_execute_async(
            _call_value,
            "order-1",
            reconciler=reconciler,
        )
        assert j_runner_context.call_results == []
        result = _run_async(async_result)
    finally:
        _close_runner_context(ctx)

    assert result == "call:order-1"
    assert reconciler_called is False
    assert j_runner_context.operations == ["peek", "append_pending", "finalize"]
    assert j_runner_context.call_results[0].status == "SUCCEEDED"


def test_flink_runner_context_async_reconciler_success() -> None:
    """Recover a successful async result through the reconciler."""
    j_runner_context = _FakeJavaRunnerContext()
    call_count = 0

    def tracked_call(value: str) -> str:
        nonlocal call_count
        call_count += 1
        return _call_value(value)

    _preload_pending(j_runner_context, tracked_call, "order-1")
    ctx = _create_runner_context(j_runner_context)

    try:
        async_result = ctx.durable_execute_async(
            tracked_call,
            "order-1",
            reconciler=lambda: "reconciled:order-1",
        )
        result = _run_async(async_result)
    finally:
        _close_runner_context(ctx)

    assert result == "reconciled:order-1"
    assert call_count == 0
    assert j_runner_context.operations == ["peek", "finalize"]


def test_flink_runner_context_async_reconciler_exception_persists_failure() -> None:
    """Persist an async reconciler failure and re-raise it."""
    j_runner_context = _FakeJavaRunnerContext()
    call_count = 0

    def tracked_call(value: str) -> str:
        nonlocal call_count
        call_count += 1
        return _call_value(value)

    _preload_pending(j_runner_context, tracked_call, "order-1")
    ctx = _create_runner_context(j_runner_context)

    def reconciler() -> str:
        error_message = "reconcile unavailable"
        raise RuntimeError(error_message)

    try:
        async_result = ctx.durable_execute_async(
            tracked_call,
            "order-1",
            reconciler=reconciler,
        )
        with pytest.raises(RuntimeError, match="reconcile unavailable"):
            _run_async(async_result)
    finally:
        _close_runner_context(ctx)

    assert call_count == 0
    assert j_runner_context.operations == ["peek", "finalize"]
    assert j_runner_context.call_results[0].status == "FAILED"
    persisted_exception = cloudpickle.loads(
        j_runner_context.call_results[0].exception_payload
    )
    assert isinstance(persisted_exception, RuntimeError)
    assert str(persisted_exception) == "reconcile unavailable"
    assert j_runner_context.current_call_index == 1


def test_flink_runner_context_reconciler_kwarg_is_not_forwarded() -> None:
    """Keep the reserved reconciler kwarg out of the user function call."""
    j_runner_context = _FakeJavaRunnerContext()
    ctx = _create_runner_context(j_runner_context)

    def collect_kwargs(**kwargs: Any) -> dict[str, Any]:
        return kwargs

    try:
        result = ctx.durable_execute(collect_kwargs, reconciler=lambda: "unused")
    finally:
        _close_runner_context(ctx)

    assert result == {}
