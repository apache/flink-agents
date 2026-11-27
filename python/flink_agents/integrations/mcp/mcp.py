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
import asyncio
from abc import ABC
from contextlib import AsyncExitStack, asynccontextmanager
from datetime import timedelta
from typing import Any, AsyncIterator, Dict, List, Mapping
from urllib.parse import urlparse

import httpx
from mcp.client.session import ClientSession
from mcp.client.streamable_http import streamable_http_client
from mcp.types import PromptArgument, TextContent
from pydantic import (
    ConfigDict,
    Field,
    PrivateAttr,
    field_validator,
)
from typing_extensions import override

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.duration import parse_duration
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.tools import ToolExecutionMetadataProvider, ToolResponse
from flink_agents.api.tools.tool import Tool, ToolMetadata, ToolType
from flink_agents.api.trace import (
    ToolExecutionMetadataKeys,
)
from flink_agents.integrations.mcp.auth import AnyMCPAuth, MCPAuth
from flink_agents.integrations.mcp.utils import extract_mcp_content_item


class MCPTool(Tool, ToolExecutionMetadataProvider):
    """MCP tool definition that can be called directly.

    This represents a single tool from an MCP server.
    """

    mcp_server: "MCPServer" = Field(default=None)
    mcp_server_name: str | None = None

    @classmethod
    @override
    def tool_type(cls) -> ToolType:
        return ToolType.MCP

    @override
    def call(self, *args: Any, **kwargs: Any) -> Any:
        """Call the MCP tool with the given arguments."""
        if self.mcp_server is None:
            return ToolResponse.error(
                "MCP tool call requires a reference to the MCP server",
                tool_name=self.metadata.name,
            )

        try:
            return asyncio.run(
                self.mcp_server.call_tool_async(self.metadata.name, *args, **kwargs)
            )
        except Exception as e:
            return ToolResponse.error(
                f"Error calling MCP tool '{self.metadata.name}': {e}",
                tool_name=self.metadata.name,
            )

    @override
    def get_tool_execution_metadata(
        self, parameters: Mapping[str, Any]
    ) -> Mapping[str, Any]:
        if self.mcp_server_name is None:
            return {}
        return {ToolExecutionMetadataKeys.MCP_SERVER: self.mcp_server_name}

    @override
    def close(self) -> None:
        if self.mcp_server is not None:
            try:
                self.mcp_server.close()
            finally:
                self.mcp_server = None


class MCPPrompt(Prompt):
    """MCP prompt definition that extends the base Prompt class.

    This represents a prompt managed by an MCP server.
    """

    name: str
    title: str | None
    description: str | None = None
    prompt_arguments: list[PromptArgument] = Field(default_factory=list)
    mcp_server: "MCPServer" = Field(default=None)

    def _check_arguments(self, **kwargs: str) -> Dict[str, str]:
        if self.mcp_server is None:
            msg = "MCP prompt requires a reference to the MCP server"
            raise ValueError(msg)

        arguments = {}
        for argument in self.prompt_arguments:
            if argument.required:
                if argument.name in kwargs:
                    arguments[argument.name] = kwargs[argument.name]
                else:
                    msg = f"Missing required argument {argument.name}"
                    raise RuntimeError(msg)
        return arguments

    @override
    def format_string(self, **arguments: str) -> str:
        """Get the prompt from the MCP server with optional arguments..

        Returns a text representation of the prompt.
        """
        text = "\n".join(message.text for message in self.format_messages(**arguments))
        return text

    @override
    def format_messages(
        self, role: MessageRole = MessageRole.SYSTEM, **arguments: str
    ) -> List[ChatMessage]:
        """Get the prompt from the MCP server with optional arguments.

        Returns a list of messages formatted according to the MCP server.
        """
        arguments = self._check_arguments(**arguments)
        return self.mcp_server.get_prompt(self.name, arguments)

    @override
    def close(self) -> None:
        if self.mcp_server is not None:
            try:
                self.mcp_server.close()
            finally:
                self.mcp_server = None


