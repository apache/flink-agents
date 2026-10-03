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
"""Deferred internal sub-agents scheduled on the operator mailbox."""

from typing import Any, Protocol, runtime_checkable

from flink_agents.api.runner_context import RunnerContext
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.runtime.deferred_subagent import DeferredSubagentSetup, PreparedTriple
from flink_agents.runtime.internal_subagent_call import InternalSubagentCall


@runtime_checkable
class InternalSubagentCallFactory(Protocol):
    """Mailbox hooks for starting and observing an internal invocation."""

    def bootstrap_subagent_call(
        self, scope: str, session_id: str, call_id: str, prompt: Any
    ) -> None:
        """Start a call on the mailbox thread."""
        ...

    def is_subagent_call_done(self, session_id: str, call_id: str) -> bool:
        """Whether the call has reached a terminal state."""
        ...

    def get_subagent_call_error(self, session_id: str, call_id: str) -> str | None:
        """Return the persisted child failure summary, if any."""
        ...

    def await_subagent_call(self, session_id: str, call_id: str) -> list[Any]:
        """Read the output after the call has completed."""
        ...


class InternalSubagentSetup(DeferredSubagentSetup):
    """An Agent compiled as a child; submission and preparation do not start it."""

    child_plan: AgentPlan
    scope: str

    def prepare(
        self,
        ctx: RunnerContext,
        prompt: Any,
        session_id: str,
        call_id: str,
    ) -> PreparedTriple:
        """Describe the call; the durable scheduler starts it after admission."""
        if not isinstance(ctx, InternalSubagentCallFactory):
            msg = (
                "InternalSubagentSetup requires a runtime context that "
                "implements InternalSubagentCallFactory"
            )
            raise NotImplementedError(msg)
        return (
            f"{session_id}#{call_id}",
            InternalSubagentCall(ctx, self.scope, session_id, call_id, prompt),
            None,
        )
