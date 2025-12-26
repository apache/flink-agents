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

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.serializer.ResourceProviderJsonDeserializer;
import org.apache.flink.agents.plan.serializer.ResourceProviderJsonSerializer;

import java.util.function.BiFunction;

/**
 * Resource provider that carries resource metadata to create Resource objects at runtime.
 *
 * <p>Resource providers are responsible for creating resources during agent execution. They carry
 * the necessary metadata and configuration to instantiate resources.
 */
@JsonSerialize(using = ResourceProviderJsonSerializer.class)
@JsonDeserialize(using = ResourceProviderJsonDeserializer.class)
public abstract class ResourceProvider implements java.io.Serializable {

    private final String name;
    private final ResourceType type;

    protected ResourceProvider(String name, ResourceType type) {
        this.name = name;
        this.type = type;
    }

    /**
     * Get the name of the resource.
     *
     * @return the resource name
     */
    public String getName() {
        return name;
    }

    /**
     * Get the type of the resource.
     *
     * @return the resource type
     */
    public ResourceType getType() {
        return type;
    }

    /**
     * Create resource at runtime.
     *
     * @param getResource helper function to get other resources declared in the same Agent
     * @return the created resource
     * @throws Exception if the resource cannot be created
     */
    public abstract Resource provide(BiFunction<String, ResourceType, Resource> getResource)
            throws Exception;
}
