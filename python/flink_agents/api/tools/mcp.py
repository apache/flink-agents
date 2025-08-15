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

from abc import ABC
from typing import Any, Dict, List, Optional, Tuple

from pydantic import Field
from typing_extensions import override

from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.tools.tool import BaseTool, ToolMetadata, ToolType
from flink_agents.api.prompts.prompt import Prompt


class MCPToolDefinition(BaseTool, ABC):
    """MCP tool definition that can be called directly.

    This represents a single tool from an MCP server.
    """

    @classmethod
    @override
    def tool_type(cls) -> ToolType:
        return ToolType.MCP

    @classmethod
    @override
    def call(self, *args: Any, **kwargs: Any) -> Any:
        """Call the MCP tool with the given arguments."""
        raise NotImplementedError


class MCPPrompt(Prompt):
    """MCP prompt definition that extends the base Prompt class.

    This represents a prompt template from an MCP server.
    """

    name: str
    description: Optional[str] = None
    arguments: Dict[str, Any] = Field(default_factory=dict)




class MCPServer(Resource, ABC):
    """Resource representing an MCP server and exposing its tools/prompts.

    This is a logical container for MCP tools and prompts; it is not directly invokable.
    """

    endpoint: str

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:
        return ResourceType.MCP_SERVER

    # Tool listing and retrieval APIs
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

    def get_tool(self, name: str) -> MCPToolDefinition:
        """Get a single callable tool by name.

        Parameters
        ----------
        name : str
            The name of the tool to retrieve.

        Returns
        -------
        MCPToolDefinition
            A callable tool that can be invoked directly.
        """
        raise NotImplementedError

    def get_tool_definition(self, name: str) -> ToolMetadata:
        """Get a single tool definition metadata by name.

        This returns the metadata without the callable implementation.
        """
        raise NotImplementedError

    # Prompt listing and retrieval APIs
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
