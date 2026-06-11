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
"""Layer B plan-compile tests for cross-language ``Function`` descriptors (Python side)."""

import json
import os
from pathlib import Path

import pytest

from flink_agents.api.agents.agent import Agent
from flink_agents.api.events.event import Event, InputEvent
from flink_agents.api.function import (
    JavaFunction as ApiJavaFunction,
)
from flink_agents.api.function import (
    PythonFunction as ApiPythonFunction,
)
from flink_agents.api.runner_context import RunnerContext
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.function import (
    JavaFunction as PlanJavaFunction,
)
from flink_agents.plan.function import (
    PythonFunction as PlanPythonFunction,
)

# python/flink_agents/plan/tests/test_*.py -> repo root is parents[4].
_REPO_ROOT = Path(__file__).resolve().parents[4]
_SNAPSHOT_DIR = _REPO_ROOT / "e2e-test" / "cross-language-agent-plan-snapshots"


def _regenerate_enabled() -> bool:
    return os.environ.get("REGENERATE_SNAPSHOTS", "").lower() in {"1", "true", "yes"}


def _plan_dump_json(plan: AgentPlan) -> str:
    """Stable JSON form of an AgentPlan, indented for diff-friendliness."""
    return plan.model_dump_json(serialize_as_any=True, indent=2)


def _dummy_action(event: Event, ctx: RunnerContext) -> None:
    """Plain Python callable referenced by Python-target plans."""


def _make_java_function_descriptor() -> ApiJavaFunction:
    return ApiJavaFunction(
        qualname="com.example.Handlers",
        method_name="handle",
        parameter_types=[
            "org.apache.flink.agents.api.Event",
            "org.apache.flink.agents.api.context.RunnerContext",
        ],
    )


def _make_python_function_descriptor() -> ApiPythonFunction:
    return ApiPythonFunction(
        module=_dummy_action.__module__,
        qualname=_dummy_action.__qualname__,
    )


# ── api → plan promotion (Python side) ──────────────────────────────────


def test_compile_agent_with_python_function_descriptor() -> None:
    """ApiPythonFunction added via add_action becomes plan PythonFunction."""
    agent = Agent()
    pf = _make_python_function_descriptor()
    agent.add_action(name="act", trigger_conditions=[InputEvent.EVENT_TYPE], func=pf)

    plan = AgentPlan.from_agent(agent, AgentConfiguration())
    action = plan.actions["act"]

    assert isinstance(action.exec, PlanPythonFunction), (
        f"Expected plan PythonFunction, got {type(action.exec).__name__}"
    )
    assert action.exec.module == pf.module
    assert action.exec.qualname == pf.qualname
    assert action.trigger_conditions== [InputEvent.EVENT_TYPE]


def test_compile_agent_with_java_function_descriptor() -> None:
    """ApiJavaFunction added via add_action becomes plan JavaFunction.

    Python's ``_to_plan_function`` does NOT resolve the Java class — it
    keeps ``parameter_types`` as opaque strings. This is the documented
    asymmetry from Java, which calls ``Class.forName`` at this point.
    """
    agent = Agent()
    jf = _make_java_function_descriptor()
    agent.add_action(name="act", trigger_conditions=[InputEvent.EVENT_TYPE], func=jf)

    plan = AgentPlan.from_agent(agent, AgentConfiguration())
    action = plan.actions["act"]

    assert isinstance(action.exec, PlanJavaFunction), (
        f"Expected plan JavaFunction, got {type(action.exec).__name__}"
    )
    assert action.exec.qualname == jf.qualname
    assert action.exec.method_name == jf.method_name
    assert list(action.exec.parameter_types) == list(jf.parameter_types)
    assert action.trigger_conditions== [InputEvent.EVENT_TYPE]


def test_python_plan_compile_does_not_validate_java_class_exists() -> None:
    """Python plan compile must not require the Java class to exist locally.

    Cross-language descriptors are pure data on the Python side; class
    resolution happens later on the Java side at runtime. A
    nonexistent FQN must compile cleanly here.
    """
    agent = Agent()
    fake = ApiJavaFunction(
        qualname="com.does.not.Exist",
        method_name="ghost",
        parameter_types=["java.lang.String", "int"],
    )
    agent.add_action(name="act", trigger_conditions=[InputEvent.EVENT_TYPE], func=fake)

    plan = AgentPlan.from_agent(agent, AgentConfiguration())
    assert plan.actions["act"].exec.qualname == "com.does.not.Exist"


