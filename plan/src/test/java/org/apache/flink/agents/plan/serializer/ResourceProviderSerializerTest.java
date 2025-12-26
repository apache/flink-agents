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

package org.apache.flink.agents.plan.serializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.resourceprovider.PythonResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.PythonSerializableResourceProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Test for {@link ResourceProviderJsonSerializer}. */
public class ResourceProviderSerializerTest {
    /**
     * Reads a JSON file from the resources' directory.
     *
     * @param resourcePath the path to the resource file
     * @return the content of the file as a string
     * @throws IOException if an I/O error occurs
     */
    private String readJsonFromResource(String resourcePath) throws IOException {
        try (InputStream inputStream =
                getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            byte[] bytes = inputStream.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    @Test
    public void testSerializePythonResourceProvider() throws IOException {
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("host", "8.8.8.8");
        kwargs.put("desc", "mock chat model");
        // Create a resource provider.
        ResourceDescriptor mockChatModelImpl =
                new ResourceDescriptor(
                        "flink_agents.plan.tests.test_resource_provider",
                        "MockChatModelImpl",
                        kwargs);
        PythonResourceProvider provider =
                new PythonResourceProvider(
                        "my_chat_model", ResourceType.CHAT_MODEL, mockChatModelImpl);

        // Serialize the resource provider to JSON
        String json =
                new ObjectMapper()
                        .enable(SerializationFeature.INDENT_OUTPUT)
                        .writeValueAsString(provider);
        // Deserialize the json to map.
        ObjectMapper mapper = new ObjectMapper();
        Map<?, ?> actual = mapper.readValue(json, Map.class);

        String expectedJson =
                readJsonFromResource("resource_providers/python_resource_provider.json");
        Map<?, ?> expect = mapper.readValue(expectedJson, Map.class);
        assertEquals(expect, actual);
    }

    @Test
    public void testSerializePythonSerializableResourceProvider() throws IOException {
        Map<String, Object> serialized = new HashMap<>();
        serialized.put("name", "add");

        // construct arguments schema
        Map<String, String> a = new HashMap<>();
        a.put("description", "The first operand");
        a.put("title", "A");
        a.put("type", "integer");

        Map<String, String> b = new HashMap<>();
        b.put("description", "The second operand");
        b.put("title", "B");
        b.put("type", "integer");

        Map<String, Object> properties = new HashMap<>();
        properties.put("a", a);
        properties.put("b", b);

        Map<String, Object> argsSchema = new HashMap<>();
        argsSchema.put("properties", properties);
        argsSchema.put("required", List.of("a", "b"));
        argsSchema.put("title", "add");
        argsSchema.put("type", "object");

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", "add");
        metadata.put("description", "Calculate the sum of a and b.\n");
        metadata.put("args_schema", argsSchema);

        serialized.put("metadata", metadata);

        Map<String, String> func = new HashMap<>();
        func.put("func_type", "PythonFunction");
        func.put("module", "flink_agents.runtime.tests.test_built_in_actions");
        func.put("qualname", "MyAgent.add");
        serialized.put("func", func);
        // Create a resource provider.
        PythonSerializableResourceProvider provider =
                new PythonSerializableResourceProvider(
                        "add",
                        ResourceType.TOOL,
                        "flink_agents.plan.tools.function_tool",
                        "FunctionTool",
                        serialized);

        // Serialize the resource provider to JSON
        String json =
                new ObjectMapper()
                        .enable(SerializationFeature.INDENT_OUTPUT)
                        .writeValueAsString(provider);
        // Deserialize the json to map.
        ObjectMapper mapper = new ObjectMapper();
        Map<?, ?> actual = mapper.readValue(json, Map.class);

        String expectedJson =
                readJsonFromResource(
                        "resource_providers/python_serializable_resource_provider.json");
        Map<?, ?> expect = mapper.readValue(expectedJson, Map.class);
        assertEquals(expect, actual);
    }
}
