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
import hashlib
import logging
import os
from concurrent.futures import ThreadPoolExecutor
from typing import Any, Callable, Dict

import cloudpickle
from typing_extensions import override

from flink_agents.api.configuration import ReadableConfiguration
from flink_agents.api.events.event import Event
from flink_agents.api.memory.long_term_memory import (
    BaseLongTermMemory,
    LongTermMemoryBackend,
    LongTermMemoryOptions,
)
from flink_agents.api.memory_object import MemoryType
from flink_agents.api.metric_group import MetricGroup
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.runner_context import AsyncExecutionResult, RunnerContext
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.runtime.flink_memory_object import FlinkMemoryObject
from flink_agents.runtime.flink_metric_group import FlinkMetricGroup
from flink_agents.runtime.memory.internal_base_long_term_memory import (
    InternalBaseLongTermMemory,
)
from flink_agents.runtime.memory.vector_store_long_term_memory import (
    VectorStoreLongTermMemory,
)
from flink_agents.runtime.python_java_utils import _build_event_log_string

logger = logging.getLogger(__name__)


class _DurableExecutionResult:
    """Wrapper that holds result and triggers recording when unwrapped."""

    def __init__(
        self,
        func: Callable,
        args: tuple,
        kwargs: dict,
        result: Any,
        record_callback: Callable,
    ) -> None:
        self.func = func
        self.args = args
        self.kwargs = kwargs
        self.result = result
        self.record_callback = record_callback
        self._recorded = False

    def get_result(self) -> Any:
        """Get the result and record completion if not already recorded."""
        if not self._recorded:
            self.record_callback(self.func, self.args, self.kwargs, self.result, None)
            self._recorded = True
        return self.result


class _DurableExecutionException(Exception):
    """Wrapper exception that holds exception info and triggers recording."""

    def __init__(
        self,
        func: Callable,
        args: tuple,
        kwargs: dict,
        result: Any,
        exception: BaseException,
        record_callback: Callable,
    ) -> None:
        super().__init__(str(exception))
        self.func = func
        self.args = args
        self.kwargs = kwargs
        self.original_exception = exception
        self.record_callback = record_callback
        self._recorded = False

    def record_and_raise(self) -> None:
        """Record completion and raise the original exception."""
        if not self._recorded:
            self.record_callback(
                self.func, self.args, self.kwargs, None, self.original_exception
            )
            self._recorded = True
        raise self.original_exception from None


class _CachedAsyncExecutionResult(AsyncExecutionResult):
    """An AsyncExecutionResult that returns a cached value immediately."""

    def __init__(self, cached_result: Any) -> None:
        # Don't call super().__init__ as we don't need executor/func/args/kwargs
        self._cached_result = cached_result

    def __await__(self) -> Any:
        """Return the cached result immediately.

        This is a generator that yields nothing and returns the cached result.
        """
        if False:
            yield  # Make this a generator function
        return self._cached_result


class _DurableAsyncExecutionResult(AsyncExecutionResult):
    """An AsyncExecutionResult that records completion after execution."""

    def __init__(
        self, executor: Any, func: Callable, args: tuple, kwargs: dict
    ) -> None:
        super().__init__(executor, func, args, kwargs)

    def __await__(self) -> Any:
        """Execute and record completion when awaited."""
        future = self._executor.submit(self._func, *self._args, **self._kwargs)
        while not future.done():
            yield
        try:
            result = future.result()
        except _DurableExecutionException as exc:
            # Record and re-raise the original exception for better diagnostics.
            exc.record_and_raise()

        # Handle the wrapped result/exception
        if isinstance(result, _DurableExecutionResult):
            return result.get_result()
        elif isinstance(result, _DurableExecutionException):
            error_message = (
                "Unexpected _DurableExecutionException returned from executor; "
                "it should have been raised via future.result()."
            )
            raise TypeError(error_message) from result.original_exception
        else:
            return result


