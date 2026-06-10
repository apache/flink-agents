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
"""Action-dispatch tests via Python's local runner."""

from typing import Any, List, Tuple

import pytest

from flink_agents.api.agents.agent import Agent
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.function import JavaFunction as ApiJavaFunction
from flink_agents.api.runner_context import RunnerContext
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.function import JavaFunction as PlanJavaFunction
from flink_agents.runtime.local_runner import LocalRunner


def echo_action(event: Event, ctx: RunnerContext) -> None:
    value = InputEvent.from_event(event).input
    ctx.send_event(OutputEvent(output=value))


def _make_java_function_descriptor() -> ApiJavaFunction:
    return ApiJavaFunction.for_action("com.example.Handlers", "handle")


def test_local_runner_dispatches_python_function_action() -> None:
    agent = Agent()
    agent.add_action(
        name="echo", events=[InputEvent.EVENT_TYPE], func=echo_action
    )

    runner = LocalRunner(agent, AgentConfiguration())
    runner.run(key="k1", value="hello")

    assert runner.get_outputs() == [{"k1": "hello"}]


def test_local_runner_dispatch_of_java_function_action_fails_without_jvm_bridge() -> None:
    agent = Agent()
    agent.add_action(
        name="handle",
        events=[InputEvent.EVENT_TYPE],
        func=_make_java_function_descriptor(),
    )

    runner = LocalRunner(agent, AgentConfiguration())
    with pytest.raises(RuntimeError, match="JVM resource adapter"):
        runner.run(key="k1", value="hello")


class _RecordingJavaAdapter:
    def __init__(self) -> None:
        self.action_calls: List[Tuple[str, str, List[str], list]] = []
        self.tool_calls: List[Tuple[str, str, List[str], dict]] = []

    def invokeJavaAction(
        self,
        qualname: str,
        method_name: str,
        parameter_types: List[str],
        args: list,
    ) -> Any:
        self.action_calls.append(
            (qualname, method_name, list(parameter_types), list(args))
        )
        return None

    def invokeJavaTool(
        self,
        qualname: str,
        method_name: str,
        parameter_types: List[str],
        kwargs: dict,
    ) -> Any:
        self.tool_calls.append(
            (qualname, method_name, list(parameter_types), dict(kwargs))
        )
        return None


def test_plan_java_function_routes_positional_to_action_dispatch() -> None:
    plan_fn = PlanJavaFunction(
        qualname="com.example.Handlers",
        method_name="handle",
        parameter_types=[
            "org.apache.flink.agents.api.Event",
            "org.apache.flink.agents.api.context.RunnerContext",
        ],
    )
    adapter = _RecordingJavaAdapter()
    plan_fn.set_java_resource_adapter(adapter)

    sentinel_event = InputEvent(input="payload")
    sentinel_ctx = object()

    plan_fn(sentinel_event, sentinel_ctx)

    assert not adapter.tool_calls
    assert len(adapter.action_calls) == 1
    qualname, method_name, parameter_types, args = adapter.action_calls[0]
    assert qualname == "com.example.Handlers"
    assert method_name == "handle"
    assert parameter_types == [
        "org.apache.flink.agents.api.Event",
        "org.apache.flink.agents.api.context.RunnerContext",
    ]
    assert args == [sentinel_event, sentinel_ctx]


def test_plan_java_function_routes_kwargs_to_tool_dispatch() -> None:
    plan_fn = PlanJavaFunction(
        qualname="com.example.Tools",
        method_name="multiply",
        parameter_types=["int", "int"],
    )
    adapter = _RecordingJavaAdapter()
    plan_fn.set_java_resource_adapter(adapter)

    plan_fn(a=2, b=3)

    assert not adapter.action_calls
    assert len(adapter.tool_calls) == 1
    _, _, _, kwargs = adapter.tool_calls[0]
    assert kwargs == {"a": 2, "b": 3}


def test_plan_java_function_rejects_mixed_positional_and_keyword_args() -> None:
    plan_fn = PlanJavaFunction(
        qualname="com.example.Handlers",
        method_name="handle",
        parameter_types=["org.apache.flink.agents.api.Event"],
    )
    adapter = _RecordingJavaAdapter()
    plan_fn.set_java_resource_adapter(adapter)

    with pytest.raises(TypeError, match="mixing positional and keyword"):
        plan_fn(InputEvent(input="x"), extra=1)

    assert not adapter.action_calls
    assert not adapter.tool_calls
