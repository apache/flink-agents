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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.resourceprovider.JavaResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.JavaSerializableResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.PythonResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.PythonSerializableResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Custom deserializer for {@link ResourceProvider} that handles the deserialization of different
 * implementation.
 */
public class ResourceProviderJsonDeserializer extends StdDeserializer<ResourceProvider> {
    private static ObjectMapper mapper = new ObjectMapper();

    public ResourceProviderJsonDeserializer() {
        super(ResourceProvider.class);
    }

    @Override
    public ResourceProvider deserialize(
            JsonParser jsonParser, DeserializationContext deserializationContext)
            throws IOException {
        JsonNode node = jsonParser.getCodec().readTree(jsonParser);
        JsonNode marker = node.get("__resource_provider_type__");
        if (marker == null) {
            throw new IOException("Missing __resource_provider_type__ in ResourceProvider JSON");
        }
        String providerType = marker.asText();

        if (PythonResourceProvider.class.getSimpleName().equals(providerType)) {
            return deserializePythonResourceProvider(node);
        } else if (PythonSerializableResourceProvider.class.getSimpleName().equals(providerType)) {
            return deserializePythonSerializableResourceProvider(node);
        } else if (JavaResourceProvider.class.getSimpleName().equals(providerType)) {
            return deserializeJavaResourceProvider(node);
        } else if (JavaSerializableResourceProvider.class.getSimpleName().equals(providerType)) {
            return deserializeJavaSerializableResourceProvider(node);
        } else {
            throw new IOException("Unsupported resource provider type: " + providerType);
        }
    }

    private PythonResourceProvider deserializePythonResourceProvider(JsonNode node) {
        String name = node.get("name").asText();
        String type = node.get("type").asText();
        try {
            ResourceDescriptor descriptor =
                    mapper.treeToValue(node.get("descriptor"), ResourceDescriptor.class);
            return new PythonResourceProvider(name, ResourceType.fromValue(type), descriptor);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private PythonSerializableResourceProvider deserializePythonSerializableResourceProvider(
            JsonNode node) {
        String name = node.get("name").asText();
        String type = node.get("type").asText();
        String module = node.get("module").asText();
        String clazz = node.get("clazz").asText();

        JsonNode serializedNode = node.get("serialized");
        Map<String, Object> serialized = new HashMap<>();
        if (serializedNode != null && serializedNode.isObject()) {
            serialized = mapper.convertValue(serializedNode, Map.class);
        }
        return new PythonSerializableResourceProvider(
                name, ResourceType.fromValue(type), module, clazz, serialized);
    }

    private JavaResourceProvider deserializeJavaResourceProvider(JsonNode node) {
        String name = node.get("name").asText();
        String type = node.get("type").asText();
        try {
            ResourceDescriptor descriptor =
                    mapper.treeToValue(node.get("descriptor"), ResourceDescriptor.class);
            return new JavaResourceProvider(name, ResourceType.fromValue(type), descriptor);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private JavaSerializableResourceProvider deserializeJavaSerializableResourceProvider(
            JsonNode node) {
        String name = node.get("name").asText();
        String type = node.get("type").asText();
        String module = node.get("module").asText();
        String clazz = node.get("clazz").asText();
        String serializedResource = node.get("serializedResource").asText();
        return new JavaSerializableResourceProvider(
                name, ResourceType.fromValue(type), module, clazz, serializedResource);
    }
}
