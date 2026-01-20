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
from abc import ABC, abstractmethod
from typing import TYPE_CHECKING, Any, Callable, Dict

from flink_agents.api.configuration import ReadableConfiguration
from flink_agents.api.events.event import Event
from flink_agents.api.memory.long_term_memory import BaseLongTermMemory
from flink_agents.api.metric_group import MetricGroup
from flink_agents.api.resource import Resource, ResourceType

if TYPE_CHECKING:
    from flink_agents.api.memory_object import MemoryObject


class AsyncExecutionResult:
    """This class wraps an asynchronous task that will be submitted to a thread pool
    only when awaited. This ensures lazy submission and serial execution semantics.

    Note: Only `await ctx.durable_execute_async(...)` is supported. asyncio
    functions like `asyncio.gather`, `asyncio.wait`, `asyncio.create_task`,
    and `asyncio.sleep` are NOT supported because there is no asyncio event loop.
    """

    def __init__(self, executor: Any, func: Callable, args: tuple, kwargs: dict) -> None:
        """Initialize an AsyncExecutionResult.

        Parameters
        ----------
        executor : Any
            The thread pool executor to submit the task to.
        func : Callable
            The function to execute asynchronously.
        args : tuple
            Positional arguments to pass to the function.
        kwargs : dict
            Keyword arguments to pass to the function.
        """
        self._executor = executor
        self._func = func
        self._args = args
        self._kwargs = kwargs

    def __await__(self) -> Any:
        """Make this object awaitable.

        When awaited, submits the task to the thread pool and yields control
        until the task completes.

        Returns:
        -------
        Any
            The result of the function execution.
        """
        future = self._executor.submit(self._func, *self._args, **self._kwargs)
        while not future.done():
            yield
        return future.result()


class RunnerContext(ABC):
    """Abstract base class providing context for agent execution.

    This context provides access to event handling.
    """

    @abstractmethod
    def send_event(self, event: Event) -> None:
        """Send an event to the agent for processing.

        Parameters
        ----------
        event : Event
            The event to be processed by the agent system.
        """

    @abstractmethod
    def get_resource(self, name: str, type: ResourceType, metric_group: MetricGroup = None) -> Resource:
        """Get resource from context.

        Parameters
        ----------
        name : str
            The name of the resource.
        type : ResourceType
            The type of the resource.
        metric_group: MetricGroup
            The metric group used for reporting the metric. If not provided,
            will use the action metric group.
        """

    @property
    @abstractmethod
    def action_config(self) -> Dict[str, Any]:
        """Get config of the action.

        Returns:
        -------
        Dict[str, Any]
          The configuration of the action executed.
        """

    @abstractmethod
    def get_action_config_value(self, key: str) -> Any:
        """Get config option value of the action.

        Parameters
        ----------
        key: str
            The key of the config option.

        Returns:
        -------
        Any
            The config option value.
        """

    @property
    @abstractmethod
    def sensory_memory(self) -> "MemoryObject":
        """Get the sensory memory.

        Sensory memory is similar to short-term memory, but will be auto cleared
        after agent run finished. User could use it to store data that does not need
        to be shared across agent runs.

        Returns:
        -------
        MemoryObject
          The root object of the sensory memory.
        """

    @property
    @abstractmethod
    def short_term_memory(self) -> "MemoryObject":
        """Get the short-term memory.

        Returns:
        -------
        MemoryObject
          The root object of the short-term memory.
        """

    @property
    @abstractmethod
    def long_term_memory(self) -> BaseLongTermMemory:
        """Get the long-term memory.

        Returns:
        -------
        BaseLongTermMemory
          The long-term memory instance.
        """

    @property
    @abstractmethod
    def agent_metric_group(self) -> MetricGroup:
        """Get the metric group for flink agents.

        Returns:
        -------
        MetricGroup
            The metric group shared across all actions.
        """

    @property
    @abstractmethod
    def action_metric_group(self) -> MetricGroup:
        """Get the individual metric group dedicated for each action.

        Returns:
        -------
        MetricGroup
            The individual metric group specific to the current action.
        """

    @abstractmethod
    def durable_execute(
        self,
        func: Callable[[Any], Any],
        *args: Any,
        **kwargs: Any,
    ) -> Any:
        """Synchronously execute the provided function with durable execution support.
        Access to memory is prohibited within the function.

        The result of the function will be stored and returned when the same
        durable_execute call is made again during job recovery. The arguments and the
        result must be serializable.

        The function is executed synchronously in the current thread, blocking
        the operator until completion.

        The action that calls this API should be deterministic, meaning that it
        will always make the durable_execute call with the same arguments and in the
        same order during job recovery. Otherwise, the behavior is undefined.

        Usage::

            def my_action(event, ctx):
                result = ctx.durable_execute(slow_function, arg1, arg2)
                ctx.send_event(OutputEvent(output=result))

        Parameters
        ----------
        func : Callable
            The function to be executed.
        *args : Any
            Positional arguments to pass to the function.
        **kwargs : Any
            Keyword arguments to pass to the function.

        Returns:
        -------
        Any
            The result of the function.
        """

    @abstractmethod
    def durable_execute_async(
        self,
        func: Callable[[Any], Any],
        *args: Any,
        **kwargs: Any,
    ) -> "AsyncExecutionResult":
        """Asynchronously execute the provided function with durable execution support.
        Access to memory is prohibited within the function.

        The result of the function will be stored and returned when the same
        durable_execute_async call is made again during job recovery. The arguments
        and the result must be serializable.

        The action that calls this API should be deterministic, meaning that it
        will always make the durable_execute_async call with the same arguments and in
        the same order during job recovery. Otherwise, the behavior is undefined.

        Usage::

            async def my_action(event, ctx):
                result = await ctx.durable_execute_async(slow_function, arg1, arg2)
                ctx.send_event(OutputEvent(output=result))

        Note: Only `await ctx.durable_execute_async(...)` is supported.
        asyncio functions like `asyncio.gather`, `asyncio.wait`,
        `asyncio.create_task`, and `asyncio.sleep` are NOT supported.

        Parameters
        ----------
        func : Callable
            The function to be executed asynchronously.
        *args : Any
            Positional arguments to pass to the function.
        **kwargs : Any
            Keyword arguments to pass to the function.

        Returns:
        -------
        AsyncExecutionResult
            An awaitable object that yields the function result when awaited.
        """

    @property
    @abstractmethod
    def config(self) -> ReadableConfiguration:
        """Get the readable configuration for flink agents.

        Returns:
        -------
        ReadableConfiguration
            The configuration for flink agents.
        """

    @abstractmethod
    def close(self) -> None:
        """Clean up the resources."""
