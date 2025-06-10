from typing import Dict, List, Type

from pydantic import BaseModel
from python.flink_agents.api.event import Event
from python.flink_agents.plan.action import Action


class WorkflowPlan(BaseModel):
    """Workflow plan compiled from user defined workflow.

    Attributes:
    ----------
    actions : Dict[Type[Event], List[Action]]
        Mapping of event types to the list of Actions that listen to them.
    """

    actions: Dict[Type[Event], List[Action]]

    def get_action(self, event_type: Type[Event]) -> List[Action]:
        """Get steps that listen to the specified event type.

        Parameters
        ----------
        event_type : Type[Event]
            The event type to query.

        Returns:
        -------
        list[Action]
            List of Actions that will respond to this event type.
        """
        return self.actions[event_type]
