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

package org.apache.flink.agents.plan.resourceprovider;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.SerializableResource;

import java.util.function.BiFunction;

/**
 * Serializable Resource Provider for Java-based resources.
 *
 * <p>This provider is responsible for creating serializable Java resources during runtime
 * execution.
 */
public class JavaSerializableResourceProvider extends SerializableResourceProvider {
    @JsonIgnore private SerializableResource resource;

    private final String serializedResource;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public JavaSerializableResourceProvider(
            String name,
            ResourceType type,
            String module,
            String clazz,
            String serializedResource) {
        super(name, type, module, clazz);
        this.serializedResource = serializedResource;
    }

    public JavaSerializableResourceProvider(
            String name,
            ResourceType type,
            String module,
            String clazz,
            SerializableResource resource,
            String serializedResource) {
        super(name, type, module, clazz);
        this.resource = resource;
        this.serializedResource = serializedResource;
    }

    public static JavaSerializableResourceProvider createResourceProvider(
            String name, ResourceType type, SerializableResource resource)
            throws JsonProcessingException {
        return new JavaSerializableResourceProvider(
                name,
                type,
                resource.getClass().getPackageName(),
                resource.getClass().getName(),
                resource,
                objectMapper.writeValueAsString(resource));
    }

    public String getSerializedResource() {
        return serializedResource;
    }

    @Override
    public Resource provide(BiFunction<String, ResourceType, Resource> getResource)
            throws Exception {
        if (resource == null) {
            resource =
                    (SerializableResource)
                            objectMapper.readValue(serializedResource, Class.forName(getClazz()));
        }
        return resource;
    }
}
