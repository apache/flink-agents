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
from typing import Any, Dict, List

from pydantic import BaseModel, ConfigDict, field_serializer, model_validator

from flink_agents.api.events.event import Event
from flink_agents.api.runner_context import RunnerContext
from flink_agents.plan.function import Function, JavaFunction, PythonFunction

_CONFIG_TYPE = "__config_type__"


class Action(BaseModel):
    """Representation of an agent action with unified trigger conditions.

    This class encapsulates a named agent action that triggers on matching
    events and executes an associated function.

    Attributes:
    ----------
    name : str
        Name/identifier of the agent Action.
    exec : Function
        To be executed when the Action is triggered.
    trigger_conditions : List[str]
        Event-type name strings that will trigger this Action. Multiple
        entries combine with OR semantics.
    """

    model_config = ConfigDict(arbitrary_types_allowed=True)

    name: str
    # TODO: Raise a warning when the action has a return value, as it will be ignored.
    exec: PythonFunction | JavaFunction
    trigger_conditions: List[str]
    config: Dict[str, Any] | None = None

    @property
    def listen_event_types(self) -> List[str]:
        """Event-type names. Kept for callers that still consume the old naming;
        in this PR all entries are plain event-type names so the list is
        identical to ``trigger_conditions``. A follow-up PR introduces CEL
        expressions and overrides this to filter out non-type entries.
        """
        return self.trigger_conditions

    @field_serializer("config")
    def __serialize_config(self, config: Dict[str, Any]) -> Dict[str, Any] | None:
        if config is None:
            return config
        data = {}
        data[_CONFIG_TYPE] = "python"
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
        if config is None or _CONFIG_TYPE not in config:
            return self
        config_type = self["config"].pop(_CONFIG_TYPE)
        if config_type == "java":
            for name, value in config.items():
                if (
                    isinstance(value, dict)
                    and "@class" in value
                    and "value" in value
                ):
                    self["config"][name] = value["value"]
            return self
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
        trigger_conditions: List[str],
        config: Dict[str, Any] | None = None,
    ) -> None:
        """Action will check function signature when init."""
        super().__init__(
            name=name, exec=exec, trigger_conditions=trigger_conditions, config=config
        )
        # TODO: Update expected signature after import State and Context.
        self.exec.check_signature(Event, RunnerContext)
