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
from typing import Any

from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.subagent import (
    BaseSubagentCallable,
    DurableCallable,
    Result,
    SubagentSetup,
)


class TestSubagentSetup(SubagentSetup):
    """Shared ``SubagentSetup`` test double, constructible directly or from a
    resource descriptor (the YAML shape).

    Echoes the prompt (prefixed with ``endpoint_url`` when set);
    ``fail_on_call=True`` makes every call fail, surfacing through ``Result``.
    """

    endpoint_url: str | None = None
    fail_on_call: bool = False

    def as_async_callable(
        self,
        ctx: RunnerContext,
        prompt: Any,
        session_id: str | None = None,
        call_id: str | None = None,
    ) -> DurableCallable[Result]:
        """Return a callable echoing (or failing) one invocation."""
        if session_id is None:
            session_id = ctx.next_session_id()
        if call_id is None:
            call_id = ctx.next_call_id(session_id)
        setup = self

        class _Call(BaseSubagentCallable):
            def call_internal(self) -> Any:
                if setup.fail_on_call:
                    msg = f"endpoint {setup.endpoint_url} is down"
                    raise RuntimeError(msg)
                if setup.endpoint_url is None:
                    return [prompt]
                return [f"{setup.endpoint_url}:{prompt}"]

        return _Call(session_id, call_id)
