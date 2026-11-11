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
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.resourceprovider.JavaResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.JavaSerializableResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.apache.flink.agents.runtime.ResourceCacheTest.TestTool;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.flink.agents.api.resource.ResourceType.MCP_SERVER;
import static org.apache.flink.agents.api.resource.ResourceType.PROMPT;
import static org.apache.flink.agents.api.resource.ResourceType.TOOL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link JavaMCPResourceDiscovery}. */
public class JavaMCPResourceDiscoveryTest {

    static class FakeMCPTool extends Resource {
        private final String name;
        private final String mcpServerName;

        FakeMCPTool(String name) {
            this(name, null);
        }

        private FakeMCPTool(String name, String mcpServerName) {
            this.name = name;
            this.mcpServerName = mcpServerName;
        }

        public String getName() {
            return name;
        }

        public String getMcpServerName() {
            return mcpServerName;
        }

        public FakeMCPTool withMcpServerName(String mcpServerName) {
            return new FakeMCPTool(name, mcpServerName);
        }

        @Override
        public ResourceType getResourceType() {
            return TOOL;
        }
    }

    static class FakeMCPPrompt extends Resource {
        private final String name;

        FakeMCPPrompt(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        @Override
        public ResourceType getResourceType() {
            return PROMPT;
        }
    }

    static class FakeMCPServer extends Resource {
        private final List<FakeMCPTool> tools;
        private final List<FakeMCPPrompt> prompts;
        private final boolean supportsPrompts;
        private boolean closed;
        private RuntimeException listToolsFailure;

        FakeMCPServer(
                List<FakeMCPTool> tools, List<FakeMCPPrompt> prompts, boolean supportsPrompts) {
            this.tools = tools;
            this.prompts = prompts;
            this.supportsPrompts = supportsPrompts;
        }

        public List<FakeMCPTool> listTools() {
            if (listToolsFailure != null) {
                throw listToolsFailure;
            }
            return tools;
        }

        public List<FakeMCPPrompt> listPrompts() {
            return prompts;
        }

        public boolean supportsPrompts() {
            return supportsPrompts;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public ResourceType getResourceType() {
            return MCP_SERVER;
        }
    }

    /** JavaResourceProvider subclass that returns a pre-built server without reflection. */
    static class StubJavaResourceProvider extends JavaResourceProvider {
        private final Resource serverToReturn;

        StubJavaResourceProvider(String name, Resource serverToReturn) {
            super(name, MCP_SERVER, new ResourceDescriptor("", "FakeServer", new HashMap<>()));
            this.serverToReturn = serverToReturn;
        }

        @Override
        public Resource provide(ResourceContext resourceContext) {
            return serverToReturn;
        }
    }

    private static Map<ResourceType, Map<String, ResourceProvider>> buildProviders(
            String serverName, ResourceProvider provider) {
        Map<String, ResourceProvider> servers = new HashMap<>();
        servers.put(serverName, provider);
        Map<ResourceType, Map<String, ResourceProvider>> resourceProviders = new HashMap<>();
        resourceProviders.put(MCP_SERVER, servers);
        return resourceProviders;
    }

    private static ResourceCache emptyCache() {
        return new ResourceCache(new HashMap<>());
    }

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    @Test
    void testDiscoverToolsAndPromptsFromJavaMCPServer() throws Exception {
        FakeMCPServer server =
                new FakeMCPServer(
                        List.of(new FakeMCPTool("add"), new FakeMCPTool("subtract")),
                        List.of(new FakeMCPPrompt("ask_sum")),
                        true);

        ResourceCache cache = emptyCache();
        JavaMCPResourceDiscovery.discoverJavaMCPResources(
                buildProviders("myServer", new StubJavaResourceProvider("myServer", server)),
                cache);

        assertThat(cache.getResource("add", TOOL)).isInstanceOf(FakeMCPTool.class);
        assertThat(cache.getResource("subtract", TOOL)).isInstanceOf(FakeMCPTool.class);
        assertThat(cache.getResource("ask_sum", PROMPT)).isInstanceOf(FakeMCPPrompt.class);
    }

