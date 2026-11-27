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
"""Compiled internal subagents execute through the shared subgraph future."""

from typing import Any

from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.subagent import SubagentFuture
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.runtime.base_subagent import BaseSubagentSetup
from flink_agents.runtime.subagent_handles import SubagentFutureImpl
from flink_agents.runtime.subgraph_future import SubgraphFuture


class InternalSubagentSetup(BaseSubagentSetup):
    """Compiled internal sub-agent, produced during ``AgentPlan`` compilation.

    Holds the child agent's compiled ``plan`` and its ``scope`` (the
    resource name used for runtime resolution). The handle delegates to the shared
    subgraph future, which drives child work through the operator. Identity
    assignment and the pending-call registry are inherited from
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
        return SubagentFutureImpl(
            session_id,
            call_id,
            SubgraphFuture.subagent(ctx, self.scope, session_id, call_id, prompt),
            self.pending_call_registry(),
        )
