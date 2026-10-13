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
import typing
from abc import ABC, abstractmethod
from copy import deepcopy
from enum import Enum
from typing import TYPE_CHECKING, Any

from pydantic import BaseModel, field_validator
from typing_extensions import override

from flink_agents.api.resource import ResourceType, SerializableResource
from flink_agents.api.tools.tool_parameter_injection import (
    InjectedArg,
    merge_injected_args,
    normalize_injected_args,
    validate_injected_arg_names,
)

if TYPE_CHECKING:
    from flink_agents.api.tools.function_tool import FunctionTool


class ToolType(Enum):
    """Tool type enum.

    Currently, only support function tool.

    Attributes:
    ----------
    MODEL_BUILT_IN : str
        The tools from the model provider, like 'web_search_preview' of OpenAI models.
    FUNCTION : str
        The python/java function defined by user.
    REMOTE_FUNCTION : str
        The remote function indicated by name.
    MCP : str
        The tools provided by MCP server.
    """

    MODEL_BUILT_IN = "model_built_in"
    FUNCTION = "function"
    REMOTE_FUNCTION = "remote_function"
    MCP = "mcp"


class ToolMetadata(BaseModel):
    """Metadata of a tools which describes what the tools does and
     how to call the tools.

    Attributes:
    ----------
    name : str
        The name of the tools.
    description : str
        The description of the tools, tells what the tools does.
    args_schema : dict
        Complete JSON Schema for model-visible arguments.
    """

    name: str
    description: str
    args_schema: dict[str, Any]

    @field_validator("args_schema", mode="before")
    @classmethod
    def _schema_data(cls, value: Any) -> dict:
        if isinstance(value, type) and issubclass(value, BaseModel):
            return value.model_json_schema()
        return deepcopy(value)

    def get_parameters_dict(self) -> dict:
        """Return the complete schema without dropping validation keywords."""
        return deepcopy(self.args_schema)


class Tool(SerializableResource, ABC):
    """Base abstract class of all kinds of tools."""

    metadata: ToolMetadata | None = None

    @staticmethod
    def from_callable(
        func: typing.Callable,
        *,
        injected_args: dict[str, InjectedArg | dict] | None = None,
    ) -> "FunctionTool":
        """Wrap a Python callable as a declarative ``FunctionTool``."""
        from flink_agents.api.function import PythonFunction
        from flink_agents.api.tools.function_tool import FunctionTool

        declared = normalize_injected_args(injected_args)
        annotated = getattr(func, "_injected_args", None)
        normalized = merge_injected_args(
            annotated, declared, tool_name=getattr(func, "__qualname__", str(func))
        )
        validate_injected_arg_names(func, normalized)
        return FunctionTool(
            func=PythonFunction.from_callable(func),
            injected_args=normalized,
        )

    @property
    def name(self) -> str:
        """Get the name of the tool."""
        return self.metadata.name

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""
        return ResourceType.TOOL

    @classmethod
    @abstractmethod
    def tool_type(cls) -> ToolType:
        """Return tool type of class."""

    @abstractmethod
    def call(
        self, *args: typing.Tuple[Any, ...], **kwargs: typing.Dict[str, Any]
    ) -> Any:
        """Call the tools with arguments.

        This is the method that should be implemented by the tools' developer.
        Ordinary return values become successful text responses. Strings are used
        directly; other values use JSON when possible, falling back to their string
        representation. Return :class:`flink_agents.api.tools.ToolResponse` to
        report an explicit tool-level failure without raising an exception.
        """
