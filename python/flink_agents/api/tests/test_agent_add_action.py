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
#################################################################################
"""Layer A API-surface tests for ``Agent.add_action`` (Python side). Mirrors Java's ``AgentAddActionTest``."""

import pytest

from flink_agents.api.agents.agent import Agent
from flink_agents.api.events.event import Event, InputEvent
from flink_agents.api.function import JavaFunction, PythonFunction
from flink_agents.api.runner_context import RunnerContext


def _dummy_action(event: Event, ctx: RunnerContext) -> None:
    """Plain Python callable used as an action body."""


def _make_java_function() -> JavaFunction:
    return JavaFunction(
        qualname="com.example.Handlers",
        method_name="handle",
        parameter_types=[
            "org.apache.flink.agents.api.Event",
            "org.apache.flink.agents.api.context.RunnerContext",
        ],
    )


# ── Descriptor pass-through ─────────────────────────────────────────────


def test_add_action_accepts_python_function_descriptor_and_stores_as_is() -> None:
    """Pre-built PythonFunction is stored verbatim — no re-wrapping."""
    agent = Agent()
    pf = PythonFunction(module="pkg.mod", qualname="MyClass.method")

    agent.add_action(name="act", trigger_conditions=[InputEvent.EVENT_TYPE], func=pf)

    trigger_conditions, stored, config = agent.actions["act"]
    assert trigger_conditions == [InputEvent.EVENT_TYPE]
    assert stored is pf, "PythonFunction descriptor should not be re-wrapped."
    assert config is None


def test_add_action_accepts_java_function_descriptor_and_stores_as_is() -> None:
    """Cross-language case: JavaFunction is stored verbatim, never wrapped."""
    agent = Agent()
    jf = _make_java_function()

    agent.add_action(name="act", trigger_conditions=[InputEvent.EVENT_TYPE], func=jf)

    trigger_conditions, stored, _ = agent.actions["act"]
    assert trigger_conditions == [InputEvent.EVENT_TYPE]
    assert stored is jf, "JavaFunction descriptor should be stored as-is."
    assert isinstance(stored, JavaFunction)


# ── Callable wrapping ───────────────────────────────────────────────────


def test_add_action_wraps_raw_callable_as_python_function() -> None:
    """Bare callables get auto-wrapped into a PythonFunction descriptor."""
    agent = Agent()
    agent.add_action(name="act", trigger_conditions=[InputEvent.EVENT_TYPE], func=_dummy_action)

    _, stored, _ = agent.actions["act"]
    assert isinstance(stored, PythonFunction)
    assert stored.qualname == "_dummy_action"
    assert stored.module == _dummy_action.__module__


# ── Duplicate name rejection ────────────────────────────────────────────


def test_add_action_duplicate_name_rejected_python_function() -> None:
    agent = Agent()
    agent.add_action(name="act", trigger_conditions=[InputEvent.EVENT_TYPE], func=_dummy_action)
    with pytest.raises(ValueError, match="act"):
        agent.add_action(
            name="act", trigger_conditions=[InputEvent.EVENT_TYPE], func=_dummy_action
        )


def test_add_action_duplicate_name_rejected_java_function() -> None:
    """Duplicate-name rejection applies uniformly regardless of descriptor type."""
    agent = Agent()
    agent.add_action(
        name="act", trigger_conditions=[InputEvent.EVENT_TYPE], func=_make_java_function()
    )
    with pytest.raises(ValueError, match="act"):
        agent.add_action(
            name="act", trigger_conditions=[InputEvent.EVENT_TYPE], func=_make_java_function()
        )


# ── Config capture ──────────────────────────────────────────────────────


def test_add_action_captures_config_kwargs() -> None:
    agent = Agent()
    agent.add_action(
        name="act",
        trigger_conditions=[InputEvent.EVENT_TYPE],
        func=_dummy_action,
        retry=3,
        timeout_sec=10,
    )

    _, _, config = agent.actions["act"]
    assert config == {"retry": 3, "timeout_sec": 10}


def test_add_action_config_is_none_when_no_kwargs() -> None:
    agent = Agent()
    agent.add_action(name="act", trigger_conditions=[InputEvent.EVENT_TYPE], func=_dummy_action)

    _, _, config = agent.actions["act"]
    assert config is None


def test_add_action_returns_self_for_chaining() -> None:
    agent = Agent()
    result = agent.add_action(
        name="act", trigger_conditions=[InputEvent.EVENT_TYPE], func=_dummy_action
    )
    assert result is agent


# ── Multiple trigger conditions ─────────────────────────────────────────


def test_add_action_supports_multiple_trigger_conditions() -> None:
    agent = Agent()
    agent.add_action(
        name="multi",
        trigger_conditions=["evt_a", "evt_b", "evt_c"],
        func=_make_java_function(),
    )

    trigger_conditions, _, _ = agent.actions["multi"]
    assert trigger_conditions == ["evt_a", "evt_b", "evt_c"]
