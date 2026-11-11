/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.agents.runtime;

import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.plan.resource.python.PythonMCPPrompt;
import org.apache.flink.agents.plan.resource.python.PythonMCPServer;
import org.apache.flink.agents.plan.resource.python.PythonMCPTool;
import org.apache.flink.agents.plan.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.plan.resourceprovider.PythonResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.apache.flink.agents.api.resource.ResourceType.MCP_SERVER;
import static org.apache.flink.agents.api.resource.ResourceType.PROMPT;
import static org.apache.flink.agents.api.resource.ResourceType.TOOL;

/**
 * Discovers tools and prompts from Python MCP servers and registers them in a ResourceCache.
 *
 * <p>Called once during operator initialization after the Python interpreter is available.
 */
public class PythonMCPResourceDiscovery {

    /**
     * Initializes Python MCP servers from the resource providers, extracts their tools and prompts,
     * and registers them in the cache.
     *
     * @param resourceProviders the resource providers from the agent plan
     * @param adapter the Python resource adapter
     * @param cache the resource cache to register discovered resources in
     * @throws Exception if a Python MCP server fails to initialize
     */
    public static void discoverPythonMCPResources(
            Map<ResourceType, Map<String, ResourceProvider>> resourceProviders,
            PythonResourceAdapter adapter,
            ResourceCache cache)
            throws Exception {

        // Store the adapter on the cache so that future cache.getResource() calls on
        // non-MCP Python resources (e.g. PythonChatModelSetup) will have the adapter available.
        cache.setPythonResourceAdapter(adapter);

        Map<String, ResourceProvider> servers = resourceProviders.get(MCP_SERVER);
        if (servers == null) {
            return;
        }

        Map<String, String> toolOwners = new HashMap<>();
        Map<String, String> promptOwners = new HashMap<>();
        for (ResourceProvider rp : new TreeMap<>(servers).values()) {
            if (!(rp instanceof PythonResourceProvider)) {
                continue;
            }
            PythonResourceProvider provider = (PythonResourceProvider) rp;
            provider.setPythonResourceAdapter(adapter);

            PythonMCPServer server = (PythonMCPServer) provider.provide(cache.getResourceContext());
            cache.put(provider.getName(), MCP_SERVER, server);

            for (PythonMCPTool tool : server.listTools(provider.getName())) {
                if (tool.getName().startsWith(SubagentSetup.CALLABLE_NAME_PREFIX)) {
                    throw new IllegalArgumentException(
                            "MCP server '"
                                    + provider.getName()
                                    + "' advertised a tool with reserved prefix '"
                                    + SubagentSetup.CALLABLE_NAME_PREFIX
                                    + "': "
                                    + tool.getName());
                }
                JavaMCPResourceDiscovery.checkNameFree(
                        cache, TOOL, tool.getName(), provider.getName(), toolOwners);
                cache.put(tool.getName(), TOOL, tool);
                toolOwners.put(tool.getName(), provider.getName());
            }
            for (PythonMCPPrompt prompt : server.listPrompts()) {
                JavaMCPResourceDiscovery.checkNameFree(
                        cache, PROMPT, prompt.getName(), provider.getName(), promptOwners);
                cache.put(prompt.getName(), PROMPT, prompt);
                promptOwners.put(prompt.getName(), provider.getName());
            }
        }
    }
}
