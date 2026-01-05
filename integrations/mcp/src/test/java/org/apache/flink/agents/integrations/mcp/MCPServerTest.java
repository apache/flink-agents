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

package org.apache.flink.agents.integrations.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.integrations.mcp.auth.ApiKeyAuth;
import org.apache.flink.agents.integrations.mcp.auth.BasicAuth;
import org.apache.flink.agents.integrations.mcp.auth.BearerTokenAuth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link MCPServer}. */
class MCPServerTest {

    private static final String DEFAULT_ENDPOINT = "http://localhost:8000/mcp";

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Create MCPServer with builder")
    void testBuilderCreation() {
        MCPServer server =
                MCPServer.builder(DEFAULT_ENDPOINT)
                        .header("X-Custom-Header", "value")
                        .timeout(Duration.ofSeconds(30))
                        .auth(new BearerTokenAuth("test-token"))
                        .build();

        assertThat(server.getEndpoint()).isEqualTo("http://localhost:8000/mcp");
        assertThat(server.getHeaders()).containsEntry("X-Custom-Header", "value");
        assertThat(server.getTimeoutSeconds()).isEqualTo(30);
        assertThat(server.getAuth()).isInstanceOf(BearerTokenAuth.class);
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Create MCPServer with simple constructor")
    void testSimpleConstructor() {
        MCPServer server = new MCPServer(DEFAULT_ENDPOINT);

        assertThat(server.getEndpoint()).isEqualTo(DEFAULT_ENDPOINT);
        assertThat(server.getHeaders()).isEmpty();
        assertThat(server.getTimeoutSeconds()).isEqualTo(30);
        assertThat(server.getAuth()).isNull();
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Builder with multiple headers")
    void testBuilderWithMultipleHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer token");
        headers.put("X-API-Key", "key123");

        MCPServer server = MCPServer.builder(DEFAULT_ENDPOINT).headers(headers).build();

        assertThat(server.getHeaders()).hasSize(2);
        assertThat(server.getHeaders()).containsEntry("Authorization", "Bearer token");
        assertThat(server.getHeaders()).containsEntry("X-API-Key", "key123");
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Test different authentication types")
    void testAuthenticationTypes() {
        // Bearer token auth
        MCPServer bearerServer =
                MCPServer.builder(DEFAULT_ENDPOINT).auth(new BearerTokenAuth("my-token")).build();
        assertThat(bearerServer.getAuth()).isInstanceOf(BearerTokenAuth.class);

        // Basic auth
        MCPServer basicServer =
                MCPServer.builder(DEFAULT_ENDPOINT).auth(new BasicAuth("user", "pass")).build();
        assertThat(basicServer.getAuth()).isInstanceOf(BasicAuth.class);

        // API key auth
        MCPServer apiKeyServer =
                MCPServer.builder(DEFAULT_ENDPOINT)
                        .auth(new ApiKeyAuth("X-API-Key", "secret"))
                        .build();
        assertThat(apiKeyServer.getAuth()).isInstanceOf(ApiKeyAuth.class);
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Validate HTTP endpoint")
    void testEndpointValidation() {
        // Valid endpoints
        new MCPServer(DEFAULT_ENDPOINT);
        new MCPServer("https://api.example.com/mcp");

        // Null endpoint
        assertThatThrownBy(() -> new MCPServer(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("endpoint cannot be null");
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Test resource type")
    void testResourceType() {
        MCPServer server = new MCPServer(DEFAULT_ENDPOINT);
        assertThat(server.getResourceType()).isEqualTo(ResourceType.MCP_SERVER);
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Test equals and hashCode")
    void testEqualsAndHashCode() {
        MCPServer server1 =
                MCPServer.builder(DEFAULT_ENDPOINT).timeout(Duration.ofSeconds(30)).build();

        MCPServer server2 =
                MCPServer.builder(DEFAULT_ENDPOINT).timeout(Duration.ofSeconds(30)).build();

        MCPServer server3 =
                MCPServer.builder(DEFAULT_ENDPOINT).timeout(Duration.ofSeconds(60)).build();

        assertThat(server1).hasSameHashCodeAs(server2).isEqualTo(server2).isNotEqualTo(server3);
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Test toString")
    void testToString() {
        MCPServer server = new MCPServer(DEFAULT_ENDPOINT);
        assertThat(server.toString()).contains("MCPServer");
        assertThat(server.toString()).contains(DEFAULT_ENDPOINT);
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("JSON serialization and deserialization")
    void testJsonSerialization() throws Exception {
        MCPServer original =
                MCPServer.builder(DEFAULT_ENDPOINT)
                        .header("X-Custom", "value")
                        .timeout(Duration.ofSeconds(45))
                        .auth(new BearerTokenAuth("test-token"))
                        .build();

        ObjectMapper mapper = new ObjectMapper();
        // Configure to ignore unknown properties during deserialization
        mapper.configure(FAIL_ON_UNKNOWN_PROPERTIES, false);

        String json = mapper.writeValueAsString(original);

        MCPServer deserialized = mapper.readValue(json, MCPServer.class);

        assertThat(deserialized.getEndpoint()).isEqualTo(original.getEndpoint());
        assertThat(deserialized.getHeaders()).isEqualTo(original.getHeaders());
        assertThat(deserialized.getTimeoutSeconds()).isEqualTo(original.getTimeoutSeconds());
        assertThat(deserialized.getAuth()).isEqualTo(original.getAuth());
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("JSON serialization with different auth types")
    void testJsonSerializationWithAuth() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        // Bearer token auth
        MCPServer bearerServer =
                MCPServer.builder(DEFAULT_ENDPOINT).auth(new BearerTokenAuth("token")).build();
        String bearerJson = mapper.writeValueAsString(bearerServer);
        MCPServer bearerDeserialized = mapper.readValue(bearerJson, MCPServer.class);
        assertThat(bearerDeserialized.getAuth()).isInstanceOf(BearerTokenAuth.class);

        // Basic auth
        MCPServer basicServer =
                MCPServer.builder(DEFAULT_ENDPOINT).auth(new BasicAuth("user", "pass")).build();
        String basicJson = mapper.writeValueAsString(basicServer);
        MCPServer basicDeserialized = mapper.readValue(basicJson, MCPServer.class);
        assertThat(basicDeserialized.getAuth()).isInstanceOf(BasicAuth.class);

        // API key auth
        MCPServer apiKeyServer =
                MCPServer.builder(DEFAULT_ENDPOINT)
                        .auth(new ApiKeyAuth("X-API-Key", "secret"))
                        .build();
        String apiKeyJson = mapper.writeValueAsString(apiKeyServer);
        MCPServer apiKeyDeserialized = mapper.readValue(apiKeyJson, MCPServer.class);
        assertThat(apiKeyDeserialized.getAuth()).isInstanceOf(ApiKeyAuth.class);
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Headers are immutable from outside")
    void testHeadersImmutability() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Test", "value");

        MCPServer server = MCPServer.builder(DEFAULT_ENDPOINT).headers(headers).build();

        // Modify original map
        headers.put("X-New", "new-value");

        // Server should not be affected
        assertThat(server.getHeaders()).hasSize(1);
        assertThat(server.getHeaders()).doesNotContainKey("X-New");

        // Modify returned map
        Map<String, String> returnedHeaders = server.getHeaders();
        returnedHeaders.put("X-Another", "another-value");

        // Server should not be affected
        assertThat(server.getHeaders()).hasSize(1);
        assertThat(server.getHeaders()).doesNotContainKey("X-Another");
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Close server gracefully")
    void testClose() {
        MCPServer server = new MCPServer(DEFAULT_ENDPOINT);
        // Should not throw any exception
        server.close();
        server.close(); // Calling twice should be safe
    }
}
