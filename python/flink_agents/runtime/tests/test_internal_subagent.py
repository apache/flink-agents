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
"""Tests for the internal sub-agent handle's issue-then-poll resolution.

The handle drives the same graph-call chain the Java side and the built-in chat
call run, split across mailbox turns:
``bootstrap_internal_subagent_call`` issues the call once, and
``try_complete_internal_subagent_call`` is polled until it reports the
child's result. Both are mailbox-confined, so waiting between polls is a bare
``yield`` rather than a blocking wait.
"""

from concurrent.futures import CancelledError
from typing import Any

import pytest

from flink_agents.api.agents.agent import Agent
from flink_agents.api.subagent import SubagentResult
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.runtime.deferred_subagent import DeferredSubagentFuture
from flink_agents.runtime.internal_subagent import (
    InternalSubagentFuture,
    InternalSubagentSetup,
)
from flink_agents.runtime.subagent_handles import PendingSubagentCallRegistry
from flink_agents.runtime.tests.test_base_subagent import _run


class _PollingContext:
    """Fake runtime context recording issues and serving canned results."""

    def __init__(self) -> None:
        self.bootstrapped: list[tuple[str, str, str, Any]] = []
        self.polled: list[str] = []
        self.outputs: dict[str, list] = {}
        self.child_failures: dict[str, str] = {}
        self.poll_faults: dict[str, Exception] = {}
        self.issued_before_first_poll: int | None = None

    def bootstrap_internal_subagent_call(
        self, scope: str, session_id: str, call_id: str, prompt: Any
    ) -> None:
        """Record the issue, keyed the way the poll is."""
        self.bootstrapped.append((scope, session_id, call_id, prompt))

    def try_complete_internal_subagent_call(
        self, session_id: str, call_id: str
    ) -> SubagentResult | None:
        """Report the recorded result, or None while the child still runs."""
        identity = f"{session_id}#{call_id}"
        if self.issued_before_first_poll is None:
            self.issued_before_first_poll = len(self.bootstrapped)
        self.polled.append(identity)
        if identity in self.poll_faults:
            raise self.poll_faults[identity]
        if identity in self.child_failures:
            return SubagentResult.error(self.child_failures[identity])
        outputs = self.outputs.get(identity)
        return None if outputs is None else SubagentResult.ok(list(outputs))


class _PlainContext:
    """Context without the internal sub-agent hooks."""


class _ImmediateAwaitable:
    """Awaitable resolving without yielding, mirroring a cached durable result."""

    def __init__(self, value: Any) -> None:
        self._value = value

    def __await__(self) -> Any:
        return self._value
        yield  # pragma: no cover - makes this a generator function


class _MixedContext(_PollingContext):
    """Polling context that also serves a deferred handle's durable call.

    Records how many internal calls had been issued when the first durable
    wait started.
    """

    def __init__(self) -> None:
        super().__init__()
        self.issued_before_first_durable: int | None = None

    def durable_execute_async(
        self,
        func: Any,
        *args: Any,
        reconciler: Any = None,
        durable_id: str | None = None,
        **kwargs: Any,
    ) -> Any:
        """Run the deferred callable, noting what was already issued."""
        if self.issued_before_first_durable is None:
            self.issued_before_first_durable = len(self.bootstrapped)
        return _ImmediateAwaitable(func(*args))


def _future(
    ctx: Any,
    prompt: Any = "ping",
    registry: PendingSubagentCallRegistry | None = None,
    session_id: str = "sid-1",
    call_id: str = "call-1",
) -> InternalSubagentFuture:
    return InternalSubagentFuture(
        session_id, call_id, ctx, "reviewer", prompt, registry=registry
    )


def _deferred(ctx: Any, prompt: Any, call_id: str) -> DeferredSubagentFuture:
    """The other kind of handle: an external call run through durable execution."""
    return DeferredSubagentFuture(
        "sid-1",
        call_id,
        ctx,
        lambda: (f"sid-1#{call_id}", lambda: SubagentResult.ok([prompt]), None),
    )


def test_submit_issues_nothing_until_the_handle_is_resolved() -> None:
    """Issuing happens on resolve, so a dropped handle starts no child."""
    ctx = _PollingContext()
    registry = PendingSubagentCallRegistry("my_action")
    future = _future(ctx, registry=registry)

    assert ctx.bootstrapped == []
    assert future.done() is False
    assert registry.is_empty() is False

    ctx.outputs[future.identity] = ["echo:ping"]
    assert _run(future).result == ["echo:ping"]
    assert ctx.bootstrapped == [("reviewer", "sid-1", "call-1", "ping")]
    assert registry.is_empty() is True


def test_handle_polls_until_the_child_reports_outputs() -> None:
    """A pending child yields the mailbox instead of blocking on it."""
    ctx = _PollingContext()
    future = _future(ctx)
    waiter = future.__await__()

    assert next(waiter) is None
    assert ctx.bootstrapped == [("reviewer", "sid-1", "call-1", "ping")]
    assert future.done() is False

    ctx.outputs[future.identity] = ["first", "second"]
    with pytest.raises(StopIteration) as stop:
        next(waiter)
    assert stop.value.value.result == ["first", "second"]
    # One issue and two polls: the pending one and the terminal one.
    assert len(ctx.bootstrapped) == 1
    assert ctx.polled == [future.identity] * 2


def test_resolved_handle_keeps_its_value_without_polling_again() -> None:
    """A second resolve replays the outcome rather than re-issuing the call."""
    ctx = _PollingContext()
    future = _future(ctx)
    ctx.outputs[future.identity] = ["done"]

    first = _run(future)
    polls = len(ctx.polled)
    second = _run(future)

    assert second is first
    assert len(ctx.bootstrapped) == 1
    assert len(ctx.polled) == polls


