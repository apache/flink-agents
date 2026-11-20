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
"""Budget duration precision and wire-format tests."""

import json
from datetime import timedelta

import pytest

from flink_agents.api.agents.react_agent import ReActAgent
from flink_agents.api.chat_message import ChatMessage
from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.events.chat_event import ChatRequestEvent
from flink_agents.api.events.event import Event, InputEvent
from flink_agents.api.react_loop_budget import BudgetMode, ReActLoopBudget
from flink_agents.api.resource import ResourceDescriptor
from flink_agents.plan.actions.action import Action
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.function import PythonFunction


@pytest.mark.parametrize(
    "value",
    [
        timedelta(milliseconds=1),
        "1 ms",
        "1000 micros",
        "1000000 ns",
        "PT0.001S",
        "1",
        1,
    ],
)
def test_millisecond_duration_round_trip(value: object) -> None:
    budget = ReActLoopBudget(max_time=value)
    assert budget.max_time == timedelta(milliseconds=1)
    assert budget.model_dump(mode="json")["max_time_ms"] == 1
    assert ReActLoopBudget.model_validate_json(budget.model_dump_json()) == budget
    config = AgentConfiguration()
    config.set(AgentExecutionOptions.LOOP_MAX_TIME, value)
    assert config.get(AgentExecutionOptions.LOOP_MAX_TIME) == timedelta(milliseconds=1)


@pytest.mark.parametrize(
    "value",
    [
        timedelta(microseconds=1),
        timedelta(microseconds=1500),
        "1 micros",
        "1500 micros",
        "1 ns",
        "0 ms",
        "-1 ms",
    ],
)
def test_non_integral_or_non_positive_milliseconds_rejected(value: object) -> None:
    with pytest.raises(ValueError, match="millisecond precision"):
        ReActLoopBudget(max_time=value)
    config = AgentConfiguration({AgentExecutionOptions.LOOP_MAX_TIME.get_key(): value})
    with pytest.raises(ValueError):
        ReActLoopBudget(max_time=config.get(AgentExecutionOptions.LOOP_MAX_TIME))


@pytest.mark.parametrize(
    "budget",
    [
        ReActLoopBudget(),
        ReActLoopBudget(
            max_turns=2, max_time="60001 ms", max_tokens=2**63 - 1, mode="hard"
        ),
    ],
)
def test_budget_remains_typed_across_event_and_action_serialization(
    budget: ReActLoopBudget,
) -> None:
    request = ChatRequestEvent("model", [ChatMessage.user("hi")], budget=budget)
    wire = request.model_dump(mode="json")["attributes"]["budget"]
    assert "max_time" not in wire
    if budget.max_time is not None:
        assert type(wire["max_time_ms"]) is int
        assert wire["max_time_ms"] == 60001
    else:
        assert "max_time_ms" not in wire
    assert request.attributes["budget"] is budget
    assert ChatRequestEvent.from_event(request).budget is budget
    restored = ChatRequestEvent.from_event(
        Event.model_validate_json(request.model_dump_json())
    )
    assert isinstance(restored.attributes["budget"], ReActLoopBudget)
    assert restored.budget == budget
    assert restored.budget is restored.budget
    agent = ReActAgent(
        chat_model=ResourceDescriptor(clazz="example.ChatModel", model="test"),
        budget=budget,
    )
    config = agent._actions["start_action"][2]
    assert config["budget"] is budget
    action = Action(
        name="start_action",
        exec=PythonFunction.from_callable(ReActAgent.start_action),
        trigger_conditions=[InputEvent.EVENT_TYPE],
        config=config,
    )
    restored_action = Action.model_validate_json(action.model_dump_json())
    assert isinstance(restored_action.config["budget"], ReActLoopBudget)
    assert restored_action.config["budget"] == budget


@pytest.mark.parametrize(
    "raw",
    [
        "not-a-dict",
        42,
        [],
        {"max_turns": 0},
        {"max_time_ms": 0},
        {"max_time_ms": -1},
        {"max_time_ms": 1.5},
        {"max_time_ms": True},
        {"max_time_ms": "1 ms"},
        {"max_time_ms": 2**63},
        {"max_tokens": 1.5},
        {"mode": "unknown"},
        {"unknown": 1},
    ],
)
def test_invalid_budget_rejected_during_event_restoration(raw: object) -> None:
    event = Event(
        type=ChatRequestEvent.EVENT_TYPE,
        attributes={
            "model": "model",
            "messages": [],
            "budget": raw,
        },
    )
    with pytest.raises(ValueError):
        ChatRequestEvent.from_event(event)


@pytest.mark.parametrize("millis", [1, 60001, 9007199254740993, None])
def test_restore_integer_milliseconds(millis: int | None) -> None:
    budget = ReActLoopBudget.model_validate({"max_time_ms": millis})
    expected = None if millis is None else timedelta(milliseconds=millis)
    assert budget.max_time == expected
    assert ReActLoopBudget.model_validate_json(budget.model_dump_json()) == budget


def test_time_names_cannot_be_combined() -> None:
    with pytest.raises(ValueError, match="Specify only one"):
        ReActLoopBudget.model_validate({"max_time": "1 s", "max_time_ms": 1})


@pytest.mark.parametrize("mode", ["soft", "SOFT", "SoFt", "hard", "HARD", "HaRd"])
def test_mode_accepts_case_insensitive_input_and_serializes_uppercase(
    mode: str,
) -> None:
    budget = ReActLoopBudget(mode=mode)
    expected = BudgetMode[mode.upper()]
    assert budget.mode is expected
    assert budget.model_dump(mode="json")["mode"] == expected.name
    assert (
        ReActLoopBudget.model_validate_json(budget.model_dump_json()).mode is expected
    )
    config = AgentConfiguration()
    config.set(AgentExecutionOptions.LOOP_BUDGET_MODE, mode)
    assert config.get(AgentExecutionOptions.LOOP_BUDGET_MODE) is expected


@pytest.mark.parametrize("mode", ["invalid", "", 1, True])
def test_invalid_mode_rejected(mode: object) -> None:
    with pytest.raises(ValueError):
        ReActLoopBudget(mode=mode)


@pytest.mark.parametrize("values", [{}, {"mode": None}])
def test_missing_or_null_mode_uses_soft_default(values: dict) -> None:
    budget = ReActLoopBudget(**values)
    assert budget.mode is BudgetMode.SOFT
    assert budget.model_dump(mode="json") == {"mode": "SOFT"}
    assert (
        ReActLoopBudget.model_validate_json(json.dumps(values)).mode is BudgetMode.SOFT
    )
    request = ChatRequestEvent("model", [ChatMessage.user("hi")], budget=budget)
    restored = ChatRequestEvent.from_event(
        Event.model_validate_json(request.model_dump_json())
    )
    assert restored.budget.mode is BudgetMode.SOFT
