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

from flink_agents.api.subagent import Result, SubagentFuture
from flink_agents.runtime.base_subagent import BaseSubagentSetup
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
        self, event_type: str = "TestEvent", attributes: dict[str, Any] | None = None
    ) -> None:
        self._type = event_type
        self._attributes = attributes or {}

    def getType(self) -> str:
        return self._type

    def getAttributes(self) -> dict[str, Any]:
        return self._attributes


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

    def submit_with_identity(
        self,
        ctx: Any,
        prompt: Any,
        session_id: str,
        call_id: str,
    ) -> SubagentFuture:
        """Complete the invocation immediately under the assigned identity."""
        return CompletedSubagentFuture(session_id, call_id, Result.ok([prompt]))


def test_short_forms_assign_through_the_prepared_task() -> None:
    """The short forms allocate deterministically from the executing task."""
    setup = _RecordingBaseSetup()
    setup.on_task_prepared(_FakeTask())

    first = setup.submit(None, "p")
    second = setup.submit(None, "p")
    under_session = setup.submit(None, "p", "given-session")
    third_call = setup.submit(None, "p", first.session_id)

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
    first.on_task_prepared(_FakeTask(key="k", sequence_number=7, action_name="act"))
    original = first.submit(None, "p")

    replay = _RecordingBaseSetup()
    replay.on_task_prepared(_FakeTask(key="k", sequence_number=7, action_name="act"))
    replayed = replay.submit(None, "p")

    assert replayed.session_id == original.session_id
    assert replayed.call_id == original.call_id


def test_allocation_continues_across_task_steps() -> None:
    """Each step of a suspended task re-prepares with the same facts, and
    the allocator persists, so the session ordinal continues instead of
    restarting.
    """
    setup = _RecordingBaseSetup()
    setup.on_task_prepared(_FakeTask())
    first = setup.submit(None, "p")

    setup.on_task_prepared(_FakeTask())
    second = setup.submit(None, "p")

    assert first.session_id.endswith("-0")
    assert second.session_id.endswith("-1")
    assert second.session_id != first.session_id


def test_finished_task_drops_its_bookkeeping() -> None:
    """After the task finishes, short forms have no task to assign from."""
    setup = _RecordingBaseSetup()
    setup.on_task_prepared(_FakeTask())
    setup.submit(None, "p")

    setup.on_task_finished(_FakeTask())

    with pytest.raises(RuntimeError, match="No prepared action task"):
        setup.submit(None, "p")


def test_resource_name_isolates_namespaces() -> None:
    """Setups sharing one caller's counting range assign disjoint ids."""
    left = _RecordingBaseSetup()
    left.set_resource_name("scope.left")
    left.on_task_prepared(_FakeTask())

    right = _RecordingBaseSetup()
    right.set_resource_name("scope.right")
    right.on_task_prepared(_FakeTask())

    assert left.submit(None, "p").session_id != right.submit(None, "p").session_id


def test_explicit_identity_passes_through_untouched() -> None:
    """A fully supplied identity skips the allocator entirely."""
    setup = _RecordingBaseSetup()

    handle = setup.submit(None, "p", "sid-x", "call-y")

    assert handle.session_id == "sid-x"
    assert handle.call_id == "call-y"
