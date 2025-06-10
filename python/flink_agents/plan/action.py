from typing import List, Type

from pydantic import BaseModel
from python.flink_agents.api.event import Event
from python.flink_agents.plan.function import Function


class Action(BaseModel):
    """Representation of a workflow action with event listening and function execution.

    This class encapsulates a named workflow action that listens for specific event
    types and executes an associated function when those events occur.

    Attributes:
    ----------
    name : str
        Name/identifier of the workflow Action.
    exec : Function
        To be executed when the Action is triggered.
    listen_event_types : List[Type[Event]]
        List of event types that will trigger this Action's execution.
    """

    name: str
    exec: Function
    listen_event_types: List[Type[Event]]
