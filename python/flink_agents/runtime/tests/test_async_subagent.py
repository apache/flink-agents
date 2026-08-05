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
"""Tests for the async sub-agent base in pub/sub mode."""

from concurrent.futures import CancelledError
from typing import Any, NamedTuple

import pytest
from pydantic import PrivateAttr

from flink_agents.api.subagent import Result
from flink_agents.runtime.async_subagent import (
    BaseAsyncSubagentSetup,
    RunStatus,
)
from flink_agents.runtime.tests.test_base_subagent import _FakeTask


class _DurableExecuteCall(NamedTuple):
    """One recorded durable execution invocation."""

    func: Any
    args: tuple
    reconciler: Any
    durable_id: str | None


class _RecordingContext:
    """Fake context recording durable execution and running it inline."""

    def __init__(self) -> None:
        self.durable_execute_calls: list[_DurableExecuteCall] = []
        self.async_durable_calls = 0

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

    def durable_execute_async(
        self,
        func: Any,
        *args: Any,
        reconciler: Any = None,
        durable_id: str | None = None,
        **kwargs: Any,
    ) -> Any:
        self.async_durable_calls += 1
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


class _MockAsyncSetup(BaseAsyncSubagentSetup):
    """Example integration: an in-memory asynchronous agent service.

    Demonstrates that an integration only supplies the transport primitives
    plus the optional cancel hook; counters let tests assert how many times
    each endpoint was hit.
    """

    _runs: dict = PrivateAttr(default_factory=dict)
    _queries_until_complete: int = PrivateAttr(default=2)
    _fail_on_post: bool = PrivateAttr(default=False)
    _post_count: int = PrivateAttr(default=0)
    _status_query_count: int = PrivateAttr(default=0)
    _fetch_count: int = PrivateAttr(default=0)
    _cancel_count: int = PrivateAttr(default=0)

    def __init__(
        self, queries_until_complete: int = 2, fail_on_post: bool = False
    ) -> None:
        super().__init__()
        self._queries_until_complete = queries_until_complete
        self._fail_on_post = fail_on_post
        self.status_poll_interval_seconds = 0.0

    def call_submit_request(self, session_id: str, call_id: str, prompt: Any) -> None:
        self._post_count += 1
        if self._fail_on_post:
            msg = "post failed"
            raise RuntimeError(msg)
        self._runs[f"{session_id}#{call_id}"] = {
            "result": f"done:{prompt}",
            "error": None,
            "queries_remaining": self._queries_until_complete,
        }

    def call_query_status(self, session_id: str, call_id: str) -> RunStatus:
        self._status_query_count += 1
        run = self._runs.get(f"{session_id}#{call_id}")
        if run is None:
            return RunStatus.not_started()
        if run["queries_remaining"] > 0:
            run["queries_remaining"] -= 1
            return RunStatus.running()
        if run["error"] is None:
            return RunStatus.completed()
        return RunStatus.failed(run["error"])

    def call_fetch_result(self, session_id: str, call_id: str) -> Result:
        self._fetch_count += 1
        run = self._runs.get(f"{session_id}#{call_id}")
        if run is None:
            return Result.error("no run on record")
        if run["error"] is None:
            return Result.ok(run["result"])
        return Result.error(run["error"])

    def call_cancel_request(self, session_id: str, call_id: str) -> None:
        self._cancel_count += 1

    def seed_run(
        self,
        session_id: str,
        call_id: str,
        result: Any,
        error: str | None,
        queries_until_complete: int,
    ) -> None:
        """Inject a run that already exists remotely, exercising reconciler reuse."""
        self._runs[f"{session_id}#{call_id}"] = {
            "result": result,
            "error": error,
            "queries_remaining": queries_until_complete,
        }

    def forget_run(self, session_id: str, call_id: str) -> None:
        """Drop the remote record of a run, simulating a POST that never landed."""
        self._runs.pop(f"{session_id}#{call_id}", None)

    def post_count(self) -> int:
        """Number of times the POST endpoint has been hit."""
        return self._post_count

    def status_query_count(self) -> int:
        """Number of times the status endpoint has been probed."""
        return self._status_query_count

    def fetch_count(self) -> int:
        """Number of times the result endpoint has been fetched."""
        return self._fetch_count

    def cancel_count(self) -> int:
        """Number of times the cancel hook has been invoked."""
        return self._cancel_count


