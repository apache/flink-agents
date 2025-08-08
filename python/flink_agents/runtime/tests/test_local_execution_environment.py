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
import time

import pytest

from flink_agents.api.agent import Agent
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.runner_context import RunnerContext


class Agent1(Agent):  # noqa: D101
    @action(InputEvent)
    @staticmethod
    def increment(event: Event, ctx: RunnerContext):  # noqa D102
        input = event.input
        value = input + 1
        ctx.send_event(OutputEvent(output=value))


class Agent1WithAsync(Agent):  # noqa: D101
    @action(InputEvent)
    @staticmethod
    def increment(event: Event, ctx: RunnerContext):  # noqa D102
        def my_func(value: int) -> int:
            time.sleep(1)
            return value + 1

        input = event.input
        value = yield from ctx.execute_async(my_func, input)
        ctx.send_event(OutputEvent(output=value))


class Agent2(Agent):  # noqa: D101
    @action(InputEvent)
    @staticmethod
    def decrease(event: Event, ctx: RunnerContext):  # noqa D102
        input = event.input
        value = input - 1
        ctx.send_event(OutputEvent(output=value))


def test_local_execution_environment() -> None:  # noqa: D103
    env = AgentsExecutionEnvironment.get_execution_environment()

    input_list = []
    agent = Agent1()

    output_list = env.from_list(input_list).apply(agent).to_list()

    input_list.append({"key": "bob", "value": 1})
    input_list.append({"k": "john", "v": 2})

    env.execute()

    assert output_list == [{"bob": 2}, {"john": 3}]


def test_local_execution_environment_with_async() -> None:  # noqa: D103
    env = AgentsExecutionEnvironment.get_execution_environment()

    input_list = []
    agent = Agent1WithAsync()

    output_list = env.from_list(input_list).apply(agent).to_list()

    input_list.append({"key": "bob", "value": 1})
    input_list.append({"k": "john", "v": 2})

    env.execute()

    assert output_list == [{"bob": 2}, {"john": 3}]


def test_local_execution_environment_apply_multi_agents() -> None:  # noqa: D103
    env = AgentsExecutionEnvironment.get_execution_environment()

    input_list = []
    agent1 = Agent1()
    agent2 = Agent2()

    with pytest.raises(RuntimeError):
        env.from_list(input_list).apply(agent1).apply(agent2).to_list()


def test_local_execution_environment_execute_multi_times() -> None:  # noqa: D103
    env = AgentsExecutionEnvironment.get_execution_environment()

    input_list = []
    agent = Agent1()

    env.from_list(input_list).apply(agent).to_list()

    input_list.append({"key": "bob", "value": 1})
    input_list.append({"k": "john", "v": 2})

    env.execute()
    with pytest.raises(RuntimeError):
        env.execute()


def test_local_execution_environment_call_from_list_twice() -> None:  # noqa: D103
    env = AgentsExecutionEnvironment.get_execution_environment()

    input_list = []

    env.from_list(input_list)
    with pytest.raises(RuntimeError):
        env.from_list(input_list)
