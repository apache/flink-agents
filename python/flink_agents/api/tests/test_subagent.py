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
"""Tests registering sub-agents as AGENT resources and the call routing."""
from typing import Any

import pytest

from flink_agents.api.agents.agent import Agent
from flink_agents.api.resource import ResourceType
from flink_agents.api.subagent import BaseSubagentCallable
from flink_agents.api.tests.subagent_test_utils import TestSubagentSetup


class _RecordingContext:
    """Fake context recording session minting and durable execution."""

    def __init__(self, session_id: str = "gen-session") -> None:
        self._session_id = session_id
        self.next_session_id_called = False
        self.next_call_id_calls: list[str] = []
        self.durable_execute_calls: list[tuple] = []

    def next_session_id(self) -> str:
        self.next_session_id_called = True
        return self._session_id

    def next_call_id(self, session_id: str) -> str:
        self.next_call_id_calls.append(session_id)
        return f"{session_id}-{len(self.next_call_id_calls)}"

    def durable_execute(
        self,
        func: Any,
        *args: Any,
        reconciler: Any = None,
        **kwargs: Any,
    ) -> Any:
        self.durable_execute_calls.append((func, args, reconciler))
        return func(*args)


def test_register_subagent_setup_as_resource() -> None:
    """A ``SubagentSetup`` registers under the AGENT resource map."""
    agent = Agent()
    setup = TestSubagentSetup()

    agent.add_resource("reviewer", ResourceType.AGENT, setup)

    agent_resources = agent.resources[ResourceType.AGENT]
    assert len(agent_resources) == 1
    assert agent_resources["reviewer"] is setup
    assert setup.resource_type() == ResourceType.AGENT


def test_duplicate_name_throws() -> None:
    """Registering a duplicate AGENT name raises."""
    agent = Agent()
    agent.add_resource("reviewer", ResourceType.AGENT, TestSubagentSetup())

    with pytest.raises(ValueError):
        agent.add_resource("reviewer", ResourceType.AGENT, TestSubagentSetup())


def test_multiple_subagents_registered() -> None:
    """Multiple distinct AGENT resources coexist."""
    agent = Agent()
    reviewer = TestSubagentSetup()
    coder = TestSubagentSetup()

    agent.add_resource("reviewer", ResourceType.AGENT, reviewer)
    agent.add_resource("coder", ResourceType.AGENT, coder)

    agent_resources = agent.resources[ResourceType.AGENT]
    assert len(agent_resources) == 2
    assert agent_resources["reviewer"] is reviewer
    assert agent_resources["coder"] is coder


def test_base_callable_captures_exception_into_error_result() -> None:
    """``BaseSubagentCallable`` wraps exceptions into ``Result.error``."""

    class _Failing(BaseSubagentCallable):
        def call_internal(self) -> Any:
            msg = "boom"
            raise RuntimeError(msg)

    result = _Failing("sid-1", "c-1").call()
    assert result.success is False
    # error_message is the full stack trace, which contains the original message.
    assert "boom" in result.error_message
    assert result.exception is not None


def test_call_with_explicit_session_id_uses_durable_execute() -> None:
    """Explicit-session ``call`` mints only a call id and routes durably."""
    setup = TestSubagentSetup()
    ctx = _RecordingContext()

    result = setup.call(ctx, "hello", "explicit-sid")

    assert ctx.next_session_id_called is False
    assert ctx.next_call_id_calls == ["explicit-sid"]
    assert len(ctx.durable_execute_calls) == 1
    assert result.success is True
    assert result.result == ["hello"]


def test_call_without_session_id_mints_both_ids() -> None:
    """2-arg ``call`` mints a session and a call id, then routes."""
    setup = TestSubagentSetup()
    ctx = _RecordingContext(session_id="minted")

    result = setup.call(ctx, "hi")

    assert ctx.next_session_id_called is True
    assert ctx.next_call_id_calls == ["minted"]
    assert len(ctx.durable_execute_calls) == 1
    assert result.success is True
    assert result.result == ["hi"]


def test_callable_id_equals_assigned_call_id() -> None:
    """The produced callable is keyed by the framework-assigned call id."""
    setup = TestSubagentSetup()
    ctx = _RecordingContext()

    callable_ = setup.as_async_callable(ctx, "ping", "sid-1")

    assert ctx.next_call_id_calls == ["sid-1"]
    assert callable_.id == "sid-1#sid-1-1"
    assert callable_.call().result == ["ping"]
