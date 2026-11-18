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
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.routing.ModelRouter;

import java.lang.reflect.Constructor;

/** Java Resource provider that carries resource instance to be used at runtime. */
public class JavaResourceProvider extends ResourceProvider {

    private final ResourceDescriptor descriptor;

    public JavaResourceProvider(String name, ResourceType type, ResourceDescriptor descriptor) {
        super(name, type);
        if (!"java".equals(descriptor.getLanguage())) {
            throw new IllegalArgumentException("JavaResourceProvider requires a Java descriptor.");
        }
        this.descriptor = descriptor;
    }

    @Override
    public Resource provide(ResourceContext resourceContext) throws Exception {
        Class<?> clazz = implementationOf(descriptor);
        Constructor<?> constructor =
                clazz.getConstructor(ResourceDescriptor.class, ResourceContext.class);
        return (Resource) constructor.newInstance(descriptor, resourceContext);
    }

    public ResourceDescriptor getDescriptor() {
        return descriptor;
    }

    /**
     * The class to instantiate for a descriptor. A model router is framework-owned: its descriptor
     * carries the api declaration name ({@link ResourceName.ModelRouter#MODEL_ROUTER}, shared with
     * the Python API, the YAML loader and the cross-language plan format), and every {@code
     * MODEL_ROUTER} descriptor instantiates the plan-side {@link ModelRouter}, whatever name it
     * carries. Every other resource names its own implementation class.
     */
    private Class<?> implementationOf(ResourceDescriptor descriptor) throws ClassNotFoundException {
        if (getType() == ResourceType.MODEL_ROUTER) {
            return ModelRouter.class;
        }
        return Class.forName(
                descriptor.getClazz(), true, Thread.currentThread().getContextClassLoader());
    }
}