class MCPServer(Resource, ABC):
    """Resource representing an MCP server and exposing its tools/prompts.

    This is a logical container for MCP tools and prompts; it is not directly invokable.
    """

    model_config = ConfigDict(arbitrary_types_allowed=True)

    # HTTP connection parameters
    endpoint: str
    headers: Dict[str, Any] | None = None
    timeout: timedelta = timedelta(seconds=30)
    sse_read_timeout: timedelta = timedelta(minutes=5)
    auth: AnyMCPAuth | None = None

    session: ClientSession = Field(default=None, exclude=True)
    connection_context: AsyncExitStack = Field(default=None, exclude=True)

    # The httpx.Auth built from ``auth``, kept so that every connection of this
    # server shares it: an OAuth provider keeps its tokens between connections.
    _httpx_auth: tuple[MCPAuth, httpx.Auth] | None = PrivateAttr(default=None)

    @field_validator("timeout", "sse_read_timeout", mode="before")
    @classmethod
    def validate_timeout(cls, value: Any) -> timedelta:
        """Parse a positive MCP timeout."""
        duration = parse_duration(value)
        if duration <= timedelta(0):
            msg = "timeout must be a positive duration."
            raise ValueError(msg)
        return duration

    @field_validator("auth", mode="before")
    @classmethod
    def validate_auth(cls, value: Any) -> Any:
        """Reject auth values that are not an auth configuration.

        Raises:
            TypeError: If ``value`` is a string or an ``httpx.Auth`` instance.
                Pydantic passes a TypeError through unchanged, so the message
                never repeats the value, which may hold a credential.
        """
        if isinstance(value, str):
            msg = (
                "auth must be an auth configuration (BasicAuth, BearerTokenAuth, "
                "ApiKeyAuth or CustomAuth) or a mapping with an 'authType' key, "
                "not a string. Pickled auth values are no longer supported."
            )
            raise TypeError(msg)
        if isinstance(value, httpx.Auth):
            msg = (
                f"auth must be an auth configuration, not an httpx.Auth instance "
                f"({type(value).__name__}). Use BasicAuth, BearerTokenAuth or "
                f"ApiKeyAuth, or CustomAuth with a factory that returns the "
                f"httpx.Auth."
            )
            raise TypeError(msg)
        return value

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

    def _get_httpx_auth(self) -> httpx.Auth | None:
        """Build the httpx.Auth for ``auth`` on first use and reuse it after."""
        if self.auth is None:
            return None
        if self._httpx_auth is None or self._httpx_auth[0] is not self.auth:
            self._httpx_auth = (self.auth, self.auth.to_httpx_auth())
        return self._httpx_auth[1]

    def _create_http_client(self) -> httpx.AsyncClient:
        """Create the HTTP client for one connection."""
        return httpx.AsyncClient(
            headers=self.headers,
            timeout=httpx.Timeout(
                self.timeout.total_seconds(),
                read=self.sse_read_timeout.total_seconds(),
            ),
            auth=self._get_httpx_auth(),
            # mcp 1.24 to 1.29 follow redirects through the client, as their
            # deprecated ``streamablehttp_client`` did. mcp 1.30 follows
            # same-origin redirects itself and ignores this setting.
            follow_redirects=True,
        )

    @asynccontextmanager
    async def _get_session(self) -> AsyncIterator[ClientSession]:
        """Get or create an MCP client session using the official SDK's streamable
        HTTP transport.
        """
        if not self._is_valid_http_url():
            msg = f"Invalid HTTP endpoint: {self.endpoint}"
            raise ValueError(msg)

        http_client = self._create_http_client()
        async with (
            http_client,
            streamable_http_client(self.endpoint, http_client=http_client) as (
                read,
                write,
                _,
            ),
            # Bound every request by ``timeout``: without it a server that accepts
            # the connection but never answers would hang discovery at startup.
            ClientSession(read, write, read_timeout_seconds=self.timeout) as session,
        ):
            await session.initialize()
            yield session

    async def _cleanup_connection(self) -> None:
        """Clean up connection resources."""
        try:
            if self.connection_context is not None:
                await self.connection_context.aclose()
                self.connection_context = None
            self.session = None
        except Exception:
            pass

    async def call_tool_async(self, tool_name: str, *args: Any, **kwargs: Any) -> Any:
        """Call a tool on the MCP server asynchronously.

        Raises:
            RuntimeError: If the MCP response marks the tool result as an error.
        """
        async with self._get_session() as session:
            arguments = kwargs if kwargs else (args[0] if args else {})

            result = await session.call_tool(
                tool_name,
                arguments,
                read_timeout_seconds=self.timeout,
            )

        content = [extract_mcp_content_item(item) for item in result.content]
        if result.isError:
            msg = f"MCP tool '{tool_name}' returned an error: {content}"
            raise RuntimeError(msg)

        return content

    def list_tools(self) -> List[MCPTool]:
        """List available tool definitions from the MCP server."""
        return asyncio.run(self._list_tools_async())

    async def _list_tools_async(self) -> List[MCPTool]:
        """Async implementation of list_tools."""
        async with self._get_session() as session:
            tools_response = await session.list_tools()

            tools = []
            for tool_data in tools_response.tools or []:
                metadata = ToolMetadata(
                    name=tool_data.name,
                    description=tool_data.description or "",
                    args_schema=tool_data.inputSchema
                    or {"type": "object", "properties": {}},
                )

                tool = MCPTool(
                    metadata=metadata,
                    mcp_server=self,
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
        msg = f"Tool '{name}' not found on MCP server at {self.endpoint}"
        raise ValueError(msg)

    def get_tool_metadata(self, name: str) -> ToolMetadata:
        """Get a single tool definition metadata by name."""
        tool = self.get_tool(name)
        return tool.metadata

    def list_prompts(self) -> List[MCPPrompt]:
        """List available prompts from the MCP server."""
        return asyncio.run(self._list_prompts_async())

    async def _list_prompts_async(self) -> List[MCPPrompt]:
        """Async implementation of list_prompts."""
        async with self._get_session() as session:
            # Check if the server supports prompts capability
            capabilities = session.get_server_capabilities()
            if capabilities is None or capabilities.prompts is None:
                # Server does not support prompts
                return []

            prompts_response = await session.list_prompts()

            prompts = []
            for prompt_data in prompts_response.prompts or []:
                arguments_dict = {}
                if prompt_data.arguments:
                    for arg in prompt_data.arguments:
                        arguments_dict[arg.name] = {
                            "description": arg.description,
                            "required": getattr(arg, "required", False),
                        }

                prompt = MCPPrompt(
                    name=prompt_data.name,
                    title=prompt_data.title,
                    description=prompt_data.description,
                    prompt_arguments=prompt_data.arguments,
                    mcp_server=self,
                )
                prompts.append(prompt)

            return prompts

    def get_prompt(
        self, name: str, arguments: Dict[str, str] | None = None
    ) -> List[ChatMessage]:
        """Get a single prompt definition by name."""
        return asyncio.run(self._get_prompt_async(name, arguments))

    async def _get_prompt_async(
        self, name: str, arguments: Dict[str, str] | None = None
    ) -> List[ChatMessage]:
        """Async implementation of get_prompt."""
        async with self._get_session() as session:
            prompt = await session.get_prompt(name, arguments)
            chat_messages = []
            for message in prompt.messages:
                if isinstance(message.content, TextContent):
                    chat_messages.append(
                        ChatMessage.of(message.role, message.content.text)
                    )
                else:
                    err_msg = f"Unsupported content type: {type(message.content)}"
                    raise TypeError(err_msg)

        return chat_messages

    @override
    def close(self) -> None:
        """Close the MCP server connection and clean up resources."""
        asyncio.run(self._cleanup_connection())
