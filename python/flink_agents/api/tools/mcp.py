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
import asyncio
from contextlib import AsyncExitStack
from datetime import timedelta
from typing import Any, Dict, List, Optional
from urllib.parse import urlparse

import httpx
from mcp.client.session import ClientSession
from mcp.client.streamable_http import streamablehttp_client
from pydantic import Field
from typing_extensions import override

from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.tools.tool import BaseTool, ToolMetadata, ToolType
from flink_agents.api.tools.utils import extract_mcp_content_item
from flink_agents.api.prompts.prompt import Prompt



class MCPTool(BaseTool):
    """MCP tool definition that can be called directly.

    This represents a single tool from an MCP server.
    """

    mcp_server: Optional["MCPServer"] = Field(default=None, exclude=True)

    @classmethod
    @override
    def tool_type(cls) -> ToolType:
        return ToolType.MCP

    @override
    def call(self, *args: Any, **kwargs: Any) -> Any:
        """Call the MCP tool with the given arguments."""
        if self.mcp_server is None:
            raise ValueError("MCP tool call requires a reference to the MCP server")

        return asyncio.run(self.mcp_server._call_tool_async(self.metadata.name, *args, **kwargs))


class MCPPrompt(Prompt):
    """MCP prompt definition that extends the base Prompt class.

    This represents a prompt template from an MCP server.
    """

    description: Optional[str] = None
    arguments: Dict[str, Any] = Field(default_factory=dict)
    mcp_server: Optional["MCPServer"] = Field(default=None, exclude=True)

    @override
    def format_string(self, **arguments: Any) -> Any:
        """Get the actual prompt content from the MCP server.

        Returns a list of messages, each containing role and content information.
        """
        if self.mcp_server is None:
            raise ValueError("MCP prompt requires a reference to the MCP server")

        return asyncio.run(self.mcp_server._get_prompt_content_async(self.name, arguments))


