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
"""Tests for the Python side of the task lifecycle bridge.

The Java operator forwards its per-record and per-action lifecycle to Python
over pemja, and ``FlinkRunnerContext`` fans each callback out to the
registered ``TaskLifecycleListener``s. These tests exercise that registration
and fan-out with a recording listener, without a live interpreter.
"""

from concurrent.futures import CancelledError
from dataclasses import replace
from typing import Any
from unittest.mock import Mock

import pytest

from flink_agents.api.resource import ResourceType
from flink_agents.runtime.base_subagent import Namespace
from flink_agents.runtime.flink_runner_context import FlinkRunnerContext
from flink_agents.runtime.resource_cache import ResourceCache
from flink_agents.runtime.task_lifecycle_listener import TaskLifecycleListener
from flink_agents.runtime.tests.test_base_subagent import (
    _FakeProvider,
    _FakeTask,
    _RecordingBaseSetup,
    _run,
)


class _RecordingListener(TaskLifecycleListener):
    """Lifecycle listener recording every callback it receives."""

    def __init__(self) -> None:
        self.calls: list = []

    def on_record_start(self, key: Any) -> None:
        self.calls.append(("record_start", key))

    def on_action_prepared(self, task: Any) -> None:
        self.calls.append(("action_prepared", task))

    def on_action_started(self, task: Any) -> None:
        self.calls.append(("action_started", task))

    def on_action_transferred(self, from_task: Any, to_task: Any) -> None:
        self.calls.append(("action_transferred", from_task, to_task))

    def on_action_finishing(self, task: Any) -> None:
        self.calls.append(("action_finishing", task))

    def on_action_finished(self, task: Any) -> None:
        self.calls.append(("action_finished", task))

    def on_action_reused(self, task: Any) -> None:
        self.calls.append(("action_reused", task))

    def on_action_failed(self, task: Any, error: Any) -> None:
        self.calls.append(("action_failed", task, error))

    def on_record_finished(self, key: Any) -> None:
        self.calls.append(("record_finished", key))


def _context(*setup_names: str) -> FlinkRunnerContext:
    """Build a context with fake Java accessors and lazily resolved resources."""
    ctx = FlinkRunnerContext.__new__(FlinkRunnerContext)
    ctx._FlinkRunnerContext__task_lifecycle_listeners = []
    ctx._FlinkRunnerContext__prepared_namespace = None
    ctx._j_runner_context = Mock()
    ctx._j_runner_context.getActiveScopePlanJson.return_value = None
    ctx._FlinkRunnerContext__resource_cache = ResourceCache(
        {
            ResourceType.AGENT: {
                name: _FakeProvider(_RecordingBaseSetup()) for name in setup_names
            }
        }
    )
    return ctx


def test_fan_out_forwards_every_callback_in_order() -> None:
    """Each callback reaches the registered listener, in order."""
    ctx = _context()
    listener = _RecordingListener()
    ctx.add_task_lifecycle_listener(listener)
    task = _FakeTask()
    continuation = _FakeTask(sequence_number=2)

    ctx.notify_record_start("k")
    ctx.notify_action_prepared(task)
    ctx.notify_action_started(task)
    ctx.notify_action_transferred(task, continuation)
    ctx.notify_action_prepared(continuation)
    ctx.notify_action_transferred(continuation, continuation)
    ctx.notify_action_prepared(continuation)
    ctx.notify_action_finishing(continuation)
    ctx.notify_action_finished(continuation)
    ctx.notify_record_finished("k")

    assert listener.calls == [
        ("record_start", "k"),
        ("action_prepared", task),
        ("action_started", task),
        ("action_transferred", task, continuation),
        ("action_prepared", continuation),
        ("action_transferred", continuation, continuation),
        ("action_prepared", continuation),
        ("action_finishing", continuation),
        ("action_finished", continuation),
        ("record_finished", "k"),
    ]


def test_fan_out_forwards_reuse_and_failure_terminals() -> None:
    """The reuse and failure paths reach the listener as their own callbacks."""
    ctx = _context()
    listener = _RecordingListener()
    ctx.add_task_lifecycle_listener(listener)

    ctx.notify_action_reused("t")
    ctx.notify_action_failed("t", "boom")

    assert listener.calls == [
        ("action_reused", "t"),
        ("action_failed", "t", "boom"),
    ]


def test_fan_out_reaches_every_registered_listener() -> None:
    """A callback is delivered to all registered listeners."""
    ctx = _context()
    first, second = _RecordingListener(), _RecordingListener()
    ctx.add_task_lifecycle_listener(first)
    ctx.add_task_lifecycle_listener(second)

    ctx.notify_action_prepared("t")

    assert first.calls == [("action_prepared", "t")]
    assert second.calls == [("action_prepared", "t")]


