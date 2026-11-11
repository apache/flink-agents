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
"""Tests for discovering MCP tools and prompts when the operator starts.

The plan only registers the MCP server. These tests stub the server's network
calls and check what happens at startup: discovery and registration, closing,
name checks, and a failed start when the server cannot be reached.
"""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
from time import monotonic
from typing import Dict, List
from unittest.mock import Mock

import pytest

from flink_agents.api.agents.agent import Agent
from flink_agents.api.resource import ResourceDescriptor, ResourceType
from flink_agents.api.tools.tool import ToolMetadata
from flink_agents.api.yaml.loader import build_agents
from flink_agents.integrations.mcp.mcp import MCPPrompt, MCPServer, MCPTool
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.runtime.flink_runner_context import (
    FlinkRunnerContext,
    initialize_mcp_scope,
)
from flink_agents.runtime.mcp_resource_discovery import discover_mcp_resources
from flink_agents.runtime.resource_cache import ResourceCache


def _tool(name: str) -> MCPTool:
    return MCPTool(
        metadata=ToolMetadata(
            name=name,
            description=f"Remote tool {name}.",
            args_schema={"type": "object", "properties": {}},
        ),
    )


def _prompt(name: str) -> MCPPrompt:
    return MCPPrompt(name=name, title=None)


def _agent(*server_names: str) -> Agent:
    agent = Agent()
    for server_name in server_names:
        agent.add_resource(
            name=server_name,
            resource_type=ResourceType.MCP_SERVER,
            instance=ResourceDescriptor(
                clazz="flink_agents.integrations.mcp.mcp.MCPServer",
                endpoint=f"http://{server_name}.invalid",
            ),
        )
    return agent


class _FakeServers:
    """Replaces the network calls of ``MCPServer`` and records what is called."""

    def __init__(
        self,
        monkeypatch: pytest.MonkeyPatch,
        tools: Dict[str, List[MCPTool]] | None = None,
        prompts: Dict[str, List[MCPPrompt]] | None = None,
        failing: Dict[str, Exception] | None = None,
    ) -> None:
        self.listed: List[str] = []
        self.closed: List[str] = []
        tools = tools or {}
        prompts = prompts or {}
        failing = failing or {}

        def name_of(server: MCPServer) -> str:
            return server.endpoint.removeprefix("http://").removesuffix(".invalid")

        def list_prompts(server: MCPServer) -> List[MCPPrompt]:
            self.listed.append(name_of(server))
            return prompts.get(name_of(server), [])

        def list_tools(server: MCPServer) -> List[MCPTool]:
            if name_of(server) in failing:
                raise failing[name_of(server)]
            return tools.get(name_of(server), [])

        def close(server: MCPServer) -> None:
            self.closed.append(name_of(server))

        monkeypatch.setattr(MCPServer, "list_prompts", list_prompts)
        monkeypatch.setattr(MCPServer, "list_tools", list_tools)
        monkeypatch.setattr(MCPServer, "close", close)


def _cache(agent: Agent) -> ResourceCache:
    config = AgentConfiguration()
    plan = AgentPlan.from_agent(agent, config)
    return ResourceCache(plan.resource_providers, config)


