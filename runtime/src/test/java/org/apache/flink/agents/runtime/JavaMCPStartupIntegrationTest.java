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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.resourceprovider.JavaResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.apache.flink.agents.runtime.subagent.InternalSubagentSetup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the real MCP client over loopback HTTP, without an external service. */
@DisabledOnJre(JRE.JAVA_11)
@Timeout(20)
class JavaMCPStartupIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void emptyDiscoveryClosesTheRealClientSession() throws Exception {
        try (EmptyServer server = new EmptyServer(false)) {
            Map<ResourceType, Map<String, ResourceProvider>> providers = providers(server.url());
            try (ResourceCache cache = new ResourceCache(providers)) {
                JavaMCPResourceDiscovery.discoverJavaMCPResources(providers, cache);
                assertThat(server.listCalls.get()).isEqualTo(1);
                assertThat(server.deletes.get()).isZero();
                assertThat(cache.materializedResources(ResourceType.TOOL)).isEmpty();
                assertThat(cache.materializedResources(ResourceType.PROMPT)).isEmpty();
            }
            assertThat(server.deletes.get()).isEqualTo(1);
        }
    }

    @Test
    void startupTimesOutWhenTheServerAcceptsButNeverAnswersInitialize() throws Exception {
        try (EmptyServer server = new EmptyServer(true);
                ResourceCache cache = new ResourceCache(providers(server.url()))) {
            long start = System.nanoTime();
            assertThatThrownBy(
                            () ->
                                    JavaMCPResourceDiscovery.discoverJavaMCPResources(
                                            providers(server.url()), cache))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MCP server 'remote'");
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                    .isLessThan(Duration.ofSeconds(5));
            assertThat(server.initializes.get()).isEqualTo(1);
        }
    }

    @Test
    void childScopeDiscoversOnceAndReleasesItsEmptyServer() throws Exception {
        try (EmptyServer server = new EmptyServer(false);
                ResourceCache parent = new ResourceCache(Map.of())) {
            AgentPlan base = new AgentPlan(new Agent());
            AgentPlan child =
                    new AgentPlan(base.getActions(), providers(server.url()), base.getConfig());
            ResourceDescriptor descriptor =
                    ResourceDescriptor.Builder.newBuilder(
                                    org.apache.flink.agents.api.subagent
                                            .InternalSubagentResourceDescriptorBuilder.SETUP_CLASS)
                            .addInitialArgument("scope", "child")
                            .addInitialArgument("plan", MAPPER.convertValue(child, Map.class))
                            .build();
            InternalSubagentSetup setup = new InternalSubagentSetup(descriptor, null);
            try {
                ResourceCache first =
                        setup.getOrCreateChildCache(getClass().getClassLoader(), parent);
                assertThat(setup.getOrCreateChildCache(getClass().getClassLoader(), parent))
                        .isSameAs(first);
                assertThat(server.listCalls.get()).isEqualTo(1);
            } finally {
                setup.close();
            }
            assertThat(server.deletes.get()).isEqualTo(1);
        }
    }

    private static Map<ResourceType, Map<String, ResourceProvider>> providers(String endpoint) {
        ResourceDescriptor descriptor =
                new ResourceDescriptor(
                        "org.apache.flink.agents.integrations.mcp.MCPServer",
                        Map.of("endpoint", endpoint, "timeout", 1, "maxRetries", 0));
        return Map.of(
                ResourceType.MCP_SERVER,
                Map.of(
                        "remote",
                        new JavaResourceProvider("remote", ResourceType.MCP_SERVER, descriptor)));
    }

    private static class EmptyServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger listCalls = new AtomicInteger();
        private final AtomicInteger deletes = new AtomicInteger();
        private final AtomicInteger initializes = new AtomicInteger();

        EmptyServer(boolean stall) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/mcp",
                    exchange -> {
                        try {
                            String method = exchange.getRequestMethod();
                            if (method.equals("DELETE")) {
                                deletes.incrementAndGet();
                                exchange.sendResponseHeaders(204, -1);
                                return;
                            }
                            if (!method.equals("POST")) {
                                exchange.sendResponseHeaders(405, -1);
                                return;
                            }
                            JsonNode request = MAPPER.readTree(exchange.getRequestBody());
                            String rpc = request.path("method").asText();
                            if (rpc.equals("initialize")) initializes.incrementAndGet();
                            if (stall || !request.has("id")) {
                                exchange.sendResponseHeaders(202, -1);
                                return;
                            }
                            Object result;
                            if (rpc.equals("initialize")) {
                                result =
                                        Map.of(
                                                "protocolVersion",
                                                "2025-03-26",
                                                "capabilities",
                                                Map.of("tools", Map.of()),
                                                "serverInfo",
                                                Map.of("name", "empty", "version", "1"));
                            } else {
                                listCalls.incrementAndGet();
                                result = Map.of("tools", java.util.List.of());
                            }
                            byte[] body =
                                    MAPPER.writeValueAsBytes(
                                            Map.of(
                                                    "jsonrpc",
                                                    "2.0",
                                                    "id",
                                                    request.get("id"),
                                                    "result",
                                                    result));
                            exchange.getResponseHeaders().add("Content-Type", "application/json");
                            exchange.getResponseHeaders().add("Mcp-Session-Id", "empty-session");
                            exchange.sendResponseHeaders(200, body.length);
                            exchange.getResponseBody().write(body);
                        } finally {
                            exchange.close();
                        }
                    });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
