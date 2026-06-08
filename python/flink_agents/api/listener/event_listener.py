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
import inspect
from abc import ABC, ABCMeta, abstractmethod

from flink_agents.api.event_context import EventContext
from flink_agents.api.events.event import Event


class EventListenerMeta(ABCMeta):
    """Metaclass for EventListener that provides a specialized string representation.

    This metaclass overrides the ``__str__`` method for classes that implement
    ``EventListener``. The resulting string format is
    ``module:class_path.on_event_processed``, which is specifically designed to be
    parsed by the agent's runtime (e.g., in Java via Pemja) to dynamically
    instantiate the listener class.

    The string representation handles:

    - Standard module-level classes.
    - Nested classes (using their full qualified name).
    - Classes defined in the ``__main__`` module (attempting to resolve the actual
      filename if available).
    - Validation to ensure classes are not defined in a local scope (which would
      make them inaccessible for remote/dynamic instantiation).
    """
    def __str__(cls) -> str:
        """Return a string representation of the listener class for dynamic
        instantiation.

        The format is ``module:class_path.on_event_processed``.
        Example: ``my_module:MyListener.on_event_processed`` or
        ``my_module:Outer.Inner.on_event_processed``.

        Returns:
        -------
        str
            A string identifier for the class and its handler method.

        Raises:
        ------
        ValueError
            If the class is defined within a local scope.
        """
        class_qualname = cls.__qualname__

        if "<locals>" in class_qualname:
            err_msg = (
                f"Cannot instantiate local class in '{class_qualname}'. "
                f"Classes defined within a local scope (indicated by '<locals>') "
                f"are not accessible via module attributes. Move the class to the module level."
            )
            raise ValueError(err_msg)

        module_obj = inspect.getmodule(cls)
        if module_obj is None:
            module_name = cls.__module__
        else:
            module_name = module_obj.__name__

        if module_name == "__main__":
            if hasattr(module_obj, "__file__"):
                from pathlib import Path
                file_path = Path(module_obj.__file__)
                module_name = file_path.stem

        return f"{module_name}:{class_qualname}.on_event_processed"


class EventListener(ABC, metaclass=EventListenerMeta):
    """Interface for event listeners that are notified when events are received
    for processing.

    EventListener provides a callback mechanism triggered at the beginning of
    event processing. This is useful for monitoring, metrics collection,
    debugging, or triggering side effects based on event reception.

    Event listeners are executed synchronously when an event is received,
    before any actions are triggered. Implementations should be lightweight
    and avoid blocking operations to prevent impacting agent performance.

    **Note:** Implementing classes must provide a public no-argument constructor to
    allow for dynamic instantiation by the agent.
    """

    @abstractmethod
    def on_event_processed(self, context: EventContext, event: Event) -> None:
        """Called when an event is being processed.

        This method is invoked when an event is received by the agent, before
        it is processed by any actions. The listener can inspect the event and
        its context to perform additional processing such as logging, metrics
        collection, or triggering external notifications.

        **Important:** This method should not throw exceptions as they will be
        caught and logged but will not affect the main event processing flow.
        Implementations should handle their own error recovery.

        Parameters:
        ----------
        context : EventContext
            The context associated with the event
        event : Event
            The event that is being processed
        """
