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

import org.apache.flink.agents.api.resource.ResourceType;

/**
 * Resource provider for serializable resources.
 *
 * <p>This class extends ResourceProvider to indicate that the resource provider is responsible for
 * providing serializable resources. This is important for distributed execution where resources
 * need to be serialized and deserialized across different processes.
 */
public abstract class SerializableResourceProvider extends ResourceProvider {

    private final String module;
    private final String clazz;

    protected SerializableResourceProvider(
            String name, ResourceType type, String module, String clazz) {
        super(name, type);
        this.module = module;
        this.clazz = clazz;
    }

    /**
     * Get the module name of the resource.
     *
     * @return the module name
     */
    public String getModule() {
        return module;
    }

    /**
     * Get the class name of the resource.
     *
     * @return the class name
     */
    public String getClazz() {
        return clazz;
    }
}
