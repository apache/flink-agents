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
from pydantic import Field, model_validator
from typing_extensions import override

from flink_agents.api.function import JavaFunction, PythonFunction
from flink_agents.api.resource import ResourceType, SerializableResource
from flink_agents.api.tools.tool_parameter_injection import (
    InjectedArg,
    normalize_injected_args,
)


class FunctionTool(SerializableResource):
    """Declarative function tool: carries a function descriptor."""

    func: PythonFunction | JavaFunction
    injected_args: dict[str, InjectedArg] = Field(default_factory=dict)

    @model_validator(mode="before")
    @classmethod
    def _normalize_injected_args(cls, data: dict) -> dict:
        if isinstance(data, dict) and "injected_args" in data:
            data = dict(data)
            data["injected_args"] = normalize_injected_args(data["injected_args"])
        return data

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""
        return ResourceType.TOOL
