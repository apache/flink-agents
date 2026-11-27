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
import base64
import json
import multiprocessing
import pickle
import runpy
import time
import warnings
from contextlib import asynccontextmanager
from datetime import timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
from typing import Any, AsyncIterator, ClassVar, Dict, Iterator, List
from unittest.mock import patch
from urllib.parse import parse_qs, urlparse

import anyio
import httpx
import pytest
from mcp.client.auth import OAuthClientProvider, TokenStorage
from mcp.client.session import ClientSession
from mcp.client.streamable_http import streamable_http_client
from mcp.shared.auth import OAuthClientInformationFull, OAuthClientMetadata, OAuthToken
from mcp.types import (
    BlobResourceContents,
    CallToolResult,
    EmbeddedResource,
    ResourceLink,
    TextContent,
    TextResourceContents,
)
from pydantic import AnyUrl

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.resource import ResourceDescriptor, ResourceName, ResourceType
from flink_agents.api.tools import ToolResponse
from flink_agents.api.tools.tool import ToolMetadata
from flink_agents.api.trace import ToolExecutionMetadataKeys
from flink_agents.api.yaml.loader import build_agents
from flink_agents.integrations.mcp.auth import (
    ApiKeyAuth,
    BasicAuth,
    BearerTokenAuth,
    CustomAuth,
    MCPAuth,
)
from flink_agents.integrations.mcp.mcp import MCPServer, MCPTool
from flink_agents.plan.actions.tool_result_utils import to_tool_response
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.runtime.mcp_resource_discovery import discover_mcp_resources
from flink_agents.runtime.resource_cache import ResourceCache


def run_server() -> None:
    runpy.run_path(f"{current_dir}/mcp_server.py")


current_dir = Path(__file__).parent


def test_mcp() -> None:
    process = multiprocessing.Process(target=run_server)
    process.start()
    time.sleep(5)

    mcp_server = MCPServer(endpoint="http://127.0.0.1:8000/mcp")
    prompts = mcp_server.list_prompts()
    assert len(prompts) == 1
    prompt = prompts[0]
    assert prompt.name == "ask_sum"
    message = prompt.format_messages(role=MessageRole.SYSTEM, a="1", b="2")
    assert [
        ChatMessage.of(
            MessageRole.USER,
            "Can you please calculate the sum of 1 and 2?",
        )
    ] == message
    tools = mcp_server.list_tools()
    assert len(tools) == 1
    tool = tools[0]
    assert tool.name == "add"

    process.kill()


class InMemoryTokenStorage(TokenStorage):
    """Demo In-memory token storage implementation."""

    def __init__(self) -> None:
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


async def handle_redirect(auth_url: str) -> None:
    print(f"Visit: {auth_url}")


async def handle_callback() -> tuple[str, str | None]:
    callback_url = input("Paste callback URL: ")
    params = parse_qs(urlparse(callback_url).query)
    return params["code"][0], params.get("state", [None])[0]


def make_oauth_provider(
    server_url: str, client_name: str, redirect_uri: str
) -> OAuthClientProvider:
    """A ``CustomAuth`` factory: callbacks and storage live in code, not config."""
    return OAuthClientProvider(
        server_url=server_url,
        client_metadata=OAuthClientMetadata(
            client_name=client_name,
            redirect_uris=[AnyUrl(redirect_uri)],
            grant_types=["authorization_code", "refresh_token"],
            response_types=["code"],
            scope="user",
        ),
        storage=InMemoryTokenStorage(),
        redirect_handler=handle_redirect,
        callback_handler=handle_callback,
    )


_factory_calls: List[Dict[str, Any]] = []


class _StaticHeaderAuth(httpx.Auth):
    def __init__(self, name: str, value: str) -> None:
        self.name = name
        self.value = value

    def auth_flow(self, request: httpx.Request) -> Iterator[httpx.Request]:
        request.headers[self.name] = self.value
        yield request


def make_header_auth(name: str, value: str) -> httpx.Auth:
    _factory_calls.append({"name": name, "value": value})
    return _StaticHeaderAuth(name, value)


def make_not_an_auth() -> str:
    return "not an auth"


_TEST_MODULE = "flink_agents.integrations.mcp.tests.test_mcp"


