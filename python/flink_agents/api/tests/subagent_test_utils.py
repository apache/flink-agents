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
"""Shared sub-agent test doubles."""

from typing import TYPE_CHECKING, Any

from pydantic import BaseModel

from flink_agents.api.subagent import SubagentSetup

if TYPE_CHECKING:
    from flink_agents.api.runner_context import RunnerContext
    from flink_agents.api.subagent import SubagentFuture


class Review(BaseModel):
    """Arguments of the typed double below."""

    path: str
    lines: int = 0


class Verdict(BaseModel):
    """Result of the typed double below."""

    approved: bool
    note: str = ""


class TestSubagentSetup(SubagentSetup):
    """Shared ``SubagentSetup`` test double, constructible directly or from a
    resource descriptor (the YAML shape).

    A pure api-layer descriptor: invocation behavior lives in the runtime
    layer, so the ``submit`` forms raise.
    """

    endpoint_url: str | None = None
    fail_on_call: bool = False

    def submit(
        self,
        ctx: "RunnerContext",
        prompt: Any,
        session_id: str | None = None,
        call_id: str | None = None,
    ) -> "SubagentFuture":
        """Descriptor-only double; invocation lives in the runtime layer."""
        msg = "Descriptor-only sub-agent setup; invocation lives in the runtime layer."
        raise NotImplementedError(msg)


class TypedTestSubagentSetup(TestSubagentSetup):
    """Types its arguments and its result instead of spelling out a schema."""

    @classmethod
    def input_type(cls) -> type:
        """Return the declared argument type."""
        return Review

    @classmethod
    def result_type(cls) -> type:
        """Return the declared result type."""
        return Verdict
