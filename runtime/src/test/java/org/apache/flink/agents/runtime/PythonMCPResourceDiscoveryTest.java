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
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.plan.resource.python.PythonMCPPrompt;
import org.apache.flink.agents.plan.resource.python.PythonMCPServer;
import org.apache.flink.agents.plan.resource.python.PythonMCPTool;
import org.apache.flink.agents.plan.resourceprovider.PythonResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.apache.flink.agents.api.resource.ResourceType.MCP_SERVER;
import static org.apache.flink.agents.api.resource.ResourceType.PROMPT;
import static org.apache.flink.agents.api.resource.ResourceType.TOOL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PythonMCPResourceDiscoveryTest {

    @Test
    void cachesServerAndDiscoveredResourcesForNormalShutdown() throws Exception {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PythonResourceProvider serverProvider = mock(PythonResourceProvider.class);
        PythonMCPServer server = mock(PythonMCPServer.class);
        PythonMCPTool tool = mock(PythonMCPTool.class);
        PythonMCPPrompt prompt = mock(PythonMCPPrompt.class);
        Map<ResourceType, Map<String, ResourceProvider>> providers =
                Map.of(MCP_SERVER, Map.of("server", serverProvider));
        ResourceCache cache = new ResourceCache(providers);

        when(serverProvider.getName()).thenReturn("server");
        when(serverProvider.provide(any())).thenReturn(server);
        when(server.listTools("server")).thenReturn(List.of(tool));
        when(server.listPrompts()).thenReturn(List.of(prompt));
        when(tool.getName()).thenReturn("tool");
        when(prompt.getName()).thenReturn("prompt");

        PythonMCPResourceDiscovery.discoverPythonMCPResources(providers, adapter, cache);

        assertThat(cache.getResource("server", MCP_SERVER)).isSameAs(server);
        assertThat(cache.getResource("tool", TOOL)).isSameAs(tool);
        assertThat(cache.getResource("prompt", PROMPT)).isSameAs(prompt);

        cache.close();

        verify(server).close();
        verify(tool).close();
        verify(prompt).close();
    }
}