def test_compile_preserves_action_config() -> None:
    agent = Agent()
    agent.add_action(
        name="act",
        trigger_conditions=[InputEvent.EVENT_TYPE],
        func=_make_python_function_descriptor(),
        timeout_sec=30,
        retry=2,
    )

    plan = AgentPlan.from_agent(agent, AgentConfiguration())
    assert plan.actions["act"].config == {"timeout_sec": 30, "retry": 2}


def test_compile_rejects_unknown_function_descriptor() -> None:
    """Sanity: an unknown ApiFunction subclass should be refused."""
    from flink_agents.api.function import Function as ApiFunction

    class WeirdFunction(ApiFunction):
        pass

    agent = Agent()
    agent.add_action(
        name="act", trigger_conditions=[InputEvent.EVENT_TYPE], func=WeirdFunction()
    )

    with pytest.raises(TypeError, match="Unsupported function descriptor"):
        AgentPlan.from_agent(agent, AgentConfiguration())


# ── Plan JSON shape (Python side) ───────────────────────────────────────


def _java_action_plan() -> AgentPlan:
    """Minimal plan with a single cross-language Java-target action."""
    agent = Agent()
    agent.add_action(
        name="handle",
        trigger_conditions=[InputEvent.EVENT_TYPE],
        func=_make_java_function_descriptor(),
    )
    return AgentPlan.from_agent(agent, AgentConfiguration())


def _python_action_plan() -> AgentPlan:
    """Minimal plan with a single same-language Python-target action."""
    agent = Agent()
    agent.add_action(
        name="handle",
        trigger_conditions=[InputEvent.EVENT_TYPE],
        func=_make_python_function_descriptor(),
    )
    return AgentPlan.from_agent(agent, AgentConfiguration())


def test_python_plan_with_java_action_has_expected_exec_shape() -> None:
    """Pin the wire shape of a Java-target action's ``exec`` block."""
    plan = _java_action_plan()
    parsed = json.loads(_plan_dump_json(plan))
    exec_block = parsed["actions"]["handle"]["exec"]

    assert exec_block == {
        "func_type": "JavaFunction",
        "qualname": "com.example.Handlers",
        "method_name": "handle",
        "parameter_types": [
            "org.apache.flink.agents.api.Event",
            "org.apache.flink.agents.api.context.RunnerContext",
        ],
    }


def test_python_plan_with_python_action_has_expected_exec_shape() -> None:
    """Pin the wire shape of a Python-target action's ``exec`` block."""
    plan = _python_action_plan()
    parsed = json.loads(_plan_dump_json(plan))
    exec_block = parsed["actions"]["handle"]["exec"]

    assert exec_block == {
        "func_type": "PythonFunction",
        "module": _dummy_action.__module__,
        "qualname": _dummy_action.__qualname__,
    }


# ── Plan JSON round-trip (Python side) ──────────────────────────────────


def test_python_plan_with_java_action_round_trips_through_json() -> None:
    plan = _java_action_plan()
    json_str = _plan_dump_json(plan)
    restored = AgentPlan.model_validate_json(json_str)

    action = restored.actions["handle"]
    assert isinstance(action.exec, PlanJavaFunction)
    assert action.exec.qualname == "com.example.Handlers"
    assert action.exec.method_name == "handle"
    assert list(action.exec.parameter_types) == [
        "org.apache.flink.agents.api.Event",
        "org.apache.flink.agents.api.context.RunnerContext",
    ]


def test_python_plan_with_python_action_round_trips_through_json() -> None:
    plan = _python_action_plan()
    json_str = _plan_dump_json(plan)
    restored = AgentPlan.model_validate_json(json_str)

    action = restored.actions["handle"]
    assert isinstance(action.exec, PlanPythonFunction)
    assert action.exec.module == _dummy_action.__module__
    assert action.exec.qualname == _dummy_action.__qualname__


# ── Cross-language snapshot (Python writes / Java reads) ────────────────


def test_regenerate_python_plan_with_java_action_snapshot() -> None:
    if not _regenerate_enabled():
        pytest.skip("Set REGENERATE_SNAPSHOTS=1 to refresh.")

    target = _SNAPSHOT_DIR / "python" / "agent_plan_with_java_action.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(_plan_dump_json(_java_action_plan()) + "\n")


