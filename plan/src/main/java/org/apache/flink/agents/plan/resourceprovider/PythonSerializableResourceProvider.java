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

import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.SerializableResource;
import org.apache.flink.agents.plan.resource.python.PythonPrompt;
import org.apache.flink.agents.plan.resource.python.PythonTool;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Resource Provider that carries Resource object or serialized object.
 *
 * <p>This provider can either carry a resource object directly or the serialized form of the
 * resource for later deserialization.
 */
public class PythonSerializableResourceProvider extends SerializableResourceProvider {
    private final Map<String, Object> serialized;
    private SerializableResource resource;

    public PythonSerializableResourceProvider(
            String name,
            ResourceType type,
            String module,
            String clazz,
            Map<String, Object> serialized) {
        super(name, type, module, clazz);
        this.serialized = serialized;
    }

    public PythonSerializableResourceProvider(
            String name,
            ResourceType type,
            String module,
            String clazz,
            Map<String, Object> serialized,
            SerializableResource resource) {
        this(name, type, module, clazz, serialized);
        this.resource = resource;
    }

    public Map<String, Object> getSerialized() {
        return serialized;
    }

    public SerializableResource getResource() {
        return resource;
    }

    @Override
    public Resource provide(BiFunction<String, ResourceType, Resource> getResource)
            throws Exception {
        if (resource == null) {
            if (this.getType() == ResourceType.PROMPT) {
                resource = PythonPrompt.fromSerializedMap(serialized);
            } else if (this.getType() == ResourceType.TOOL) {
                resource = PythonTool.fromSerializedMap(serialized);
            } else {
                throw new UnsupportedOperationException(
                        "Unsupported resource type: " + this.getType());
            }
        }
        return resource;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        PythonSerializableResourceProvider that = (PythonSerializableResourceProvider) o;
        return Objects.equals(this.getName(), that.getName())
                && Objects.equals(this.getType(), that.getType())
                && Objects.equals(this.getModule(), that.getModule())
                && Objects.equals(this.getClazz(), that.getClazz())
                && Objects.equals(this.serialized, that.serialized);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                this.getName(), this.getType(), this.getModule(), this.getClazz(), serialized);
    }
}