def test_module_entries_delegate_to_the_context() -> None:
    """The Java-invoked module functions delegate to the context fan-out."""
    from flink_agents.runtime import flink_runner_context as frc

    ctx = _context()
    listener = _RecordingListener()
    ctx.add_task_lifecycle_listener(listener)

    frc.notify_record_start(ctx, "k")
    frc.notify_action_prepared(ctx, "t")
    frc.notify_action_transferred(ctx, "t", "t2")
    frc.notify_action_prepared(ctx, "t2")
    frc.notify_action_finishing(ctx, "t2")
    frc.notify_action_finished(ctx, "t2")
    frc.notify_record_finished(ctx, "k")

    assert [call[0] for call in listener.calls] == [
        "record_start",
        "action_prepared",
        "action_transferred",
        "action_prepared",
        "action_finishing",
        "action_finished",
        "record_finished",
    ]


@pytest.mark.parametrize("same_task", [True, False])
def test_transfer_waits_for_preparation_before_binding_lazy_setups(
    same_task: bool,
) -> None:
    """Lazy handles adopt only an active prepared phase across interleaved tasks."""
    ctx = _context("before", "gap", "interleaved", "resumed")
    task = _FakeTask(key="caller", action_name="caller-action")
    continuation = task if same_task else _FakeTask(action_name="continuation")
    other = _FakeTask(key="other", action_name="other-action")

    ctx.notify_action_prepared(task)
    before = ctx.get_resource("before", ResourceType.AGENT)
    assert before._current_namespace == replace(
        Namespace.from_task(task), subagent_name="before"
    )
    first = _run(before.submit(None, "p"))
    allocator = before._current_allocator()
    registry = before.pending_call_registry()
    registry.track_pending_subagent_call(first.identity)

    ctx.notify_action_transferred(task, continuation)
    assert ctx._FlinkRunnerContext__prepared_namespace is None
    assert before.pending_call_registry() is None
    gap = ctx.get_resource("gap", ResourceType.AGENT)
    assert gap._current_namespace is None
    assert not gap._per_task_allocators
    assert not gap._per_task_registries
    with pytest.raises(RuntimeError, match="No prepared action task"):
        _run(gap.submit(None, "outside execution"))

    ctx.notify_action_prepared(other)
    interleaved = ctx.get_resource("interleaved", ResourceType.AGENT)
    assert interleaved._current_namespace == replace(
        Namespace.from_task(other), subagent_name="interleaved"
    )
    _run(interleaved.submit(None, "other"))
    ctx.notify_action_finishing(other)
    ctx.notify_action_finished(other)
    assert ctx._FlinkRunnerContext__prepared_namespace is None

    ctx.notify_action_prepared(continuation)
    assert before._current_namespace == replace(
        Namespace.from_task(continuation), subagent_name="before"
    )
    assert before._current_allocator() is allocator
    assert before.pending_call_registry() is registry
    second = _run(before.submit(None, "resumed"))
    next_call = _run(before.submit(None, "resumed", first.session_id))
    assert second.session_id == first.session_id.rsplit("-", 1)[0] + "-1"
    assert next_call.call_id == f"{first.session_id}-2"
    with pytest.raises(RuntimeError, match=first.identity):
        registry.check_empty()

    resumed = ctx.get_resource("resumed", ResourceType.AGENT)
    assert resumed._current_namespace == replace(
        Namespace.from_task(continuation), subagent_name="resumed"
    )
    assert gap._current_namespace == replace(
        Namespace.from_task(continuation), subagent_name="gap"
    )
    expected_session = resumed._current_namespace.namespace_digest() + "-0"
    assert _run(resumed.submit(None, "p")).session_id == expected_session
    assert ctx.get_resource("resumed", ResourceType.AGENT) is resumed
    assert (
        sum(
            listener is resumed
            for listener in ctx._FlinkRunnerContext__task_lifecycle_listeners
        )
        == 1
    )

    registry.untrack_pending_subagent_call(first.identity)
    ctx.notify_action_finishing(continuation)
    ctx.notify_action_finished(continuation)
    assert ctx._FlinkRunnerContext__prepared_namespace is None
    for setup in (before, gap, interleaved, resumed):
        assert setup.pending_call_registry() is None
        assert not setup._per_task_allocators
        assert not setup._per_task_registries


@pytest.mark.parametrize("terminal", ["finishing", "reused", "failed"])
def test_terminal_clears_namespace_before_later_materialization(terminal: str) -> None:
    """No lazy handle can inherit a task after its terminal callback."""
    ctx = _context("active", "late")
    task = _FakeTask()
    ctx.notify_action_prepared(task)
    active = ctx.get_resource("active", ResourceType.AGENT)
    _run(active.submit(None, "p"))

    if terminal == "failed":
        active.pending_call_registry().track_pending_subagent_call("sid#pending")
        ctx.notify_action_failed(task, RuntimeError("invocation failed"))
    else:
        getattr(ctx, f"notify_action_{terminal}")(task)

    assert ctx._FlinkRunnerContext__prepared_namespace is None
    assert active.pending_call_registry() is None
    late = ctx.get_resource("late", ResourceType.AGENT)
    assert late._current_namespace is None
    with pytest.raises(RuntimeError, match="No prepared action task"):
        _run(late.submit(None, "outside execution"))


