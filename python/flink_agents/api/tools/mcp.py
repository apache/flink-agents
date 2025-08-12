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
from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any, Dict, List, Optional, Tuple

from pydantic import BaseModel, Field
from typing_extensions import override

from flink_agents.api.resource import ResourceType
from flink_agents.api.chat_models.chat_model import BaseChatModel
from flink_agents.api.tools.tool import BaseTool, ToolMetadata, ToolType


class MCPToolDefinition(BaseModel, ABC):
    """MCP tool definition returned by list_tools or get_tool_definition."""

    name: str
    description: str
    parameters: Dict[str, Any] = Field(default_factory=dict)

    def to_model_tool(self, chat_model: BaseChatModel) -> Dict[str, Any]:
        """Convert this MCP tool definition to a model-specific tool dict.

        Default implementation is not provided; subclasses must override.
        """
        raise NotImplementedError(
            "to_model_tool must be implemented by MCPToolDefinition subclasses"
        )


class MCPPrompt(BaseModel):
    """MCP prompt definition returned by list_prompts/get_prompt."""

    name: str
    description: Optional[str] = None
    arguments: Dict[str, Any] = Field(default_factory=dict)


class MCPServer(BaseTool, ABC):
    """Resource representing an MCP server and exposing its tools/prompts.

    This is a logical tool container; it is not directly invokable with call().
    """

    endpoint: str
    metadata: ToolMetadata = Field(  # default metadata for the server wrapper
        default_factory=lambda: ToolMetadata(
            name="mcp_server",
            description="A container for tools provided by an MCP server.",
            args_schema=BaseModel,  # not used
        )
    )

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:  # type: ignore[override]
        return ResourceType.MCP_SERVER

    @classmethod
    @override
    def tool_type(cls) -> ToolType:
        return ToolType.MCP

    def call_tool(self, name: str,*args: Any, **kwargs: Any) -> Any:  # noqa: D401
        """MCPServer isn't directly invokable; raise to signal misuse."""
        raise NotImplementedError("MCPServer does not support direct call().")

    # Listing and retrieval APIs
    def list_tools(self, cursor: Optional[str] = None) -> Tuple[List[MCPToolDefinition], Optional[str]]:
        """List available tool definitions from the MCP server and an optional cursor.

        Parameters
        ----------
        cursor : Optional[str]
            Optional pagination cursor/state.

        Returns
        -------
        Tuple[List[MCPToolDefinition], Optional[str]]
            A tuple of (tools, next_cursor). next_cursor may be None when there are no more pages.
        """
        raise NotImplementedError

    def get_tool_definition(self, name: str) -> MCPToolDefinition:
        """Get a single tool definition by name."""
        raise NotImplementedError

    def list_prompts(self, cursor: Optional[str] = None) -> Tuple[List[MCPPrompt], Optional[str]]:
        """List available prompts from the MCP server and an optional cursor.

        Parameters
        ----------
        cursor : Optional[str]
            Optional pagination cursor/state.

        Returns
        -------
        Tuple[List[MCPPrompt], Optional[str]]
            A tuple of (prompts, next_cursor). next_cursor may be None when there are no more pages.
        """
        raise NotImplementedError

    def get_prompt(self, name: str) -> MCPPrompt:
        """Get a single prompt definition by name."""
        raise NotImplementedError

    # Conversion helpers
    def to_model_tool(self, name: str, chat_model: BaseChatModel) -> Dict[str, Any]:
        """Convert a named MCP tool to a model-specific tool dict.

        Default behavior is not implemented; subclasses should override.
        """
        raise NotImplementedError(
            "MCPServer.to_model_tool must be implemented by subclasses"
        )