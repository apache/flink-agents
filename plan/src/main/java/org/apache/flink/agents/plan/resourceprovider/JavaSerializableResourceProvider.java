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

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.concurrent.Callable;

/**
 * Serializable Resource Provider for Java-based resources.
 *
 * <p>This provider is responsible for creating serializable Java resources during runtime
 * execution.
 */
public class JavaSerializableResourceProvider extends SerializableResourceProvider {

    public JavaSerializableResourceProvider(
            String name, ResourceType type, String module, String clazz) {
        super(name, type, module, clazz);
    }

    @Override
    public Resource provide(Callable<Resource> getResource) throws Exception {
        // Instantiate the resource reflectively to avoid recursion back into AgentPlan.getResource
        Class<?> resourceClass = Class.forName(getClazz());
        Constructor<?> ctor = resourceClass.getDeclaredConstructor();
        if (!Modifier.isPublic(resourceClass.getModifiers())
                || !Modifier.isPublic(ctor.getModifiers())) {
            ctor.setAccessible(true);
        }
        Object instance = ctor.newInstance();
        if (!(instance instanceof SerializableResource)) {
            throw new IllegalArgumentException(
                    "Expected a SerializableResource, but got: " + resourceClass.getName());
        }
        return (Resource) instance;
    }
}
