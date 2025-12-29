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
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.runner_context import AsyncExecutionResult, RunnerContext
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.runtime.flink_memory_object import FlinkMemoryObject
from flink_agents.runtime.flink_metric_group import FlinkMetricGroup
from flink_agents.runtime.memory.vector_store_long_term_memory import (
    VectorStoreLongTermMemory,
)


class FlinkRunnerContext(RunnerContext):
    """Providing context for agent execution in Flink Environment.

    This context allows access to event handling.
    """

    __agent_plan: AgentPlan
    __ltm: BaseLongTermMemory = None

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

    def set_long_term_memory(self, ltm: BaseLongTermMemory) -> None:
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
        event_string = str(event)
        try:
            self._j_runner_context.sendEvent(class_path, event_bytes, event_string)
        except Exception as e:
            err_msg = "Failed to send event " + class_path + " to runner context"
            raise RuntimeError(err_msg) from e

    @override
    def get_resource(self, name: str, type: ResourceType) -> Resource:
        resource = self.__agent_plan.get_resource(name, type)
        # Bind current action's metric group to the resource
        resource.set_metric_group(self.action_metric_group)
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

    @override
    def execute_async(
        self,
        func: Callable[[Any], Any],
        *args: Any,
        **kwargs: Any,
    ) -> AsyncExecutionResult:
        """Asynchronously execute the provided function. Access to memory
        is prohibited within the function.
        """
        return AsyncExecutionResult(self.executor, func, args, kwargs)

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


def create_flink_runner_context(
    j_runner_context: Any,
    agent_plan_json: str,
    executor: ThreadPoolExecutor,
    j_resource_adapter: Any,
) -> FlinkRunnerContext:
    """Used to create a FlinkRunnerContext Python object in Pemja environment."""
    return FlinkRunnerContext(
        j_runner_context, agent_plan_json, executor, j_resource_adapter
    )


def flink_runner_context_switch_action_context(
    ctx: FlinkRunnerContext,
    job_identifier: str,
    key: int,
) -> None:
    """Switch the context of the flink runner context.

    The ctx is reused across keyed partitions, the context related to
    specific key should be switched when process new action.
    """
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
                key=str(key),
            )
        )


def create_async_thread_pool() -> ThreadPoolExecutor:
    """Used to create a thread pool to execute asynchronous
    code block in action.
    """
    return ThreadPoolExecutor(max_workers=os.cpu_count() * 2)


def close_async_thread_pool(executor: ThreadPoolExecutor) -> None:
    """Used to close the thread pool."""
    executor.shutdown()
