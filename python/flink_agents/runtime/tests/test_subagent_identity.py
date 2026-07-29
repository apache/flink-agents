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
"""Tests for sub-agent identity allocation on the Python side.

``FlinkRunnerContext`` forwards both allocators to the Java runner context over
pemja; determinism and failover-replay behavior are covered by the Java runtime
tests (``SubagentIdentityContextTest`` / ``SubagentIdentityRecoveryTest``).
"""
from typing import Any, List

from flink_agents.runtime.flink_runner_context import FlinkRunnerContext


class _FakeJavaRunnerContextForIdentity:
    """Records nextSessionId()/nextCallId() calls made by FlinkRunnerContext."""

    def __init__(self) -> None:
        self.next_session_id_calls = 0
        self.next_call_id_calls: List[str] = []

    def nextSessionId(self) -> str:
        """Fake Java allocator: return a distinguishable, incrementing id."""
        self.next_session_id_calls += 1
        return f"java-session-{self.next_session_id_calls}"

    def nextCallId(self, session_id: str) -> str:
        """Fake Java allocator: record the session id it was given."""
        self.next_call_id_calls.append(session_id)
        return f"java-call-{session_id}-{len(self.next_call_id_calls)}"


def _create_flink_runner_context(j_runner_context: Any) -> FlinkRunnerContext:
    """Bare-construct a FlinkRunnerContext, bypassing __init__.

    Mirrors the ``FlinkRunnerContext.__new__(FlinkRunnerContext)`` pattern
    used by ``test_flink_runner_context_reconcilable.py``.
    """
    ctx = FlinkRunnerContext.__new__(FlinkRunnerContext)
    ctx._j_runner_context = j_runner_context
    return ctx


def test_flink_runner_context_next_session_id_delegates_to_java() -> None:
    """next_session_id() is a pure delegation to the Java allocator."""
    j_ctx = _FakeJavaRunnerContextForIdentity()
    ctx = _create_flink_runner_context(j_ctx)

    session_id = ctx.next_session_id()

    assert session_id == "java-session-1"
    assert j_ctx.next_session_id_calls == 1


def test_flink_runner_context_next_call_id_delegates_to_java_with_session_id() -> (
    None
):
    """next_call_id() forwards the session id to the Java allocator."""
    j_ctx = _FakeJavaRunnerContextForIdentity()
    ctx = _create_flink_runner_context(j_ctx)

    call_id = ctx.next_call_id("session-x")

    assert call_id == "java-call-session-x-1"
    assert j_ctx.next_call_id_calls == ["session-x"]
