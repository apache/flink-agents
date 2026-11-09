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
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.plan.resourceprovider.JavaResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.apache.flink.agents.api.resource.ResourceType.MCP_SERVER;
import static org.apache.flink.agents.api.resource.ResourceType.PROMPT;
import static org.apache.flink.agents.api.resource.ResourceType.TOOL;

/**
 * Discovers tools and prompts from Java MCP servers and registers them in a ResourceCache.
 *
 * <p>Called once during operator initialization, immediately after the ResourceCache is created.
 * Uses reflection throughout to preserve Java 11 compatibility (MCP classes are conditionally
 * compiled for Java 17+).
 *
 * <p>Each server is also put into the cache, so {@link ResourceCache#close()} closes it even when
 * discovery finds no tool or prompt. A server that stays unreachable after its own timeout and
 * retries fails the operator start, and so does a discovered name that is reserved or taken.
 */
public class JavaMCPResourceDiscovery {

    /**
     * Initializes Java MCP servers from the resource providers, extracts their tools and prompts,
     * and registers them in the cache.
     *
     * @param resourceProviders the resource providers from the agent plan
     * @param cache the resource cache to register discovered resources in
     * @throws Exception if a Java MCP server fails to initialize, discovery fails, or a discovered
     *     name is reserved or duplicated
     */
    public static void discoverJavaMCPResources(
            Map<ResourceType, Map<String, ResourceProvider>> resourceProviders, ResourceCache cache)
            throws Exception {

        Map<String, ResourceProvider> servers = resourceProviders.get(MCP_SERVER);
        if (servers == null) {
            return;
        }

        Map<String, String> toolOwners = new HashMap<>();
        Map<String, String> promptOwners = new HashMap<>();
        // Sorted so that a name clash between two servers is reported the same way on every run.
        for (Map.Entry<String, ResourceProvider> entry : new TreeMap<>(servers).entrySet()) {
            if (!(entry.getValue() instanceof JavaResourceProvider)) {
                continue;
            }
            discoverServer(entry.getKey(), entry.getValue(), cache, toolOwners, promptOwners);
        }
    }

    private static void discoverServer(
            String serverName,
            ResourceProvider provider,
            ResourceCache cache,
            Map<String, String> toolOwners,
            Map<String, String> promptOwners)
            throws Exception {
        Object mcpServer = provider.provide(cache.getResourceContext());
        // Cached before any network call: the cache closes it on operator close, whether discovery
        // finds tools, finds nothing, or fails half way.
        cache.put(serverName, MCP_SERVER, (Resource) mcpServer);

        try {
            Method listToolsMethod = mcpServer.getClass().getMethod("listTools");
            @SuppressWarnings("unchecked")
            Iterable<Resource> tools = (Iterable<Resource>) invoke(listToolsMethod, mcpServer);
            for (Resource discovered : tools) {
                Resource tool = attachMcpServerNameIfSupported(discovered, serverName);
                String toolName = nameOf(tool);
                if (toolName.startsWith(SubagentSetup.CALLABLE_NAME_PREFIX)) {
                    throw new IllegalArgumentException(
                            String.format(
                                    "MCP server '%s' advertised a tool with a reserved name: tool"
                                            + " name '%s' must not start with the reserved prefix"
                                            + " '%s', which identifies sub-agent callables.",
                                    serverName, toolName, SubagentSetup.CALLABLE_NAME_PREFIX));
                }
                checkNameFree(cache, TOOL, toolName, serverName, toolOwners);
                cache.put(toolName, TOOL, tool);
                toolOwners.put(toolName, serverName);
            }

            Method supportsPromptsMethod = mcpServer.getClass().getMethod("supportsPrompts");
            boolean supportsPrompts = (Boolean) invoke(supportsPromptsMethod, mcpServer);
            if (supportsPrompts) {
                Method listPromptsMethod = mcpServer.getClass().getMethod("listPrompts");
                @SuppressWarnings("unchecked")
                Iterable<Resource> prompts =
                        (Iterable<Resource>) invoke(listPromptsMethod, mcpServer);
                for (Resource prompt : prompts) {
                    String promptName = nameOf(prompt);
                    checkNameFree(cache, PROMPT, promptName, serverName, promptOwners);
                    cache.put(promptName, PROMPT, prompt);
                    promptOwners.put(promptName, serverName);
                }
            }
        } catch (IllegalArgumentException nameProblem) {
            throw nameProblem;
        } catch (Exception e) {
            throw new IllegalStateException(
                    String.format(
                            "Failed to discover tools and prompts from MCP server '%s' at"
                                    + " operator startup. Check that the server is reachable"
                                    + " and that its timeout and retry settings fit your"
                                    + " deployment.",
                            serverName),
                    e);
        }
    }

    static void checkNameFree(
            ResourceCache cache,
            ResourceType type,
            String name,
            String serverName,
            Map<String, String> owners) {
        String owner = owners.get(name);
        if (owner != null) {
            throw new IllegalArgumentException(
                    String.format(
                            "Duplicate %s name '%s': advertised by both MCP server '%s' and MCP"
                                    + " server '%s'.",
                            type.name().toLowerCase(), name, owner, serverName));
        }
        if (cache.hasResource(name, type)) {
            throw new IllegalArgumentException(
                    String.format(
                            "Duplicate %s name '%s': MCP server '%s' advertised a name that is"
                                    + " already declared on the agent.",
                            type.name().toLowerCase(), name, serverName));
        }
    }

    private static String nameOf(Resource resource) throws Exception {
        return (String) invoke(resource.getClass().getMethod("getName"), resource);
    }

    private static Object invoke(Method method, Object target) throws Exception {
        try {
            return method.invoke(target);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }

    /** Tags a discovered tool with the server it came from, when the tool supports it. */
    private static Resource attachMcpServerNameIfSupported(Resource tool, String mcpServerName)
            throws Exception {
        try {
            Method method = tool.getClass().getMethod("withMcpServerName", String.class);
            Object associatedTool = method.invoke(tool, mcpServerName);
            return associatedTool instanceof Resource ? (Resource) associatedTool : tool;
        } catch (NoSuchMethodException ignored) {
            return tool;
        }
    }
}
