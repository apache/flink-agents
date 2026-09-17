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
``(id, call, reconcile)`` triple whose call waits off the mailbox thread for
the child to quiesce, so the operator can dispatch the child agent's actions
in between. The wait releases the
mailbox through the await-only resolve contract: a synchronous Python action
holds the mailbox for its whole ``pemja`` invocation, so it can never yield
for the operator to dispatch the child's actions and a blocking wait would
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

    Split into a mailbox-thread ``bootstrap`` (sends the call event) and an
    off-mailbox ``await``, because a Python action cannot cooperatively yield
    the mailbox from inside one blocking call the way the Java continuation
    path does.
    """

    def bootstrap_subagent_call(
        self, scope: str, session_id: str, call_id: str, prompt: Any
    ) -> None:
        """Send the call event for ``scope`` under the assigned identity.

        Must be invoked on the mailbox thread; it sends the bootstrap event.
        """
        ...

    def await_subagent_call(self, session_id: str, call_id: str) -> List[Any]:
        """Block until the identified call quiesces and return its outputs.

        Must be invoked off the mailbox thread so the mailbox can dispatch the
        child agent's actions while this waits.
        """
        ...


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

    def prepare(
        self,
        ctx: RunnerContext,
        prompt: Any,
        session_id: str,
        call_id: str,
    ) -> PreparedTriple:
        """Send the call event and return the prepared triple.

        Runs on the mailbox thread when the deferred handle is first resolved,
        as sending the bootstrap event requires; the returned call only
        awaits completion and runs off the mailbox thread so the operator can
        dispatch the child agent's actions in between.
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

        def _call() -> SubagentResult:
            # Runs off the mailbox thread (Python async worker), so the mailbox
            # is free to dispatch the child agent's actions until the call
            # quiesces. A child failure surfaces as a failed Result (mirroring
            # Java's InternalSubagentSetup) rather than propagating out of the
            # call.
            try:
                output = ctx.await_subagent_call(session_id, call_id)
            except Exception as e:
                return SubagentResult.error(e)
            return SubagentResult.ok(output)

        return (f"{session_id}#{call_id}", _call, None)
