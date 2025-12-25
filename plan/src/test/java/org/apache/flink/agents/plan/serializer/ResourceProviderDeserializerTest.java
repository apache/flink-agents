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
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.resourceprovider.PythonResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.PythonSerializableResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Test for {@link ResourceProviderJsonDeserializer}. */
public class ResourceProviderDeserializerTest {
    @Test
    public void testDeserializePythonResourceProvider() throws Exception {
        // Read JSON for an Action with JavaFunction from resource file
        String json =
                Utils.readJsonFromResource("resource_providers/python_resource_provider.json");

        // Deserialize the JSON to an Action
        ObjectMapper mapper = new ObjectMapper();
        ResourceProvider provider = mapper.readValue(json, ResourceProvider.class);
        assertInstanceOf(PythonResourceProvider.class, provider);

        PythonResourceProvider pythonResourceProvider = (PythonResourceProvider) provider;
        assertEquals("my_chat_model", pythonResourceProvider.getName());
        assertEquals(ResourceType.CHAT_MODEL, pythonResourceProvider.getType());

        ResourceDescriptor descriptor = pythonResourceProvider.getDescriptor();
        assertEquals("flink_agents.plan.tests.test_resource_provider", descriptor.getModule());
        assertEquals("MockChatModelImpl", descriptor.getClazz());

        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("host", "8.8.8.8");
        kwargs.put("desc", "mock chat model");

        assertEquals(kwargs, descriptor.getInitialArguments());
    }

    @Test
    public void testDeserializePythonSerializableResourceProvider() throws Exception {
        // Read JSON for an Action with JavaFunction from resource file
        String json =
                Utils.readJsonFromResource(
                        "resource_providers/python_serializable_resource_provider.json");

        // Deserialize the JSON to an Action
        ObjectMapper mapper = new ObjectMapper();
        ResourceProvider provider = mapper.readValue(json, ResourceProvider.class);

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

        assertInstanceOf(PythonSerializableResourceProvider.class, provider);
        PythonSerializableResourceProvider pythonSerializableResourceProvider =
                (PythonSerializableResourceProvider) provider;
        assertEquals("add", pythonSerializableResourceProvider.getName());
        assertEquals(ResourceType.TOOL, pythonSerializableResourceProvider.getType());
        assertEquals(
                "flink_agents.plan.tools.function_tool",
                pythonSerializableResourceProvider.getModule());
        assertEquals("FunctionTool", pythonSerializableResourceProvider.getClazz());
        assertEquals(serialized, pythonSerializableResourceProvider.getSerialized());
    }
}
