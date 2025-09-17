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

import json
from datetime import timedelta
from pathlib import Path
from typing import TYPE_CHECKING
from urllib.parse import parse_qs, urlparse

import pytest
from mcp.client.auth import OAuthClientProvider, TokenStorage
from mcp.shared.auth import OAuthClientMetadata
from pydantic import AnyUrl

from flink_agents.api.tools.mcp import MCPServer
from flink_agents.api.tools.tool import ToolMetadata
from flink_agents.api.tools.utils import create_schema_from_function

if TYPE_CHECKING:
    from mcp.shared.auth import OAuthClientInformationFull, OAuthToken

current_dir = Path(__file__).parent


def foo(bar: int, baz: str) -> str:
    """Function for testing ToolMetadata.

    Parameters
    ----------
    bar : int
        The bar value.
    baz : str
        The baz value.

    Returns:
    -------
    str
        Response string value.
    """
    raise NotImplementedError


@pytest.fixture(scope="module")
def tool_metadata() -> ToolMetadata:  # noqa: D103
    return ToolMetadata(
        name="foo",
        description="Function for testing ToolMetadata",
        args_schema=create_schema_from_function(name="foo", func=foo),
    )


def test_serialize_tool_metadata(tool_metadata: ToolMetadata) -> None:  # noqa: D103
    json_value = tool_metadata.model_dump_json(serialize_as_any=True)
    with Path(f"{current_dir}/resources/tool_metadata.json").open() as f:
        expected_json = f.read()
    actual = json.loads(json_value)
    expected = json.loads(expected_json)
    assert actual == expected


def test_deserialize_tool_metadata(tool_metadata: ToolMetadata) -> None:  # noqa: D103
    with Path(f"{current_dir}/resources/tool_metadata.json").open() as f:
        expected_json = f.read()
    actual_tool_metadata = tool_metadata.model_validate_json(expected_json)
    assert actual_tool_metadata == tool_metadata


class InMemoryTokenStorage(TokenStorage):
    """Demo In-memory token storage implementation."""

    def __init__(self) -> None:  # noqa:D107
        self.tokens: OAuthToken | None = None
        self.client_info: OAuthClientInformationFull | None = None

    async def get_tokens(self) -> OAuthToken | None:
        """Get stored tokens."""
        return self.tokens

    async def set_tokens(self, tokens: OAuthToken) -> None:
        """Store tokens."""
        self.tokens = tokens

    async def get_client_info(self) -> OAuthClientInformationFull | None:
        """Get stored client information."""
        return self.client_info

    async def set_client_info(self, client_info: OAuthClientInformationFull) -> None:
        """Store client information."""
        self.client_info = client_info


async def handle_redirect(auth_url: str) -> None:  # noqa:D103
    print(f"Visit: {auth_url}")


async def handle_callback() -> tuple[str, str | None]:  # noqa:D103
    callback_url = input("Paste callback URL: ")
    params = parse_qs(urlparse(callback_url).query)
    return params["code"][0], params.get("state", [None])[0]


def test_serialize_mcp_server() -> None:  # noqa:D103
    oauth_auth = OAuthClientProvider(
        server_url="http://localhost:8001",
        client_metadata=OAuthClientMetadata(
            client_name="Example MCP Client",
            redirect_uris=[AnyUrl("http://localhost:3000/callback")],
            grant_types=["authorization_code", "refresh_token"],
            response_types=["code"],
            scope="user",
        ),
        storage=InMemoryTokenStorage(),
        redirect_handler=handle_redirect,
        callback_handler=handle_callback,
    )
    mcp_server = MCPServer(
        endpoint="http://localhost:8080",
        auth=oauth_auth,
        timeout=timedelta(seconds=5),
    )
    data = mcp_server.model_dump_json(serialize_as_any=True)

    deserialized = mcp_server.model_validate_json(data)
    assert deserialized.endpoint == mcp_server.endpoint
    assert deserialized.timeout == mcp_server.timeout
    assert deserialized.auth.context.server_url == mcp_server.auth.context.server_url
    assert (
        deserialized.auth.context.client_metadata
        == mcp_server.auth.context.client_metadata
    )