    @Test
    void testSkipsPromptDiscoveryWhenNotSupported() throws Exception {
        FakeMCPServer server =
                new FakeMCPServer(
                        List.of(new FakeMCPTool("add")),
                        List.of(new FakeMCPPrompt("should_not_appear")),
                        false /* supportsPrompts = false */);

        ResourceCache cache = emptyCache();
        JavaMCPResourceDiscovery.discoverJavaMCPResources(
                buildProviders("myServer", new StubJavaResourceProvider("myServer", server)),
                cache);

        assertThat(cache.getResource("add", TOOL)).isInstanceOf(FakeMCPTool.class);

        // Prompt must not be in the cache
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> cache.getResource("should_not_appear", PROMPT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testSkipsNonJavaResourceProviders() throws Exception {
        // Use a JavaSerializableResourceProvider (not JavaResourceProvider) — must be ignored
        TestTool dummyTool = new TestTool("dummyTool");
        ResourceProvider nonJavaProvider =
                JavaSerializableResourceProvider.createResourceProvider("nonJava", TOOL, dummyTool);

        Map<String, ResourceProvider> servers = new HashMap<>();
        servers.put("nonJava", nonJavaProvider);
        Map<ResourceType, Map<String, ResourceProvider>> resourceProviders = new HashMap<>();
        resourceProviders.put(MCP_SERVER, servers);

        ResourceCache cache = emptyCache();
        // Should complete without errors and without putting anything in the cache
        JavaMCPResourceDiscovery.discoverJavaMCPResources(resourceProviders, cache);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cache.getResource("nonJava", TOOL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testHandlesNoMCPServersRegistered() throws Exception {
        // No MCP_SERVER entry at all
        Map<ResourceType, Map<String, ResourceProvider>> resourceProviders = new HashMap<>();

        ResourceCache cache = emptyCache();
        // Must complete without throwing
        JavaMCPResourceDiscovery.discoverJavaMCPResources(resourceProviders, cache);
    }

    @Test
    void testHandlesEmptyToolAndPromptLists() throws Exception {
        FakeMCPServer server =
                new FakeMCPServer(List.of(), List.of(), true /* supportsPrompts but no prompts */);

        ResourceCache cache = emptyCache();
        JavaMCPResourceDiscovery.discoverJavaMCPResources(
                buildProviders("myServer", new StubJavaResourceProvider("myServer", server)),
                cache);

        // Nothing should be in the cache; no exception should be thrown
    }

    @Test
    void testMixedJavaAndNonJavaProviders() throws Exception {
        FakeMCPServer javaServer =
                new FakeMCPServer(List.of(new FakeMCPTool("javaToolA")), List.of(), false);

        TestTool dummyTool = new TestTool("dummyTool");
        ResourceProvider nonJavaProvider =
                JavaSerializableResourceProvider.createResourceProvider("nonJava", TOOL, dummyTool);

        Map<String, ResourceProvider> servers = new HashMap<>();
        servers.put("javaServer", new StubJavaResourceProvider("javaServer", javaServer));
        servers.put("nonJavaServer", nonJavaProvider);

        Map<ResourceType, Map<String, ResourceProvider>> resourceProviders = new HashMap<>();
        resourceProviders.put(MCP_SERVER, servers);

        ResourceCache cache = emptyCache();
        JavaMCPResourceDiscovery.discoverJavaMCPResources(resourceProviders, cache);

        // Only the Java server's tools should be discoverable
        assertThat(cache.getResource("javaToolA", TOOL)).isInstanceOf(FakeMCPTool.class);
    }

    @Test
    void testServerIsCachedAndClosedWithCacheEvenWhenNothingIsDiscovered() throws Exception {
        FakeMCPServer server = new FakeMCPServer(List.of(), List.of(), false);

        ResourceCache cache = emptyCache();
        JavaMCPResourceDiscovery.discoverJavaMCPResources(
                buildProviders("myServer", new StubJavaResourceProvider("myServer", server)),
                cache);

        assertThat(cache.getResource("myServer", MCP_SERVER)).isSameAs(server);
        assertThat(server.closed).isFalse();
        cache.close();
        assertThat(server.closed).isTrue();
    }

    @Test
    void testDiscoveredToolsRememberTheirServer() throws Exception {
        FakeMCPServer server = new FakeMCPServer(List.of(new FakeMCPTool("add")), List.of(), false);

        ResourceCache cache = emptyCache();
        JavaMCPResourceDiscovery.discoverJavaMCPResources(
                buildProviders("myServer", new StubJavaResourceProvider("myServer", server)),
                cache);

        assertThat(((FakeMCPTool) cache.getResource("add", TOOL)).getMcpServerName())
                .isEqualTo("myServer");
    }

    @Test
    void testUnreachableServerFailsDiscoveryNamingTheServer() throws Exception {
        FakeMCPServer server = new FakeMCPServer(List.of(), List.of(), true);
        server.listToolsFailure = new IllegalStateException("connect timed out");

        ResourceCache cache = emptyCache();
        assertThatThrownBy(
                        () ->
                                JavaMCPResourceDiscovery.discoverJavaMCPResources(
                                        buildProviders(
                                                "myServer",
                                                new StubJavaResourceProvider("myServer", server)),
                                        cache))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MCP server 'myServer'")
                .hasRootCauseMessage("connect timed out");

        // The half-initialized server is still owned by the cache, so it gets closed.
        cache.close();
        assertThat(server.closed).isTrue();
    }

    @Test
    void testDuplicateToolNameAcrossServersIsRejected() throws Exception {
        FakeMCPServer first = new FakeMCPServer(List.of(new FakeMCPTool("add")), List.of(), false);
        FakeMCPServer second = new FakeMCPServer(List.of(new FakeMCPTool("add")), List.of(), false);
        Map<String, ResourceProvider> servers = new HashMap<>();
        servers.put("serverA", new StubJavaResourceProvider("serverA", first));
        servers.put("serverB", new StubJavaResourceProvider("serverB", second));
        Map<ResourceType, Map<String, ResourceProvider>> providers = new HashMap<>();
        providers.put(MCP_SERVER, servers);

        assertThatThrownBy(
                        () ->
                                JavaMCPResourceDiscovery.discoverJavaMCPResources(
                                        providers, emptyCache()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate tool name 'add'")
                .hasMessageContaining("serverA")
                .hasMessageContaining("serverB");
    }

    @Test
    void testDuplicatePromptNameAcrossServersIsRejected() throws Exception {
        FakeMCPServer first =
                new FakeMCPServer(List.of(), List.of(new FakeMCPPrompt("ask_sum")), true);
        FakeMCPServer second =
                new FakeMCPServer(List.of(), List.of(new FakeMCPPrompt("ask_sum")), true);
        Map<String, ResourceProvider> servers = new HashMap<>();
        servers.put("serverA", new StubJavaResourceProvider("serverA", first));
        servers.put("serverB", new StubJavaResourceProvider("serverB", second));
        Map<ResourceType, Map<String, ResourceProvider>> providers = new HashMap<>();
        providers.put(MCP_SERVER, servers);

        assertThatThrownBy(
                        () ->
                                JavaMCPResourceDiscovery.discoverJavaMCPResources(
                                        providers, emptyCache()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate prompt name 'ask_sum'");
    }

    @Test
    void testToolNameClashingWithDeclaredToolIsRejected() throws Exception {
        Map<ResourceType, Map<String, ResourceProvider>> providers =
                buildProviders(
                        "myServer",
                        new StubJavaResourceProvider(
                                "myServer",
                                new FakeMCPServer(
                                        List.of(new FakeMCPTool("add")), List.of(), false)));
        providers.put(
                TOOL,
                Map.of(
                        "add",
                        JavaSerializableResourceProvider.createResourceProvider(
                                "add", TOOL, new TestTool("add"))));

        assertThatThrownBy(
                        () ->
                                JavaMCPResourceDiscovery.discoverJavaMCPResources(
                                        providers, new ResourceCache(providers)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate tool name 'add'")
                .hasMessageContaining("already declared on the agent");
    }

    @Test
    void testReservedSubagentToolNameIsRejected() throws Exception {
        FakeMCPServer server =
                new FakeMCPServer(List.of(new FakeMCPTool("_subagent_helper")), List.of(), false);

        assertThatThrownBy(
                        () ->
                                JavaMCPResourceDiscovery.discoverJavaMCPResources(
                                        buildProviders(
                                                "myServer",
                                                new StubJavaResourceProvider("myServer", server)),
                                        emptyCache()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MCP server 'myServer' advertised a tool")
                .hasMessageContaining("reserved name")
                .hasMessageContaining("_subagent_");
    }
}
