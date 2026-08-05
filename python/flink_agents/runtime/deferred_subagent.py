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
"""The framework-level deferred execution mode for sub-agent setups."""

from abc import ABC, abstractmethod
from concurrent.futures import CancelledError
from typing import Any, Callable

from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.subagent import (
    Result,
    SubagentFuture,
    SubagentFutures,
)
from flink_agents.runtime.base_subagent import BaseSubagentSetup
from flink_agents.runtime.subagent_handles import (
    PendingSubagentCallRegistry,
    SubagentFutureGroup,
)

#: The native triple one durable sub-agent invocation needs: the stable
#: durable id keying the invocation across restarts, the callable running
#: the off-mailbox part of the invocation, and the optional reconciler that
#: recovers an in-flight invocation after failover.
PreparedTriple = tuple[Any, Any, Any]


class DeferredSubagentFuture(SubagentFuture):
    """Deferred handle: the request is prepared when the handle is resolved.

    Resolving prepares the request through the factory and runs it through
    durable execution; the prepared triple ``(id, call, reconcile)`` is fed
    to durable execution directly. The optional ``registry`` records the
    handle until it is resolved, so a caller that drops the handle fails
    instead of silently skipping the invocation.

    Cancelling a handle whose request has not been prepared yet discards
    the request: :meth:`prepare` and the await raise
    :class:`CancelledError`, and nothing is sent for the cancelled handle.
    The request was never prepared, so cancellation has an unambiguous
    meaning.
    """

    def __init__(
        self,
        session_id: str,
        call_id: str,
        ctx: RunnerContext,
        prepared_factory: Callable[[], PreparedTriple],
        registry: PendingSubagentCallRegistry | None = None,
    ) -> None:
        """Initialize with the identity and the factory preparing the call."""
        super().__init__(session_id, call_id)
        self._ctx = ctx
        self._prepared_factory = prepared_factory
        self._registry = registry
        self._prepared: PreparedTriple | None = None
        self._done = False
        self._cancelled = False
        self._value: Result | None = None
        if registry is not None:
            registry.track_pending_subagent_call(self.identity)

    def done(self) -> bool:
        """Whether the invocation has been resolved or cancelled."""
        return self._done or self._cancelled

    def cancel(self) -> None:
        """Cancel before the request is prepared: the request is discarded.

        Resolving a cancelled handle raises :class:`CancelledError`. An
        already resolved handle ignores the cancellation request.
        """
        if self._done:
            return
        self._cancelled = True
        if self._registry is not None:
            self._registry.untrack_pending_subagent_call(self.identity)

    def prepare(self) -> PreparedTriple:
        """Prepare the request if it has not been prepared yet.

        Mailbox-confined, so it runs on the calling thread.
        """
        if self._cancelled:
            msg = f"Sub-agent call cancelled: {self.identity}"
            raise CancelledError(msg)
        if self._prepared is None:
            self._prepared = self._prepared_factory()
        return self._prepared

    def execute(self) -> Any:
        """Run the prepared request through durable execution and record
        the outcome; awaitable, releasing the mailbox while waiting.
        """
        durable_id, call, reconcile = self.prepare()
        try:
            value = yield from self._ctx.durable_execute_async(
                call,
                reconciler=reconcile,
                durable_id=durable_id,
            ).__await__()
        except Exception as e:
            # Failures converge into a failed Result.
            value = Result.error(e)
        self._resolve(value)

    def combine(self, *others: SubagentFuture) -> SubagentFutures:
        """Group this handle with others for a batched resolve."""
        return SubagentFutureGroup((self, *others))

    def __await__(self) -> Any:
        """Resolve the invocation, releasing the mailbox while waiting."""
        if self._cancelled:
            msg = f"Sub-agent call cancelled: {self.identity}"
            raise CancelledError(msg)
        if not self._done:
            yield from self.execute()
        return self._value

    def _resolve(self, value: Result) -> None:
        self._value = value
        self._done = True
        if self._registry is not None:
            self._registry.untrack_pending_subagent_call(self.identity)


class DeferredSubagentSetup(BaseSubagentSetup, ABC):
    """Runtime base for sub-agent setups running through one async callable.

    :meth:`submit_with_identity` always returns a deferred handle built on
    :class:`DeferredSubagentFuture`: the request is prepared on the first
    resolve. Implementations only provide the terminal :meth:`prepare`,
    which supplies the ``(id, call, reconcile)`` triple; the handle feeds
    that triple to durable execution itself.

    Ids come from the base: the short forms assign through the currently
    executing task's allocator, and handles record themselves in the
    base's per-task registry, so a dropped handle fails the task instead
    of silently skipping the invocation.
    """

    def submit_with_identity(
        self,
        ctx: RunnerContext,
        prompt: Any,
        session_id: str,
        call_id: str,
    ) -> SubagentFuture:
        """Issue an invocation under the given identity and return its handle.

        The returned handle prepares the invocation on first use, so it
        participates in failover recovery. The request is deferred to the
        first resolve.
        """
        return DeferredSubagentFuture(
            session_id,
            call_id,
            ctx,
            prepared_factory=lambda: self.prepare(ctx, prompt, session_id, call_id),
            registry=self.pending_call_registry(),
        )

    @abstractmethod
    def prepare(
        self,
        ctx: RunnerContext,
        prompt: Any,
        session_id: str,
        call_id: str,
    ) -> PreparedTriple:
        """Prepare one invocation and return its ``(id, call, reconcile)``
        triple; ids are supplied.

        The durable id MUST be derived solely from the
        ``(session_id, call_id)`` pair so it is reproducible after failover;
        ``reconcile`` may be ``None`` to fall back to replay or
        re-execution.

        Called exactly once per invocation, when the deferred handle is
        first resolved, on the mailbox thread; implementations may therefore
        perform the mailbox-confined part of issuing the request here (an
        internal sub-agent sends its call event), leaving only the
        off-mailbox part in the returned call.
        """
