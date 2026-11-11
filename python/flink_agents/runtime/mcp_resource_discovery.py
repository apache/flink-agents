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
from typing import TYPE_CHECKING, Dict

from flink_agents.api.resource import ResourceType
from flink_agents.api.subagent import CALLABLE_NAME_PREFIX
from flink_agents.plan.resource_provider import is_python_owned

if TYPE_CHECKING:
    from flink_agents.integrations.mcp.mcp import MCPServer
    from flink_agents.runtime.resource_cache import ResourceCache


def discover_mcp_resources(cache: "ResourceCache") -> None:
    """Discover the tools and prompts of the Python MCP servers in ``cache``.

    Runs once when the operator starts, not when the plan is built. Each server
    is built through the cache, so ``cache.close()`` closes it even when it
    advertises nothing. A server that cannot be reached within its own
    ``timeout`` fails the start with the server name in the error, and so does
    an advertised name that is reserved or already taken.
    """
    owners: Dict[ResourceType, Dict[str, str]] = {
        ResourceType.TOOL: {},
        ResourceType.PROMPT: {},
    }
    providers = cache.get_resource_providers(ResourceType.MCP_SERVER)
    for server_name in sorted(providers):
        if not is_python_owned(providers[server_name]):
            # Java-owned servers are discovered by the Java resource cache.
            continue
        server: MCPServer = cache.get_resource(server_name, ResourceType.MCP_SERVER)
        try:
            prompts = server.list_prompts()
            tools = server.list_tools()
        except Exception as e:
            msg = (
                f"Failed to discover tools and prompts from MCP server "
                f"'{server_name}' at operator startup. Check that the server is "
                f"reachable and that its timeout fits your deployment."
            )
            raise RuntimeError(msg) from e

        for prompt in prompts:
            _check_name_free(
                cache, ResourceType.PROMPT, prompt.name, server_name, owners
            )
            cache.put(prompt.name, ResourceType.PROMPT, prompt)
            owners[ResourceType.PROMPT][prompt.name] = server_name
        for tool in tools:
            # The remote server picks the tool names, so the reserved prefix has
            # to be checked here: dispatch routes any ``_subagent_*`` call to the
            # AGENT namespace, so such a tool could never be called.
            if tool.name.startswith(CALLABLE_NAME_PREFIX):
                msg = (
                    f"MCP server '{server_name}' advertised a tool with a reserved "
                    f"name: Tool name '{tool.name}' must not start with the reserved "
                    f"prefix '{CALLABLE_NAME_PREFIX}', which identifies sub-agent "
                    f"callables."
                )
                raise ValueError(msg)
            _check_name_free(cache, ResourceType.TOOL, tool.name, server_name, owners)
            tool.mcp_server_name = server_name
            cache.put(tool.name, ResourceType.TOOL, tool)
            owners[ResourceType.TOOL][tool.name] = server_name


def _check_name_free(
    cache: "ResourceCache",
    type: ResourceType,
    name: str,
    server_name: str,
    owners: Dict[ResourceType, Dict[str, str]],
) -> None:
    kind = type.value
    owner = owners[type].get(name)
    if owner is not None:
        msg = (
            f"Duplicate {kind} name '{name}': advertised by both MCP server "
            f"'{owner}' and MCP server '{server_name}'."
        )
        raise ValueError(msg)
    if cache.has_resource(name, type):
        msg = (
            f"Duplicate {kind} name '{name}': MCP server '{server_name}' "
            f"advertised a name that is already declared on the agent."
        )
        raise ValueError(msg)
