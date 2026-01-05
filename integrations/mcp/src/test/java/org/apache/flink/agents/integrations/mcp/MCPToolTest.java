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
import org.apache.flink.agents.api.tools.ToolType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;
import static org.assertj.core.api.Assertions.assertThat;

/** Tests for {@link MCPTool}. */
class MCPToolTest {

    private static final String DEFAULT_ENDPOINT = "http://localhost:8000/mcp";

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Create MCPTool with metadata and server")
    void testCreation() {
        ToolMetadata metadata = new ToolMetadata("add", "Add two numbers", "{\"type\":\"object\"}");
        MCPServer server = new MCPServer(DEFAULT_ENDPOINT);

        MCPTool tool = new MCPTool(metadata, server);

        assertThat(tool.getName()).isEqualTo("add");
        assertThat(tool.getMetadata()).isEqualTo(metadata);
        assertThat(tool.getMcpServer()).isEqualTo(server);
        assertThat(tool.getToolType()).isEqualTo(ToolType.MCP);
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Test tool metadata access")
    void testToolMetadataAccess() {
        ToolMetadata metadata =
                new ToolMetadata(
                        "calculator",
                        "Calculate mathematical expressions",
                        "{\"type\":\"object\",\"properties\":{\"expression\":{\"type\":\"string\"}}}");
        MCPServer server = new MCPServer(DEFAULT_ENDPOINT);

        MCPTool tool = new MCPTool(metadata, server);

        assertThat(tool.getMetadata()).isEqualTo(metadata);
        assertThat(tool.getMetadata().getName()).isEqualTo("calculator");
        assertThat(tool.getMetadata().getDescription())
                .isEqualTo("Calculate mathematical expressions");
        assertThat(tool.getMetadata().getInputSchema()).contains("expression");
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Test tool name and description getters")
    void testToolGetters() {
        ToolMetadata metadata =
                new ToolMetadata("multiply", "Multiply two numbers", "{\"type\":\"object\"}");
        MCPServer server = new MCPServer(DEFAULT_ENDPOINT);

        MCPTool tool = new MCPTool(metadata, server);

        assertThat(tool.getName()).isEqualTo("multiply");
        assertThat(tool.getDescription()).isEqualTo("Multiply two numbers");
        assertThat(tool.getMcpServer()).isEqualTo(server);
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Test equals and hashCode")
    void testEqualsAndHashCode() {
        ToolMetadata metadata1 = new ToolMetadata("tool1", "Description", "{\"type\":\"object\"}");
        MCPServer server1 = new MCPServer(DEFAULT_ENDPOINT);

        MCPTool tool1 = new MCPTool(metadata1, server1);
        MCPTool tool2 = new MCPTool(metadata1, server1);

        ToolMetadata metadata2 = new ToolMetadata("tool2", "Description", "{\"type\":\"object\"}");
        MCPTool tool3 = new MCPTool(metadata2, server1);

        assertThat(tool1).isEqualTo(tool2).hasSameHashCodeAs(tool2).isNotEqualTo(tool3);
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Test toString")
    void testToString() {
        ToolMetadata metadata = new ToolMetadata("add", "Add numbers", "{\"type\":\"object\"}");
        MCPServer server = new MCPServer(DEFAULT_ENDPOINT);
        MCPTool tool = new MCPTool(metadata, server);

        String str = tool.toString();
        assertThat(str).contains("MCPTool").contains("add").contains(DEFAULT_ENDPOINT);
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("JSON serialization and deserialization")
    void testJsonSerialization() throws Exception {
        ToolMetadata metadata =
                new ToolMetadata("multiply", "Multiply two numbers", "{\"type\":\"object\"}");
        MCPServer server = new MCPServer(DEFAULT_ENDPOINT);
        MCPTool original = new MCPTool(metadata, server);

        ObjectMapper mapper = new ObjectMapper();
        // Configure to ignore unknown properties during deserialization
        mapper.configure(FAIL_ON_UNKNOWN_PROPERTIES, false);

        String json = mapper.writeValueAsString(original);

        MCPTool deserialized = mapper.readValue(json, MCPTool.class);

        assertThat(deserialized.getName()).isEqualTo(original.getName());
        assertThat(deserialized.getMetadata()).isEqualTo(original.getMetadata());
        assertThat(deserialized.getMcpServer()).isEqualTo(original.getMcpServer());
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Test tool with complex input schema")
    void testToolWithComplexSchema() {
        String complexSchema =
                "{\"type\":\"object\",\"properties\":{"
                        + "\"param1\":{\"type\":\"string\",\"description\":\"First parameter\"},"
                        + "\"param2\":{\"type\":\"number\",\"description\":\"Second parameter\"},"
                        + "\"param3\":{\"type\":\"boolean\",\"description\":\"Third parameter\"}"
                        + "},\"required\":[\"param1\",\"param2\"]}";

        ToolMetadata metadata =
                new ToolMetadata("complexTool", "Tool with complex schema", complexSchema);
        MCPServer server = new MCPServer(DEFAULT_ENDPOINT);

        MCPTool tool = new MCPTool(metadata, server);

        assertThat(tool.getMetadata().getInputSchema()).contains("param1");
        assertThat(tool.getMetadata().getInputSchema()).contains("param2");
        assertThat(tool.getMetadata().getInputSchema()).contains("param3");
        assertThat(tool.getMetadata().getInputSchema()).contains("required");
    }
}
