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
from typing import Callable, List, Type

from pydantic import BaseModel

from flink_agents.api.event import Event
from flink_agents.plan.function import Function


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

    def __init__(
            self,
            name: str,
            exec: Function,
            listen_event_types: List[Type[Event]],
    ) -> None:
        """Action will check function signature when init."""
        super().__init__(name=name, exec=exec, listen_event_types=listen_event_types)
        exec.check_signature(self.check_signature)

    @classmethod
    def check_signature(cls, func: Callable) -> None:
        """" Checker for action function signature."""
        #TODO: update check logic after import State and RunnerContext.
        params = inspect.signature(func).parameters
        if len(params) != 1:
            err_msg = "Action function must have exactly 1 parameter"
            raise TypeError(err_msg)
        for i, param in enumerate(params.values()):
            if i == 0:
                if not issubclass(param.annotation, Event):
                    err_msg = "Action function first parameter must be Event"
                    raise TypeError(err_msg)

