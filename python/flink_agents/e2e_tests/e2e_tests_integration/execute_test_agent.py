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
"""Agent definitions for testing durable_execute() in Flink execution environment."""

from pydantic import BaseModel
from pyflink.datastream import KeySelector

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.runner_context import RunnerContext


class ExecuteTestData(BaseModel):
    """Data model for testing durable execute method.

    Attributes:
    ----------
    id : int
        Unique identifier of the item
    value : int
        The input value for computation
    """

    id: int
    value: int


class ExecuteTestOutput(BaseModel):
    """Output data model for durable execute test.

    Attributes:
    ----------
    id : int
        Unique identifier of the item
    result : int
        The computed result
    """

    id: int
    result: int


class ExecuteTestErrorOutput(BaseModel):
    """Output data model for durable execute exception test.

    Attributes:
    ----------
    id : int
        Unique identifier of the item
    error : str
        The error message
    """

    id: int
    error: str


class ExecuteTestKeySelector(KeySelector):
    """KeySelector for extracting key from ExecuteTestData."""

    def get_key(self, value: ExecuteTestData) -> int:
        """Extract key from ExecuteTestData."""
        return value.id


def compute_value(x: int, y: int) -> int:
    """A function that performs computation."""
    return x + y


def multiply_value(x: int, y: int) -> int:
    """A function that multiplies two values."""
    return x * y


def raise_exception(message: str) -> None:
    """A function that raises a ValueError for testing."""
    raise ValueError(message)


class ExecuteTestAgent(Agent):
    """Agent that uses synchronous durable_execute() method for testing."""

    @action(InputEvent)
    @staticmethod
    def process(event: Event, ctx: RunnerContext) -> None:
        """Process an event using durable_execute()."""
        input_data: ExecuteTestData = event.input
        # Use synchronous durable execute
        result = ctx.durable_execute(compute_value, input_data.value, 10)
        ctx.send_event(OutputEvent(output=ExecuteTestOutput(id=input_data.id, result=result)))


class ExecuteMultipleTestAgent(Agent):
    """Agent that makes multiple durable_execute() calls."""

    @action(InputEvent)
    @staticmethod
    def process(event: Event, ctx: RunnerContext) -> None:
        """Process an event with multiple durable_execute() calls."""
        input_data: ExecuteTestData = event.input
        result1 = ctx.durable_execute(compute_value, input_data.value, 5)
        result2 = ctx.durable_execute(multiply_value, result1, 2)
        ctx.send_event(OutputEvent(output=ExecuteTestOutput(id=input_data.id, result=result2)))


class ExecuteWithAsyncTestAgent(Agent):
    """Agent that uses both durable_execute() and durable_execute_async()."""

    @action(InputEvent)
    @staticmethod
    async def process(event: Event, ctx: RunnerContext) -> None:
        """Process an event using both durable_execute() and durable_execute_async()."""
        input_data: ExecuteTestData = event.input
        # Use synchronous durable execute
        sync_result = ctx.durable_execute(compute_value, input_data.value, 5)
        # Use async durable execute
        async_result = await ctx.durable_execute_async(multiply_value, sync_result, 3)
        ctx.send_event(
            OutputEvent(output=ExecuteTestOutput(id=input_data.id, result=async_result))
        )


class ExecuteWithAsyncExceptionTestAgent(Agent):
    """Agent that tests exception handling in durable_execute_async()."""

    @action(InputEvent)
    @staticmethod
    async def process(event: Event, ctx: RunnerContext) -> None:
        """Process an event and capture durable_execute_async() exceptions."""
        input_data: ExecuteTestData = event.input
        try:
            await ctx.durable_execute_async(
                raise_exception, f"Test error: {input_data.value}"
            )
        except ValueError as exc:
            ctx.send_event(
                OutputEvent(
                    output=ExecuteTestErrorOutput(id=input_data.id, error=str(exc))
                )
            )

