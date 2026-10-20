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
"""Runtime setup for internal sub-agents.

The compiled form of an ``Agent`` registered directly as an AGENT resource:
the compiled descriptor carries the child
:class:`~flink_agents.plan.agent_plan.AgentPlan` and the scope (the resource
name) in a ``JavaResourceProvider`` referencing the Java runtime setup.
This Python caller owns the invocation behavior.

Execution is the same graph-call chain the Java side and the built-in chat
call run. :meth:`submit` returns a handle that does nothing until it is
resolved; resolving issues the call through the runtime context, then polls it
to completion, yielding the mailbox between polls so the operator can dispatch
the child agent's actions. Both phases run on the mailbox thread, so an action
resolving a handle must be a coroutine: a synchronous action holds the mailbox
for its whole ``pemja`` invocation and could never let the child run.

Issuing the call also reads the caller's durable slot, on the Java side and
before the bootstrap event is emitted, so replaying an already completed call
installs the recorded result and the child graph never starts. Sending the
bootstrap event outside a durable boundary is deliberate for the same reason: a
replayed send carries the same event attributes, so the child action resolves
to the same persisted action state and replays its recorded output instead of
running again.
"""

from concurrent.futures import CancelledError
from typing import Any, Protocol, runtime_checkable

from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.subagent import (
    SubagentFuture,
    SubagentFutures,
    SubagentResult,
)
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.runtime.base_subagent import BaseSubagentSetup
from flink_agents.runtime.subagent_handles import (
    PendingSubagentCallRegistry,
    SubagentFutureGroup,
)


@runtime_checkable
class InternalSubagentCallFactory(Protocol):
    """Runtime hook a ``RunnerContext`` implements to drive internal sub-agents.

    Both halves run on the mailbox thread. The handle yields between polls, so
    the child's actions are dispatched in between rather than being waited for
    inside one blocking call.
    """

    def bootstrap_internal_subagent_call(
        self, scope: str, session_id: str, call_id: str, prompt: Any
    ) -> None:
        """Issue the call for ``scope`` under the assigned identity.

        Must be invoked on the mailbox thread; it sends the bootstrap event
        unless the caller's durable slot already holds the result.
        """
        ...

    def try_complete_internal_subagent_call(
        self, session_id: str, call_id: str
    ) -> SubagentResult | None:
        """Take the issued call's result, or None while its child still runs.

        Must be invoked on the mailbox thread. A child failure comes back
        inside the result rather than being raised, so anything raised here
        belongs to the call itself and must reach the caller.
        """
        ...


class InternalSubagentFuture(SubagentFuture):
    """Handle to one internal sub-agent call, issued and polled to completion.

    Unlike a deferred external handle it carries no durable callable of its
    own: the Java runtime owns this call's durable slot, reading it before the
    child graph starts and recording the terminal result once it quiesces.
    """

    def __init__(
        self,
        session_id: str,
        call_id: str,
        ctx: InternalSubagentCallFactory,
        scope: str,
        prompt: Any,
        registry: PendingSubagentCallRegistry | None = None,
    ) -> None:
        """Initialize with the identity and everything issuing the call needs."""
        super().__init__(session_id, call_id)
        self._ctx = ctx
        self._scope = scope
        self._prompt = prompt
        self._registry = registry
        self._issued = False
        self._done = False
        self._cancelled = False
        self._value: SubagentResult | None = None
        if registry is not None:
            registry.track_pending_subagent_call(self.identity)

    def done(self) -> bool:
        """Whether the invocation has been resolved or cancelled."""
        return self._done or self._cancelled

    def cancel(self) -> None:
        """Cancel before the call is issued: nothing was sent, so there is
        nothing to undo. An already resolved handle ignores the request.
        """
        if self._done:
            return
        self._cancelled = True
        if self._registry is not None:
            self._registry.untrack_pending_subagent_call(self.identity)

    def prepare(self) -> None:
        """Issue the call if it has not been issued yet.

        Mailbox-confined: must run on the mailbox thread.
        """
        if self._cancelled:
            msg = f"Sub-agent call cancelled: {self.identity}"
            raise CancelledError(msg)
        if not self._issued:
            self._ctx.bootstrap_internal_subagent_call(
                self._scope, self._session_id, self._call_id, self._prompt
            )
            self._issued = True

    def execute(self) -> Any:
        """Poll the issued call to completion, yielding the mailbox in between.

        A child failure converges into a failed :class:`SubagentResult`, as it
        does on the Java side. A fault of the call itself propagates and fails
        the action rather than being reported as the child's answer.
        """
        self.prepare()
        while True:
            result = self._ctx.try_complete_internal_subagent_call(
                self._session_id, self._call_id
            )
            if result is not None:
                self._resolve(result)
                return
            yield

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


class InternalSubagentSetup(BaseSubagentSetup):
    """Compiled internal sub-agent, produced during ``AgentPlan`` compilation.

    Holds the child agent's compiled ``plan`` and its ``scope`` (the
    resource name used for runtime resolution). The child work is orchestrated
    by the operator: the handle delegates to
    :class:`InternalSubagentCallFactory` on the runtime context. Identity
    assignment, deferred issue and the pending-call registry are inherited from
    :class:`~flink_agents.runtime.base_subagent.BaseSubagentSetup`.
    """

    plan: AgentPlan
    scope: str

    async def submit_with_identity(
        self,
        ctx: RunnerContext,
        prompt: Any,
        session_id: str,
        call_id: str,
    ) -> SubagentFuture:
        """Return the handle issuing this call when it is resolved."""
        if not isinstance(ctx, InternalSubagentCallFactory):
            msg = (
                "InternalSubagentSetup requires a runtime context that "
                "implements InternalSubagentCallFactory"
            )
            raise NotImplementedError(msg)
        return InternalSubagentFuture(
            session_id,
            call_id,
            ctx,
            self.scope,
            prompt,
            registry=self.pending_call_registry(),
        )
