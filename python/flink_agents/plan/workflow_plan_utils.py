from typing import List

from flink_agents.api.workflow import Workflow
from flink_agents.plan.action import Action
from flink_agents.plan.function import PythonFunction


def get_actions(workflow: Workflow) -> List[Action]:
    """Extract all registered workflow actions from a workflow.

    Parameters
    ----------
    workflow : Workflow
        The workflow to be analyzed.

    Returns:
    -------
    List[Action]
        List of Action defined in the workflow.
    """
    return [Action(
                name=name,
                exec=PythonFunction.from_callable(value),
                listen_event_types=value._listen_events
            )
            for name, value in workflow.__class__.__dict__.items()
            if callable(value) and hasattr(value, '_listen_events')]
