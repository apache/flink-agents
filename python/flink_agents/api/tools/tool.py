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
from enum import Enum
from typing import Any, Type

from pydantic import BaseModel, field_serializer, model_validator
from typing_extensions import override

from flink_agents.api.resource import ResourceType, SerializableResource
from flink_agents.api.tools.utils import create_model_from_schema


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
    args_schema : Type[BaseModel]
        The schema of the arguments passed to the tools.
    """

    name: str
    description: str
    args_schema: Type[BaseModel]

    @field_serializer("args_schema")
    def __serialize_args_schema(self, args_schema: Type[BaseModel]) -> dict[str, Any]:
        return args_schema.model_json_schema()

    @model_validator(mode="before")
    def __custom_deserialize(self) -> "ToolMetadata":
        args_schema = self["args_schema"]
        if isinstance(args_schema, dict):
            self["args_schema"] = create_model_from_schema(
                args_schema["title"], args_schema
            )
        return self

    def __eq__(self, other: "ToolMetadata") -> bool:
        return (
            other.name == self.name
            and other.description == self.description
            and other.args_schema.model_json_schema()
            == self.args_schema.model_json_schema()
        )

    def __get_parameters_dict(self) -> dict:
        parameters = self.args_schema.model_json_schema()
        parameters = {
            k: v
            for k, v in parameters.items()
            if k in ["type", "properties", "required", "definitions", "$defs"]
        }
        return parameters

    def to_openai_tool(self, skip_length_check: bool = False) -> typing.Dict[str, Any]: # noqa:FBT001
        """To OpenAI tool."""
        if not skip_length_check and len(self.description) > 1024:
            msg = (
                "Tool description exceeds maximum length of 1024 characters. "
                "Please shorten your description or move it to the prompt."
            )
            raise ValueError(
                msg
            )
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": self.__get_parameters_dict(),
            },
        }


class BaseTool(SerializableResource, ABC):
    """Base abstract class of all kinds of tools.

    Attributes:
    ----------
    metadata : ToolMetadata
        The metadata of the tools, includes name, description and arguments schema.
    """

    metadata: ToolMetadata

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
        """
