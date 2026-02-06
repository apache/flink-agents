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
import multiprocessing
import runpy
import time
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from mcp.client.auth import OAuthClientProvider, TokenStorage
from mcp.shared.auth import OAuthClientInformationFull, OAuthClientMetadata, OAuthToken
from pydantic import AnyUrl

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.integrations.mcp.mcp import MCPServer


def run_server() -> None: # noqa : D103
    runpy.run_path(f"{current_dir}/mcp_server.py")

current_dir = Path(__file__).parent
def test_mcp() -> None: # noqa : D103
    process = multiprocessing.Process(target=run_server)
    process.start()
    time.sleep(5)

    mcp_server = MCPServer(endpoint="http://127.0.0.1:8000/mcp")
    prompts = mcp_server.list_prompts()
    assert len(prompts) == 1
    prompt = prompts[0]
    assert prompt.name == "ask_sum"
    message = prompt.format_messages(role=MessageRole.SYSTEM, a="1", b="2")
    assert [ChatMessage(
            role=MessageRole.USER,
            content="Can you please calculate the sum of 1 and 2?",
        )] == message
    tools = mcp_server.list_tools()
    assert len(tools) == 1
    tool = tools[0]
    assert tool.name == "add"

    process.kill()



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
        timeout=5,
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