@pytest.mark.parametrize(
    ("auth", "expected_json"),
    [
        (
            BasicAuth(username="user", password="secret"),
            {"authType": "basic", "username": "user", "password": "secret"},
        ),
        (
            BearerTokenAuth(token="tok"),
            {"authType": "bearer", "token": "tok"},
        ),
        (
            ApiKeyAuth(header_name="X-API-Key", api_key="key"),
            {"authType": "api_key", "headerName": "X-API-Key", "apiKey": "key"},
        ),
        (
            CustomAuth(factory=f"{_TEST_MODULE}:make_header_auth", arguments={"a": 1}),
            {
                "authType": "custom",
                "factory": f"{_TEST_MODULE}:make_header_auth",
                "arguments": {"a": 1},
            },
        ),
    ],
    ids=["basic", "bearer", "api_key", "custom"],
)
def test_auth_serializes_as_plain_json(
    auth: MCPAuth, expected_json: Dict[str, Any]
) -> None:
    # The first three use the JSON shape of the Java classes of the same names.
    server = MCPServer(endpoint="https://example.com/mcp", auth=auth)

    data = server.model_dump_json()

    assert json.loads(data)["auth"] == expected_json
    assert MCPServer.model_validate_json(data).auth == auth
    assert (
        MCPServer.model_validate({"endpoint": "http://x", "auth": expected_json}).auth
        == auth
    )


@pytest.mark.parametrize(
    "auth",
    [
        BasicAuth(username="user", password="secret"),
        BearerTokenAuth(token="secret"),
        ApiKeyAuth(header_name="X-API-Key", api_key="secret"),
        CustomAuth(
            factory=f"{_TEST_MODULE}:make_header_auth",
            arguments={"name": "X-Key", "value": "secret"},
        ),
    ],
    ids=["basic", "bearer", "api_key", "custom"],
)
def test_auth_repr_hides_the_secret(auth: MCPAuth) -> None:
    assert "secret" not in repr(auth)
    assert "secret" not in repr(MCPServer(endpoint="http://x", auth=auth))


def test_auth_from_yaml_reaches_the_server_through_the_plan(tmp_path: Path) -> None:
    source = tmp_path / "mcp.yaml"
    source.write_text(f"""agents:
  - name: a
    mcp_servers:
      - name: basic
        clazz: mcp
        endpoint: http://basic.invalid/mcp
        auth:
          authType: basic
          username: user
          password: secret
      - name: bearer
        clazz: mcp
        endpoint: http://bearer.invalid/mcp
        auth: {{authType: bearer, token: tok}}
      - name: api_key
        clazz: mcp
        endpoint: http://api-key.invalid/mcp
        auth: {{authType: api_key, headerName: X-API-Key, apiKey: key}}
      - name: custom
        clazz: mcp
        endpoint: http://custom.invalid/mcp
        auth:
          authType: custom
          factory: {_TEST_MODULE}:make_header_auth
          arguments: {{name: X-Custom, value: v}}
""")
    agents, _, _ = build_agents(source)
    config = AgentConfiguration()
    plan = AgentPlan.from_agent(agents["a"], config)
    restored = AgentPlan.model_validate_json(plan.model_dump_json())
    cache = ResourceCache(restored.resource_providers, config)

    def auth_of(name: str) -> MCPAuth | None:
        return cache.get_resource(name, ResourceType.MCP_SERVER).auth

    assert auth_of("basic") == BasicAuth(username="user", password="secret")
    assert auth_of("bearer") == BearerTokenAuth(token="tok")
    assert auth_of("api_key") == ApiKeyAuth(header_name="X-API-Key", api_key="key")
    assert auth_of("custom") == CustomAuth(
        factory=f"{_TEST_MODULE}:make_header_auth",
        arguments={"name": "X-Custom", "value": "v"},
    )
    cache.close()


def test_auth_declared_in_a_descriptor_reaches_the_server_through_the_plan() -> None:
    agent = Agent()
    agent.add_resource(
        "remote",
        ResourceType.MCP_SERVER,
        ResourceDescriptor(
            clazz=ResourceName.MCP_SERVER,
            endpoint="http://remote.invalid/mcp",
            auth=BearerTokenAuth(token="tok"),
        ),
    )
    config = AgentConfiguration()
    plan_json = AgentPlan.from_agent(agent, config).model_dump_json()
    restored = AgentPlan.model_validate_json(plan_json)
    cache = ResourceCache(restored.resource_providers, config)

    assert '"authType":"bearer"' in plan_json
    server = cache.get_resource("remote", ResourceType.MCP_SERVER)
    assert server.auth == BearerTokenAuth(token="tok")
    cache.close()


