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
import logging
import uuid
from collections import deque
from typing import Any, Callable, Dict, Generator, List, Tuple

from typing_extensions import override

from flink_agents.api.agent import Agent
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.memory_object import MemoryObject
from flink_agents.api.metric_group import MetricGroup
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.runtime.agent_runner import AgentRunner
from flink_agents.runtime.local_memory_object import LocalMemoryObject

logging.basicConfig(
    level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s"
)
logger = logging.getLogger(__name__)


class LocalRunnerContext(RunnerContext):
    """Implementation of RunnerContext for local agent execution.

    Attributes:
    ----------
    __agent_plan : AgentPlan
        Internal agent plan for this context.
    __key : Any
        Unique identifier for the context, correspond to the key in flink KeyedStream.
    events : deque[Event]
        Queue of events to be processed in this context.
    action_name: str
        Name of the action being executed.
    """

    __agent_plan: AgentPlan
    __key: Any
    events: deque[Event]
    action_name: str
    _store: dict[str, Any]
    _short_term_memory: MemoryObject
    _config: AgentConfiguration

    def __init__(self, agent_plan: AgentPlan, key: Any, config: AgentConfiguration) -> None:
        """Initialize a new context with the given agent and key.

        Parameters
        ----------
        agent_plan : AgentPlan
            Agent plan used for this context.
        key : Any
            Unique context identifier, which is corresponding to the key in flink
            KeyedStream.
        """
        self.__agent_plan = agent_plan
        self.__key = key
        self.events = deque()
        self._store = {}
        self._short_term_memory = LocalMemoryObject(
            self._store, LocalMemoryObject.ROOT_KEY
        )
        self._config = config

    @property
    def key(self) -> Any:
        """Get the unique identifier for this context.

        Returns:
        -------
        Any
            The unique identifier for this context.
        """
        return self.__key

    @override
    def send_event(self, event: Event) -> None:
        """Send an event to the context's event queue and log it.

        Parameters
        ----------
        event : Event
            The event to be added to the queue.
        """
        logger.info("key: %s, send_event: %s", self.__key, event)
        self.events.append(event)

    @override
    def get_resource(self, name: str, type: ResourceType) -> Resource:
        return self.__agent_plan.get_resource(name, type)

    @property
    @override
    def action_config(self) -> Dict[str, Any]:
        """Get config of the action."""
        return self.__agent_plan.get_action_config(action_name=self.action_name)

    @override
    def get_action_config_value(self, key: str) -> Any:
        """Get config option value of the key."""
        return self.__agent_plan.get_action_config_value(
            action_name=self.action_name, key=key
        )

    @property
    @override
    def short_term_memory(self) -> MemoryObject:
        """Get the short-term memory object associated with this context.

        Returns:
        -------
        MemoryObject
            The root object of the short-term memory.
        """
        return self._short_term_memory

    @property
    @override
    def agent_metric_group(self) -> MetricGroup:
        # TODO: Support metric mechanism for local agent execution.
        err_msg = "Metric mechanism is not supported for local agent execution yet."
        raise NotImplementedError(err_msg)

    @property
    @override
    def action_metric_group(self) -> MetricGroup:
        # TODO: Support metric mechanism for local agent execution.
        err_msg = "Metric mechanism is not supported for local agent execution yet."
        raise NotImplementedError(err_msg)

    def execute_async(
        self,
        func: Callable[[Any], Any],
        *args: Tuple[Any, ...],
        **kwargs: Dict[str, Any],
    ) -> Any:
        """Asynchronously execute the provided function. Access to memory
        is prohibited within the function.
        """
        logger.warning(
            "Local runner does not support asynchronous execution; falling back to synchronous execution."
        )
        func_result = func(*args, **kwargs)
        yield
        return func_result

    @property
    @override
    def config(self) -> AgentConfiguration:
        return self._config


class LocalRunner(AgentRunner):
    """Agent runner implementation for local execution, which is
    convenient for debugging.

    Attributes:
    ----------
    __agent_plan : AgentPlan
        Internal agent plan.
    __keyed_contexts : dict[Any, LocalRunnerContext]
        Dictionary of active contexts indexed by key.
    __outputs:
        Outputs generated by agent execution.
    __config:
        Internal configration.
    """

    __agent_plan: AgentPlan
    __keyed_contexts: Dict[Any, LocalRunnerContext]
    __outputs: List[Dict[str, Any]]
    __config: AgentConfiguration

    def __init__(self, agent: Agent, config: AgentConfiguration) -> None:
        """Initialize the runner with the provided agent.

        Parameters
        ----------
        agent : Agent
            The agent class to convert and run.
        """
        self.__agent_plan = AgentPlan.from_agent(agent, config)
        self.__keyed_contexts = {}
        self.__outputs = []
        self.__config = config

    @override
    def run(self, **data: Dict[str, Any]) -> Any:
        """Execute the agent to process the given data.

        Parameters
        ----------
        **data : dict[str, Any]
            input record from upstream.

        Returns:
        -------
        key
            The key of the input that was processed.
        """
        if "key" in data:
            key = data["key"]
        elif "k" in data:
            key = data["k"]
        else:
            key = uuid.uuid4()

        if key not in self.__keyed_contexts:
            self.__keyed_contexts[key] = LocalRunnerContext(self.__agent_plan, key, self.__config)
        context = self.__keyed_contexts[key]

        if "value" in data:
            input_event = InputEvent(input=data["value"])
        elif "v" in data:
            input_event = InputEvent(input=data["v"])
        else:
            msg = "Input data must be dict has 'v' or 'value' field"
            raise RuntimeError(msg)

        context.send_event(input_event)

        while len(context.events) > 0:
            event = context.events.popleft()
            if isinstance(event, OutputEvent):
                self.__outputs.append({key: event.output})
                continue
            event_type = f"{event.__class__.__module__}.{event.__class__.__name__}"
            for action in self.__agent_plan.get_actions(event_type):
                logger.info("key: %s, performing action: %s", key, action.name)
                context.action_name = action.name
                func_result = action.exec(event, context)
                if isinstance(func_result, Generator):
                    try:
                        for _ in func_result:
                            pass
                    except Exception:
                        logger.exception("Error in async execution")
                        raise
        return key

    def get_outputs(self) -> List[Dict[str, Any]]:
        """Get the outputs generated by agent execution.

        Returns:
        -------
        List[Dict[str, Any]]
            The agent execution outputs.
        """
        return self.__outputs
