from abc import ABC, abstractmethod
from collections import deque
from typing import Any, Dict
from uuid import UUID


class WorkflowRunner(ABC):
    """Abstract base class defining the interface for workflow execution.

    Concrete implementations must implement the `run` method to handle workflow
    execution logic specific to their use case.
    """

    @abstractmethod
    def run(self, context_id: UUID, **kwargs: Dict[str, Any]) -> UUID:
        """Execute the workflow and return its context id.

        Parameters
        ----------
        context_id : int
            Identifier for the context in which the workflow is running.
        **kwargs : dict
            Additional parameters required for workflow initialization.

        Returns:
        -------
        UUID
            Context id for the workflow execution.
        """

    @abstractmethod
    def get_outputs(self, context_id: UUID) -> deque[Any]:
        """Obtain the workflow output for a specific context.

        Returns:
        -------
        Any
            The workflow output.
        """

