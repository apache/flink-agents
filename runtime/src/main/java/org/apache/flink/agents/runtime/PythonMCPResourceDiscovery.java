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

import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.plan.resource.python.PythonMCPPrompt;
import org.apache.flink.agents.plan.resource.python.PythonMCPServer;
import org.apache.flink.agents.plan.resource.python.PythonMCPTool;
import org.apache.flink.agents.plan.resourceprovider.PythonResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.apache.flink.util.LambdaUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;
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

        List<Resource> discoveredResources = new ArrayList<>();
        List<ResourceRegistration> registrations = new ArrayList<>();
        Set<String> toolNames = new HashSet<>();
        Set<String> promptNames = new HashSet<>();
        try {
            for (ResourceProvider rp : servers.values()) {
                if (!(rp instanceof PythonResourceProvider)) {
                    continue;
                }
                PythonResourceProvider provider = (PythonResourceProvider) rp;
                provider.setPythonResourceAdapter(adapter);

                PythonMCPServer server =
                        (PythonMCPServer) provider.provide(cache.getResourceContext());
                discoveredResources.add(server);
                registrations.add(new ResourceRegistration(provider.getName(), MCP_SERVER, server));

                List<PythonMCPTool> tools = server.listTools(provider.getName());
                discoveredResources.addAll(tools);
                for (PythonMCPTool tool : tools) {
                    String name = requireNonNull(tool.getName(), "MCP tool name");
                    validateDiscoveredNameUnique(name, TOOL, toolNames);
                    registrations.add(new ResourceRegistration(name, TOOL, tool));
                }

                List<PythonMCPPrompt> prompts = server.listPrompts();
                discoveredResources.addAll(prompts);
                for (PythonMCPPrompt prompt : prompts) {
                    String name = requireNonNull(prompt.getName(), "MCP prompt name");
                    validateDiscoveredNameUnique(name, PROMPT, promptNames);
                    registrations.add(new ResourceRegistration(name, PROMPT, prompt));
                }
            }
        } catch (Exception discoveryFailure) {
            Collections.reverse(discoveredResources);
            try {
                LambdaUtil.applyToAllWhileSuppressingExceptions(
                        discoveredResources, Resource::close);
            } catch (Exception closeFailure) {
                discoveryFailure.addSuppressed(closeFailure);
            }
            throw discoveryFailure;
        }

        for (ResourceRegistration registration : registrations) {
            cache.put(registration.name, registration.type, registration.resource);
        }
    }

    private static void validateDiscoveredNameUnique(
            String name, ResourceType type, Set<String> discoveredNames) {
        // Python plan construction registers serialized MCP tool and prompt providers. Runtime
        // discovery intentionally replaces those snapshots with resources bound to the live MCP
        // server, so only duplicates within this discovery are ambiguous.
        if (!discoveredNames.add(name)) {
            throw new IllegalStateException(
                    String.format("Duplicate Python MCP %s name: %s", type.getValue(), name));
        }
    }

    private static final class ResourceRegistration {
        private final String name;
        private final ResourceType type;
        private final Resource resource;

        private ResourceRegistration(String name, ResourceType type, Resource resource) {
            this.name = name;
            this.type = type;
            this.resource = resource;
        }
    }
}
