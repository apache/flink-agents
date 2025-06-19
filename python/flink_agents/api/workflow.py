import importlib
from collections import deque
from typing import Any, Dict, final
from uuid import UUID

from flink_agents.api.workflow_runner import WorkflowRunner


class Workflow:
    """Base class for defining workflow logic.

    Attributes:
    ----------
    __runner : WorkflowRunner
        Internal workflow runner instance used to execute the workflow.
    """

    __runner: WorkflowRunner = None

    @final
    def run(self, context_id: UUID = 0, **kwargs: Dict[str, Any]) -> UUID:
        """Execute the workflow with optional context resumption and initialization
        parameters.

        Parameters
        ----------
        context_id : int, optional
            Reusing a previous workflow context with the given ID.
            If not set, 0 will be used for default. -1 means starting a new context.
        **kwargs : Dict[str, Any]
            All the arguments will be set to the StartEvent as attributes.

        Returns:
        -------
        UUID
            ID of the current context.

        Raises:
        ------
        RuntimeError
            If the workflow runner has not been initialized and cannot be created.
        """
        if self.__runner is None:
            self.__runner = (importlib.import_module(f"flink_agents.runtime.{kwargs['runner']}")
                             .get_workflow_runner(self))
        return self.__runner.run(context_id, **kwargs)

    @final
    def get_outputs(self, context_id: UUID) -> deque[Any]:
        """Obtain the workflow output for a specific context.

        Returns:
        -------
        Any
            The workflow output.
        """
        return self.__runner.get_outputs(context_id)
