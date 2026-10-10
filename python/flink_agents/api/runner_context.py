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
    "AsyncFuture",
    "DurableFuture",
    "Outcome",
    "RunnerContext",
]

T = TypeVar("T")

if TYPE_CHECKING:
    from flink_agents.api.memory_object import MemoryObject


@dataclass(frozen=True)
class Outcome:
    """Result or failure for one asynchronous batch entry."""

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


class AsyncFuture(ABC, Generic[T]):
    """A deferred call resolved by the runner's scheduler, without an asyncio loop.

    Creation does not start work. Await directly or compose single-call handles
    with :meth:`RunnerContext.gather`. Repeated awaits reuse the local result or
    exception. Persistence across recovery is an additional DurableFuture contract.
    Handles belong to their creating action execution and are not thread-safe;
    do not await them in callbacks or pass them to another action. Unawaited
    handles perform no work and can be discarded when the action finishes.
    Polling, explicit cancellation, and standard asyncio composition are unsupported.
    """

    def __init__(self) -> None:
        """Initialize an unresolved async future."""
        self._done = False
        self._value: T | None = None
        self._error: BaseException | None = None

    def _is_done(self) -> bool:
        """Return whether this handle resolved in the current action execution."""
        return self._done

    def _get_completed_outcome(self) -> Outcome:
        """Return the locally cached outcome of a resolved async future."""
        if not self._done:
            msg = "Async future has not been resolved"
            raise RuntimeError(msg)
        if self._error is not None:
            return Outcome.failure(self._error)
        return Outcome.success(self._value)

    def __await__(self) -> Any:
        """Resolve once and replay the local outcome on subsequent awaits."""
        if not self._done:
            try:
                self._value = yield from self._resolve()
            except Exception as error:
                # Control-flow BaseExceptions leave the handle unresolved so a later
                # await or gather cannot mistake cancellation for a terminal outcome.
                if self._is_cancellation(error):
                    raise
                self._error = error
                self._done = True
                raise
            self._done = True

        if self._error is not None:
            raise self._error
        return self._value

    def _complete(self, outcome: Outcome) -> None:
        if self._done:
            msg = "Async future has already been resolved"
            raise RuntimeError(msg)
        self._value = outcome.value
        self._error = outcome.error
        self._done = True

    def _is_cancellation(self, error: Exception) -> bool:
        """Return whether ``error`` reports a cancelled attempt rather than the
        outcome of the call. Such an error leaves the handle unresolved.
        """
        return False

    @abstractmethod
    def _resolve(self) -> Any:
        """Resolve this future and return a generator consumed by ``__await__``."""


class DurableFuture(AsyncFuture[T]):
    """An AsyncFuture with durable result persistence and recovery replay.

    Creating the handle reserves no state. When awaited as part of a batch,
    all required durable slots are reserved before any batch callback starts.
    The runner manages call identity, persistence and optional reconciliation.
    """


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
    def execute_async(
        self, func: Callable[..., T], *args: Any, **kwargs: Any
    ) -> AsyncFuture[T]:
        """Create a deferred call without durable persistence or recovery replay.

        Await the handle directly, or await ``ctx.gather(...)`` to run calls
        concurrently. Creating handles does not start work. Repeated awaits reuse
        this handle's local result or exception, but recovery that re-executes the
        action may call the function again. No durable slot is consumed and this
        API does not require serializable arguments or results.

        Pass a synchronous callable, not an ``async def`` function. The callback
        must not access context memory, events, metrics, resource lookup, or
        execution methods. Read configuration and obtain resources in the action
        before submission; captured resources must support the intended concurrent
        access. Handles must not escape their creating action. There is no
        fire-and-forget execution or asyncio event loop.
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
    def gather(self, *futures: "AsyncFuture[Any]") -> "AsyncFuture[list[Outcome]]":
        """Compose deferred ordinary and/or durable calls into one deferred batch.

        The input order defines result order. When the returned future is awaited, the
        runtime reuses locally completed outcomes and reserves all required slots for
        unresolved durable calls only before starting any callback. Individual
        callable failures are returned as failure outcomes. Only single-call handles
        created by this context are accepted; duplicates, foreign handles and nested
        batches are rejected before work starts. Mixed batches persist only durable
        children, in their relative input order.
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
