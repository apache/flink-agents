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
import importlib
import inspect
from typing import Any, Dict, List, Optional, Union

from pydantic import BaseModel, field_serializer, model_validator

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
    config: Optional[Dict[str, Any]] = None

    @field_serializer("config")
    def __serialize_config(self, config: Dict[str, Any]) -> Union[Dict[str, Any], None]:
        if config is None:
            return config
        data = {}
        data["config_type"] = "python"
        for name, value in config.items():
            if isinstance(value, BaseModel):
                data[name] = (
                    inspect.getmodule(value).__name__,
                    value.__class__.__name__,
                    value,
                )
            else:
                data[name] = value
        return data

    @model_validator(mode="before")
    def __custom_deserialize(self) -> "Action":
        config = self["config"]
        if config is not None and "config_type" in config:
            self["config"].pop("config_type")
            for name, value in config.items():
                try:
                    module = importlib.import_module(value[0])
                    clazz = getattr(module, value[1])
                    self["config"][name] = clazz.model_validate(value[2])
                except Exception:  # noqa : PERF203
                    self["config"][name] = value
        return self

    def __init__(
        self,
        name: str,
        exec: Function,
        listen_event_types: List[str],
        config: Optional[Dict[str, Any]] = None,
    ) -> None:
        """Action will check function signature when init."""
        super().__init__(
            name=name, exec=exec, listen_event_types=listen_event_types, config=config
        )
        # TODO: Update expected signature after import State and Context.
        self.exec.check_signature(Event, RunnerContext)
