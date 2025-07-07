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
from typing import Callable, Tuple, Type

from flink_agents.api.event import Event


def action(*listen_events: Tuple[Type[Event], ...]) -> Callable:
    """Decorator for marking a function as an agent action.

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
    assert len(listen_events) > 0, 'action must have at least one event type to listen to'

    for event in listen_events:
        assert issubclass(event, Event), 'action must only listen to event types.'

    def decorator(func: Callable) -> Callable:
        func._listen_events = listen_events
        return func

    return decorator
