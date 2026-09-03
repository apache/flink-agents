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
"""Tests for the framework base of sub-agent setups.

The Python parity of Java's ``BaseSubagentSetupTest``: lifecycle-driven
id assignment, replay determinism, continuity across the steps of a
suspended task, dropped-handle enforcement, and resource-name isolation.
"""

from typing import Any

import pytest

from flink_agents.api.resource import ResourceType
from flink_agents.api.subagent import SubagentFuture, SubagentResult
from flink_agents.runtime.base_subagent import BaseSubagentSetup
from flink_agents.runtime.resource_cache import ResourceCache
from flink_agents.runtime.subagent_handles import CompletedSubagentFuture


class _FakeAction:
    """Duck-typed ``Action`` exposing the name getter the base reads."""

    def __init__(self, name: str) -> None:
        self._name = name

    def getName(self) -> str:
        return self._name


class _FakeEvent:
    """Duck-typed ``Event`` exposing the getters the base reads."""

    def __init__(
        self,
        event_type: str = "TestEvent",
        attributes: dict[str, Any] | None = None,
        event_id: str = "event-1",
    ) -> None:
        self._type = event_type
        self._attributes = attributes or {}
        self._id = event_id

    def getType(self) -> str:
        return self._type

    def getAttributes(self) -> dict[str, Any]:
        return self._attributes

    def getId(self) -> str:
        return self._id


class _FakeTask:
    """Duck-typed ``ActionTask`` carrying the caller-side facts."""

    def __init__(
        self,
        key: str = "k",
        sequence_number: int = 1,
        action_name: str = "act",
        event: _FakeEvent | None = None,
    ) -> None:
        self._key = key
        self._sequence_number = sequence_number
        self._action = _FakeAction(action_name)
        self._event = event or _FakeEvent()

    def getKey(self) -> str:
        return self._key

    def getSequenceNumber(self) -> int:
        return self._sequence_number

    def getAction(self) -> _FakeAction:
        return self._action

    def getEvent(self) -> _FakeEvent:
        return self._event


class _RecordingBaseSetup(BaseSubagentSetup):
    """Base subclass completing handles without any transport."""

    async def submit_with_identity(
        self,
        ctx: Any,
        prompt: Any,
        session_id: str,
        call_id: str,
    ) -> SubagentFuture:
        """Complete the invocation immediately under the assigned identity."""
        return CompletedSubagentFuture(session_id, call_id, SubagentResult.ok([prompt]))


def _run(awaitable: Any) -> Any:
    """Drive an awaitable the way the runtime drives an action coroutine."""
    iterator = awaitable.__await__()
    try:
        while True:
            next(iterator)
    except StopIteration as stop:
        return stop.value


def test_short_forms_assign_through_the_prepared_task() -> None:
    """The short forms allocate deterministically from the executing task."""
    setup = _RecordingBaseSetup()
    setup.on_action_prepared(_FakeTask())

    first = _run(setup.submit(None, "p"))
    second = _run(setup.submit(None, "p"))
    under_session = _run(setup.submit(None, "p", "given-session"))
    third_call = _run(setup.submit(None, "p", first.session_id))

    assert first.session_id.endswith("-0")
    assert first.call_id == f"{first.session_id}-1"
    assert second.session_id.endswith("-1")
    assert second.call_id == f"{second.session_id}-1"
    assert third_call.call_id == f"{first.session_id}-2"
    assert under_session.session_id == "given-session"
    assert under_session.call_id == "given-session-1"


def test_replay_assigns_the_same_ids() -> None:
    """A failover replay of the same task facts hands out the same ids."""
    first = _RecordingBaseSetup()
    first.on_action_prepared(_FakeTask(key="k", sequence_number=7, action_name="act"))
    original = _run(first.submit(None, "p"))

    replay = _RecordingBaseSetup()
    replay.on_action_prepared(_FakeTask(key="k", sequence_number=7, action_name="act"))
    replayed = _run(replay.submit(None, "p"))

    assert replayed.session_id == original.session_id
    assert replayed.call_id == original.call_id


def test_allocation_continues_across_task_steps() -> None:
    """Each step of a suspended task re-prepares with the same facts, and
    the allocator persists, so the session ordinal continues instead of
    restarting.
    """
    setup = _RecordingBaseSetup()
    setup.on_action_prepared(_FakeTask())
    first = _run(setup.submit(None, "p"))

    setup.on_action_prepared(_FakeTask())
    second = _run(setup.submit(None, "p"))

    assert first.session_id.endswith("-0")
    assert second.session_id.endswith("-1")
    assert second.session_id != first.session_id