_unpickled: List[str] = []


def _record_unpickling() -> str:
    _unpickled.append("called")
    return "unpickled"


class _UnpicklingProbe:
    def __reduce__(self) -> tuple:
        return (_record_unpickling, ())


def test_string_auth_is_rejected_and_never_unpickled() -> None:
    _unpickled.clear()
    payload = base64.b64encode(pickle.dumps(_UnpicklingProbe())).decode()

    with pytest.raises(TypeError, match="not a string"):
        MCPServer(endpoint="http://x", auth=payload)
    with pytest.raises(TypeError, match="not a string") as raised:
        MCPServer.model_validate_json(
            json.dumps({"endpoint": "http://x", "auth": payload})
        )
    assert _unpickled == []
    assert payload not in str(raised.value)


def test_string_auth_in_a_descriptor_fails_when_the_server_is_built() -> None:
    # The plan builds, because descriptor arguments are only data; the server
    # rejects the value when the operator starts.
    agent = Agent()
    agent.add_resource(
        "remote",
        ResourceType.MCP_SERVER,
        ResourceDescriptor(
            clazz=ResourceName.MCP_SERVER,
            endpoint="http://remote.invalid/mcp",
            auth="Bearer s3cret-token",
        ),
    )
    config = AgentConfiguration()
    plan_json = AgentPlan.from_agent(agent, config).model_dump_json()
    cache = ResourceCache(
        AgentPlan.model_validate_json(plan_json).resource_providers, config
    )

    with pytest.raises(TypeError, match="not a string") as raised:
        cache.get_resource("remote", ResourceType.MCP_SERVER)
    # The value may be a credential, so the error must not repeat it.
    assert "s3cret-token" not in str(raised.value)


def test_httpx_auth_instance_is_rejected() -> None:
    with pytest.raises(TypeError, match="not an httpx.Auth instance"):
        MCPServer(endpoint="http://x", auth=httpx.BasicAuth("user", "secret"))


@pytest.mark.parametrize(
    ("auth", "match"),
    [
        (
            {"authType": "oauth", "token": "t"},
            "does not match any of the expected tags",
        ),
        ({"token": "t"}, "Unable to extract tag"),
        ({"authType": "bearer"}, "token"),
        (
            {"authType": "bearer", "token": "t", "extra": 1},
            "Extra inputs are not permitted",
        ),
        ({"authType": "custom", "factory": "no_colon"}, "<module>:<qualname>"),
        ({"authType": "custom", "factory": "pkg:"}, "<module>:<qualname>"),
        ({"authType": "custom", "factory": " os : getcwd"}, "<module>:<qualname>"),
        ({"authType": "custom", "factory": ".rel:make"}, "<module>:<qualname>"),
        (
            {"authType": "custom", "factory": "pkg:make", "arguments": {"a": {1, 2}}},
            "arguments",
        ),
    ],
    ids=[
        "unknown-type",
        "no-type",
        "missing-field",
        "extra-field",
        "factory-no-colon",
        "factory-no-name",
        "factory-spaces",
        "factory-relative",
        "arguments-not-json",
    ],
)
def test_invalid_auth_config_is_rejected(auth: Dict[str, Any], match: str) -> None:
    with pytest.raises(ValueError, match=match):
        MCPServer(endpoint="http://x", auth=auth)


def test_custom_auth_builds_an_oauth_provider_from_json() -> None:
    server = MCPServer(
        endpoint="http://localhost:8080",
        auth=CustomAuth(
            factory=f"{_TEST_MODULE}:make_oauth_provider",
            arguments={
                "server_url": "http://localhost:8001",
                "client_name": "Example MCP Client",
                "redirect_uri": "http://localhost:3000/callback",
            },
        ),
    )
    restored = MCPServer.model_validate_json(server.model_dump_json())

    provider = restored._get_httpx_auth()

    assert isinstance(provider, OAuthClientProvider)
    # Built once per server, so the provider and its tokens are shared by every
    # connection of that server.
    assert restored._get_httpx_auth() is provider


