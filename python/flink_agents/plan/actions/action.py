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

from pydantic import (
    BaseModel,
    ConfigDict,
    StrictStr,
    field_serializer,
    model_validator,
)

from flink_agents.api.events.event import Event
from flink_agents.api.runner_context import RunnerContext
from flink_agents.plan.function import JavaFunction, PythonFunction

_CONFIG_TYPE = "__config_type__"
# Tags a config entry that we serialized from a pydantic model, so on the way
# back we only rebuild the ones we tagged and leave plain values alone.
_PYDANTIC_MODEL_MARKER = "__pydantic_model__"


class Action(BaseModel):
    """Representation of an agent action with raw trigger conditions.

    This class encapsulates a named agent action that triggers on matching
    events and executes an associated function.

    Attributes:
    ----------
    name : str
        Name/identifier of the agent Action.
    exec : Function
        To be executed when the Action is triggered.
    trigger_conditions : List[str]
        Event-type names or Boolean condition expressions. Entries combine with
        OR semantics.
    """

    model_config = ConfigDict(arbitrary_types_allowed=True)

    name: str
    exec: PythonFunction | JavaFunction
    trigger_conditions: List[StrictStr]
    config: Dict[str, Any] | None = None

    @field_serializer("config")
    def __serialize_config(self, config: Dict[str, Any]) -> Dict[str, Any] | None:
        if config is None:
            return config
        data = {}
        data[_CONFIG_TYPE] = "python"
        for name, value in config.items():
            if isinstance(value, BaseModel):
                data[name] = {
                    _PYDANTIC_MODEL_MARKER: True,
                    "module": inspect.getmodule(value).__name__,
                    "class": value.__class__.__name__,
                    "value": value,
                }
            else:
                data[name] = value
        return data

    @model_validator(mode="before")
    def __custom_deserialize(self) -> "Action":
        config = self.get("config")
        if config is None or _CONFIG_TYPE not in config:
            return self
        config_type = self["config"].pop(_CONFIG_TYPE)
        if config_type == "java":
            for name, value in config.items():
                if isinstance(value, dict) and "@class" in value and "value" in value:
                    self["config"][name] = value["value"]
            return self
        for name, value in config.items():
            # Rebuild only entries with our marker, so a plain list or dict
            # value is not treated as a model by mistake. Do not catch errors:
            # if the class cannot be imported (e.g. missing on the worker),
            # fail here with the real cause instead of a confusing error later.
            if isinstance(value, dict) and value.get(_PYDANTIC_MODEL_MARKER):
                module = importlib.import_module(value["module"])
                clazz = getattr(module, value["class"])
                self["config"][name] = clazz.model_validate(value["value"])
            else:
                self["config"][name] = value
        return self

    def model_post_init(self, __context: Any, /) -> None:
        """Validate Python-owned structure and the action function signature."""
        if not self.trigger_conditions:
            msg = f"Action '{self.name}' must have at least one trigger condition"
            raise ValueError(msg)

        for index, raw_source in enumerate(self.trigger_conditions):
            if not raw_source.strip():
                msg = (
                    f"Invalid trigger condition #{index + 1} for action "
                    f"'{self.name}' from source \"{raw_source}\": "
                    "Trigger condition must be non-blank"
                )
                raise ValueError(msg)

        # TODO: Update expected signature after import State and Context.
        self.exec.check_signature(Event, RunnerContext)
        self.exec.warn_if_returns_value(self.name)
