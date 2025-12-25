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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.apache.flink.agents.plan.resourceprovider.JavaResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.JavaSerializableResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.PythonResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.PythonSerializableResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;

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
            serializeJavaResourceProvider(jsonGenerator, (JavaResourceProvider) resourceProvider);
        } else if (resourceProvider instanceof JavaSerializableResourceProvider) {
            serializeJavaSerializableResourceProvider(
                    jsonGenerator, (JavaSerializableResourceProvider) resourceProvider);
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
        gen.writeObjectField("descriptor", provider.getDescriptor());
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

    private void serializeJavaSerializableResourceProvider(
            JsonGenerator gen, JavaSerializableResourceProvider provider) throws IOException {
        gen.writeStringField("name", provider.getName());
        gen.writeStringField("type", provider.getType().getValue());
        gen.writeStringField("module", provider.getModule());
        gen.writeStringField("clazz", provider.getClazz());
        gen.writeStringField("serializedResource", provider.getSerializedResource());
        gen.writeStringField(
                "__resource_provider_type__",
                JavaSerializableResourceProvider.class.getSimpleName());
    }

    private void serializeJavaResourceProvider(JsonGenerator gen, JavaResourceProvider provider)
            throws IOException {
        gen.writeStringField("name", provider.getName());
        gen.writeStringField("type", provider.getType().getValue());
        gen.writeObjectField("descriptor", provider.getDescriptor());
        gen.writeStringField(
                "__resource_provider_type__", JavaResourceProvider.class.getSimpleName());
    }
}
