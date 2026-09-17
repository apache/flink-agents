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
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, Callable, Dict, Generic, TypeVar

from flink_agents.api.configuration import ReadableConfiguration
from flink_agents.api.events.event import Event
from flink_agents.api.memory.long_term_memory import BaseLongTermMemory
from flink_agents.api.metric_group import MetricGroup
from flink_agents.api.resource import Resource, ResourceType

__all__ = [
    "DurableFuture",
    "Outcome",
    "RunnerContext",
]

T = TypeVar("T")

if TYPE_CHECKING:
    from flink_agents.api.memory_object import MemoryObject


@dataclass(frozen=True)
class Outcome:
    """Result or failure for one durable batch slot."""

    value: Any = None
    error: BaseException | None = None

    @classmethod
    def success(cls, value: Any) -> "Outcome":
        """Create a successful outcome."""
        return cls(value=value)

    @classmethod
    def failure(cls, error: BaseException) -> "Outcome":
        """Create a failed outcome."""
        return cls(error=error)

    def is_success(self) -> bool:
        """Return whether this outcome is successful."""
        return self.error is None

    def is_failure(self) -> bool:
        """Return whether this outcome is failed."""
        return self.error is not None


class DurableFuture(ABC, Generic[T]):
    """A deferred durable call owned and resolved by a runner context.

    Creating a durable future does not start its callable or reserve durable state.
    Await the handle directly for a single call, or compose handles with
    :meth:`RunnerContext.gather` so the runtime can reserve the whole batch before
    starting any callable. Completion is driven only by awaiting; the handle
    intentionally exposes no polling API.
    """

    def __init__(self) -> None:
        """Initialize an unresolved durable future."""
        self._done = False
        self._value: T | None = None
        self._error: BaseException | None = None

    def _is_done(self) -> bool:
        """Return whether this handle resolved in the current action execution."""
        return self._done

    def __await__(self) -> Any:
        """Resolve once and replay the local outcome on subsequent awaits."""
        if not self._done:
            try:
                self._value = yield from self._resolve()
            except BaseException as error:
                self._error = error
                self._done = True
                raise
            self._done = True

        if self._error is not None:
            raise self._error
        return self._value

    def _complete(self, outcome: Outcome) -> None:
        if self._done:
            msg = "Durable future has already been resolved"
            raise RuntimeError(msg)
        self._value = outcome.value
        self._error = outcome.error
        self._done = True

    @abstractmethod
    def _resolve(self) -> Any:
        """Resolve this future and return a generator consumed by ``__await__``."""


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
            The event to be sent.
        """

    @abstractmethod
    def get_resource(
        self, name: str, type: ResourceType, metric_group: MetricGroup = None
    ) -> Resource:
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
    def agent_metric_group(self) -> MetricGroup | None:
        """Get the metric group for flink agents.

        Returns:
        -------
        MetricGroup | None
            The metric group shared across all actions.
            May return None when not running on Flink.
        """

    @property
    @abstractmethod
    def action_metric_group(self) -> MetricGroup | None:
        """Get the individual metric group dedicated for each action.

        Returns:
        -------
        MetricGroup | None
            The individual metric group specific to the current action.
            May return None when not running on Flink.
        """

    @abstractmethod
    def durable_execute(
        self,
        func: Callable[[Any], Any],
        *args: Any,
        reconciler: Callable[[], Any] | None = None,
        durable_id: str | None = None,
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

        If `reconciler` is provided, recovery invokes it only when revisiting
        this durable call and no terminal outcome from the previous durable
        invocation has been persisted yet. The reconciler may:

        * return a result to provide the recovered successful outcome for this
          durable call; The runtime persists and replays that recovered result
        * raise an exception to provide the recovered failed outcome for this
          durable call; The runtime persists and replays that recovered
          failure

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
        reconciler : Callable[[], Any] | None
            Optional zero-argument reconciler callable used only during recovery.
            This is a reserved keyword-only parameter and is not forwarded to
            `func`.
        durable_id : str | None
            Optional stable identity keying this call's persisted state. Supply
            it when the caller owns an identity that survives failover;
            otherwise the identity is derived from the callable and its
            arguments. Reserved keyword-only parameter, not forwarded to
            `func`.
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
        reconciler: Callable[[], Any] | None = None,
        durable_id: str | None = None,
        **kwargs: Any,
    ) -> "DurableFuture[Any]":
        """Asynchronously execute the provided function with durable execution support.
        Access to memory is prohibited within the function.

        The result of the function will be stored and returned when the same
        durable_execute_async call is made again during job recovery. The arguments
        and the result must be serializable.

        The action that calls this API should be deterministic, meaning that it
        will always make the durable_execute_async call with the same arguments and in
        the same order during job recovery. Otherwise, the behavior is undefined.

        If `reconciler` is provided, recovery invokes it only when revisiting
        this durable call and no terminal outcome from the previous durable
        invocation has been persisted yet. The reconciler may:

        * return a result to provide the recovered successful outcome for this
          durable call; The runtime persists and replays that recovered result
        * raise an exception to provide the recovered failed outcome for this
          durable call; The runtime persists and replays that recovered
          failure

        Usage::

            async def my_action(event, ctx):
                result = await ctx.durable_execute_async(slow_function, arg1, arg2)
                ctx.send_event(OutputEvent(output=result))

        Note: The returned durable future can be awaited directly or composed
        with `ctx.gather(...)`. asyncio functions like `asyncio.gather`,
        `asyncio.wait`, `asyncio.create_task`, and `asyncio.sleep` are NOT
        supported.

        Parameters
        ----------
        func : Callable
            The function to be executed asynchronously.
        *args : Any
            Positional arguments to pass to the function.
        reconciler : Callable[[], Any] | None
            Optional zero-argument reconciler callable used only during recovery.
            This is a reserved keyword-only parameter and is not forwarded to
            `func`.
        durable_id : str | None
            Optional stable identity keying this call's persisted state. Supply
            it when the caller owns an identity that survives failover;
            otherwise the identity is derived from the callable and its
            arguments. Reserved keyword-only parameter, not forwarded to
            `func`.
        **kwargs : Any
            Keyword arguments to pass to the function.

        Returns:
        -------
        DurableFuture
            An awaitable object that yields the function result when awaited.
        """

    @abstractmethod
    def gather(self, *futures: "DurableFuture[Any]") -> "DurableFuture[list[Outcome]]":
        """Compose deferred durable calls into one deferred batch.

        The input order defines durable slot and result order. When the returned
        future is awaited, the runtime reserves all required slots before starting
        uncached calls. Individual callable failures are returned as failure outcomes.
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
