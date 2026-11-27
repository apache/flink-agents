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
"""Deferred subagents share the ordinary durable-future execution path."""

from abc import ABC, abstractmethod
from typing import Any

from typing_extensions import override

from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.subagent import SubagentCall, SubagentFuture
from flink_agents.runtime.async_future import SingleDurableFuture
from flink_agents.runtime.base_subagent import BaseSubagentSetup
from flink_agents.runtime.subagent_handles import SubagentFutureImpl


class DeferredSubagentSetup(BaseSubagentSetup, ABC):
    """Prepare on the mailbox, then execute or reconcile on the async pool."""

    @override
    async def submit_with_identity(
        self, ctx: RunnerContext, prompt: Any, session_id: str, call_id: str
    ) -> SubagentFuture:
        def preparation() -> tuple:
            prepared = self.prepare(ctx, prompt, session_id, call_id)
            return prepared.call, prepared.reconciler

        delegate = SingleDurableFuture(ctx, f"{session_id}#{call_id}", preparation)
        return SubagentFutureImpl(
            session_id, call_id, delegate, self.pending_call_registry()
        )

    @abstractmethod
    def prepare(
        self, ctx: RunnerContext, prompt: Any, session_id: str, call_id: str
    ) -> SubagentCall:
        """Build execution and optional reconciliation callbacks on the mailbox.

        The framework reserves the durable slot before calling this method. A
        recorded terminal outcome skips preparation entirely. Recovery of a pending
        call may repeat preparation, so any request sent here must be safe to repeat.
        The framework owns the durable id; implementations only provide the work.
        """
