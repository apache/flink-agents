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
the plan layer carries the child
:class:`~flink_agents.plan.agent_plan.AgentPlan` and the scope (the resource
name) in a ``PythonSerializableResourceProvider`` referencing this class by
name; this runtime class owns the invocation behavior.

Execution mode is the deferred one, inherited from
:class:`~flink_agents.runtime.deferred_subagent.DeferredSubagentSetup`:
:meth:`submit` returns a deferred handle whose request is prepared on the
first resolve. :meth:`prepare` then runs the mailbox-confined bootstrap —
sending the call event through the runtime context — and returns the
``(id, call, reconcile)`` triple whose call is read after a cooperative wait
for the child to quiesce. No async worker is occupied by the wait. The wait
releases the mailbox through the await-only resolve contract: a synchronous
Python action holds the mailbox for its whole ``pemja`` invocation, so it cannot
yield for the operator to dispatch the child's actions and a blocking wait would
deadlock.

Sending the bootstrap event outside the durable boundary is deliberate: a
replayed send carries the same event attributes, so the child action resolves
to the same persisted action state and replays its recorded output instead of
running again.
"""

from typing import Any, List, Protocol, runtime_checkable

from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.subagent import SubagentResult
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.runtime.deferred_subagent import (
    DeferredSubagentSetup,
    PreparedTriple,
)


@runtime_checkable
class InternalSubagentCallFactory(Protocol):
    """Runtime hook a ``RunnerContext`` implements to drive internal sub-agents.

    Bootstrap sends the call event on the mailbox. A readiness probe lets the
    coroutine yield until the result can be read without blocking.
    """

    def bootstrap_subagent_call(
        self, scope: str, session_id: str, call_id: str, prompt: Any
    ) -> None:
        """Send the call event for ``scope`` under the assigned identity.

        Must be invoked on the mailbox thread; it sends the bootstrap event.
        """
        ...

    def await_subagent_call(self, session_id: str, call_id: str) -> List[Any]:
        """Read the outputs of a completed call; fail if it is still running."""
        ...

    def is_subagent_call_done(self, session_id: str, call_id: str) -> bool:
        """Check completion on the mailbox without blocking a worker."""
        ...

    def subagent_failure_message(self, session_id: str, call_id: str) -> str | None:
        """Return the recorded failure summary, independent of bridge exceptions."""
        ...


class InternalSubagentCall:
    """Read a completed internal call on the mailbox through durable execution."""

    def __init__(
        self, ctx: InternalSubagentCallFactory, session_id: str, call_id: str
    ) -> None:
        """Keep the call identity independent of the currently active scope."""
        self.ctx = ctx
        self.session_id = session_id
        self.call_id = call_id

    def done(self) -> bool:
        """Whether the operator has completed the child invocation."""
        return self.ctx.is_subagent_call_done(self.session_id, self.call_id)

    def __call__(self) -> SubagentResult:
        """Read the ready result; preserve the recorded child failure summary."""
        try:
            output = self.ctx.await_subagent_call(self.session_id, self.call_id)
        except Exception:
            message = self.ctx.subagent_failure_message(self.session_id, self.call_id)
            if message is None:
                # Cancellation and runtime errors are not terminal child failures.
                raise
            return SubagentResult.error(message)
        return SubagentResult.ok(output)


class InternalSubagentSetup(DeferredSubagentSetup):
    """Compiled internal sub-agent, produced during ``AgentPlan`` compilation.

    Holds the child agent's compiled ``child_plan`` and its ``scope`` (the
    resource name used for runtime resolution). The child work is orchestrated
    by the operator: the async callable delegates to
    :class:`InternalSubagentCallFactory` on the runtime context. The
    :meth:`submit` behavior is inherited as-is from the deferred base:
    explicit identities, deferred issue, and the await-only resolve.
    """

    child_plan: AgentPlan
    scope: str

    @classmethod
    def result_type(cls) -> type:
        """Internal agents return a list, including structured output values."""
        return list

    def prepare(
        self,
        ctx: RunnerContext,
        prompt: Any,
        session_id: str,
        call_id: str,
    ) -> PreparedTriple:
        """Send the call event and return the prepared triple.

        Runs on the mailbox thread when the deferred handle is first resolved,
        as sending the bootstrap event requires. The runtime yields until the
        child finishes, then records its result without an async worker.
        """
        if not isinstance(ctx, InternalSubagentCallFactory):
            msg = (
                "InternalSubagentSetup requires a runtime context that "
                "implements InternalSubagentCallFactory"
            )
            raise NotImplementedError(msg)
        scope = self.scope
        # Mailbox-thread phase: the event carries the assigned identity, so a
        # replayed send reproduces the same event attributes.
        ctx.bootstrap_subagent_call(scope, session_id, call_id, prompt)

        return (
            f"{session_id}#{call_id}",
            InternalSubagentCall(ctx, session_id, call_id),
            None,
        )
