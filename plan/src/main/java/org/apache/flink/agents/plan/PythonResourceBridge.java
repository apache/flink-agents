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

package org.apache.flink.agents.plan;

import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.plan.resource.python.PythonMCPPrompt;
import org.apache.flink.agents.plan.resource.python.PythonMCPServer;
import org.apache.flink.agents.plan.resource.python.PythonMCPTool;
import org.apache.flink.agents.plan.resourceprovider.PythonResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;

import java.util.Map;

import static org.apache.flink.agents.api.resource.ResourceType.MCP_SERVER;
import static org.apache.flink.agents.api.resource.ResourceType.PROMPT;
import static org.apache.flink.agents.api.resource.ResourceType.TOOL;

/**
 * Discovers tools and prompts from Python MCP servers and registers them in a ResourceCache.
 *
 * <p>Called once during operator initialization after the Python interpreter is available.
 */
public class PythonResourceBridge {

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

        for (ResourceProvider rp : servers.values()) {
            if (!(rp instanceof PythonResourceProvider)) {
                continue;
            }
            PythonResourceProvider provider = (PythonResourceProvider) rp;
            provider.setPythonResourceAdapter(adapter);

            PythonMCPServer server =
                    (PythonMCPServer)
                            provider.provide(
                                    (name, type) -> {
                                        try {
                                            return cache.getResource(name, type);
                                        } catch (Exception e) {
                                            throw new RuntimeException(e);
                                        }
                                    });

            for (PythonMCPTool tool : server.listTools()) {
                cache.put(tool.getName(), TOOL, tool);
            }
            for (PythonMCPPrompt prompt : server.listPrompts()) {
                cache.put(prompt.getName(), PROMPT, prompt);
            }
        }
    }
}
