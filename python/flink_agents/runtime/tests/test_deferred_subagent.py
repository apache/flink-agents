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
"""Tests for the deferred sub-agent futures and call routing."""

from concurrent.futures import CancelledError
from typing import Any, Callable, NamedTuple

import pytest

from flink_agents.api.subagent import SubagentResult
from flink_agents.runtime.deferred_subagent import (
    DeferredSubagentFuture,
    DeferredSubagentSetup,
)
from flink_agents.runtime.subagent_handles import (
    CompletedSubagentFuture,
    PendingSubagentCallRegistry,
)
from flink_agents.runtime.tests.test_base_subagent import _FakeTask, _run


class _DurableExecuteCall(NamedTuple):
    """One recorded ``durable_execute`` invocation."""

    func: Any
    args: tuple
    reconciler: Any
    durable_id: str | None


class _RecordingContext:
    """Fake context recording durable execution."""

    def __init__(self) -> None:
        self.durable_execute_calls: list[_DurableExecuteCall] = []

    def durable_execute(
        self,
        func: Any,
        *args: Any,
        reconciler: Any = None,
        durable_id: str | None = None,
        **kwargs: Any,
    ) -> Any:
        self.durable_execute_calls.append(
            _DurableExecuteCall(func, args, reconciler, durable_id)
        )
        return func(*args)


class _AwaitingContext(_RecordingContext):
    """Recording context whose ``durable_execute_async`` is awaitable.

    Records how many requests had been issued when the first wait started:
    a batched wait prepares every pending deferred handle up front, so the
    whole batch is issued before any execution starts.
    """

    def __init__(self) -> None:
        super().__init__()
        self.issued_before_first_wait: int | None = None
        self.issued_count = 0

    def durable_execute_async(
        self,
        func: Any,
        *args: Any,
        reconciler: Any = None,
        durable_id: str | None = None,
        **kwargs: Any,
    ) -> Any:
        if self.issued_before_first_wait is None:
            self.issued_before_first_wait = self.issued_count
        self.durable_execute_calls.append(
            _DurableExecuteCall(func, args, reconciler, durable_id)
        )
        return _ImmediateAwaitable(func(*args))


class _ImmediateAwaitable:
    """Awaitable resolving without yielding, mirroring a cached durable result."""

    def __init__(self, value: Any) -> None:
        self._value = value

    def __await__(self) -> Any:
        return self._value
        yield  # pragma: no cover - makes this a generator function


def _echo_callable(prompt: Any) -> Callable[[], SubagentResult]:
    """Callable echoing the prompt as a successful result."""

    def call() -> SubagentResult:
        return SubagentResult.ok([prompt])

    return call


def _raising_callable(exc: Exception) -> Callable[[], SubagentResult]:
    """Callable raising a system-level failure instead of returning a result."""

    def call() -> SubagentResult:
        raise exc

    return call


_ISSUED = 0
_REGISTRY: PendingSubagentCallRegistry | None = None


def _reset_echoing_state() -> None:
    global _ISSUED, _REGISTRY
    _ISSUED = 0
    _REGISTRY = None


class _MockDeferredSetup(DeferredSubagentSetup):
    """Setup issuing deferred futures like the runtime bases do.

    Counts how many times a request has been issued in module-level state
    (one setup instance is shared by every resolving task, mirroring the
    runtime); an optional per-task registry records deferred handles until
    they resolve.
    """

    def prepare(
        self,
        ctx: Any,
        prompt: Any,
        session_id: str,
        call_id: str,
    ) -> tuple:
        """Count the issue and prepare the echoing triple."""
        global _ISSUED
        _ISSUED += 1
        if isinstance(ctx, _AwaitingContext):
            ctx.issued_count += 1
        return (f"{session_id}#{call_id}", _echo_callable(prompt), None)

    def pending_call_registry(self) -> PendingSubagentCallRegistry | None:
        """Opt into tracking when a registry has been assigned."""
        return _REGISTRY


class _LifecycleDeferredSetup(DeferredSubagentSetup):
    """Deferred setup tracking handles through the base's per-task registry."""

    def prepare(
        self,
        ctx: Any,
        prompt: Any,
        session_id: str,
        call_id: str,
    ) -> tuple:
        """Prepare the echoing triple keyed by the assigned identity."""
        return (f"{session_id}#{call_id}", _echo_callable(prompt), None)


def test_prepare_returns_the_prepared_triple() -> None:
    """``prepare`` supplies the durable id, the call, and the reconciler."""
    setup = _MockDeferredSetup()
    ctx = _RecordingContext()

    durable_id, call, reconcile = setup.prepare(ctx, "ping", "sid-1", "call-1")

    assert durable_id == "sid-1#call-1"
    assert reconcile is None
    assert call().result == ["ping"]


