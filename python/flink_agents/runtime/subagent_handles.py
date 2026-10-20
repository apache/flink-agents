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
"""Framework-level handle utilities for sub-agent setups.

These companions are execution-mode agnostic. The execution modes build
on them, so the framework never needs to know how a request is issued.
"""

from typing import Any, Protocol, runtime_checkable

from flink_agents.api.subagent import (
    SubagentFuture,
    SubagentFutures,
    SubagentResult,
)


@runtime_checkable
class PhasedSubagentFuture(Protocol):
    """A handle with separate preparation and execution phases.

    :class:`SubagentFutureGroup` prepares every member before executing any;
    preparation need not issue the call.
    """

    def prepare(self) -> Any:
        """Prepare the call without waiting for it. Idempotent."""
        ...

    def execute(self) -> Any:
        """Wait for the prepared call, preparing it first if awaited on its own."""
        ...


class PendingSubagentCallRegistry:
    """The per-action-execution set of sub-agent handles submitted but
    not yet resolved.
    """

    def __init__(self, action_name: str) -> None:
        """Initialize an empty registry for the given action."""
        self._action_name = action_name
        self._pending_calls: list[str] = []

    def set_action_name(self, action_name: str) -> None:
        """Adopt the continuation's action when the execution moves onto
        another task.
        """
        self._action_name = action_name

    def track_pending_subagent_call(self, call_identity: str) -> None:
        """Record a pending handle. Duplicate identities collapse to one."""
        if call_identity not in self._pending_calls:
            self._pending_calls.append(call_identity)

    def untrack_pending_subagent_call(self, call_identity: str) -> None:
        """Drop a resolved handle and do nothing when the identity is unknown."""
        if call_identity in self._pending_calls:
            self._pending_calls.remove(call_identity)

    def is_empty(self) -> bool:
        """Whether no handle is pending."""
        return not self._pending_calls

    def check_empty(self) -> None:
        """Fail when the finished action left a handle unresolved."""
        if self._pending_calls:
            msg = (
                f"Action {self._action_name} finished without resolving the "
                f"sub-agent calls it submitted: {self._pending_calls}. "
                f"Resolve every handle returned by submit(), individually "
                f"or through SubagentFutures."
            )
            raise RuntimeError(msg)


class CompletedSubagentFuture(SubagentFuture):
    """A handle for an invocation that has already produced ``value``."""

    def __init__(self, session_id: str, call_id: str, value: SubagentResult) -> None:
        """Initialize with the identity and the produced value."""
        super().__init__(session_id, call_id)
        self._value = value

    def done(self) -> bool:
        """The invocation has already reached its terminal state."""
        return True

    def combine(self, *others: SubagentFuture) -> SubagentFutures:
        """Group this handle with others to be resolved together."""
        return SubagentFutureGroup((self, *others))

    def __await__(self) -> Any:
        """Resolve immediately with the produced value."""
        return self._value
        yield  # pragma: no cover - makes this a generator function


class SubagentFutureGroup(SubagentFutures):
    """The :class:`SubagentFutures` returned by ``combine``: several
    handles held together.
    """

    def __init__(self, futures: tuple) -> None:
        """Initialize with the handles to resolve together."""
        self._futures = tuple(futures)

    def done(self) -> bool:
        """Whether every handle in the group has been resolved."""
        return all(future.done() for future in self._futures)

    def cancel(self) -> None:
        """Propagate the cancellation request to every handle in the group."""
        for future in self._futures:
            future.cancel()

    def combine(self, *others: SubagentFuture) -> SubagentFutures:
        """Add more handles to the group."""
        return SubagentFutureGroup((*self._futures, *others))

    def __await__(self) -> Any:
        """Wait for every handle in submission order.

        Pending phased handles are prepared before any execution starts, then
        executed one by one.
        """
        pending = [
            future
            for future in self._futures
            if isinstance(future, PhasedSubagentFuture) and not future.done()
        ]
        _check_not_mixed_internal_and_deferred(pending)
        for future in pending:
            future.prepare()
        # TODO(#926): wait for the issued calls as one batch once durable
        # execution supports batched submission; until then they are waited
        # for one by one.
        for future in pending:
            yield from future.execute()
        outcomes = []
        for future in self._futures:
            outcome = yield from future.__await__()
            outcomes.append(outcome)
        return outcomes


def _check_not_mixed_internal_and_deferred(pending: list) -> None:
    """Fail when a group mixes internal and deferred handles: the two consult
    the calling action's durable journal in different phases, so mixing them
    currently breaks durable recovery.
    """
    # Imported here because both modules import this one for SubagentFutureGroup.
    from flink_agents.runtime.deferred_subagent import DeferredSubagentFuture
    from flink_agents.runtime.internal_subagent import InternalSubagentFuture

    internal = None
    deferred = None
    for future in pending:
        if isinstance(future, InternalSubagentFuture):
            internal = future
        elif isinstance(future, DeferredSubagentFuture):
            deferred = future
    if internal is not None and deferred is not None:
        msg = (
            f"A combined sub-agent group cannot mix the internal call "
            f"{_describe(internal)} with the deferred call {_describe(deferred)}: "
            f"the two consult this action's durable journal in different phases, "
            f"so recovery cannot replay the reads in the written order. Resolve "
            f"the two calls separately."
        )
        raise RuntimeError(msg)


def _describe(future: SubagentFuture) -> str:
    """Name a handle the way the Java-side rejection does."""
    return f"{type(future).__name__} {future.identity}"