def _run(awaitable: Any) -> Any:
    """Drive an awaitable the way the runtime drives an action coroutine."""
    iterator = awaitable.__await__()
    try:
        while True:
            next(iterator)
    except StopIteration as stop:
        return stop.value


# ------------------------------------------------------------------------------------------
# The pub: one durable POST, issued immediately
# ------------------------------------------------------------------------------------------


def test_submit_posts_immediately_under_the_call_identity() -> None:
    setup = _MockAsyncSetup()
    ctx = _RecordingContext()

    future = setup.submit(ctx, "ping", "sid-1", "call-1")

    assert setup.post_count() == 1
    assert setup.status_query_count() == 0
    assert setup.fetch_count() == 0
    assert len(ctx.durable_execute_calls) == 1
    assert ctx.durable_execute_calls[0].durable_id == "sid-1#call-1"
    assert ctx.durable_execute_calls[0].reconciler is not None
    assert future.session_id == "sid-1"
    assert future.call_id == "call-1"


def test_post_failure_fails_the_submit() -> None:
    setup = _MockAsyncSetup(fail_on_post=True)
    ctx = _RecordingContext()

    with pytest.raises(RuntimeError, match="post failed"):
        setup.submit(ctx, "ping", "sid-1", "call-1")
    assert setup.status_query_count() == 0
    assert setup.fetch_count() == 0


def test_short_forms_fail_without_a_prepared_task() -> None:
    setup = _MockAsyncSetup()
    ctx = _RecordingContext()

    with pytest.raises(RuntimeError, match="No prepared action task"):
        setup.submit(ctx, "ping")
    with pytest.raises(RuntimeError, match="No prepared action task"):
        setup.submit(ctx, "ping", "sid-1")
    assert setup.post_count() == 0


def test_short_forms_assign_through_the_prepared_task() -> None:
    """Short forms assign ids from the executing task and POST under them."""
    setup = _MockAsyncSetup()
    ctx = _RecordingContext()
    setup.on_task_prepared(_FakeTask())

    handle = setup.submit(ctx, "ping")

    assert setup.post_count() == 1
    assert handle.call_id == f"{handle.session_id}-1"
    posted = ctx.durable_execute_calls[0]
    assert posted.durable_id == f"{handle.session_id}#{handle.call_id}"


# ------------------------------------------------------------------------------------------
# The crash-window reconciler of the POST
# ------------------------------------------------------------------------------------------


def _recorded_reconciler(setup: _MockAsyncSetup, ctx: _RecordingContext) -> Any:
    setup.submit(ctx, "ping", "sid-1", "call-1")
    return ctx.durable_execute_calls[0].reconciler


def test_reconciler_reposts_when_the_run_is_not_on_record() -> None:
    setup = _MockAsyncSetup(queries_until_complete=1)
    ctx = _RecordingContext()
    reconciler = _recorded_reconciler(setup, ctx)
    # The remote has no record of the run: the POST never landed.
    setup.forget_run("sid-1", "call-1")

    reconciler()

    # Probe reported NOT_STARTED, so the missing POST was issued exactly once.
    assert setup.post_count() == 2
    assert setup.status_query_count() == 1


def test_reconciler_does_not_repost_a_running_run() -> None:
    setup = _MockAsyncSetup(queries_until_complete=1)
    ctx = _RecordingContext()
    reconciler = _recorded_reconciler(setup, ctx)
    setup.seed_run("sid-1", "call-1", "done:ping", None, 1)

    reconciler()

    assert setup.post_count() == 1
    assert setup.status_query_count() == 1


def test_reconciler_does_not_repost_a_terminal_run() -> None:
    setup = _MockAsyncSetup(queries_until_complete=0)
    ctx = _RecordingContext()
    reconciler = _recorded_reconciler(setup, ctx)
    setup.seed_run("sid-1", "call-1", "done:ping", None, 0)

    reconciler()

    assert setup.post_count() == 1
    assert setup.status_query_count() == 1