def test_submit_with_explicit_ids_routes_durably() -> None:
    """``submit`` with explicit ids routes through durable execution."""
    setup = _MockDeferredSetup()
    ctx = _AwaitingContext()

    future = _run(setup.submit(ctx, "hello", "explicit-sid", "call-1"))
    result = _run(future)

    assert len(ctx.durable_execute_calls) == 1
    assert ctx.durable_execute_calls[0].durable_id == "explicit-sid#call-1"
    assert result.success is True
    assert result.result == ["hello"]


def test_short_forms_without_a_task_fail_to_assign() -> None:
    """Short forms assign through the executing task; without one they fail."""
    setup = _MockDeferredSetup()
    ctx = _RecordingContext()

    with pytest.raises(RuntimeError, match="No prepared action task"):
        _run(setup.submit(ctx, "ping"))
    with pytest.raises(RuntimeError, match="No prepared action task"):
        _run(setup.submit(ctx, "ping", "sid-1"))


def test_callable_id_is_derived_from_the_explicit_identity() -> None:
    """The framework keys the durable call by the caller-supplied ids."""
    setup = _MockDeferredSetup()
    ctx = _AwaitingContext()

    future = _run(setup.submit(ctx, "ping", "sid-1", "call-1"))
    _run(future)

    assert ctx.durable_execute_calls[0].durable_id == "sid-1#call-1"


def test_submit_returns_handle_carrying_the_explicit_identity() -> None:
    """``submit`` exposes the caller-supplied identity on the handle."""
    setup = _MockDeferredSetup()
    ctx = _AwaitingContext()

    future = _run(setup.submit(ctx, "ping", "sid-1", "call-1"))

    assert future.session_id == "sid-1"
    assert future.call_id == "call-1"
    assert future.done() is False
    assert _run(future).result == ["ping"]
    assert future.done() is True


def test_submit_resolves_once_and_keys_by_the_call_identity() -> None:
    """Resolving twice runs one durable call, keyed by ``session_id#call_id``."""
    setup = _MockDeferredSetup()
    ctx = _AwaitingContext()

    future = _run(setup.submit(ctx, "ping", "sid-1", "call-1"))
    first = _run(future)
    second = _run(future)

    assert first is second
    assert len(ctx.durable_execute_calls) == 1
    assert ctx.durable_execute_calls[0].durable_id == "sid-1#call-1"


def test_submit_defers_the_request_until_resolve() -> None:
    """``submit`` never issues the request up front; resolve does."""
    global _REGISTRY
    _reset_echoing_state()
    ctx = _AwaitingContext()
    setup = _MockDeferredSetup()
    registry = PendingSubagentCallRegistry("my_action")
    _REGISTRY = registry

    future = _run(setup.submit(ctx, "ping", "sid-1", "call-1"))

    assert _ISSUED == 0
    assert registry.is_empty() is False

    _run(future)

    assert _ISSUED == 1
    assert registry.is_empty() is True


def test_deferred_handles_are_resolved_in_submission_order() -> None:
    """``combine`` resolves every handle in submission order; the group
    prepares the whole batch before any execution starts.
    """
    global _REGISTRY
    _reset_echoing_state()
    ctx = _AwaitingContext()
    setup = _MockDeferredSetup()
    registry = PendingSubagentCallRegistry("my_action")
    _REGISTRY = registry

    first = _run(setup.submit(ctx, "a", "sid-1", "call-1"))
    second = _run(setup.submit(ctx, "b", "sid-1", "call-2"))
    third = _run(setup.submit(ctx, "c", "sid-1", "call-3"))
    assert _ISSUED == 0

    outcomes = _run(first.combine(second, third))

    assert [outcome.result for outcome in outcomes] == [["a"], ["b"], ["c"]]
    assert first.done()
    assert second.done()
    assert third.done()
    assert registry.is_empty() is True
    # The group prepared the whole batch before the first execution
    # started.
    assert ctx.issued_before_first_wait == 3
    assert _ISSUED == 3


def test_batching_an_already_resolved_handle_joins_the_batch() -> None:
    """A resolved handle contributes its value to a mixed batch."""
    ctx = _AwaitingContext()
    setup = _MockDeferredSetup()

    resolved = CompletedSubagentFuture("s", "c", SubagentResult.ok("x"))
    pending = _run(setup.submit(ctx, "pending", "sid-1", "call-1"))

    outcomes = _run(resolved.combine(pending))

    # Only the pending handle prepared a request; the resolved one kept its
    # value.
    assert ctx.issued_before_first_wait == 1
    assert [outcome.result for outcome in outcomes] == ["x", ["pending"]]
    assert pending.done()
    assert resolved.done()


