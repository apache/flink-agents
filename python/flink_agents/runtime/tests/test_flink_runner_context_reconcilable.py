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
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from typing import Any, Callable

import cloudpickle
import pytest

from flink_agents.api.runner_context import DurableCall
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.runtime.durable_execution import (
    _compute_args_digest,
    _compute_function_id,
    durable_identity_for_call,
)
from flink_agents.runtime.flink_runner_context import FlinkRunnerContext


@dataclass
class _StoredCallResult:
    function_id: str
    args_digest: str
    status: str
    result_payload: bytes | None = None
    exception_payload: bytes | None = None


def _durable_call(
    func: Callable[..., Any],
    *args: Any,
    **kwargs: Any,
) -> DurableCall:
    return DurableCall(func=func, args=args, kwargs=kwargs or None)


def _stored_call(
    func: Callable[..., Any],
    *args: Any,
    status: str,
    **kwargs: Any,
) -> _StoredCallResult:
    function_id, args_digest = durable_identity_for_call(func, args, kwargs or None)
    return _StoredCallResult(
        function_id=function_id,
        args_digest=args_digest,
        status=status,
    )


class _FakeJavaRunnerContext:
    def __init__(self) -> None:
        self.call_results: list[_StoredCallResult] = []
        self.current_call_index = 0
        self.operations: list[str] = []

    def getCurrentCallIndex(self) -> int:
        return self.current_call_index

    def getCallResultFieldsAt(self, index: int) -> list[Any] | None:
        self.operations.append(f"read_at:{index}")
        if index < len(self.call_results):
            current = self.call_results[index]
            return [
                current.function_id,
                current.args_digest,
                current.status,
                current.result_payload,
                current.exception_payload,
            ]
        return None

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
                if current.status == "PENDING":
                    return None
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

    def reservePendingBatch(
        self, function_ids: list[str], args_digests: list[str]
    ) -> None:
        self.operations.append(f"reserve:{len(function_ids)}")
        for function_id, digest in zip(function_ids, args_digests, strict=True):
            self.call_results.append(
                _StoredCallResult(
                    function_id=function_id,
                    args_digest=digest,
                    status="PENDING",
                )
            )

    def finalizeCallAt(
        self,
        index: int,
        function_id: str,
        args_digest: str,
        result_payload: bytes | None,
        exception_payload: bytes | None,
    ) -> None:
        self.operations.append(f"finalize_at:{index}")
        current = self.call_results[index]
        assert current.status == "PENDING"
        assert current.function_id == function_id
        assert current.args_digest == args_digest
        self.call_results[index] = _StoredCallResult(
            function_id=function_id,
            args_digest=args_digest,
            status="FAILED" if exception_payload is not None else "SUCCEEDED",
            result_payload=result_payload,
            exception_payload=exception_payload,
        )

    def clearCallResultsFromAndPersist(self, index: int) -> None:
        self.operations.append(f"clear_at:{index}")
        self.call_results = self.call_results[:index]

    def advanceCallIndexBy(self, count: int) -> None:
        self.operations.append(f"advance:{count}")
        self.current_call_index += count


def _create_runner_context(
    j_runner_context: _FakeJavaRunnerContext,
    config: AgentConfiguration | None = None,
    executor_workers: int = 2,
) -> FlinkRunnerContext:
    ctx = FlinkRunnerContext.__new__(FlinkRunnerContext)
    ctx._j_runner_context = j_runner_context
    ctx.executor = ThreadPoolExecutor(max_workers=executor_workers)
    ctx._FlinkRunnerContext__agent_plan = None
    ctx._FlinkRunnerContext__ltm = None
    ctx._FlinkRunnerContext__config = config or AgentConfiguration({})
    return ctx


def _close_runner_context(ctx: FlinkRunnerContext) -> None:
    ctx.executor.shutdown(wait=True)