def test_reconciler_treats_a_failed_run_as_landed() -> None:
    setup = _MockAsyncSetup(queries_until_complete=0)
    ctx = _RecordingContext()
    reconciler = _recorded_reconciler(setup, ctx)
    setup.seed_run("sid-1", "call-1", None, "run exploded", 0)

    reconciler()

    assert setup.post_count() == 1
    assert setup.status_query_count() == 1


# ------------------------------------------------------------------------------------------
# The sub: status probes, the await composition, and direct fetch
# ------------------------------------------------------------------------------------------


def test_done_probes_the_status_directly_without_durable_calls() -> None:
    setup = _MockAsyncSetup(queries_until_complete=1)
    ctx = _RecordingContext()

    future = setup.submit(ctx, "ping", "sid-1", "call-1")

    assert future.done() is False  # first probe: RUNNING
    assert future.done() is True  # second probe: COMPLETED
    assert setup.status_query_count() == 2
    assert len(ctx.durable_execute_calls) == 1  # only the pub POST


def test_await_waits_durably_then_fetches() -> None:
    setup = _MockAsyncSetup(queries_until_complete=2)
    ctx = _RecordingContext()

    future = setup.submit(ctx, "ping", "sid-1", "call-1")
    result = _run(future)

    assert result.success is True
    assert result.result == "done:ping"
    assert ctx.durable_execute_calls[1].durable_id == "sid-1#call-1#await"
    # Two RUNNING probes, the terminal one, then the separate fetch.
    assert setup.status_query_count() == 3
    assert setup.fetch_count() == 1


def test_await_surfaces_a_failed_run_without_fetching() -> None:
    setup = _MockAsyncSetup(queries_until_complete=0)
    ctx = _RecordingContext()

    future = setup.submit(ctx, "ping", "sid-1", "call-1")
    # The remote run fails before the handle resolves.
    setup.seed_run("sid-1", "call-1", None, "run exploded", 0)
    result = _run(future)

    assert result.success is False
    assert "run exploded" in result.error_message
    assert setup.fetch_count() == 0


def test_resolve_twice_runs_one_durable_await() -> None:
    setup = _MockAsyncSetup(queries_until_complete=0)
    ctx = _RecordingContext()

    future = setup.submit(ctx, "ping", "sid-1", "call-1")
    first = _run(future)
    second = _run(future)

    assert first is second
    # Only the pub POST and one await composition.
    assert len(ctx.durable_execute_calls) == 2


def test_fetch_result_records_the_outcome_under_its_own_id() -> None:
    setup = _MockAsyncSetup(queries_until_complete=0)
    ctx = _RecordingContext()
    setup.seed_run("sid-1", "call-1", "done:ping", None, 0)

    result = setup.fetch_result(ctx, "sid-1", "call-1")

    assert result.success is True
    assert result.result == "done:ping"
    assert ctx.durable_execute_calls[0].durable_id == "sid-1#call-1#fetch"
    assert setup.fetch_count() == 1


def test_resolve_without_probing_goes_straight_to_the_await() -> None:
    setup = _MockAsyncSetup(queries_until_complete=2)
    ctx = _RecordingContext()

    result = _run(setup.submit(ctx, "ping", "sid-1", "call-1"))

    assert result.result == "done:ping"
    # No isDone-style probes: the await composition did them all.
    assert setup.status_query_count() == 3
    assert setup.fetch_count() == 1


# ------------------------------------------------------------------------------------------
# Failover replay: fresh probes may take a different path to the same result
# ------------------------------------------------------------------------------------------


def test_replay_after_the_run_completed_takes_fewer_probes() -> None:
    # Original execution: the run completes only after two RUNNING probes.
    original = _MockAsyncSetup(queries_until_complete=2)
    original.seed_run("sid-1", "call-1", "done:ping", None, 2)
    before = original._await_until_terminal("sid-1", "call-1")
    assert original.status_query_count() == 3

    # Replay: the run has already reached a terminal state, so the same await
    # takes a shorter path — fewer probes — to the same result.
    replay = _MockAsyncSetup(queries_until_complete=2)
    replay.seed_run("sid-1", "call-1", "done:ping", None, 0)
    after = replay._await_until_terminal("sid-1", "call-1")

    assert after.success is True
    assert after.result == before.result
    assert replay.status_query_count() == 1