@pytest.mark.parametrize(
    ("factory", "error", "match"),
    [
        (
            f"{_TEST_MODULE}:make_not_an_auth",
            TypeError,
            "returned str, expected an httpx.Auth",
        ),
        (f"{_TEST_MODULE}:missing_factory", AttributeError, "missing_factory"),
        (
            "flink_agents_no_such_module:make",
            ModuleNotFoundError,
            "flink_agents_no_such_module",
        ),
    ],
    ids=["wrong-return-type", "missing-attribute", "missing-module"],
)
def test_custom_auth_factory_errors_surface_on_connect(
    factory: str, error: type, match: str
) -> None:
    server = MCPServer(
        endpoint="http://127.0.0.1:9/mcp", auth=CustomAuth(factory=factory)
    )

    with pytest.raises(error, match=match):
        server.list_tools()


def test_custom_auth_factory_error_fails_startup_naming_the_server() -> None:
    agent = Agent()
    agent.add_resource(
        "remote",
        ResourceType.MCP_SERVER,
        ResourceDescriptor(
            clazz=ResourceName.MCP_SERVER,
            endpoint="http://127.0.0.1:9/mcp",
            auth=CustomAuth(factory=f"{_TEST_MODULE}:make_not_an_auth"),
        ),
    )
    config = AgentConfiguration()
    cache = ResourceCache(
        AgentPlan.from_agent(agent, config).resource_providers, config
    )

    with pytest.raises(RuntimeError, match="MCP server 'remote'") as raised:
        discover_mcp_resources(cache)
    assert isinstance(raised.value.__cause__, TypeError)
    cache.close()


class _RecordingMCPHandler(BaseHTTPRequestHandler):
    """Answers ``initialize`` and ``tools/list`` on ``/mcp`` and records requests.

    ``/old`` redirects to ``/mcp`` with a 307.
    """

    requests: ClassVar[List[Dict[str, Any]]] = []

    def log_message(self, format: str, *args: Any) -> None:
        pass

    def do_POST(self) -> None:
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
        type(self).requests.append(
            {
                "path": self.path,
                "method": body.get("method"),
                "headers": dict(self.headers.items()),
            }
        )
        if self.path == "/old":
            self.send_response(307)
            self.send_header("Location", "/mcp")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if "id" not in body:
            self.send_response(202)
            self.end_headers()
            return
        if body["method"] == "initialize":
            result = {
                "protocolVersion": body["params"]["protocolVersion"],
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "recording", "version": "1"},
            }
        else:
            result = {"tools": []}
        data = json.dumps({"jsonrpc": "2.0", "id": body["id"], "result": result})
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data.encode())

    def do_GET(self) -> None:
        self.send_response(405)
        self.end_headers()