def test_startup_discovers_and_registers_tools_and_prompts(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _FakeServers(
        monkeypatch,
        tools={"remote": [_tool("lookup"), _tool("add")]},
        prompts={"remote": [_prompt("ask_sum")]},
    )
    cache = _cache(_agent("remote"))

    discover_mcp_resources(cache)

    lookup = cache.get_resource("lookup", ResourceType.TOOL)
    assert isinstance(lookup, MCPTool)
    assert lookup.mcp_server_name == "remote"
    assert cache.get_resource("add", ResourceType.TOOL).name == "add"
    assert cache.get_resource("ask_sum", ResourceType.PROMPT).name == "ask_sum"
    assert isinstance(cache.get_resource("remote", ResourceType.MCP_SERVER), MCPServer)


def test_server_is_closed_with_the_cache_even_when_nothing_is_discovered(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fake = _FakeServers(monkeypatch)
    cache = _cache(_agent("remote"))

    discover_mcp_resources(cache)
    assert fake.closed == []

    cache.close()

    assert fake.closed == ["remote"]


def test_unreachable_server_fails_startup_naming_the_server(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    refused = ConnectionError("connection refused")
    _FakeServers(monkeypatch, failing={"remote": refused})
    cache = _cache(_agent("remote"))

    with pytest.raises(RuntimeError, match="MCP server 'remote'") as excinfo:
        discover_mcp_resources(cache)

    assert excinfo.value.__cause__ is refused


def test_tool_name_with_reserved_subagent_prefix_is_rejected(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A remote ``_subagent_lookup`` would land in the TOOL namespace, but
    dispatch routes any prefixed call to AGENT, so it could never be called.
    """
    _FakeServers(monkeypatch, tools={"remote": [_tool("_subagent_lookup")]})
    cache = _cache(_agent("remote"))

    with pytest.raises(ValueError, match="reserved prefix '_subagent_'") as excinfo:
        discover_mcp_resources(cache)

    assert "MCP server 'remote'" in str(excinfo.value)


def test_duplicate_tool_name_across_servers_is_rejected(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _FakeServers(
        monkeypatch,
        tools={"alpha": [_tool("add")], "beta": [_tool("add")]},
    )
    cache = _cache(_agent("alpha", "beta"))

    with pytest.raises(
        ValueError,
        match="Duplicate tool name 'add'.*'alpha'.*'beta'",
    ):
        discover_mcp_resources(cache)


def test_duplicate_prompt_name_across_servers_is_rejected(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _FakeServers(
        monkeypatch,
        prompts={"alpha": [_prompt("ask_sum")], "beta": [_prompt("ask_sum")]},
    )
    cache = _cache(_agent("alpha", "beta"))

    with pytest.raises(ValueError, match="Duplicate prompt name 'ask_sum'"):
        discover_mcp_resources(cache)


def test_tool_name_declared_on_the_agent_is_rejected(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from flink_agents.api.function import PythonFunction as ApiPythonFunction
    from flink_agents.api.tools.function_tool import FunctionTool as ApiFunctionTool

    _FakeServers(monkeypatch, tools={"remote": [_tool("add")]})
    agent = _agent("remote")
    agent.add_resource(
        name="add",
        resource_type=ResourceType.TOOL,
        instance=ApiFunctionTool(func=ApiPythonFunction.from_callable(_add)),
    )
    cache = _cache(agent)

    with pytest.raises(ValueError, match="already declared on the agent"):
        discover_mcp_resources(cache)


def _add(a: int, b: int) -> int:
    """Add two numbers.

    Parameters
    ----------
    a : int
        First number.
    b : int
        Second number.
    """
    return a + b


def test_runner_context_discovers_mcp_resources_when_it_is_created(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fake = _FakeServers(monkeypatch, tools={"remote": [_tool("lookup")]})
    plan = AgentPlan.from_agent(_agent("remote"), AgentConfiguration())

    ctx = FlinkRunnerContext(Mock(), plan.model_dump_json(), Mock(), Mock())

    assert fake.listed == ["remote"]
    cache = ctx._FlinkRunnerContext__resource_cache
    assert cache.has_resource("lookup", ResourceType.TOOL)


def test_runner_context_creation_fails_when_the_server_is_unreachable(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fake = _FakeServers(monkeypatch, failing={"remote": ConnectionError("refused")})
    plan = AgentPlan.from_agent(_agent("remote"), AgentConfiguration())

    with pytest.raises(RuntimeError, match="MCP server 'remote'"):
        FlinkRunnerContext(Mock(), plan.model_dump_json(), Mock(), Mock())

    assert fake.closed == ["remote"]


def test_yaml_shared_and_local_servers_are_discovered_only_at_startup(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    """YAML aliases reach discovery with both shared and local declarations."""
    fake = _FakeServers(monkeypatch, tools={"local": [_tool("lookup")]})
    source = tmp_path / "mcp.yaml"
    source.write_text("""mcp_servers:
  - name: shared
    clazz: mcp
    endpoint: http://shared.invalid
agents:
  - name: agent
    mcp_servers:
      - name: local
        clazz: mcp
        endpoint: http://local.invalid
""")
    agents, shared, _ = build_agents(source)
    for name, descriptor in shared[ResourceType.MCP_SERVER].items():
        agents["agent"].add_resource(name, ResourceType.MCP_SERVER, descriptor)
    plan = AgentPlan.from_agent(agents["agent"], AgentConfiguration())
    assert fake.listed == []
    assert set(plan.resource_providers[ResourceType.MCP_SERVER]) == {"local", "shared"}

    ctx = FlinkRunnerContext(Mock(), plan.model_dump_json(), Mock(), Mock())
    assert fake.listed == ["local", "shared"]
    cache = ctx._FlinkRunnerContext__resource_cache
    assert cache.get_resource("lookup", ResourceType.TOOL).mcp_server_name == "local"
    ctx.close()
    assert set(fake.closed) == {"local", "shared"}


class _UnresponsiveMCPHandler(BaseHTTPRequestHandler):
    """Accept requests without completing the JSON-RPC initialization."""

    def do_POST(self) -> None:
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        self.send_response(202)
        self.end_headers()

    def do_GET(self) -> None:
        self.send_response(405)
        self.end_headers()


def test_real_startup_timeout_without_a_json_rpc_response() -> None:
    """A reachable HTTP server must not leave operator startup waiting forever."""
    server = ThreadingHTTPServer(("127.0.0.1", 0), _UnresponsiveMCPHandler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        agent = Agent()
        agent.add_resource("remote", ResourceType.MCP_SERVER, ResourceDescriptor(
            clazz="flink_agents.integrations.mcp.mcp.MCPServer",
            endpoint=f"http://127.0.0.1:{server.server_port}/mcp", timeout=1,
        ))
        plan = AgentPlan.from_agent(agent, AgentConfiguration())
        start = monotonic()
        with pytest.raises(RuntimeError, match="MCP server 'remote'"):
            FlinkRunnerContext(Mock(), plan.model_dump_json(), Mock(), Mock())
        assert monotonic() - start < 5
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def test_failed_child_scope_discovery_closes_the_new_cache(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fake = _FakeServers(monkeypatch, failing={"child": ConnectionError("refused")})
    root_plan = AgentPlan.from_agent(Agent(), AgentConfiguration())
    child_plan = AgentPlan.from_agent(_agent("child"), AgentConfiguration())
    j_context = Mock()
    ctx = FlinkRunnerContext(j_context, root_plan.model_dump_json(), Mock(), Mock())
    with pytest.raises(RuntimeError, match="MCP server 'child'"):
        initialize_mcp_scope(ctx, child_plan.model_dump_json())
    assert fake.closed == ["child"]
    ctx.close()


def test_child_mcp_is_discovered_before_the_first_action(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fake = _FakeServers(monkeypatch, tools={"child": [_tool("lookup")]})
    root_plan = AgentPlan.from_agent(Agent(), AgentConfiguration())
    child_plan = AgentPlan.from_agent(_agent("child"), AgentConfiguration())
    j_context = Mock()
    ctx = FlinkRunnerContext(j_context, root_plan.model_dump_json(), Mock(), Mock())
    assert fake.listed == []
    child_json = child_plan.model_dump_json()
    initialize_mcp_scope(ctx, child_json)
    assert fake.listed == ["child"]
    j_context.getActiveScopePlanJson.return_value = child_json
    assert ctx.get_resource("lookup", ResourceType.TOOL).name == "lookup"
    initialize_mcp_scope(ctx, child_json)
    assert fake.listed == ["child"]
    ctx.close()
    assert fake.closed == ["child"]
