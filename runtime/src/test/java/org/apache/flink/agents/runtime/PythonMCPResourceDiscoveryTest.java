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
import org.mockito.InOrder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.apache.flink.agents.api.resource.ResourceType.MCP_SERVER;
import static org.apache.flink.agents.api.resource.ResourceType.PROMPT;
import static org.apache.flink.agents.api.resource.ResourceType.TOOL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PythonMCPResourceDiscoveryTest {

    @Test
    void replacesPlanSnapshotProvidersWithRuntimeDiscoveredResources() throws Exception {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PythonResourceProvider serverProvider = mock(PythonResourceProvider.class);
        ResourceProvider toolSnapshotProvider = mock(ResourceProvider.class);
        ResourceProvider promptSnapshotProvider = mock(ResourceProvider.class);
        PythonMCPServer server = mock(PythonMCPServer.class);
        PythonMCPTool tool = mock(PythonMCPTool.class);
        PythonMCPPrompt prompt = mock(PythonMCPPrompt.class);
        Map<ResourceType, Map<String, ResourceProvider>> providers =
                Map.of(
                        MCP_SERVER, Map.of("server", serverProvider),
                        TOOL, Map.of("tool", toolSnapshotProvider),
                        PROMPT, Map.of("prompt", promptSnapshotProvider));
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
    }

    @Test
    void closesAllDiscoveredResourcesWhenNameResolutionFails() throws Exception {
        RuntimeException failure = new RuntimeException("name resolution failed");
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PythonResourceProvider provider = mock(PythonResourceProvider.class);
        PythonMCPServer server = mock(PythonMCPServer.class);
        PythonMCPTool tool = mock(PythonMCPTool.class);
        PythonMCPPrompt prompt = mock(PythonMCPPrompt.class);
        Map<ResourceType, Map<String, ResourceProvider>> providers =
                Map.of(MCP_SERVER, Map.of("server", provider));
        ResourceCache cache = new ResourceCache(Map.of());

        when(provider.getName()).thenReturn("server");
        when(provider.provide(any())).thenReturn(server);
        when(server.listTools("server")).thenReturn(List.of(tool));
        when(server.listPrompts()).thenReturn(List.of(prompt));
        when(tool.getName()).thenReturn("tool");
        when(prompt.getName()).thenThrow(failure);

        assertThatThrownBy(
                        () ->
                                PythonMCPResourceDiscovery.discoverPythonMCPResources(
                                        providers, adapter, cache))
                .isSameAs(failure);

        InOrder closeOrder = inOrder(prompt, tool, server);
        closeOrder.verify(prompt).close();
        closeOrder.verify(tool).close();
        closeOrder.verify(server).close();
    }

    @Test
    void discoversAllServersBeforeRegisteringAndRollsBackDuplicateNames() throws Exception {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PythonResourceProvider firstProvider = mock(PythonResourceProvider.class);
        PythonResourceProvider secondProvider = mock(PythonResourceProvider.class);
        PythonMCPServer firstServer = mock(PythonMCPServer.class);
        PythonMCPServer secondServer = mock(PythonMCPServer.class);
        PythonMCPTool firstTool = mock(PythonMCPTool.class);
        PythonMCPTool secondTool = mock(PythonMCPTool.class);
        Map<String, ResourceProvider> serverProviders = new LinkedHashMap<>();
        serverProviders.put("first-server", firstProvider);
        serverProviders.put("second-server", secondProvider);
        Map<ResourceType, Map<String, ResourceProvider>> providers =
                Map.of(MCP_SERVER, serverProviders);
        ResourceCache cache = new ResourceCache(Map.of());

        when(firstProvider.getName()).thenReturn("first-server");
        when(secondProvider.getName()).thenReturn("second-server");
        when(firstProvider.provide(any())).thenReturn(firstServer);
        when(secondProvider.provide(any())).thenReturn(secondServer);
        when(firstServer.listTools("first-server")).thenReturn(List.of(firstTool));
        when(secondServer.listTools("second-server")).thenReturn(List.of(secondTool));
        when(firstServer.listPrompts()).thenReturn(List.of());
        when(secondServer.listPrompts()).thenReturn(List.of());
        when(firstTool.getName()).thenReturn("duplicate");
        when(secondTool.getName()).thenReturn("duplicate");

        assertThatThrownBy(
                        () ->
                                PythonMCPResourceDiscovery.discoverPythonMCPResources(
                                        providers, adapter, cache))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");

        assertThat(cache.hasResource("duplicate", TOOL)).isFalse();
        InOrder closeOrder = inOrder(secondTool, secondServer, firstTool, firstServer);
        closeOrder.verify(secondTool).close();
        closeOrder.verify(secondServer).close();
        closeOrder.verify(firstTool).close();
        closeOrder.verify(firstServer).close();
    }
}
