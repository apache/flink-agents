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
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;

import java.lang.reflect.Constructor;
import java.util.function.BiFunction;

/** Java Resource provider that carries resource instance to be used at runtime. */
public class JavaResourceProvider extends ResourceProvider {
    private final ResourceDescriptor descriptor;

    public JavaResourceProvider(String name, ResourceType type, ResourceDescriptor descriptor) {
        super(name, type);
        this.descriptor = descriptor;
    }

    @Override
    public Resource provide(BiFunction<String, ResourceType, Resource> getResource)
            throws Exception {
        Class<?> clazz = Class.forName(descriptor.getClazz());
        Constructor<?> constructor =
                clazz.getConstructor(BiFunction.class, ResourceDescriptor.class);
        return (Resource) constructor.newInstance(getResource, descriptor);
    }

    public ResourceDescriptor getDescriptor() {
        return descriptor;
    }
}
