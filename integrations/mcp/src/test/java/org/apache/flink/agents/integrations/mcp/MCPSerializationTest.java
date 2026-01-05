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
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for MCP classes serialization.
 *
 * <p>This test ensures that MCP objects (MCPServer, MCPTool, MCPPrompt) can be properly serialized
 * and deserialized by Flink's serialization framework. It verifies that all fields are preserved
 */
class MCPSerializationTest {

    private static final String DEFAULT_ENDPOINT = "http://localhost:8000/mcp";

    /**
     * Create an ObjectMapper configured to ignore unknown properties during deserialization. This
     * is needed because base classes may have getters that are serialized.
     */
    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Test MCPServer JSON serialization and deserialization")
    void testMCPServerJsonSerialization() throws Exception {
        MCPServer original =
                MCPServer.builder(DEFAULT_ENDPOINT)
                        .timeout(Duration.ofSeconds(30))
                        .header("X-Custom", "value")
                        .build();

        ObjectMapper mapper = createObjectMapper();
        String json = mapper.writeValueAsString(original);
        MCPServer deserialized = mapper.readValue(json, MCPServer.class);

        assertThat(deserialized.getEndpoint()).isEqualTo(original.getEndpoint());
        assertThat(deserialized.getTimeoutSeconds()).isEqualTo(original.getTimeoutSeconds());
        assertThat(deserialized.getHeaders()).isEqualTo(original.getHeaders());
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Test MCPTool JSON serialization and deserialization")
    void testMCPToolJsonSerialization() throws Exception {
        MCPServer server = new MCPServer(DEFAULT_ENDPOINT);
        ToolMetadata metadata =
                new ToolMetadata("add", "Add numbers", "{\"type\":\"object\",\"properties\":{}}");
        MCPTool original = new MCPTool(metadata, server);

        ObjectMapper mapper = createObjectMapper();
        String json = mapper.writeValueAsString(original);
        MCPTool deserialized = mapper.readValue(json, MCPTool.class);

        assertThat(deserialized.getName()).isEqualTo(original.getName());
        assertThat(deserialized.getMetadata()).isEqualTo(original.getMetadata());
        assertThat(deserialized.getMcpServer()).isEqualTo(original.getMcpServer());
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Test MCPPrompt JSON serialization and deserialization")
    void testMCPPromptJsonSerialization() throws Exception {
        MCPServer server = new MCPServer(DEFAULT_ENDPOINT);
        Map<String, MCPPrompt.PromptArgument> args = new HashMap<>();
        args.put("name", new MCPPrompt.PromptArgument("name", "User name", true));

        MCPPrompt original = new MCPPrompt("greeting", "Greet user", args, server);

        ObjectMapper mapper = createObjectMapper();
        String json = mapper.writeValueAsString(original);
        MCPPrompt deserialized = mapper.readValue(json, MCPPrompt.class);

        assertThat(deserialized.getName()).isEqualTo(original.getName());
        assertThat(deserialized.getDescription()).isEqualTo(original.getDescription());
        assertThat(deserialized.getPromptArguments()).hasSameSizeAs(original.getPromptArguments());
        assertThat(deserialized.getMcpServer()).isEqualTo(original.getMcpServer());
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Test HashMap serialization in MCP objects")
    void testHashMapSerialization() throws Exception {
        // This specifically tests that the HashMap instances in MCP objects
        // don't cause Kryo serialization issues (like Arrays$ArrayList did)

        Map<String, String> headers = new HashMap<>();
        headers.put("Header1", "Value1");
        headers.put("Header2", "Value2");
        headers.put("Header3", "Value3");

        MCPServer server = MCPServer.builder(DEFAULT_ENDPOINT).headers(headers).build();

        Map<String, MCPPrompt.PromptArgument> args = new HashMap<>();
        args.put("arg1", new MCPPrompt.PromptArgument("arg1", "Argument 1", true));
        args.put("arg2", new MCPPrompt.PromptArgument("arg2", "Argument 2", false));
        args.put("arg3", new MCPPrompt.PromptArgument("arg3", "Argument 3", true));

        MCPPrompt prompt = new MCPPrompt("test", "Test prompt", args, server);

        // Serialize
        ObjectMapper mapper = createObjectMapper();
        String json = mapper.writeValueAsString(prompt);

        // Deserialize
        MCPPrompt deserialized = mapper.readValue(json, MCPPrompt.class);

        // Verify HashMaps are properly serialized
        assertThat(deserialized.getMcpServer().getHeaders()).hasSize(3);
        assertThat(deserialized.getPromptArguments()).hasSize(3);
        assertThat(deserialized.getMcpServer().getHeaders()).containsEntry("Header1", "Value1");
        assertThat(deserialized.getPromptArguments()).containsKey("arg1");
    }
}