def _run_async(result: Any) -> object:
    iterator = result.__await__()
    value = None
    while True:
        try:
            iterator.send(value)
            value = None
        except StopIteration as e:  # noqa: PERF203
            return e.value


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
    assert j_runner_context.operations == [
        "peek",
        "clear",
        "append_pending",
        "finalize",
    ]
    assert len(j_runner_context.call_results) == 1
    assert j_runner_context.call_results[0].function_id == _compute_function_id(
        _call_value
    )
    assert j_runner_context.call_results[0].args_digest == _compute_args_digest(
        ("order-1",), {}
    )
    assert j_runner_context.call_results[0].status == "SUCCEEDED"


def test_flink_runner_context_durable_execute_reexecutes_pending_after_batch_reservation() -> (
    None
):
    """Finalize matching pending slots in place during serial recovery."""
    j_runner_context = _FakeJavaRunnerContext()
    first_call_count = 0
    second_call_count = 0

    def first_call() -> str:
        nonlocal first_call_count
        first_call_count += 1
        return "one"

    def second_call() -> str:
        nonlocal second_call_count
        second_call_count += 1
        return "two"

    first_id, first_digest = durable_identity_for_call(first_call, (), None)
    second_id, second_digest = durable_identity_for_call(second_call, (), None)
    j_runner_context.call_results = [
        _StoredCallResult(
            function_id=first_id,
            args_digest=first_digest,
            status="PENDING",
        ),
        _StoredCallResult(
            function_id=second_id,
            args_digest=second_digest,
            status="PENDING",
        ),
    ]
    ctx = _create_runner_context(j_runner_context)

    try:
        assert ctx.durable_execute(first_call) == "one"
        assert ctx.durable_execute(second_call) == "two"
    finally:
        _close_runner_context(ctx)

    assert first_call_count == 1
    assert second_call_count == 1
    assert j_runner_context.current_call_index == 2
    assert len(j_runner_context.call_results) == 2
    assert j_runner_context.call_results[0].status == "SUCCEEDED"
    assert j_runner_context.call_results[1].status == "SUCCEEDED"
    assert j_runner_context.operations.count("finalize") == 2
    assert "record" not in j_runner_context.operations


def test_flink_runner_context_durable_execute_async_reexecutes_pending_after_batch_reservation() -> (
    None
):
    """Finalize matching pending slots in place during async serial recovery."""
    j_runner_context = _FakeJavaRunnerContext()
    call_count = 0

    def tracked_call(value: str) -> str:
        nonlocal call_count
        call_count += 1
        return f"call:{value}"

    function_id, args_digest = durable_identity_for_call(tracked_call, ("order-1",), None)
    j_runner_context.call_results = [
        _StoredCallResult(
            function_id=function_id,
            args_digest=args_digest,
            status="PENDING",
        ),
    ]
    ctx = _create_runner_context(j_runner_context)

    try:
        async_result = ctx.durable_execute_async(tracked_call, "order-1")
        result = _run_async(async_result)
    finally:
        _close_runner_context(ctx)

    assert result == "call:order-1"
    assert call_count == 1
    assert j_runner_context.current_call_index == 1
    assert len(j_runner_context.call_results) == 1
    assert j_runner_context.call_results[0].status == "SUCCEEDED"
    assert j_runner_context.operations == ["peek", "finalize"]


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


def test_flink_runner_context_durable_execute_all_async_runs_calls_in_parallel() -> None:
    j_runner_context = _FakeJavaRunnerContext()
    parallelism = 3
    config = AgentConfiguration(
        {"tool-call.batch.timeout.ms": -1, "tool-call.parallelism": parallelism}
    )
    ctx = _create_runner_context(
        j_runner_context, config=config, executor_workers=parallelism
    )

    # A barrier trips only when every call is executing concurrently. If the
    # runtime instead ran them serially, the first call would block until the
    # timeout and raise BrokenBarrierError, deterministically failing the test
    # rather than relying on a fragile wall-clock threshold. On genuine
    # parallel execution the barrier releases immediately, so the success path
    # stays fast.
    barrier = threading.Barrier(parallelism, timeout=10)

    def concurrent_call(value: str) -> str:
        barrier.wait()
        return value

    try:
        outcomes = _run_async(
            ctx.durable_execute_all_async(
                [
                    _durable_call(concurrent_call, "one"),
                    _durable_call(concurrent_call, "two"),
                    _durable_call(concurrent_call, "three"),
                ]
            )
        )
    finally:
        _close_runner_context(ctx)

    assert [outcome.value for outcome in outcomes] == ["one", "two", "three"]
    assert [result.status for result in j_runner_context.call_results] == [
        "SUCCEEDED",
        "SUCCEEDED",
        "SUCCEEDED",
    ]
    assert j_runner_context.current_call_index == 3