class MCPServer(Resource, ABC):
    """Resource representing an MCP server and exposing its tools/prompts.

    This is a logical container for MCP tools and prompts; it is not directly invokable.
    """

    model_config = {"arbitrary_types_allowed": True}

    # HTTP connection parameters
    endpoint: str
    headers: Optional[Dict[str, Any]] = None
    timeout: timedelta = timedelta(seconds=30)
    sse_read_timeout: timedelta = timedelta(seconds=60 * 5)
    auth: Optional[httpx.Auth] = None

    session: Optional[ClientSession] = Field(default=None, exclude=True)
    connection_context: Optional[AsyncExitStack] = Field(default=None, exclude=True)

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:
        return ResourceType.MCP_SERVER

    def _is_valid_http_url(self) -> bool:
        """Check if the endpoint is a valid HTTP URL."""
        try:
            parsed = urlparse(self.endpoint)
            return parsed.scheme in ("http", "https") and bool(parsed.netloc)
        except Exception:
            return False

    async def _get_session(self) -> ClientSession:
        """Get or create an MCP client session using the official SDK's streamable HTTP transport."""
        if self.session is not None:
            return self.session

        if not self._is_valid_http_url():
            raise ValueError(f"Invalid HTTP endpoint: {self.endpoint}")

        try:
            self.connection_context = AsyncExitStack()

            # Use streamable HTTP client with context management
            read_stream, write_stream, _ = await self.connection_context.enter_async_context(
                streamablehttp_client(
                    url=self.endpoint,
                    headers=self.headers,
                    timeout=self.timeout,
                    sse_read_timeout=self.sse_read_timeout,
                    auth=self.auth
                )
            )

            self.session = await self.connection_context.enter_async_context(
                ClientSession(read_stream, write_stream)
            )

            await self.session.initialize()

            return self.session

        except Exception as e:
            await self._cleanup_connection()
            raise

    async def _cleanup_connection(self) -> None:
        """Clean up connection resources."""
        try:
            if self.connection_context is not None:
                await self.connection_context.aclose()
                self.connection_context = None
            self.session = None
        except Exception:
            pass

    async def _call_tool_async(self, tool_name: str, *args: Any, **kwargs: Any) -> Any:
        """Call a tool on the MCP server asynchronously."""
        session = await self._get_session()

        arguments = kwargs if kwargs else (args[0] if args else {})

        result = await session.call_tool(tool_name, arguments)

        content = []
        for item in result.content:
            content.append(extract_mcp_content_item(item))

        return content

    def list_tools(self) -> List[MCPTool]:
        """List available tool definitions from the MCP server."""
        return asyncio.run(self._list_tools_async())

    async def _list_tools_async(self) -> List[MCPTool]:
        """Async implementation of list_tools."""
        session = await self._get_session()
        tools_response = await session.list_tools()

        tools = []
        for tool_data in tools_response.tools or []:
            metadata = ToolMetadata(
                name=tool_data.name,
                description=tool_data.description or "",
                args_schema=tool_data.inputSchema or {"type": "object", "properties": {}}
            )

            tool = MCPTool(
                metadata=metadata,
                mcp_server=self,
                timeout=self.timeout
            )
            tools.append(tool)

        return tools

    def get_tool(self, name: str) -> MCPTool:
        """Get a single callable tool by name."""
        return asyncio.run(self._get_tool_async(name))

    async def _get_tool_async(self, name: str) -> MCPTool:
        """Async implementation of get_tool."""
        tools = await self._list_tools_async()
        for tool in tools:
            if tool.metadata.name == name:
                return tool
        raise ValueError(f"Tool '{name}' not found on MCP server at {self.endpoint}")

    def get_tool_metadata(self, name: str) -> ToolMetadata:
        """Get a single tool definition metadata by name."""
        tool = self.get_tool(name)
        return tool.metadata

    def list_prompts(self) -> List[MCPPrompt]:
        """List available prompts from the MCP server."""
        return asyncio.run(self._list_prompts_async())

    async def _list_prompts_async(self) -> List[MCPPrompt]:
        """Async implementation of list_prompts."""
        session = await self._get_session()
        prompts_response = await session.list_prompts()

        prompts = []
        for prompt_data in prompts_response.prompts or []:
            arguments_dict = {}
            if prompt_data.arguments:
                for arg in prompt_data.arguments:
                    arguments_dict[arg.name] = {
                        "description": arg.description,
                        "required": getattr(arg, 'required', False)
                    }

            prompt = MCPPrompt(
                name=prompt_data.name,
                template="",  # MCP prompts don't have templates in metadata - content comes from get_prompt call
                description=prompt_data.description,
                arguments=arguments_dict,
                mcp_server=self
            )
            prompts.append(prompt)

        return prompts

    def get_prompt(self, name: str) -> MCPPrompt:
        """Get a single prompt definition by name."""
        return asyncio.run(self._get_prompt_async(name))

    async def _get_prompt_async(self, name: str) -> MCPPrompt:
        """Async implementation of get_prompt."""
        prompts, _ = await self._list_prompts_async()
        for prompt in prompts:
            if prompt.name == name:
                return prompt
        raise ValueError(f"Prompt '{name}' not found on MCP server at {self.endpoint}")

    async def _get_prompt_content_async(self, prompt_name: str, arguments: Dict[str, Any]) -> Any:
        """Get the actual content of a prompt from the MCP server."""
        session = await self._get_session()
        result = await session.get_prompt(prompt_name, arguments)

        # Extract content from the result messages
        messages = []
        for message in result.messages:
            content_item = extract_mcp_content_item(message.content)
            messages.append(content_item)

        return messages

    async def close(self) -> None:
        """Close the MCP session and clean up resources."""
        await self._cleanup_connection()

    async def __aenter__(self):
        """Async context manager entry."""
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb):
        """Async context manager exit."""
        await self.close()
        return False
