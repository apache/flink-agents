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
from typing import List, Union

from pydantic import BaseModel

from flink_agents.api.events.event import Event
from flink_agents.api.runner_context import RunnerContext
from flink_agents.plan.function import Function, JavaFunction, PythonFunction


class Action(BaseModel):
    """Representation of an agent action with event listening and function execution.

    This class encapsulates a named agent action that listens for specific event
    types and executes an associated function when those events occur.

    Attributes:
    ----------
    name : str
        Name/identifier of the agent Action.
    exec : Function
        To be executed when the Action is triggered.
    listen_event_types : List[str]
        List of event types that will trigger this Action's execution.
    """

    name: str
    # TODO: Raise a warning when the action has a return value, as it will be ignored.
    exec: Union[PythonFunction, JavaFunction]
    listen_event_types: List[str]

    def __init__(
        self,
        name: str,
        exec: Function,
        listen_event_types: List[str],
    ) -> None:
        """Action will check function signature when init."""
        super().__init__(name=name, exec=exec, listen_event_types=listen_event_types)
        # TODO: Update expected signature after import State and Context.
        self.exec.check_signature(Event, RunnerContext)
