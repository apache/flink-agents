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
import org.apache.flink.agents.plan.resourceprovider.JavaResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;

import java.lang.reflect.Method;
import java.util.Map;

import static org.apache.flink.agents.api.resource.ResourceType.MCP_SERVER;
import static org.apache.flink.agents.api.resource.ResourceType.PROMPT;
import static org.apache.flink.agents.api.resource.ResourceType.TOOL;

/**
 * Discovers tools and prompts from Java MCP servers and registers them in a ResourceCache.
 *
 * <p>Called once during operator initialization, immediately after the ResourceCache is created.
 * Uses reflection throughout to preserve Java 11 compatibility (MCP classes are conditionally
 * compiled for Java 17+).
 */
public class JavaMCPResourceDiscovery {

    /**
     * Initializes Java MCP servers from the resource providers, extracts their tools and prompts,
     * and registers them in the cache.
     *
     * @param resourceProviders the resource providers from the agent plan
     * @param cache the resource cache to register discovered resources in
     * @throws Exception if a Java MCP server fails to initialize or discovery fails
     */
    public static void discoverJavaMCPResources(
            Map<ResourceType, Map<String, ResourceProvider>> resourceProviders, ResourceCache cache)
            throws Exception {

        Map<String, ResourceProvider> servers = resourceProviders.get(MCP_SERVER);
        if (servers == null) {
            return;
        }

        for (ResourceProvider rp : servers.values()) {
            if (!(rp instanceof JavaResourceProvider)) {
                continue;
            }

            Object mcpServer = rp.provide(null);

            Method listToolsMethod = mcpServer.getClass().getMethod("listTools");
            @SuppressWarnings("unchecked")
            Iterable<Resource> tools = (Iterable<Resource>) listToolsMethod.invoke(mcpServer);
            for (Resource tool : tools) {
                String toolName = (String) tool.getClass().getMethod("getName").invoke(tool);
                cache.put(toolName, TOOL, tool);
            }

            Method supportsPromptsMethod = mcpServer.getClass().getMethod("supportsPrompts");
            boolean supportsPrompts = (Boolean) supportsPromptsMethod.invoke(mcpServer);
            if (supportsPrompts) {
                Method listPromptsMethod = mcpServer.getClass().getMethod("listPrompts");
                @SuppressWarnings("unchecked")
                Iterable<Resource> prompts =
                        (Iterable<Resource>) listPromptsMethod.invoke(mcpServer);
                for (Resource prompt : prompts) {
                    String promptName =
                            (String) prompt.getClass().getMethod("getName").invoke(prompt);
                    cache.put(promptName, PROMPT, prompt);
                }
            }
        }
    }
}
