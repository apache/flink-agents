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

package org.apache.flink.agents.plan.resource.python;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Test class for {@link PythonTool}. */
public class PythonToolTest {

    @Test
    public void testFromSerializedMapSuccess() throws JsonProcessingException {
        // Create test data
        Map<String, Object> argsSchema = new HashMap<>();
        argsSchema.put("type", "function");
        argsSchema.put("properties", new HashMap<>());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", "test_tool");
        metadata.put("description", "A test tool for validation");
        metadata.put("args_schema", argsSchema);

        Map<String, Object> serialized = new HashMap<>();
        serialized.put("metadata", metadata);

        // Test the method
        PythonTool tool = PythonTool.fromSerializedMap(serialized);

        // Verify the result
        assertThat(tool).isNotNull();
        assertThat(tool.getMetadata().getName()).isEqualTo("test_tool");
        assertThat(tool.getMetadata().getDescription()).isEqualTo("A test tool for validation");
        assertThat(tool.getMetadata().getInputSchema()).contains("\"type\":\"function\"");
        assertThat(tool.getToolType()).isEqualTo(ToolType.REMOTE_FUNCTION);
    }

    @Test
    public void testFromSerializedMapWithComplexArgsSchema() throws JsonProcessingException {
        // Create complex args schema
        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> nameProperty = new HashMap<>();
        nameProperty.put("type", "string");
        nameProperty.put("description", "The name parameter");
        properties.put("name", nameProperty);

        Map<String, Object> ageProperty = new HashMap<>();
        ageProperty.put("type", "integer");
        ageProperty.put("description", "The age parameter");
        properties.put("age", ageProperty);

        Map<String, Object> argsSchema = new HashMap<>();
        argsSchema.put("type", "object");
        argsSchema.put("properties", properties);
        argsSchema.put("required", new String[] {"name"});

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", "complex_tool");
        metadata.put("description", "A tool with complex schema");
        metadata.put("args_schema", argsSchema);

        Map<String, Object> serialized = new HashMap<>();
        serialized.put("metadata", metadata);

        // Test the method
        PythonTool tool = PythonTool.fromSerializedMap(serialized);

        // Verify the result
        assertThat(tool).isNotNull();
        assertThat(tool.getMetadata().getName()).isEqualTo("complex_tool");
        assertThat(tool.getMetadata().getDescription()).isEqualTo("A tool with complex schema");
        String inputSchema = tool.getMetadata().getInputSchema();
        assertThat(inputSchema).contains("\"name\"");
        assertThat(inputSchema).contains("\"age\"");
        assertThat(inputSchema).contains("\"required\"");
    }

    @Test
    public void testFromSerializedMapWithNullMap() {
        assertThatThrownBy(() -> PythonTool.fromSerializedMap(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Serialized map cannot be null");
    }

    @Test
    public void testFromSerializedMapMissingMetadata() {
        Map<String, Object> serialized = new HashMap<>();
        // Missing metadata key

        assertThatThrownBy(() -> PythonTool.fromSerializedMap(serialized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Map must contain 'metadata' key");
    }

    @Test
    public void testFromSerializedMapWithInvalidMetadataType() {
        Map<String, Object> serialized = new HashMap<>();
        serialized.put("metadata", "invalid_type"); // Should be Map

        assertThatThrownBy(() -> PythonTool.fromSerializedMap(serialized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'metadata' must be a Map");
    }

    @Test
    public void testFromSerializedMapMissingName() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("description", "A test tool");
        metadata.put("args_schema", new HashMap<>());
        // Missing name

        Map<String, Object> serialized = new HashMap<>();
        serialized.put("metadata", metadata);

        assertThatThrownBy(() -> PythonTool.fromSerializedMap(serialized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Metadata must contain 'name' key");
    }

    @Test
    public void testFromSerializedMapMissingDescription() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", "test_tool");
        metadata.put("args_schema", new HashMap<>());
        // Missing description

        Map<String, Object> serialized = new HashMap<>();
        serialized.put("metadata", metadata);

        assertThatThrownBy(() -> PythonTool.fromSerializedMap(serialized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Metadata must contain 'description' key");
    }

    @Test
    public void testFromSerializedMapMissingArgsSchema() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", "test_tool");
        metadata.put("description", "A test tool");
        // Missing args_schema

        Map<String, Object> serialized = new HashMap<>();
        serialized.put("metadata", metadata);

        assertThatThrownBy(() -> PythonTool.fromSerializedMap(serialized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Metadata must contain 'args_schema' key");
    }

    @Test
    public void testFromSerializedMapWithNullName() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", null);
        metadata.put("description", "A test tool");
        metadata.put("args_schema", new HashMap<>());

        Map<String, Object> serialized = new HashMap<>();
        serialized.put("metadata", metadata);

        assertThatThrownBy(() -> PythonTool.fromSerializedMap(serialized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'name' cannot be null");
    }

    @Test
    public void testFromSerializedMapWithNullDescription() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", "test_tool");
        metadata.put("description", null);
        metadata.put("args_schema", new HashMap<>());

        Map<String, Object> serialized = new HashMap<>();
        serialized.put("metadata", metadata);

        assertThatThrownBy(() -> PythonTool.fromSerializedMap(serialized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'description' cannot be null");
    }

    @Test
    public void testGetToolType() throws JsonProcessingException {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", "test_tool");
        metadata.put("description", "A test tool");
        metadata.put("args_schema", new HashMap<>());

        Map<String, Object> serialized = new HashMap<>();
        serialized.put("metadata", metadata);

        PythonTool tool = PythonTool.fromSerializedMap(serialized);

        assertThat(tool.getToolType()).isEqualTo(ToolType.REMOTE_FUNCTION);
    }

    @Test
    public void testCallMethodThrowsUnsupportedOperationException() throws JsonProcessingException {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", "test_tool");
        metadata.put("description", "A test tool");
        metadata.put("args_schema", new HashMap<>());

        Map<String, Object> serialized = new HashMap<>();
        serialized.put("metadata", metadata);

        PythonTool tool = PythonTool.fromSerializedMap(serialized);

        assertThatThrownBy(() -> tool.call(new ToolParameters()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("PythonTool does not support call method.");
    }

    @Test
    public void testFromSerializedMapWithEmptyArgsSchema() throws JsonProcessingException {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", "empty_schema_tool");
        metadata.put("description", "Tool with empty schema");
        metadata.put("args_schema", new HashMap<>());

        Map<String, Object> serialized = new HashMap<>();
        serialized.put("metadata", metadata);

        PythonTool tool = PythonTool.fromSerializedMap(serialized);

        assertThat(tool).isNotNull();
        assertThat(tool.getMetadata().getName()).isEqualTo("empty_schema_tool");
        assertThat(tool.getMetadata().getDescription()).isEqualTo("Tool with empty schema");
        assertThat(tool.getMetadata().getInputSchema()).isEqualTo("{}");
    }
}