def test_recorded_child_result_never_yields_the_mailbox() -> None:
    """A replayed call installs its recorded result at issue time."""
    ctx = _PollingContext()
    future = _future(ctx)
    ctx.outputs[future.identity] = ["replayed"]

    waiter = future.__await__()
    with pytest.raises(StopIteration) as stop:
        next(waiter)

    assert stop.value.value.result == ["replayed"]
    assert ctx.polled == [future.identity]


def test_child_failure_resolves_as_a_failed_result() -> None:
    """A child's own failure is a result the caller inspects, not a raise."""
    ctx = _PollingContext()
    registry = PendingSubagentCallRegistry("my_action")
    future = _future(ctx, registry=registry)
    ctx.child_failures[future.identity] = "ValueError: child refused"

    result = _run(future)

    assert result == SubagentResult.error("ValueError: child refused")
    assert future.done() is True
    assert registry.is_empty() is True


def test_fault_of_the_call_itself_reaches_the_caller() -> None:
    """The contrast: a fault of the call itself is not its child's outcome.

    A slot that cannot be written used to be folded into the child's failure,
    resolving the handle so the call was never retried.
    """
    ctx = _PollingContext()
    registry = PendingSubagentCallRegistry("my_action")
    future = _future(ctx, registry=registry)
    ctx.poll_faults[future.identity] = RuntimeError("could not record the slot")

    with pytest.raises(RuntimeError, match="could not record the slot"):
        _run(future)

    assert future.done() is False
    assert registry.is_empty() is False

    # Nothing was resolved, so a retry polls again rather than replaying a result.
    ctx.poll_faults.clear()
    ctx.outputs[future.identity] = ["late"]
    assert _run(future).result == ["late"]
    assert registry.is_empty() is True


def test_group_issues_every_member_before_polling_any() -> None:
    """A batch runs its child graphs concurrently, in submission order."""
    ctx = _PollingContext()
    first = _future(ctx, "a", call_id="call-1")
    second = _future(ctx, "b", call_id="call-2")
    third = _future(ctx, "c", call_id="call-3")
    for future in (first, second, third):
        ctx.outputs[future.identity] = [future.call_id]

    outcomes = _run(first.combine(second, third))

    assert [outcome.result for outcome in outcomes] == [
        ["call-1"],
        ["call-2"],
        ["call-3"],
    ]
    assert ctx.issued_before_first_poll == 3
    assert [call_id for _, _, call_id, _ in ctx.bootstrapped] == [
        "call-1",
        "call-2",
        "call-3",
    ]


@pytest.mark.parametrize("internal_first", [True, False])
def test_group_rejects_a_mixed_internal_and_deferred_batch(
    internal_first: bool,
) -> None:
    """A batch holding both kinds is refused before any member is prepared, in
    either order: mixing them currently breaks durable recovery.
    """
    ctx = _MixedContext()
    internal = _future(ctx, "inner", call_id="call-i")
    ctx.outputs[internal.identity] = ["child"]
    deferred = _deferred(ctx, "outer", "call-d")
    group = (
        internal.combine(deferred) if internal_first else deferred.combine(internal)
    )

    with pytest.raises(
        RuntimeError, match="cannot mix the internal call InternalSubagentFuture"
    ):
        _run(group)

    assert ctx.bootstrapped == []
    assert ctx.issued_before_first_durable is None
    assert internal.done() is False
    assert deferred.done() is False


def test_group_rejects_an_internal_handle_separated_by_a_deferred_one() -> None:
    """A deferred handle anywhere in the batch makes it mixed."""
    ctx = _MixedContext()
    first = _future(ctx, "inner-1", call_id="call-i1")
    second = _future(ctx, "inner-2", call_id="call-i2")
    for future in (first, second):
        ctx.outputs[future.identity] = [future.call_id]
    deferred = _deferred(ctx, "outer", "call-d")

    with pytest.raises(
        RuntimeError, match="cannot mix the internal call InternalSubagentFuture"
    ):
        _run(first.combine(deferred, second))

    assert ctx.bootstrapped == []


def test_group_allows_a_resolved_deferred_handle_before_an_internal_one() -> None:
    """A resolved member is skipped by both loops, so it mixes no phases."""
    ctx = _MixedContext()
    internal = _future(ctx, "inner", call_id="call-i")
    ctx.outputs[internal.identity] = ["child"]
    deferred = _deferred(ctx, "outer", "call-d")
    assert _run(deferred).result == ["outer"]

    outcomes = _run(deferred.combine(internal))

    assert [outcome.result for outcome in outcomes] == [["outer"], ["child"]]
    assert internal.done() is True


def test_cancel_before_resolve_issues_nothing() -> None:
    """A cancelled handle sends no bootstrap event and unregisters."""
    ctx = _PollingContext()
    registry = PendingSubagentCallRegistry("my_action")
    future = _future(ctx, registry=registry)

    future.cancel()

    assert future.done() is True
    assert registry.is_empty() is True
    with pytest.raises(CancelledError):
        future.prepare()
    with pytest.raises(CancelledError):
        _run(future)
    assert ctx.bootstrapped == []


def test_setup_requires_a_context_implementing_the_call_factory() -> None:
    """A context without the hooks cannot drive the chain."""
    setup = InternalSubagentSetup(
        scope="reviewer",
        plan=AgentPlan.from_agent(Agent(), AgentConfiguration()),
    )

    with pytest.raises(NotImplementedError, match="InternalSubagentCallFactory"):
        _run(setup.submit(_PlainContext(), "ping", "sid-1", "call-1"))

    future = _run(setup.submit(_PollingContext(), "ping", "sid-1", "call-1"))
    assert isinstance(future, InternalSubagentFuture)
    assert future.identity == "sid-1#call-1"
