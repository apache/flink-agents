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

import org.apache.flink.agents.api.tools.ToolType;
import org.apache.flink.agents.plan.resourceprovider.JavaResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.JavaSerializableResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.PythonResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.PythonSerializableResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ToolResourceProvider;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.JsonGenerator;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.SerializerProvider;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;

/**
 * Custom serializer for {@link ResourceProvider} that handles the serialization of different
 * implementation.
 */
public class ResourceProviderJsonSerializer extends StdSerializer<ResourceProvider> {
    public ResourceProviderJsonSerializer() {
        super(ResourceProvider.class);
    }

    @Override
    public void serialize(
            ResourceProvider resourceProvider,
            JsonGenerator jsonGenerator,
            SerializerProvider serializerProvider)
            throws IOException {
        jsonGenerator.writeStartObject();

        if (resourceProvider instanceof PythonResourceProvider) {
            serializePythonResourceProvider(
                    jsonGenerator, (PythonResourceProvider) resourceProvider);
        } else if (resourceProvider instanceof PythonSerializableResourceProvider) {
            serializePythonSerializableResourceProvider(
                    jsonGenerator, (PythonSerializableResourceProvider) resourceProvider);
        } else if (resourceProvider instanceof JavaResourceProvider) {
            jsonGenerator.writeStringField("name", resourceProvider.getName());
            jsonGenerator.writeStringField("type", resourceProvider.getType().getValue());
            jsonGenerator.writeStringField(
                    "__resource_provider_type__", JavaResourceProvider.class.getSimpleName());
        } else if (resourceProvider instanceof JavaSerializableResourceProvider) {
            jsonGenerator.writeStringField("name", resourceProvider.getName());
            jsonGenerator.writeStringField("type", resourceProvider.getType().getValue());
            jsonGenerator.writeStringField(
                    "module", ((JavaSerializableResourceProvider) resourceProvider).getModule());
            jsonGenerator.writeStringField(
                    "clazz", ((JavaSerializableResourceProvider) resourceProvider).getClazz());
            jsonGenerator.writeStringField(
                    "__resource_provider_type__",
                    JavaSerializableResourceProvider.class.getSimpleName());
        } else if (resourceProvider instanceof ToolResourceProvider) {
            serializeToolResourceProvider(jsonGenerator, (ToolResourceProvider) resourceProvider);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported resource provider type: " + resourceProvider.getClass().getName());
        }
        jsonGenerator.writeEndObject();
    }

    private void serializePythonResourceProvider(JsonGenerator gen, PythonResourceProvider provider)
            throws IOException {
        gen.writeStringField("name", provider.getName());
        gen.writeStringField("type", provider.getType().getValue());
        gen.writeStringField("module", provider.getModule());
        gen.writeStringField("clazz", provider.getClazz());

        gen.writeFieldName("kwargs");
        gen.writeStartObject();
        provider.getKwargs()
                .forEach(
                        (name, value) -> {
                            try {
                                gen.writeObjectField(name, value);
                            } catch (IOException e) {
                                throw new RuntimeException(
                                        "Error writing kwargs of PythonResourceProvider: " + name,
                                        e);
                            }
                        });
        gen.writeEndObject();

        gen.writeStringField("__resource_provider_type__", "PythonResourceProvider");
    }

    private void serializePythonSerializableResourceProvider(
            JsonGenerator gen, PythonSerializableResourceProvider provider) throws IOException {
        gen.writeStringField("name", provider.getName());
        gen.writeStringField("type", provider.getType().getValue());
        gen.writeStringField("module", provider.getModule());
        gen.writeStringField("clazz", provider.getClazz());

        gen.writeFieldName("serialized");
        gen.writeStartObject();
        provider.getSerialized()
                .forEach(
                        (name, value) -> {
                            try {
                                gen.writeObjectField(name, value);
                            } catch (IOException e) {
                                throw new RuntimeException(
                                        "Error writing SerializableResource of PythonSerializableResourceProvider: "
                                                + name,
                                        e);
                            }
                        });
        gen.writeEndObject();

        gen.writeStringField("__resource_provider_type__", "PythonSerializableResourceProvider");
    }

    private void serializeToolResourceProvider(JsonGenerator gen, ToolResourceProvider provider)
            throws IOException {
        gen.writeStringField("name", provider.getName());
        gen.writeStringField("type", provider.getType().getValue());
        gen.writeStringField("declaringClass", provider.getDeclaringClass());
        gen.writeStringField("methodName", provider.getMethodName());
        gen.writeArrayFieldStart("parameterTypeNames");
        for (String s : provider.getParameterTypeNames()) {
            gen.writeString(s);
        }
        gen.writeEndArray();
        // Explicitly record the tool type of the method-based tool for plan-level visibility
        gen.writeStringField("tool_type", ToolType.FUNCTION.getValue());
        gen.writeStringField(
                "__resource_provider_type__", ToolResourceProvider.class.getSimpleName());
    }
}