def test_failure_listener_cannot_block_identity_scoped_unbinding() -> None:
    """A failing observer cannot stop cleanup or unbind an unrelated task."""

    class FailingListener(TaskLifecycleListener):
        def on_action_failed(self, task: Any, error: Any) -> None:
            message = "listener failed"
            raise RuntimeError(message)

    ctx = _context("active", "late", "after-failure")
    ctx.add_task_lifecycle_listener(FailingListener())
    failed = _FakeTask(action_name="failed")
    other = _FakeTask(action_name="other")
    ctx.notify_action_prepared(failed)
    active = ctx.get_resource("active", ResourceType.AGENT)
    failed_registry = active.pending_call_registry()
    failed_registry.track_pending_subagent_call("failed#pending")
    ctx.notify_action_transferred(failed, failed)
    ctx.notify_action_prepared(other)
    other_registry = active.pending_call_registry()
    other_registry.track_pending_subagent_call("other#pending")
    recording = _RecordingListener()
    ctx.add_task_lifecycle_listener(recording)

    error = RuntimeError("invocation failed")
    ctx.notify_action_failed(failed, error)
    assert recording.calls == [("action_failed", failed, error)]
    assert ctx._FlinkRunnerContext__prepared_namespace == Namespace.from_task(other)
    assert active.pending_call_registry() is other_registry
    late = ctx.get_resource("late", ResourceType.AGENT)
    assert late._current_namespace == replace(
        Namespace.from_task(other), subagent_name="late"
    )

    # Pemja may supply a new proxy for the same task on the failure callback.
    other_proxy = _FakeTask(action_name="other")
    ctx.notify_action_failed(other_proxy, error)
    assert recording.calls[-1] == ("action_failed", other_proxy, error)
    assert ctx._FlinkRunnerContext__prepared_namespace is None
    assert active.pending_call_registry() is None
    assert late.pending_call_registry() is None
    assert active._per_task_registries[Namespace.from_task(failed).task_identity] is (
        failed_registry
    )
    assert active._per_task_registries[Namespace.from_task(other).task_identity] is (
        other_registry
    )
    with pytest.raises(RuntimeError, match="failed#pending"):
        failed_registry.check_empty()
    with pytest.raises(RuntimeError, match="other#pending"):
        other_registry.check_empty()
    after_failure = ctx.get_resource("after-failure", ResourceType.AGENT)
    assert after_failure._current_namespace is None


def test_failure_listener_errors_are_contained_and_the_guard_is_not_over_broad(
    caplog: pytest.LogCaptureFixture,
) -> None:
    """A listener failure is logged and contained; cancellation escapes."""

    class FailingListener(TaskLifecycleListener):
        def on_action_failed(self, task: Any, error: Any) -> None:
            message = "listener failed"
            raise RuntimeError(message)

    ctx = _context()
    recording = _RecordingListener()
    ctx.add_task_lifecycle_listener(FailingListener())
    ctx.add_task_lifecycle_listener(recording)

    error = RuntimeError("invocation failed")
    ctx.notify_action_failed(_FakeTask(action_name="failed"), error)

    # The guard still holds: a later listener is not starved by an earlier one.
    assert len(recording.calls) == 1
    # The warning with its traceback is the whole report: `error` is a pemja
    # proxy, so nothing hung off it reaches the throwable Java rethrows.
    assert "Task failure listener failed" in caplog.text
    assert "listener failed" in caplog.text

    class InterruptingListener(TaskLifecycleListener):
        def on_action_failed(self, task: Any, error: Any) -> None:
            raise KeyboardInterrupt

    # KeyboardInterrupt is an operator's stop request rather than a listener bug,
    # so the guard must let it through instead of swallowing it into a warning.
    interrupting = _context()
    interrupting.add_task_lifecycle_listener(InterruptingListener())
    with pytest.raises(KeyboardInterrupt):
        interrupting.notify_action_failed(
            _FakeTask(action_name="failed"), RuntimeError("invocation failed")
        )

    class CancellingListener(TaskLifecycleListener):
        def on_action_failed(self, task: Any, error: Any) -> None:
            message = "handle cancelled"
            raise CancelledError(message)

    # Cancellation is control flow rather than a listener bug too, but unlike
    # KeyboardInterrupt it derives from Exception, so it needs its own arm.
    cancelling = _context()
    cancelling.add_task_lifecycle_listener(CancellingListener())
    with pytest.raises(CancelledError):
        cancelling.notify_action_failed(
            _FakeTask(action_name="failed"), RuntimeError("invocation failed")
        )


def test_registration_entry_registers_an_observing_listener() -> None:
    """The Java side registers a Python listener through the module entry."""
    from flink_agents.runtime import flink_runner_context as frc

    ctx = _context()
    listener = _RecordingListener()

    assert frc.add_task_lifecycle_listener(ctx, listener) is True

    ctx.notify_record_start("k")
    assert listener.calls == [("record_start", "k")]


def test_registration_entry_rejects_an_object_that_ignores_the_lifecycle() -> None:
    """Registering a non-listener reports that there is nothing to notify."""
    from flink_agents.runtime import flink_runner_context as frc

    ctx = _context()

    assert frc.add_task_lifecycle_listener(ctx, object()) is False

    # Nothing was registered, so a fan-out cannot fail on a missing callback.
    ctx.notify_record_start("k")