def _compute_function_id(func: Callable) -> str:
    """Compute a stable function identifier from a callable.

    Returns module.qualname for functions/methods.
    """
    module = getattr(func, "__module__", "<unknown>")
    qualname = getattr(func, "__qualname__", getattr(func, "__name__", "<unknown>"))
    return f"{module}.{qualname}"


def _compute_args_digest(args: tuple, kwargs: dict) -> str:
    """Compute a stable digest of the serialized arguments.

    The digest is used to validate that the same arguments are passed
    during recovery as during the original execution.
    """
    try:
        serialized = cloudpickle.dumps((args, kwargs))
        return hashlib.sha256(serialized).hexdigest()[:16]
    except Exception:
        # If serialization fails, return a fallback digest
        return hashlib.sha256(str((args, kwargs)).encode()).hexdigest()[:16]


class FlinkRunnerContext(RunnerContext):
    """Providing context for agent execution in Flink Environment.

    This context allows access to event handling and provides fine-grained
    durable execution support through execute() and execute_async() methods.
    """

    __agent_plan: AgentPlan | None
    __ltm: InternalBaseLongTermMemory = None

    def __init__(
        self,
        j_runner_context: Any,
        agent_plan_json: str,
        executor: ThreadPoolExecutor,
        j_resource_adapter: Any,
    ) -> None:
        """Initialize a flink runner context with the given java runner context.

        Parameters
        ----------
        j_runner_context : Any
            Java runner context used to synchronize data between Python and Java.
        """
        self._j_runner_context = j_runner_context
        self.__agent_plan = AgentPlan.model_validate_json(agent_plan_json)
        self.__agent_plan.set_java_resource_adapter(j_resource_adapter)
        self.executor = executor

    def set_long_term_memory(self, ltm: InternalBaseLongTermMemory) -> None:
        """Set long term memory instance to this context.

        Parameters
        ----------
        ltm : BaseLongTermMemory
            The long term memory to keep.
        """
        self.__ltm = ltm

    @override
    def send_event(self, event: Event) -> None:
        """Send an event to the agent for processing.

        Parameters
        ----------
        event : Event
            The event to be processed by the agent system.
        """
        class_path = f"{event.__class__.__module__}.{event.__class__.__qualname__}"
        event_bytes = cloudpickle.dumps(event)
        event_json_str = _build_event_log_string(event, class_path)
        try:
            self._j_runner_context.sendEvent(class_path, event_bytes, event_json_str)
        except Exception as e:
            err_msg = "Failed to send event " + class_path + " to runner context"
            raise RuntimeError(err_msg) from e

    @override
    def get_resource(self, name: str, type: ResourceType, metric_group: MetricGroup = None) -> Resource:
        resource = self.__agent_plan.get_resource(name, type)
        # Bind metric group to the resource
        resource.set_metric_group(metric_group or self.action_metric_group)
        return resource

    @property
    @override
    def action_config(self) -> Dict[str, Any]:
        """Get config of the action."""
        return self.__agent_plan.get_action_config(
            self._j_runner_context.getActionName()
        )

    @override
    def get_action_config_value(self, key: str) -> Any:
        """Get config of the action."""
        return self.__agent_plan.get_action_config_value(
            action_name=self._j_runner_context.getActionName(), key=key
        )

    @property
    @override
    def sensory_memory(self) -> FlinkMemoryObject:
        """Get the sensory memory object associated with this context.

        Returns:
        -------
        MemoryObject
            The sensory memory object that can be used to access and modify
            temporary state data.
        """
        try:
            return FlinkMemoryObject(
                MemoryType.SENSORY, self._j_runner_context.getSensoryMemory()
            )
        except Exception as e:
            err_msg = "Failed to get sensory memory of runner context"
            raise RuntimeError(err_msg) from e

    @property
    @override
    def short_term_memory(self) -> FlinkMemoryObject:
        """Get the short-term memory object associated with this context.

        Returns:
        -------
        MemoryObject
            The short-term memory object that can be used to access and modify
            temporary state data.
        """
        try:
            return FlinkMemoryObject(
                MemoryType.SHORT_TERM, self._j_runner_context.getShortTermMemory()
            )
        except Exception as e:
            err_msg = "Failed to get short-term memory of runner context"
            raise RuntimeError(err_msg) from e

    @property
    @override
    def long_term_memory(self) -> BaseLongTermMemory:
        return self.__ltm

    @property
    @override
    def agent_metric_group(self) -> FlinkMetricGroup:
        """Get the metric group for flink agents.

        Returns:
        -------
        FlinkMetricGroup
            The metric group shared across all actions.
        """
        return FlinkMetricGroup(self._j_runner_context.getAgentMetricGroup())

    @property
    @override
    def action_metric_group(self) -> FlinkMetricGroup:
        """Get the individual metric group dedicated for each action.

        Returns:
        -------
        FlinkMetricGroup
            The individual metric group specific to the current action.
        """
        return FlinkMetricGroup(self._j_runner_context.getActionMetricGroup())

    def _try_get_cached_result(
        self, func: Callable, args: tuple, kwargs: dict
    ) -> tuple[bool, Any]:
        """Try to get a cached result from a previous execution.

        Returns:
        -------
        tuple[bool, Any]
            A tuple of (is_hit, result_or_exception). If is_hit is True,
            the second element is the cached result or an exception to re-raise.
        """
        function_id = _compute_function_id(func)
        args_digest = _compute_args_digest(args, kwargs)

        cached_exception: BaseException | None = None
        try:
            cached = self._j_runner_context.matchNextOrClearSubsequentCallResult(
                function_id, args_digest
            )
            if cached is not None:
                is_hit, result_payload, exception_payload = cached
                if is_hit:
                    if exception_payload is not None:
                        # Store cached exception to re-raise outside try block
                        cached_exception = cloudpickle.loads(bytes(exception_payload))
                    elif result_payload is not None:
                        return True, cloudpickle.loads(bytes(result_payload))
                    else:
                        return True, None
        except Exception as e:
            # If Java method doesn't exist (not supported), fall through to execute
            if "matchNextOrClearSubsequentCallResult" in str(e):
                logger.debug("Durable execution not supported, executing directly")
            else:
                raise

        # Re-raise cached exception outside try block
        if cached_exception is not None:
            raise cached_exception

        return False, None

    def _record_call_completion(
        self,
        func: Callable,
        args: tuple,
        kwargs: dict,
        result: Any,
        exception: BaseException | None,
    ) -> None:
        """Record the completion of a call for durable execution.

        Parameters
        ----------
        func : Callable
            The function that was executed.
        args : tuple
            Positional arguments passed to the function.
        kwargs : dict
            Keyword arguments passed to the function.
        result : Any
            The result of the function (None if exception occurred).
        exception : BaseException | None
            The exception raised by the function (None if successful).
        """
        function_id = _compute_function_id(func)
        args_digest = _compute_args_digest(args, kwargs)

        try:
            result_payload = None if exception else cloudpickle.dumps(result)
            exception_payload = cloudpickle.dumps(exception) if exception else None

            self._j_runner_context.recordCallCompletion(
                function_id, args_digest, result_payload, exception_payload
            )
        except Exception as e:
            # If Java method doesn't exist, silently ignore
            if "recordCallCompletion" not in str(e):
                logger.warning("Failed to record call completion: %s", e)

    @override
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
        """
        # Try to get cached result for recovery
        is_hit, cached_result = self._try_get_cached_result(func, args, kwargs)
        if is_hit:
            return cached_result

        # Execute the function
        exception = None
        result = None
        try:
            result = func(*args, **kwargs)
        except BaseException as e:
            exception = e

        # Record the completion
        self._record_call_completion(func, args, kwargs, result, exception)

        if exception:
            raise exception
        return result

    @override
    def durable_execute_async(
        self,
        func: Callable[[Any], Any],
        *args: Any,
        **kwargs: Any,
    ) -> AsyncExecutionResult:
        """Asynchronously execute the provided function with durable execution support.
        Access to memory is prohibited within the function.

        The result of the function will be stored and returned when the same
        durable_execute_async call is made again during job recovery. The arguments
        and the result must be serializable.

        Important: The result is only recorded when the returned AsyncExecutionResult
        is awaited. Fire-and-forget calls (not awaiting the result) will NOT be
        recorded and cannot be recovered.
        """
        # Try to get cached result for recovery
        is_hit, cached_result = self._try_get_cached_result(func, args, kwargs)
        if is_hit:
            # Return a pre-completed AsyncExecutionResult
            return _CachedAsyncExecutionResult(cached_result)

        # Create a wrapper function that records completion
        def wrapped_func(*a: Any, **kw: Any) -> Any:
            exception = None
            result = None
            try:
                result = func(*a, **kw)
            except BaseException as e:
                exception = e

            # Note: This runs in a thread pool, so we need to be careful
            # The actual recording will happen when the result is awaited
            if exception:
                raise _DurableExecutionException(
                    func, args, kwargs, result, exception, self._record_call_completion
                )
            return _DurableExecutionResult(
                func, args, kwargs, result, self._record_call_completion
            )

        return _DurableAsyncExecutionResult(self.executor, wrapped_func, args, kwargs)

    @property
    @override
    def config(self) -> ReadableConfiguration:
        """Get the readable configuration for flink agents.

        Returns:
        -------
        ReadableConfiguration
            The configuration for flink agents.
        """
        return self.__agent_plan.config

    @override
    def close(self) -> None:
        if self.long_term_memory is not None:
            self.long_term_memory.close()

        if self.__agent_plan is not None:
            try:
                self.__agent_plan.close()
            finally:
                self.__agent_plan = None


def create_flink_runner_context(
    j_runner_context: Any,
    agent_plan_json: str,
    executor: ThreadPoolExecutor,
    j_resource_adapter: Any,
    job_identifier: str,
) -> FlinkRunnerContext:
    """Used to create a FlinkRunnerContext Python object in Pemja environment."""
    ctx = FlinkRunnerContext(
        j_runner_context, agent_plan_json, executor, j_resource_adapter
    )

    backend = ctx.config.get(LongTermMemoryOptions.BACKEND)
    # use external vector store based long term memory
    if backend == LongTermMemoryBackend.EXTERNAL_VECTOR_STORE:
        vector_store_name = ctx.config.get(
            LongTermMemoryOptions.EXTERNAL_VECTOR_STORE_NAME
        )
        ctx.set_long_term_memory(
            VectorStoreLongTermMemory(
                ctx=ctx,
                vector_store=vector_store_name,
                job_id=job_identifier,
            )
        )

    return ctx


def flink_runner_context_switch_action_context(
    ctx: FlinkRunnerContext,
    key: int,
) -> None:
    """Switch the context of the flink runner context.

    The ctx is reused across keyed partitions, the context related to
    specific key should be switched when process new action.
    """
    if ctx.long_term_memory is not None:
        ctx.long_term_memory.switch_context(str(key))

def close_flink_runner_context(
    ctx: FlinkRunnerContext,
) -> None:
    """Clean up the resources kept by the flink runner context."""
    ctx.close()


def create_async_thread_pool(max_workers: int | None) -> ThreadPoolExecutor:
    """Used to create a thread pool to execute asynchronous
    code block in action.
    """
    logging.info(f"Initialize fixed thread pool for async task with {max_workers} threads")
    return ThreadPoolExecutor(max_workers=max_workers or os.cpu_count() * 2)


def close_async_thread_pool(executor: ThreadPoolExecutor) -> None:
    """Used to close the thread pool."""
    executor.shutdown(cancel_futures=True)