def test_python_plan_with_java_action_snapshot_is_stable() -> None:
    snapshot_path = _SNAPSHOT_DIR / "python" / "agent_plan_with_java_action.json"
    assert snapshot_path.exists(), (
        f"Python plan snapshot missing from {snapshot_path}. "
        f"Regenerate with REGENERATE_SNAPSHOTS=1 and commit alongside the test."
    )

    actual = json.loads(_plan_dump_json(_java_action_plan()))
    expected = json.loads(snapshot_path.read_text())
    assert actual == expected, (
        "Python plan-with-Java-action JSON drifted from committed snapshot."
    )


def test_python_can_deserialize_java_plan_with_python_action() -> None:
    """Java produces a plan referencing a Python action; Python must read it back."""
    snapshot = _SNAPSHOT_DIR / "java" / "agent_plan_with_python_action.json"
    assert snapshot.exists(), (
        f"Java plan snapshot missing from {snapshot}. "
        f"Regenerate the Java side with -Dregenerate.snapshots=true "
        f"and commit alongside this test."
    )

    json_str = snapshot.read_text()
    restored = AgentPlan.model_validate_json(json_str)

    action = restored.actions["handle"]
    assert isinstance(action.exec, PlanPythonFunction), (
        f"Expected plan PythonFunction, got {type(action.exec).__name__}"
    )
    assert action.exec.module == _dummy_action.__module__
    assert action.exec.qualname == _dummy_action.__qualname__


def test_python_plan_with_java_action_matches_runtime_operator_wire_shape() -> None:
    handler_qualname = (
        "org.apache.flink.agents.runtime.operator."
        "CrossLanguageActionRuntimeTest$Handlers"
    )
    expected_parameter_types = [
        "org.apache.flink.agents.api.Event",
        "org.apache.flink.agents.api.context.RunnerContext",
    ]

    agent = Agent()
    agent.add_action(
        name="handle",
        trigger_conditions=[InputEvent.EVENT_TYPE],
        func=ApiJavaFunction(
            qualname=handler_qualname,
            method_name="handleInput",
            parameter_types=expected_parameter_types,
        ),
    )

    plan = AgentPlan.from_agent(agent, AgentConfiguration())
    emitted = json.loads(_plan_dump_json(plan))

    handle_block = emitted["actions"]["handle"]
    assert handle_block["name"] == "handle"
    assert handle_block["trigger_conditions"] == [InputEvent.EVENT_TYPE]
    assert handle_block["config"] is None
    assert handle_block["exec"] == {
        "func_type": "JavaFunction",
        "qualname": handler_qualname,
        "method_name": "handleInput",
        "parameter_types": expected_parameter_types,
    }
    assert emitted["actions_by_event"][InputEvent.EVENT_TYPE] == ["handle"]


def test_python_preserves_conf_data_types_and_event_ordering() -> None:
    json_str = json.dumps(
        {
            "actions": {
                "first": {
                    "name": "first",
                    "exec": {
                        "func_type": "PythonFunction",
                        "module": _dummy_action.__module__,
                        "qualname": _dummy_action.__qualname__,
                    },
                    "trigger_conditions": [InputEvent.EVENT_TYPE],
                    "config": None,
                },
                "second": {
                    "name": "second",
                    "exec": {
                        "func_type": "PythonFunction",
                        "module": _dummy_action.__module__,
                        "qualname": _dummy_action.__qualname__,
                    },
                    "trigger_conditions": [InputEvent.EVENT_TYPE],
                    "config": None,
                },
            },
            "actions_by_event": {InputEvent.EVENT_TYPE: ["first", "second"]},
            "resource_providers": {},
            "config": {
                "conf_data": {
                    "k_int": 1,
                    "k_float": 1.5,
                    "k_bool": True,
                    "k_str": "v1",
                }
            },
        }
    )
    restored = AgentPlan.model_validate_json(json_str)

    assert restored.config.conf_data == {
        "k_int": 1,
        "k_float": 1.5,
        "k_bool": True,
        "k_str": "v1",
    }
    assert restored.actions_by_event[InputEvent.EVENT_TYPE] == ["first", "second"]
