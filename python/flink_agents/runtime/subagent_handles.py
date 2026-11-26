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
"""Subagent business handles delegating execution to runtime futures."""

from concurrent.futures import CancelledError
from typing import Any, Callable

from typing_extensions import override

from flink_agents.api.runner_context import Outcome
from flink_agents.api.subagent import SubagentFuture, SubagentFutures, SubagentResult
from flink_agents.runtime.async_future import MappedAsyncFuture, SchedulableFuture


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


class SubagentFutureImpl(MappedAsyncFuture, SubagentFuture):
    """Identity, remote probes and cancellation around one schedulable call."""

    def __init__(
        self,
        session_id: str,
        call_id: str,
        delegate: SchedulableFuture,
        registry: PendingSubagentCallRegistry | None = None,
        probe: Callable[[], bool] | None = None,
        remote_cancel: Callable[[], None] | None = None,
    ) -> None:
        """Add subagent identity and lifecycle hooks to the execution delegate."""
        MappedAsyncFuture.__init__(self, delegate, lambda value: value)
        self._session_id = session_id
        self._call_id = call_id
        self._registry = registry
        self._probe = probe
        self._remote_cancel = remote_cancel
        self._cancelled = False
        if registry is not None:
            registry.track_pending_subagent_call(self.identity)

    @override
    def done(self) -> bool:
        return (
            self._is_done()
            or self._cancelled
            or (self._probe is not None and self._probe())
        )

    @override
    def prepare(self) -> Outcome | None:
        if self._cancelled:
            message = f"Sub-agent call cancelled: {self.identity}"
            raise CancelledError(message)
        return super().prepare()

    @override
    def cancel(self) -> None:
        if self._is_done() or self._cancelled:
            return
        # Let a completed result win a timeout/cancel race; retain it for finish().
        if self._source.ready():
            return
        self._cancelled = True
        try:
            super().cancel()
            if self._remote_cancel is not None:
                self._remote_cancel()
        finally:
            self._release()

    @override
    def close(self) -> None:
        try:
            super().close()
        finally:
            self._release()

    def _release(self) -> None:
        if self._registry is not None:
            self._registry.untrack_pending_subagent_call(self.identity)

    @override
    def combine(self, *others: SubagentFuture) -> SubagentFutures:
        return SubagentFutureGroup((self, *others))


class CompletedSubagentFuture(SchedulableFuture, SubagentFuture):
    """An already resolved subagent result; no runtime work or durable slot."""

    def __init__(self, session_id: str, call_id: str, value: SubagentResult) -> None:
        """Cache an existing subagent result without scheduling work."""
        SchedulableFuture.__init__(self, None)
        self._session_id = session_id
        self._call_id = call_id
        self._complete(Outcome.success(value))

    @override
    def done(self) -> bool:
        return True

    @override
    def ready(self) -> bool:
        return True

    @override
    def start(self) -> None:
        pass

    @override
    def finish(self, timeout: TimeoutError | None = None) -> Outcome:
        return self._get_completed_outcome()

    @override
    def combine(self, *others: SubagentFuture) -> SubagentFutures:
        return SubagentFutureGroup((self, *others))


class SubagentFutureGroup(SubagentFutures):
    """Subagent convenience grouping over the same mixed gather scheduler."""

    def __init__(self, futures: tuple) -> None:
        """Keep the handles to resolve through their owning context."""
        self._futures = futures

    @override
    def done(self) -> bool:
        return all(future.done() for future in self._futures)

    @override
    def cancel(self) -> None:
        for future in self._futures:
            future.cancel()

    @override
    def combine(self, *others: SubagentFuture) -> SubagentFutures:
        return SubagentFutureGroup((*self._futures, *others))

    def __await__(self) -> Any:
        ctx = next(
            (
                future._ctx
                for future in self._futures
                if isinstance(future, SchedulableFuture) and future._ctx is not None
            ),
            None,
        )
        if ctx is None:
            values = []
            for future in self._futures:
                values.append((yield from future.__await__()))  # noqa: PERF401
            return values
        outcomes = yield from ctx.gather(*self._futures).__await__()
        values = []
        for outcome in outcomes:
            if outcome.error is not None:
                raise outcome.error
            values.append(outcome.value)
        return values
