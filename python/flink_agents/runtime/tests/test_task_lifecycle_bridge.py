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

from typing import Any

from flink_agents.runtime.flink_runner_context import FlinkRunnerContext
from flink_agents.runtime.task_lifecycle_listener import TaskLifecycleListener


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


def _context() -> FlinkRunnerContext:
    """Build a FlinkRunnerContext with an empty listener registry.

    Bypasses ``__init__`` (which needs a Java runner context) and starts from
    an empty registry, as the operator does before registering listeners.
    """
    ctx = FlinkRunnerContext.__new__(FlinkRunnerContext)
    ctx._FlinkRunnerContext__task_lifecycle_listeners = []
    return ctx


def test_fan_out_forwards_every_callback_in_order() -> None:
    """Each callback reaches the registered listener, in order."""
    ctx = _context()
    listener = _RecordingListener()
    ctx.add_task_lifecycle_listener(listener)

    ctx.notify_record_start("k")
    ctx.notify_action_prepared("t")
    ctx.notify_action_started("t")
    ctx.notify_action_transferred("t", "t2")
    ctx.notify_action_finishing("t2")
    ctx.notify_action_finished("t2")
    ctx.notify_record_finished("k")

    assert listener.calls == [
        ("record_start", "k"),
        ("action_prepared", "t"),
        ("action_started", "t"),
        ("action_transferred", "t", "t2"),
        ("action_finishing", "t2"),
        ("action_finished", "t2"),
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
    frc.notify_action_finishing(ctx, "t2")
    frc.notify_action_finished(ctx, "t2")
    frc.notify_record_finished(ctx, "k")

    assert [call[0] for call in listener.calls] == [
        "record_start",
        "action_prepared",
        "action_transferred",
        "action_finishing",
        "action_finished",
        "record_finished",
    ]


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