def test_divergent_replay_paths_produce_the_same_result() -> None:
    setup = _MockAsyncSetup(queries_until_complete=0)
    ctx = _RecordingContext()
    setup.seed_run("sid-1", "call-1", "done:ping", None, 0)

    # One replay waits through the await composition, another fetches the
    # result directly: different durable records, the same outcome.
    awaited = setup._await_until_terminal("sid-1", "call-1")
    fetched = setup.fetch_result(ctx, "sid-1", "call-1")

    assert fetched.success is True
    assert fetched.result == awaited.result
    assert ctx.durable_execute_calls[0].durable_id == "sid-1#call-1#fetch"


# ------------------------------------------------------------------------------------------
# Cancellation: the hook's return governs the cancelled resolve
# ------------------------------------------------------------------------------------------


def test_cancel_then_resolve_raises_cancelled_error_by_default() -> None:
    setup = _MockAsyncSetup()
    ctx = _RecordingContext()

    future = setup.submit(ctx, "ping", "sid-1", "call-1")
    future.cancel()

    assert setup.cancel_count() == 1
    assert future.done() is True
    with pytest.raises(CancelledError):
        _run(future)
    # The pub landed, but the cancelled resolve never awaited nor fetched.
    assert setup.post_count() == 1
    assert setup.status_query_count() == 0
    assert setup.fetch_count() == 0
    assert len(ctx.durable_execute_calls) == 1


def test_repeated_cancel_is_a_local_no_op() -> None:
    setup = _MockAsyncSetup()
    ctx = _RecordingContext()

    future = setup.submit(ctx, "ping", "sid-1", "call-1")
    future.cancel()
    future.cancel()

    # A repeated cancel on the same handle does not propagate again; a
    # failover replay creates a fresh handle, which may.
    assert setup.cancel_count() == 1


def test_cancel_after_the_resolve_is_ignored() -> None:
    setup = _MockAsyncSetup(queries_until_complete=0)
    ctx = _RecordingContext()

    future = setup.submit(ctx, "ping", "sid-1", "call-1")
    assert _run(future).result == "done:ping"

    future.cancel()

    assert setup.cancel_count() == 0
    assert _run(future).result == "done:ping"


def test_cancel_propagates_through_the_group() -> None:
    setup = _MockAsyncSetup()
    ctx = _RecordingContext()

    first = setup.submit(ctx, "a", "sid-1", "call-1")
    second = setup.submit(ctx, "b", "sid-1", "call-2")
    first.combine(second).cancel()

    assert setup.cancel_count() == 2
    with pytest.raises(CancelledError):
        _run(first)
    with pytest.raises(CancelledError):
        _run(second)


def test_combine_resolves_every_handle_of_the_batch() -> None:
    setup = _MockAsyncSetup(queries_until_complete=0)
    ctx = _RecordingContext()

    first = setup.submit(ctx, "a", "sid-1", "call-1")
    second = setup.submit(ctx, "b", "sid-1", "call-2")

    outcomes = _run(first.combine(second))

    assert [outcome.result for outcome in outcomes] == ["done:a", "done:b"]


def test_the_await_form_waits_through_the_async_durable_composition() -> None:
    setup = _MockAsyncSetup(queries_until_complete=1)
    ctx = _RecordingContext()

    future = setup.submit(ctx, "ping", "sid-1", "call-1")
    result = _run(future)

    assert result.result == "done:ping"
    assert ctx.async_durable_calls == 1
    assert ctx.durable_execute_calls[1].durable_id == "sid-1#call-1#await"
    assert future.done() is True


def test_the_await_form_of_a_cancelled_handle_raises() -> None:
    setup = _MockAsyncSetup()
    ctx = _RecordingContext()

    future = setup.submit(ctx, "ping", "sid-1", "call-1")
    future.cancel()

    with pytest.raises(CancelledError):
        _run(future)
    assert ctx.async_durable_calls == 0