@pytest.fixture
def recording_server() -> Iterator[str]:
    _RecordingMCPHandler.requests = []
    server = ThreadingHTTPServer(("127.0.0.1", 0), _RecordingMCPHandler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


@pytest.mark.parametrize(
    ("auth", "header", "value"),
    [
        (
            BasicAuth(username="user", password="secret"),
            "Authorization",
            "Basic " + base64.b64encode(b"user:secret").decode(),
        ),
        (
            BasicAuth(username="\u00fcser", password="p\u00e4ss"),
            "Authorization",
            "Basic " + base64.b64encode("\u00fcser:p\u00e4ss".encode()).decode(),
        ),
        (BearerTokenAuth(token="tok"), "Authorization", "Bearer tok"),
        (ApiKeyAuth(header_name="X-API-Key", api_key="key"), "X-API-Key", "key"),
    ],
    ids=["basic", "basic-utf8", "bearer", "api_key"],
)
def test_auth_and_headers_are_sent_on_every_request(
    recording_server: str, auth: MCPAuth, header: str, value: str
) -> None:
    server = MCPServer(
        endpoint=f"{recording_server}/mcp", headers={"X-Trace": "1"}, auth=auth
    )

    assert server.list_tools() == []

    requests = _RecordingMCPHandler.requests
    assert {"initialize", "tools/list"} <= {request["method"] for request in requests}
    for request in requests:
        assert request["headers"][header] == value
        assert request["headers"]["X-Trace"] == "1"


def test_without_auth_only_the_configured_headers_are_sent(
    recording_server: str,
) -> None:
    server = MCPServer(endpoint=f"{recording_server}/mcp", headers={"X-Trace": "1"})

    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        assert server.list_tools() == []

    assert not [w for w in caught if issubclass(w.category, DeprecationWarning)]
    requests = _RecordingMCPHandler.requests
    assert {"initialize", "tools/list"} <= {request["method"] for request in requests}
    for request in requests:
        assert request["headers"]["X-Trace"] == "1"
        assert "Authorization" not in request["headers"]


def test_each_connection_uses_a_client_with_the_configured_timeouts_and_closes_it(
    recording_server: str,
) -> None:
    clients: List[httpx.AsyncClient] = []

    def recording_transport(url: str, *, http_client: httpx.AsyncClient) -> Any:
        clients.append(http_client)
        return streamable_http_client(url, http_client=http_client)

    server = MCPServer(
        endpoint=f"{recording_server}/mcp",
        timeout=timedelta(seconds=5),
        sse_read_timeout=timedelta(seconds=7),
    )
    with patch(
        "flink_agents.integrations.mcp.mcp.streamable_http_client", recording_transport
    ):
        server.list_tools()
        server.list_tools()

    assert len(clients) == 2
    for client in clients:
        assert client.timeout == httpx.Timeout(5.0, read=7.0)
        assert client.is_closed


def test_auth_replaces_a_header_with_the_same_name(recording_server: str) -> None:
    server = MCPServer(
        endpoint=f"{recording_server}/mcp",
        headers={"Authorization": "Bearer stale"},
        auth=BearerTokenAuth(token="tok"),
    )

    server.list_tools()

    for request in _RecordingMCPHandler.requests:
        assert request["headers"]["Authorization"] == "Bearer tok"


def test_same_origin_redirect_is_followed_with_auth(recording_server: str) -> None:
    server = MCPServer(
        endpoint=f"{recording_server}/old", auth=BearerTokenAuth(token="tok")
    )

    assert server.list_tools() == []

    requests = _RecordingMCPHandler.requests
    assert {"initialize", "tools/list"} <= {
        request["method"] for request in requests if request["path"] == "/mcp"
    }
    for request in requests:
        assert request["headers"]["Authorization"] == "Bearer tok"


def test_custom_auth_is_built_once_and_used_by_every_connection(
    recording_server: str,
) -> None:
    _factory_calls.clear()
    server = MCPServer(
        endpoint=f"{recording_server}/mcp",
        auth=CustomAuth(
            factory=f"{_TEST_MODULE}:make_header_auth",
            arguments={"name": "X-Custom", "value": "v"},
        ),
    )

    server.list_tools()
    server.list_tools()

    assert _factory_calls == [{"name": "X-Custom", "value": "v"}]
    requests = _RecordingMCPHandler.requests
    assert [r["method"] for r in requests].count("initialize") == 2
    assert all(request["headers"]["X-Custom"] == "v" for request in requests)


def test_mcp_tool_roundtrip_preserves_metadata() -> None:
    metadata = ToolMetadata(
        name="add",
        description="Add two integers.",
        args_schema={
            "type": "object",
            "properties": {
                "a": {"type": "integer"},
                "b": {"type": "integer"},
            },
            "required": ["a", "b"],
        },
    )
    tool = MCPTool(
        metadata=metadata,
        mcp_server=MCPServer(endpoint="http://x"),
        mcp_server_name="calculator_server",
    )

    dumped = tool.model_dump()
    assert "metadata" in dumped, "serialized form must expose `metadata` key"
    assert "metadata_" not in dumped

    restored = MCPTool.model_validate(dumped)
    assert restored.metadata == metadata
    assert restored.name == "add"
    assert restored.mcp_server_name == "calculator_server"
    assert restored.get_tool_execution_metadata({}) == {
        ToolExecutionMetadataKeys.MCP_SERVER: "calculator_server"
    }


class _ProtocolErrorClientSession(ClientSession):
    async def call_tool(self, *args: object, **kwargs: object) -> CallToolResult:
        return CallToolResult(
            content=[TextContent(type="text", text="business failure")],
            isError=True,
        )


class _ProtocolErrorServer(MCPServer):
    @asynccontextmanager
    async def _get_session(self) -> AsyncIterator[ClientSession]:
        server_send, client_receive = anyio.create_memory_object_stream(1)
        client_send, server_receive = anyio.create_memory_object_stream(1)
        async with (
            server_send,
            client_receive,
            client_send,
            server_receive,
            _ProtocolErrorClientSession(client_receive, client_send) as session,
        ):
            yield session


class _ProtocolSuccessSession:
    async def call_tool(self, *args: object, **kwargs: object) -> CallToolResult:
        return CallToolResult(
            content=[TextContent(type="text", text="result")],
            isError=False,
        )


class _ProtocolSuccessServer(MCPServer):
    @asynccontextmanager
    async def _get_session(self) -> AsyncIterator[_ProtocolSuccessSession]:
        yield _ProtocolSuccessSession()


def test_mcp_protocol_error_maps_to_tool_failure() -> None:
    server = _ProtocolErrorServer(endpoint="http://localhost/mcp")

    with pytest.raises(RuntimeError, match="business failure"):
        asyncio.run(server.call_tool_async("lookup", query="flink"))

    tool = MCPTool(
        metadata=ToolMetadata(
            name="lookup",
            description="Lookup a value.",
            args_schema={"type": "object", "properties": {}},
        ),
        mcp_server=server,
    )
    response = tool.call(query="flink")

    assert isinstance(response, ToolResponse)
    assert response.is_error()
    assert "business failure" in response.error_message


def test_mcp_tool_success_preserves_raw_result() -> None:
    tool = MCPTool(
        metadata=ToolMetadata(
            name="lookup",
            description="Lookup a value.",
            args_schema={"type": "object", "properties": {}},
        ),
        mcp_server=_ProtocolSuccessServer(endpoint="http://localhost/mcp"),
    )

    assert tool.call(query="flink") == ["result"]


@pytest.mark.parametrize(
    "content_item",
    [
        EmbeddedResource(
            type="resource",
            resource=TextResourceContents(uri="file:///report.txt", text="report"),
        ),
        EmbeddedResource(
            type="resource",
            resource=BlobResourceContents(uri="file:///report.bin", blob="/wA="),
        ),
        ResourceLink(type="resource_link", uri="file:///report.txt", name="report"),
    ],
    ids=["text-resource", "blob-resource", "resource-link"],
)
def test_mcp_resource_result_converts_to_tool_response(
    content_item: EmbeddedResource | ResourceLink,
) -> None:
    class ResourceSession:
        async def call_tool(self, *args: object, **kwargs: object) -> CallToolResult:
            return CallToolResult(content=[content_item], isError=False)

    @asynccontextmanager
    async def get_session(server: MCPServer) -> AsyncIterator[ResourceSession]:
        yield ResourceSession()

    tool = MCPTool(
        metadata=ToolMetadata(
            name="lookup",
            description="Return a resource.",
            args_schema={"type": "object", "properties": {}},
        ),
        mcp_server=MCPServer(endpoint="http://localhost/mcp"),
    )
    with patch.object(MCPServer, "_get_session", get_session):
        response = to_tool_response(tool.call())

    assert not response.is_error()
    result = json.loads(response.get_text())
    if isinstance(content_item, EmbeddedResource):
        expected = {"type": "resource", "uri": str(content_item.resource.uri)}
        if isinstance(content_item.resource, TextResourceContents):
            expected["text"] = content_item.resource.text
        else:
            expected["blob"] = content_item.resource.blob
    else:
        expected = content_item.model_dump(mode="json")
    assert result == [expected]


@pytest.mark.parametrize(
    "value", [timedelta(microseconds=1500), "1500 us", "PT0.0015S", 1500]
)
def test_duration_timeouts(value: object) -> None:
    server = MCPServer(
        endpoint="https://example.com/mcp", timeout=value, sse_read_timeout=value
    )
    restored = MCPServer.model_validate_json(server.model_dump_json())
    expected = (
        timedelta(milliseconds=1500)
        if isinstance(value, int)
        else timedelta(microseconds=1500)
    )
    assert restored.timeout == expected
    assert restored.sse_read_timeout == expected


@pytest.mark.parametrize("value", [0, "-1 ms", "1 ns", "invalid", None])
@pytest.mark.parametrize("name", ["timeout", "sse_read_timeout"])
def test_invalid_duration_timeout(name: str, value: object) -> None:
    with pytest.raises(ValueError, match=name):
        MCPServer(endpoint="https://example.com/mcp", **{name: value})