def test_registry_check_empty_fails_on_dropped_handles() -> None:
    """``check_empty`` names every handle the action dropped unresolved."""
    registry = PendingSubagentCallRegistry("my_action")
    registry.track_pending_subagent_call("sid-1#call-1")

    with pytest.raises(RuntimeError, match="sid-1#call-1"):
        registry.check_empty()

    # The state is left intact, so the caller can inspect the dropped handles.
    assert registry.is_empty() is False


def test_system_level_failure_propagates_out_of_resolve() -> None:
    """An exception escaping the callable propagates instead of folding.

    The integration folds its own comprehensible failures into the
    SubagentResult; a raised exception is system-level.
    """
    ctx = _AwaitingContext()
    boom = RuntimeError("durable execution crashed")

    future = DeferredSubagentFuture(
        "sid-1",
        "call-1",
        ctx,
        prepared_factory=lambda: ("sid-1#call-1", _raising_callable(boom), None),
    )

    with pytest.raises(RuntimeError, match="durable execution crashed"):
        _run(future)
    assert future.done() is False


def test_lifecycle_dropped_handles_fail_the_finished_task() -> None:
    """A short-form handle left unresolved fails the task on finish."""
    setup = _LifecycleDeferredSetup()
    setup.on_action_prepared(_FakeTask())
    _run(setup.submit(_AwaitingContext(), "p"))

    with pytest.raises(RuntimeError, match="finished without resolving"):
        setup.on_action_finishing(_FakeTask())


def test_lifecycle_an_unawaited_submit_registers_nothing() -> None:
    """Dropping the submit awaitable issues nothing, so the finish check
    finds no handle to report and the mistake stays invisible to it.
    """
    setup = _LifecycleDeferredSetup()
    setup.on_action_prepared(_FakeTask())
    submission = setup.submit(_AwaitingContext(), "p")

    setup.on_action_finishing(_FakeTask())

    # Close the dropped coroutine so it does not warn while other tests run.
    submission.close()


def test_lifecycle_resolved_handles_let_the_task_finish() -> None:
    """Resolving every short-form handle lets the task finish cleanly."""
    setup = _LifecycleDeferredSetup()
    setup.on_action_prepared(_FakeTask())
    handle = _run(setup.submit(_AwaitingContext(), "p"))

    _run(handle)
    setup.on_action_finishing(_FakeTask())


def test_deferred_future_prepares_through_the_factory_once() -> None:
    """Handles prepare through the supplied factory exactly once."""
    ctx = _AwaitingContext()
    calls = 0

    def factory() -> tuple:
        nonlocal calls
        calls += 1
        return ("sid-1#call-1", _echo_callable("ping"), None)

    future: DeferredSubagentFuture = DeferredSubagentFuture(
        "sid-1", "call-1", ctx, prepared_factory=factory
    )

    assert _run(future).result == ["ping"]
    assert calls == 1


def test_cancel_before_resolve_discards_the_request() -> None:
    """A cancelled deferred handle never issues the request."""
    global _REGISTRY
    _reset_echoing_state()
    ctx = _RecordingContext()
    setup = _MockDeferredSetup()
    registry = PendingSubagentCallRegistry("my_action")
    _REGISTRY = registry

    future = _run(setup.submit(ctx, "ping", "sid-1", "call-1"))
    future.cancel()

    assert _ISSUED == 0
    # The cancelled handle unregisters, so tracking setups see nothing left.
    assert registry.is_empty() is True
    assert future.done() is True
    with pytest.raises(CancelledError):
        future.prepare()
    with pytest.raises(CancelledError):
        _run(future)
    assert ctx.durable_execute_calls == []


def test_cancel_propagates_through_the_group() -> None:
    """A group cancel reaches every handle; resolving the batch fails."""
    _reset_echoing_state()
    ctx = _AwaitingContext()
    setup = _MockDeferredSetup()

    first = _run(setup.submit(ctx, "a", "sid-1", "call-1"))
    second = _run(setup.submit(ctx, "b", "sid-1", "call-2"))
    first.combine(second).cancel()

    with pytest.raises(CancelledError):
        _run(first.combine(second))
    assert _ISSUED == 0


def test_cancel_of_a_resolved_handle_is_ignored() -> None:
    """An already resolved handle keeps its value after a cancel request."""
    ctx = _AwaitingContext()
    setup = _MockDeferredSetup()

    future = _run(setup.submit(ctx, "ping", "sid-1", "call-1"))
    assert _run(future).result == ["ping"]

    future.cancel()

    assert _run(future).result == ["ping"]
