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
from typing import List

from pydantic import BaseModel, field_serializer, model_validator

from flink_agents.api.event import Event
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
    #TODO: Raise a warning when the action has a return value, as it will be ignored.
    exec: Function
    listen_event_types: List[str]

    @field_serializer('exec')
    def __serialize_exec(self, exec: Function) -> dict:
        # append meta info to help deserialize exec
        data = exec.model_dump()
        data['func_type'] = exec.__class__.__qualname__
        return data

    @model_validator(mode='before')
    def __custom_deserialize(self) -> 'Action':
        exec = self['exec']
        # restore exec from serialized json.
        if isinstance(exec, dict):
            func_type = exec['func_type']
            if func_type == 'PythonFunction':
                self['exec'] = PythonFunction(**exec)
            elif func_type == 'JavaFunction':
                self['exec'] = JavaFunction(**exec)
            else:
                err_msg = f'Unknown function type: {func_type}'
                raise NotImplementedError(err_msg)
        return self

    def __init__(
            self,
            name: str,
            exec: Function,
            listen_event_types: List[str],
    ) -> None:
        """Action will check function signature when init."""
        super().__init__(name=name, exec=exec, listen_event_types=listen_event_types)
        #TODO: Update expected signature after import State and Context.
        self.exec.check_signature(Event, RunnerContext)

