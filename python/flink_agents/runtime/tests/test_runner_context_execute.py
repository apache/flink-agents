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
"""Tests for RunnerContext durable_execute() method."""

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.runner_context import RunnerContext


def slow_computation(x: int, y: int) -> int:
    """A sample function that simulates slow computation."""
    return x + y


def multiply(x: int, y: int) -> int:
    """A sample function that multiplies two numbers."""
    return x * y


def raise_exception(msg: str) -> None:
    """A sample function that raises an exception."""
    raise ValueError(msg)


class AgentWithDurableExecute(Agent):
    """Agent that uses synchronous durable_execute() method."""

    @action(InputEvent)
    @staticmethod
    def process(event: Event, ctx: RunnerContext) -> None:
        """Process an event using durable_execute()."""
        input_val = event.input
        # Use synchronous durable execute
        result = ctx.durable_execute(slow_computation, input_val, 10)
        ctx.send_event(OutputEvent(output=result))


class AgentWithMultipleDurableExecute(Agent):
    """Agent that makes multiple durable_execute() calls."""

    @action(InputEvent)
    @staticmethod
    def process(event: Event, ctx: RunnerContext) -> None:
        """Process an event with multiple durable_execute() calls."""
        input_val = event.input
        result1 = ctx.durable_execute(slow_computation, input_val, 5)
        result2 = ctx.durable_execute(multiply, result1, 2)
        ctx.send_event(OutputEvent(output=result2))


class AgentWithDurableExecuteAndAsync(Agent):
    """Agent that uses both durable_execute() and durable_execute_async()."""

    @action(InputEvent)
    @staticmethod
    async def process(event: Event, ctx: RunnerContext) -> None:
        """Process an event using both durable_execute() and durable_execute_async()."""
        input_val = event.input
        # Use synchronous durable execute
        sync_result = ctx.durable_execute(slow_computation, input_val, 5)
        # Use async durable execute
        async_result = await ctx.durable_execute_async(multiply, sync_result, 3)
        ctx.send_event(OutputEvent(output=async_result))


class AgentWithDurableExecuteException(Agent):
    """Agent that uses durable_execute() with a function that raises an exception."""

    @action(InputEvent)
    @staticmethod
    def process(event: Event, ctx: RunnerContext) -> None:
        """Process an event where durable_execute() raises an exception."""
        input_val = event.input
        try:
            ctx.durable_execute(raise_exception, f"Test error: {input_val}")
        except ValueError as e:
            ctx.send_event(OutputEvent(output=f"Caught: {e}"))


class AgentWithKwargs(Agent):
    """Agent that uses durable_execute() with keyword arguments."""

    @action(InputEvent)
    @staticmethod
    def process(event: Event, ctx: RunnerContext) -> None:
        """Process an event using durable_execute() with kwargs."""
        input_val = event.input
        result = ctx.durable_execute(slow_computation, x=input_val, y=20)
        ctx.send_event(OutputEvent(output=result))


def test_durable_execute_basic() -> None:
    """Test basic synchronous durable_execute() functionality."""
    env = AgentsExecutionEnvironment.get_execution_environment()

    input_list = []
    agent = AgentWithDurableExecute()

    output_list = env.from_list(input_list).apply(agent).to_list()

    input_list.append({"key": "alice", "value": 5})
    input_list.append({"key": "bob", "value": 15})

    env.execute()

    assert output_list == [{"alice": 15}, {"bob": 25}]


def test_durable_execute_multiple_calls() -> None:
    """Test multiple durable_execute() calls in a single action."""
    env = AgentsExecutionEnvironment.get_execution_environment()

    input_list = []
    agent = AgentWithMultipleDurableExecute()

    output_list = env.from_list(input_list).apply(agent).to_list()

    input_list.append({"key": "alice", "value": 10})

    env.execute()

    # (10 + 5) * 2 = 30
    assert output_list == [{"alice": 30}]


def test_durable_execute_with_async() -> None:
    """Test durable_execute() and durable_execute_async() in the same action."""
    env = AgentsExecutionEnvironment.get_execution_environment()

    input_list = []
    agent = AgentWithDurableExecuteAndAsync()

    output_list = env.from_list(input_list).apply(agent).to_list()

    input_list.append({"key": "alice", "value": 7})

    env.execute()

    # (7 + 5) * 3 = 36
    assert output_list == [{"alice": 36}]


def test_durable_execute_exception_handling() -> None:
    """Test that exceptions from durable_execute() can be caught."""
    env = AgentsExecutionEnvironment.get_execution_environment()

    input_list = []
    agent = AgentWithDurableExecuteException()

    output_list = env.from_list(input_list).apply(agent).to_list()

    input_list.append({"key": "alice", "value": "test"})

    env.execute()

    assert output_list == [{"alice": "Caught: Test error: test"}]


def test_durable_execute_with_kwargs() -> None:
    """Test durable_execute() with keyword arguments."""
    env = AgentsExecutionEnvironment.get_execution_environment()

    input_list = []
    agent = AgentWithKwargs()

    output_list = env.from_list(input_list).apply(agent).to_list()

    input_list.append({"key": "alice", "value": 5})

    env.execute()

    assert output_list == [{"alice": 25}]

