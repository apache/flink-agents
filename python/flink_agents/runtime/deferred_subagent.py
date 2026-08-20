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
    SubagentFuture,
    SubagentFutures,
    SubagentResult,
)
from flink_agents.runtime.base_subagent import BaseSubagentSetup
from flink_agents.runtime.subagent_handles import (
    PendingSubagentCallRegistry,
    SubagentFutureGroup,
)

#: The (durable id, call, reconcile) triple returned by
#: :meth:`DeferredSubagentSetup.prepare`.
PreparedTriple = tuple[Any, Any, Any]


class DeferredSubagentFuture(SubagentFuture):
    """Deferred handle to one sub-agent invocation."""

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
        self._value: SubagentResult | None = None
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

        Mailbox-confined: must run on the mailbox thread.
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

        A system-level failure escaping durable execution propagates and fails
        the action instead of being folded into an error result.
        """
        durable_id, call, reconcile = self.prepare()
        value = yield from self._ctx.durable_execute_async(
            call,
            reconciler=reconcile,
            durable_id=durable_id,
        ).__await__()
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

    def _resolve(self, value: SubagentResult) -> None:
        self._value = value
        self._done = True
        if self._registry is not None:
            self._registry.untrack_pending_subagent_call(self.identity)


class DeferredSubagentSetup(BaseSubagentSetup, ABC):
    """The framework-level deferred execution mode for sub-agent setups.

    ``submit`` registers the invocation and returns a deferred handle
    without sending anything; the actual request is issued lazily when
    the handle is first resolved, and runs through one durable async
    callable keyed by a failover-reproducible id, so the invocation
    participates in the task's durable execution.
    """

    def submit_with_identity(
        self,
        ctx: RunnerContext,
        prompt: Any,
        session_id: str,
        call_id: str,
    ) -> SubagentFuture:
        """Issue an invocation under the given identity and return its handle."""
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
        ``(session_id, call_id)`` pair so it is reproducible after
        failover.

        Called exactly once per invocation, when the deferred handle is
        first resolved, on the mailbox thread; implementations may
        therefore perform the mailbox-confined part of issuing the request
        here, leaving only the off-mailbox part in the returned call.

        The returned call folds its own comprehensible failures into the
        :class:`SubagentResult` it returns; an exception escaping the call
        is a system-level failure that propagates and fails the action.

        Skipping ``reconcile`` has a cost: a crash between the call landing
        and its result being persisted re-invokes the sub-agent on replay,
        possibly duplicating external side effects.
        """