def test_transfer_moves_bookkeeping_onto_a_different_continuation() -> None:
    """The generated task may carry a different identity than the finishing
    task, so the allocator and the pending-call registry are re-keyed onto
    it instead of assumed equal.
    """
    setup = _RecordingBaseSetup()
    from_task = _FakeTask(event=_FakeEvent(event_id="event-from"))
    to_task = _FakeTask(action_name="act-next", event=_FakeEvent(event_id="event-to"))

    setup.on_action_prepared(from_task)
    first = _run(setup.submit(None, "p"))
    setup.pending_call_registry().track_pending_subagent_call("sid#call-1")
    setup.on_action_transferred(from_task, to_task)

    setup.on_action_prepared(to_task)
    second = _run(setup.submit(None, "p"))

    # The allocator moved with the execution: the session ordinal continues
    # instead of restarting under the continuation's own facts.
    assert first.session_id.endswith("-0")
    assert second.session_id.endswith("-1")

    # The pending-call registry moved as well, and adopted the continuation's
    # action: finishing the continuation still reports the handle tracked
    # under the finishing task.
    with pytest.raises(RuntimeError, match=r"act-next.*sid#call-1"):
        setup.on_action_finishing(to_task)


def test_finished_task_drops_its_bookkeeping() -> None:
    """After the task finishes, short forms have no task to assign from."""
    setup = _RecordingBaseSetup()
    setup.on_action_prepared(_FakeTask())
    _run(setup.submit(None, "p"))

    setup.on_action_finishing(_FakeTask())

    with pytest.raises(RuntimeError, match="No prepared action task"):
        _run(setup.submit(None, "p"))


def test_new_action_restarts_call_ordinal_for_a_reused_session_id() -> None:
    """Ids assigned without an explicit id are only valid within one action
    execution: a new task starts the per-session call ordinal at 1 again, so
    reusing a session id across actions reproduces the same call ids.
    """
    setup = _RecordingBaseSetup()
    setup.on_action_prepared(_FakeTask(sequence_number=1))
    first = _run(setup.submit(None, "p", "shared-session"))
    second = _run(setup.submit(None, "p", "shared-session"))
    assert first.call_id == "shared-session-1"
    assert second.call_id == "shared-session-2"

    # The next action prepares a different task; its fresh allocator hands
    # out the identical ids under the reused session id.
    setup.on_action_finishing(_FakeTask(sequence_number=1))
    setup.on_action_prepared(_FakeTask(sequence_number=2))
    reused = _run(setup.submit(None, "p", "shared-session"))
    assert reused.call_id == "shared-session-1"


def test_subagent_name_isolates_namespaces() -> None:
    """Setups sharing one caller's counting range assign disjoint ids."""
    left = _RecordingBaseSetup()
    left.set_subagent_name("scope.left")
    left.on_action_prepared(_FakeTask())

    right = _RecordingBaseSetup()
    right.set_subagent_name("scope.right")
    right.on_action_prepared(_FakeTask())

    left_handle = _run(left.submit(None, "p"))
    right_handle = _run(right.submit(None, "p"))

    assert left_handle.session_id != right_handle.session_id


def test_interleaved_executions_of_one_action_keep_apart() -> None:
    """Tasks of one action triggered by different events within one record
    interleave: the earlier one may still be suspended when the later one
    finishes, and their bookkeeping must not mix.
    """
    setup = _RecordingBaseSetup()
    first_task = _FakeTask(event=_FakeEvent(event_id="e1"))
    second_task = _FakeTask(event=_FakeEvent(event_id="e2"))

    setup.on_action_prepared(first_task)
    first = _run(setup.submit(None, "p"))
    left = setup.pending_call_registry()
    left.track_pending_subagent_call(first.identity)

    setup.on_action_prepared(second_task)
    _run(setup.submit(None, "p"))
    setup.on_action_finishing(second_task)

    # The finished execution dropped only its own bookkeeping.
    setup.on_action_prepared(first_task)
    resumed = _run(setup.submit(None, "p"))
    assert resumed.session_id.endswith("-1")
    assert setup.pending_call_registry() is left

    left.untrack_pending_subagent_call(first.identity)
    setup.on_action_finishing(first_task)


def test_explicit_identity_passes_through_untouched() -> None:
    """A fully supplied identity skips the allocator entirely."""
    setup = _RecordingBaseSetup()

    handle = _run(setup.submit(None, "p", "sid-x", "call-y"))

    assert handle.session_id == "sid-x"
    assert handle.call_id == "call-y"


class _FakeProvider:
    """Resource provider returning a fixed resource instance."""

    def __init__(self, resource: Any) -> None:
        self._resource = resource

    def provide(self, resource_context: Any, config: Any) -> Any:
        """Return the pre-built resource."""
        return self._resource


def test_resource_cache_injects_the_subagent_name() -> None:
    """Materializing a sub-agent setup injects the resource name, like Java."""
    setup = _RecordingBaseSetup()
    cache = ResourceCache({ResourceType.AGENT: {"reviewer": _FakeProvider(setup)}})

    resolved = cache.get_resource("reviewer", ResourceType.AGENT)

    assert resolved is setup
    assert setup.subagent_name == "reviewer"
