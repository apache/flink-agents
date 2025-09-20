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
from typing import TYPE_CHECKING, Any, Callable, Dict, Tuple

from flink_agents.api.configuration import ReadableConfiguration
from flink_agents.api.events.event import Event
from flink_agents.api.metric_group import MetricGroup
from flink_agents.api.resource import Resource, ResourceType

if TYPE_CHECKING:
    from flink_agents.api.memory_object import MemoryObject


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
    def get_resource(self, name: str, type: ResourceType) -> Resource:
        """Get resource from context.

        Parameters
        ----------
        name : str
            The name of the resource.
        type : ResourceType
            The type of the resource.
        """

    @abstractmethod
    def get_action_config(self) -> Dict[str, Any]:
        """Get config of the action.

        Returns:
        -------
        Dict[str, Any]
          The configuration of the action executed.
        """

    @property
    def action_config(self) -> Dict[str, Any]:
        """Get the config of the action."""
        return self.get_action_config()

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

    @abstractmethod
    def get_short_term_memory(self) -> "MemoryObject":
        """Get the short-term memory.

        Returns:
        -------
        MemoryObject
          The root object of the short-term memory.
        """

    @property
    def short_term_memory(self) -> "MemoryObject":
        """Get the short-term memory."""
        return self.get_short_term_memory()

    @abstractmethod
    def get_agent_metric_group(self) -> MetricGroup:
        """Get the metric group for flink agents.

        Returns:
        -------
        MetricGroup
            The metric group shared across all actions.
        """

    @property
    def agent_metric_group(self) -> MetricGroup:
        """Get the metric group for flink agents."""
        return self.get_agent_metric_group()

    @abstractmethod
    def get_action_metric_group(self) -> MetricGroup:
        """Get the individual metric group dedicated for each action.

        Returns:
        -------
        MetricGroup
            The individual metric group specific to the current action.
        """

    @property
    def action_metric_group(self) -> MetricGroup:
        """Get the individual metric group dedicated for each action."""
        return self.get_action_metric_group()

    @abstractmethod
    def execute_async(
        self,
        func: Callable[[Any], Any],
        *args: Tuple[Any, ...],
        **kwargs: Dict[str, Any],
    ) -> Any:
        """Asynchronously execute the provided function. Access to memory
         is prohibited within the function.

        Parameters
        ----------
        func : Callable
            The function need to be asynchronously processing.
        *args : tuple
            Positional arguments to pass to the function.
        **kwargs : dict
            Keyword arguments to pass to the function.

        Returns:
        -------
        Any
            The result of the function.
        """

    @abstractmethod
    def get_config(self) -> ReadableConfiguration:
        """Get the readable configuration for flink agents.

        Returns:
        -------
        ReadableConfiguration
            The configuration for flink agents.
        """

    @property
    def config(self) -> ReadableConfiguration:
        """Get the readable configuration for flink agents."""
        return self.get_config()
