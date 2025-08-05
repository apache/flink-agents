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
from typing import Any, Callable, Dict, Tuple

import cloudpickle
from typing_extensions import override

from flink_agents.api.events.event import Event
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.runtime.flink_memory_object import FlinkMemoryObject
from flink_agents.runtime.flink_metric_group import FlinkMetricGroup


class FlinkRunnerContext(RunnerContext):
    """Providing context for agent execution in Flink Environment.

    This context allows access to event handling.
    """

    __agent_plan: AgentPlan

    def __init__(
        self, j_runner_context: Any, agent_plan_json: str, executor: ThreadPoolExecutor
    ) -> None:
        """Initialize a flink runner context with the given java runner context.

        Parameters
        ----------
        j_runner_context : Any
            Java runner context used to synchronize data between Python and Java.
        """
        self._j_runner_context = j_runner_context
        self.__agent_plan = AgentPlan.model_validate_json(agent_plan_json)
        self.executor = executor

    @override
    def send_event(self, event: Event) -> None:
        """Send an event to the agent for processing.

        Parameters
        ----------
        event : Event
            The event to be processed by the agent system.
        """
        class_path = f"{event.__class__.__module__}.{event.__class__.__qualname__}"
        try:
            self._j_runner_context.sendEvent(class_path, cloudpickle.dumps(event))
        except Exception as e:
            err_msg = "Failed to send event " + class_path + " to runner context"
            raise RuntimeError(err_msg) from e

    @override
    def get_resource(self, name: str, type: ResourceType) -> Resource:
        return self.__agent_plan.get_resource(name, type)

    @override
    def get_short_term_memory(self) -> FlinkMemoryObject:
        """Get the short-term memory object associated with this context.

        Returns:
        -------
        MemoryObject
            The short-term memory object that can be used to access and modify
            temporary state data.
        """
        try:
            return FlinkMemoryObject(self._j_runner_context.getShortTermMemory())
        except Exception as e:
            err_msg = "Failed to get short-term memory of runner context"
            raise RuntimeError(err_msg) from e

    @override
    def get_agent_metric_group(self) -> FlinkMetricGroup:
        """Get the metric group for flink agents.

        Returns:
        -------
        FlinkMetricGroup
            The metric group shared across all actions.
        """
        return FlinkMetricGroup(self._j_runner_context.getAgentMetricGroup())

    @override
    def get_action_metric_group(self) -> FlinkMetricGroup:
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
        *args: Tuple[Any, ...],
        **kwargs: Dict[str, Any],
    ) -> Any:
        """Asynchronously execute the provided function. Access to memory
        is prohibited within the function.
        """
        future = self.executor.submit(func, *args, **kwargs)
        while not future.done():
            # TODO: Currently, we are using a polling mechanism to check whether
            #  the future has completed. This approach should be optimized in the
            #  future by switching to a notification-based model, where the Flink
            #  operator is notified directly once the future is completed.
            yield
        return future.result()


def create_flink_runner_context(
    j_runner_context: Any, agent_plan_json: str, executor: ThreadPoolExecutor
) -> FlinkRunnerContext:
    """Used to create a FlinkRunnerContext Python object in Pemja environment."""
    return FlinkRunnerContext(j_runner_context, agent_plan_json, executor)


def create_async_thread_pool() -> ThreadPoolExecutor:
    """Used to create a thread pool to execute asynchronous
    code block in action.
    """
    return ThreadPoolExecutor(max_workers=os.cpu_count() * 2)


def close_async_thread_pool(executor: ThreadPoolExecutor) -> None:
    """Used to close the thread pool."""
    executor.shutdown()
