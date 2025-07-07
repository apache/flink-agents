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
from typing import Dict, List

from pydantic import BaseModel

from flink_agents.api.agent import Agent
from flink_agents.plan.action import Action
from flink_agents.plan.function import PythonFunction


class AgentPlan(BaseModel):
    """Agent plan compiled from user defined agent.

    Attributes:
    ----------
    actions: Dict[str, Action]
        Mapping of action names to actions
    event_trigger_actions : Dict[Type[Event], str]
        Mapping of event types to the list of actions name that listen to them.
    """
    actions: Dict[str, Action]
    event_trigger_actions: Dict[str, List[str]]

    @staticmethod
    def from_agent(agent: Agent) -> "AgentPlan":
        """Build a AgentPlan from user defined agent."""
        actions = {}
        event_trigger_actions = {}
        for action in _get_actions(agent):
            assert action.name not in actions, f"Duplicate action name: {action.name}"
            actions[action.name] = action
            for event_type in action.listen_event_types:
                if event_type not in event_trigger_actions:
                    event_trigger_actions[event_type] = []
                event_trigger_actions[event_type].append(action.name)
        return AgentPlan(actions=actions, event_trigger_actions=event_trigger_actions)

    def get_actions(self, event_type: str) -> List[Action]:
        """Get actions that listen to the specified event type.

        Parameters
        ----------
        event_type : Type[Event]
            The event type to query.

        Returns:
        -------
        list[Action]
            List of Actions that will respond to this event type.
        """
        return [self.actions[name] for name in self.event_trigger_actions[event_type]]


def _get_actions(agent: Agent) -> List[Action]:
    """Extract all registered agent actions from an agent.

    Parameters
    ----------
    agent : Agent
        The agent to be analyzed.

    Returns:
    -------
    List[Action]
        List of Action defined in the agent.
    """
    actions = []
    for name, value in agent.__class__.__dict__.items():
        if isinstance(value, staticmethod) and hasattr(value, '_listen_events'):
            actions.append(Action(name=name, exec=PythonFunction.from_callable(value.__func__),
                                  listen_event_types=[f'{event_type.__module__}.{event_type.__name__}'
                                                      for event_type in value._listen_events]))
        elif callable(value) and hasattr(value, '_listen_events'):
            actions.append(Action(name=name, exec=PythonFunction.from_callable(value),
                                  listen_event_types=[f'{event_type.__module__}.{event_type.__name__}'
                                                      for event_type in value._listen_events]))
    return actions
