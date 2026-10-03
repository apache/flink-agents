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
"""A mailbox-owned internal call, observed without an async worker thread."""

from typing import TYPE_CHECKING, Any

from flink_agents.api.subagent import SubagentResult

if TYPE_CHECKING:
    from flink_agents.runtime.internal_subagent import InternalSubagentCallFactory


class InternalSubagentCall:
    """The durable scheduler admits this call before sending its bootstrap event."""

    def __init__(
        self,
        ctx: "InternalSubagentCallFactory",
        scope: str,
        session_id: str,
        call_id: str,
        prompt: Any,
    ) -> None:
        """Capture the deterministic invocation identity and input."""
        self._ctx = ctx
        self._scope = scope
        self._session_id = session_id
        self._call_id = call_id
        self._prompt = prompt

    def __call__(self) -> SubagentResult:
        """Reject blocking invocation outside the mailbox scheduler."""
        msg = "Internal sub-agent calls must be awaited through durable execution"
        raise RuntimeError(msg)

    def start(self) -> "InternalSubagentCall":
        """Bootstrap on the mailbox after the durable slot has been reserved."""
        self._ctx.bootstrap_subagent_call(
            self._scope, self._session_id, self._call_id, self._prompt
        )
        return self

    def done(self) -> bool:
        """Poll completion without blocking the mailbox or an async worker."""
        return self._ctx.is_subagent_call_done(self._session_id, self._call_id)

    def result(self) -> SubagentResult:
        """Read a completed call, preserving child failures as per-call results."""
        if not self.done():
            msg = "Internal sub-agent call has not completed"
            raise RuntimeError(msg)
        error = self._ctx.get_subagent_call_error(self._session_id, self._call_id)
        if error is not None:
            return SubagentResult.error(error)
        output = self._ctx.await_subagent_call(self._session_id, self._call_id)
        return SubagentResult.ok(output)