def test_flink_runner_context_durable_execute_all_async_initial_batch() -> None:
    j_runner_context = _FakeJavaRunnerContext()
    ctx = _create_runner_context(j_runner_context)

    try:
        outcomes = _run_async(
            ctx.durable_execute_all_async(
                [
                    _durable_call(_call_value, "one"),
                    _durable_call(_call_value, "two"),
                ]
            )
        )
    finally:
        _close_runner_context(ctx)

    assert [outcome.value for outcome in outcomes] == ["call:one", "call:two"]
    assert [result.status for result in j_runner_context.call_results] == [
        "SUCCEEDED",
        "SUCCEEDED",
    ]
    assert j_runner_context.current_call_index == 2


def test_flink_runner_context_durable_execute_all_async_finalize_failure_keeps_slot_pending(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    j_runner_context = _FakeJavaRunnerContext()
    ctx = _create_runner_context(j_runner_context)
    original = FlinkRunnerContext._serialize_call_payloads

    def failing_serialize(
        result: Any, exception: BaseException | None
    ) -> tuple[bytes | None, bytes | None]:
        if result == "two":
            msg = "serialize failed"
            raise RuntimeError(msg)
        return original(result, exception)

    monkeypatch.setattr(
        FlinkRunnerContext,
        "_serialize_call_payloads",
        staticmethod(failing_serialize),
    )

    try:
        outcomes = _run_async(
            ctx.durable_execute_all_async(
                [
                    _durable_call(lambda: "one"),
                    _durable_call(lambda: "two"),
                ]
            )
        )
    finally:
        _close_runner_context(ctx)

    assert outcomes[0].value == "one"
    assert outcomes[1].error is not None
    assert "serialize failed" in str(outcomes[1].error)
    assert j_runner_context.call_results[0].status == "SUCCEEDED"
    assert j_runner_context.call_results[1].status == "PENDING"
    assert j_runner_context.current_call_index == 2
    assert "finalize_at:0" in j_runner_context.operations
    assert "finalize_at:1" not in j_runner_context.operations


def test_flink_runner_context_durable_execute_all_async_recovers_partial_batch() -> None:
    j_runner_context = _FakeJavaRunnerContext()
    call_count = 0

    def tracked_call(value: str) -> str:
        nonlocal call_count
        call_count += 1
        return _call_value(value)

    cached_call = _durable_call(tracked_call, "one")
    cached_function_id, cached_digest = durable_identity_for_call(
        cached_call.func, cached_call.args, cached_call.kwargs
    )
    j_runner_context.call_results.extend(
        [
            _StoredCallResult(
                function_id=cached_function_id,
                args_digest=cached_digest,
                status="SUCCEEDED",
                result_payload=cloudpickle.dumps("cached-one"),
            ),
            _stored_call(tracked_call, "two", status="PENDING"),
        ]
    )

    ctx = _create_runner_context(j_runner_context)
    try:
        outcomes = _run_async(
            ctx.durable_execute_all_async(
                [
                    _durable_call(tracked_call, "one"),
                    _durable_call(tracked_call, "two"),
                ]
            )
        )
    finally:
        _close_runner_context(ctx)

    assert [outcome.value for outcome in outcomes] == ["cached-one", "call:two"]
    assert call_count == 1
    assert j_runner_context.call_results[1].status == "SUCCEEDED"
    assert j_runner_context.current_call_index == 2


def test_flink_runner_context_durable_execute_all_async_returns_cached_failure() -> None:
    j_runner_context = _FakeJavaRunnerContext()
    cached_call = _durable_call(_call_value, "one")
    cached_function_id, cached_digest = durable_identity_for_call(
        cached_call.func, cached_call.args, cached_call.kwargs
    )
    j_runner_context.call_results.append(
        _StoredCallResult(
            function_id=cached_function_id,
            args_digest=cached_digest,
            status="FAILED",
            exception_payload=cloudpickle.dumps(ValueError("cached failure")),
        )
    )
    ctx = _create_runner_context(j_runner_context)

    try:
        outcomes = _run_async(
            ctx.durable_execute_all_async(
                [_durable_call(_call_value, "one")]
            )
        )
    finally:
        _close_runner_context(ctx)

    assert outcomes[0].is_failure()
    assert isinstance(outcomes[0].error, ValueError)
    assert str(outcomes[0].error) == "cached failure"
    assert j_runner_context.current_call_index == 1


def test_flink_runner_context_durable_execute_all_async_collects_failures() -> None:
    j_runner_context = _FakeJavaRunnerContext()
    ctx = _create_runner_context(j_runner_context)

    def fail_call() -> str:
        msg = "failed"
        raise ValueError(msg)

    try:
        outcomes = _run_async(
            ctx.durable_execute_all_async(
                [
                    _durable_call(_call_value, "one"),
                    _durable_call(fail_call),
                ]
            )
        )
    finally:
        _close_runner_context(ctx)

    assert outcomes[0].value == "call:one"
    assert outcomes[1].is_failure()
    assert isinstance(outcomes[1].error, ValueError)
    assert [result.status for result in j_runner_context.call_results] == [
        "SUCCEEDED",
        "FAILED",
    ]
    assert j_runner_context.current_call_index == 2


def test_flink_runner_context_durable_execute_all_async_timeout_keeps_completed_results() -> None:
    j_runner_context = _FakeJavaRunnerContext()
    config = AgentConfiguration({"tool-call.batch.timeout.ms": 100})
    ctx = _create_runner_context(j_runner_context, config=config)

    # The slow worker blocks on an event instead of racing a sleep against the
    # deadline. Once its worker thread starts, _mark_started_on_run has already
    # flipped the started flag, so the timeout must record it as FAILED (started
    # but unfinished) rather than PENDING. The event guarantees it never
    # finishes before the deadline, and the finally block releases it so the
    # pool can shut down.
    release = threading.Event()

    def slow_call() -> str:
        release.wait(5)
        return "slow"

    try:
        outcomes = _run_async(
            ctx.durable_execute_all_async(
                [
                    _durable_call(lambda: "fast"),
                    _durable_call(slow_call),
                ]
            )
        )
    finally:
        release.set()
        _close_runner_context(ctx)

    assert outcomes[0].value == "fast"
    assert outcomes[1].is_failure()
    assert isinstance(outcomes[1].error, TimeoutError)
    assert j_runner_context.call_results[0].status == "SUCCEEDED"
    assert j_runner_context.call_results[1].status == "FAILED"
    assert j_runner_context.current_call_index == 2


def test_flink_runner_context_durable_execute_all_async_timeout_leaves_unsubmitted_slots_pending() -> None:
    j_runner_context = _FakeJavaRunnerContext()
    config = AgentConfiguration(
        {"tool-call.batch.timeout.ms": 100, "tool-call.parallelism": 2}
    )
    ctx = _create_runner_context(j_runner_context, config=config, executor_workers=2)

    # The two blocking calls saturate the parallelism budget and hold their
    # worker threads (started flag flipped) until the deadline, so they fail; the
    # remaining two suppliers are never submitted and stay pending. Blocking on an
    # event instead of sleeping makes "started" deterministic rather than racing
    # thread startup against a short wall-clock timeout.
    release = threading.Event()

    def blocking_call(value: str) -> str:
        release.wait(5)
        return value

    try:
        outcomes = _run_async(
            ctx.durable_execute_all_async(
                [
                    _durable_call(blocking_call, "one"),
                    _durable_call(blocking_call, "two"),
                    _durable_call(_call_value, "three"),
                    _durable_call(_call_value, "four"),
                ]
            )
        )
    finally:
        release.set()
        _close_runner_context(ctx)

    assert outcomes[0].is_failure()
    assert outcomes[1].is_failure()
    assert outcomes[2].is_failure()
    assert outcomes[3].is_failure()
    assert [result.status for result in j_runner_context.call_results] == [
        "FAILED",
        "FAILED",
        "PENDING",
        "PENDING",
    ]
    assert j_runner_context.current_call_index == 4


def test_flink_runner_context_durable_execute_all_async_timeout_leaves_queued_slots_pending() -> (
    None
):
    j_runner_context = _FakeJavaRunnerContext()
    # Parallelism budget exceeds the worker count, so two suppliers are handed to a
    # saturated pool and wait in its queue without ever starting before the deadline.
    config = AgentConfiguration(
        {"tool-call.batch.timeout.ms": 100, "tool-call.parallelism": 4}
    )
    ctx = _create_runner_context(j_runner_context, config=config, executor_workers=2)

    # Blocking on an event (rather than sleeping) keeps the two running workers
    # deterministically "started but unfinished" until the deadline, while the two
    # queued suppliers never begin.
    release = threading.Event()
    executions: list[str] = []

    def blocking_call(value: str) -> str:
        executions.append(value)
        release.wait(5)
        return value

    def queued_call(value: str) -> str:
        executions.append(value)
        return value

    try:
        outcomes = _run_async(
            ctx.durable_execute_all_async(
                [
                    _durable_call(blocking_call, "one"),
                    _durable_call(blocking_call, "two"),
                    _durable_call(queued_call, "three"),
                    _durable_call(queued_call, "four"),
                ]
            )
        )
    finally:
        release.set()
        _close_runner_context(ctx)
        # Give the pool a chance to pick up any work item that escaped
        # cancellation; a retracted item stays absent from executions.
        time.sleep(0.1)

    assert all(outcome.is_failure() for outcome in outcomes)
    statuses = [result.status for result in j_runner_context.call_results]
    # Only the two workers that actually began executing are persisted as failures; the
    # queued-but-never-started pair stays pending so recovery re-executes them instead of
    # replaying a false failure.
    assert statuses.count("FAILED") == 2
    assert statuses.count("PENDING") == 2
    # The queued suppliers were cancelled before running, so the tool bodies
    # must never execute and get thrown away before recovery re-runs them.
    assert sorted(executions) == ["one", "two"]
    assert j_runner_context.current_call_index == 4


def test_flink_runner_context_durable_execute_all_async_returns_deserialize_failure_as_outcome() -> (
    None
):
    j_runner_context = _FakeJavaRunnerContext()
    call = _durable_call(lambda: "should-not-run")
    function_id, args_digest = durable_identity_for_call(
        call.func, call.args, call.kwargs
    )
    j_runner_context.call_results.append(
        _StoredCallResult(
            function_id=function_id,
            args_digest=args_digest,
            status="SUCCEEDED",
            result_payload=b"not-valid-pickle",
        )
    )
    ctx = _create_runner_context(j_runner_context)
    try:
        outcomes = _run_async(ctx.durable_execute_all_async([call]))
    finally:
        _close_runner_context(ctx)

    assert outcomes[0].is_failure()
    assert j_runner_context.current_call_index == 1


def test_flink_runner_context_durable_execute_all_async_reconciles_pending_slot() -> None:
    j_runner_context = _FakeJavaRunnerContext()
    call_count = 0
    reconcile_count = 0

    def tracked_call() -> str:
        nonlocal call_count
        call_count += 1
        msg = "call should not run"
        raise RuntimeError(msg)

    def reconciler() -> str:
        nonlocal reconcile_count
        reconcile_count += 1
        return "recovered"

    call = DurableCall(func=tracked_call, reconciler=reconciler)
    function_id, args_digest = durable_identity_for_call(
        call.func, call.args, call.kwargs
    )
    j_runner_context.call_results.append(
        _StoredCallResult(
            function_id=function_id,
            args_digest=args_digest,
            status="PENDING",
        )
    )
    ctx = _create_runner_context(j_runner_context)
    try:
        outcomes = _run_async(ctx.durable_execute_all_async([call]))
    finally:
        _close_runner_context(ctx)

    assert outcomes[0].value == "recovered"
    assert call_count == 0
    assert reconcile_count == 1
    assert j_runner_context.call_results[0].status == "SUCCEEDED"
    assert j_runner_context.current_call_index == 1


def test_flink_runner_context_durable_execute_all_async_recovers_three_slot_partial_batch() -> (
    None
):
    j_runner_context = _FakeJavaRunnerContext()
    call_count = 0

    def tracked_call(value: str) -> str:
        nonlocal call_count
        call_count += 1
        return _call_value(value)

    first = _durable_call(tracked_call, "one")
    second = _durable_call(tracked_call, "two")
    third = _durable_call(tracked_call, "three")
    first_id, first_digest = durable_identity_for_call(
        first.func, first.args, first.kwargs
    )
    second_id, second_digest = durable_identity_for_call(
        second.func, second.args, second.kwargs
    )
    third_id, third_digest = durable_identity_for_call(
        third.func, third.args, third.kwargs
    )
    j_runner_context.call_results.extend(
        [
            _StoredCallResult(
                function_id=first_id,
                args_digest=first_digest,
                status="SUCCEEDED",
                result_payload=cloudpickle.dumps("cached-one"),
            ),
            _StoredCallResult(
                function_id=second_id,
                args_digest=second_digest,
                status="SUCCEEDED",
                result_payload=cloudpickle.dumps("cached-two"),
            ),
            _StoredCallResult(
                function_id=third_id,
                args_digest=third_digest,
                status="PENDING",
            ),
        ]
    )

    ctx = _create_runner_context(j_runner_context)
    try:
        outcomes = _run_async(
            ctx.durable_execute_all_async([first, second, third])
        )
    finally:
        _close_runner_context(ctx)

    assert [outcome.value for outcome in outcomes] == [
        "cached-one",
        "cached-two",
        "call:three",
    ]
    assert call_count == 1
    assert j_runner_context.current_call_index == 3


def test_flink_runner_context_durable_execute_all_async_respects_max_parallelism() -> None:
    j_runner_context = _FakeJavaRunnerContext()
    config = AgentConfiguration(
        {"tool-call.batch.timeout.ms": -1, "tool-call.parallelism": 2}
    )
    ctx = _create_runner_context(j_runner_context, config=config, executor_workers=4)
    active_count = 0
    peak_active = 0
    counter_lock = threading.Lock()
    hold_seconds = 0.15

    def slow_call(value: str) -> str:
        nonlocal active_count, peak_active
        with counter_lock:
            active_count += 1
            peak_active = max(peak_active, active_count)
        time.sleep(hold_seconds)
        with counter_lock:
            active_count -= 1
        return value

    try:
        start = time.perf_counter()
        outcomes = _run_async(
            ctx.durable_execute_all_async(
                [
                    _durable_call(slow_call, "one"),
                    _durable_call(slow_call, "two"),
                    _durable_call(slow_call, "three"),
                    _durable_call(slow_call, "four"),
                ]
            )
        )
        elapsed = time.perf_counter() - start
    finally:
        _close_runner_context(ctx)

    assert [outcome.value for outcome in outcomes] == ["one", "two", "three", "four"]
    assert peak_active == 2
    # parallelism=2 → two waves; allow scheduler slack on shared CI runners
    assert elapsed >= hold_seconds * 2 - 0.08
    assert elapsed < hold_seconds * 4 + 0.4
    assert [result.status for result in j_runner_context.call_results] == [
        "SUCCEEDED",
        "SUCCEEDED",
        "SUCCEEDED",
        "SUCCEEDED",
    ]
    assert j_runner_context.current_call_index == 4
