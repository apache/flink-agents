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

The execution-mode-agnostic companions of the sub-agent handles: the
dropped-handle registry, the already-resolved handle, and the batched
resolve. Execution modes build on them without the framework knowing how a
request is issued.
"""

from typing import Any

from flink_agents.api.subagent import (
    Result,
    SubagentFuture,
    SubagentFutures,
)


class PendingSubagentCallRegistry:
    """Tracks sub-agent handles submitted by one action execution but not
    resolved yet.

    The runtime future implementations record handles here directly; setups
    that want the dropped-handle safety net own one per task. The framework
    never attaches it implicitly.

    Per-task heap state owned by the consuming setup for the duration of one
    action execution. Mailbox-confined: no synchronization. Insertion order is
    preserved so failure messages list the dropped handles deterministically.
    """

    def __init__(self) -> None:
        """Initialize an empty registry."""
        self._pending_calls: list[str] = []

    def track_pending_subagent_call(self, call_identity: str) -> None:
        """Record a pending handle; duplicate identities collapse to one."""
        if call_identity not in self._pending_calls:
            self._pending_calls.append(call_identity)

    def untrack_pending_subagent_call(self, call_identity: str) -> None:
        """Drop a resolved handle; no-op when the identity is unknown."""
        if call_identity in self._pending_calls:
            self._pending_calls.remove(call_identity)

    def is_empty(self) -> bool:
        """Whether no handle is pending."""
        return not self._pending_calls

    def check_empty(self, action_name: str) -> None:
        """Fail when the finished action left a handle unresolved.

        The request was never resolved, so the invocation silently did not
        happen. Clears the registry before throwing so the failure cannot be
        reported twice.
        """
        if self._pending_calls:
            dropped = list(self._pending_calls)
            self._pending_calls.clear()
            msg = (
                f"Action {action_name} finished without resolving the "
                f"sub-agent calls it submitted: {dropped}. Resolve every "
                f"handle returned by submit(), individually or through "
                f"SubagentFutures."
            )
            raise RuntimeError(msg)


class CompletedSubagentFuture(SubagentFuture):
    """A handle for an invocation that has already produced ``value``."""

    def __init__(self, session_id: str, call_id: str, value: Result) -> None:
        """Initialize with the identity and the produced value."""
        super().__init__(session_id, call_id)
        self._value = value

    def done(self) -> bool:
        """The invocation has already reached its terminal state."""
        return True

    def combine(self, *others: SubagentFuture) -> SubagentFutures:
        """Group this handle with others for a batched resolve."""
        return SubagentFutureGroup((self, *others))

    def __await__(self) -> Any:
        """Resolve immediately with the produced value."""
        return self._value
        yield  # pragma: no cover - makes this a generator function


class SubagentFutureGroup(SubagentFutures):
    """Batched resolve of several handles in submission order.

    The group knows deferred handles: the batched wait prepares every
    pending deferred handle up front, executes the prepared calls as a
    batch, and only then collects the outcomes — so the requests are issued
    together instead of one at a time as each wait starts. Already resolved
    handles simply contribute their value.
    """

    def __init__(self, futures: tuple) -> None:
        """Initialize with the handles to resolve together."""
        self._futures = tuple(futures)

    def done(self) -> bool:
        """Whether every handle in the batch has been resolved."""
        return all(future.done() for future in self._futures)

    def cancel(self) -> None:
        """Propagate the cancellation request to every handle in the batch."""
        for future in self._futures:
            future.cancel()

    def combine(self, *others: SubagentFuture) -> SubagentFutures:
        """Add more handles to the batch."""
        return SubagentFutureGroup((*self._futures, *others))

    def __await__(self) -> Any:
        """Prepare every pending deferred handle, batch-execute, then wait."""
        # Late import: deferred handles build on this module.
        from flink_agents.runtime.deferred_subagent import DeferredSubagentFuture

        pending = [
            future
            for future in self._futures
            if isinstance(future, DeferredSubagentFuture) and not future.done()
        ]
        # Prepare the whole batch before any execution starts.
        for future in pending:
            future.prepare()
        # TODO: execute the prepared calls as one batch once durable
        # execution supports batched submission; until then the batch is
        # executed serially.
        for future in pending:
            yield from future.execute()
        outcomes = []
        for future in self._futures:
            outcome = yield from future.__await__()
            outcomes.append(outcome)
        return outcomes
