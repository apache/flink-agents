from typing import Callable, Tuple, Type

from flink_agents.api.event import Event


#TODO: implement Closure to support access self in action function, like llama-index
def action(*listen_events: Tuple[Type[Event], ...]) -> Callable:
    """Decorator for marking a function as a workflow action.

    Parameters
    ----------
    listen_events : list[Type[Event]]
        List of event types that this action should respond to.

    Returns:
    -------
    Callable
        Decorator function that marks the target function with event listeners.

    Raises:
    ------
    AssertionError
        If no events are provided to listen to.
    """
    assert len(listen_events) > 0

    def decorator(func: Callable) -> Callable:
        func._listen_events = listen_events
        return func

    return decorator
