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
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import pemja.core.object.PyObject;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Objects;
import java.util.function.BiFunction;

import static org.apache.flink.util.Preconditions.checkState;

/**
 * Python Resource provider that carries resource metadata to create Resource objects at runtime.
 *
 * <p>This provider is used for creating Python-based resources by carrying the necessary module,
 * class, and initialization arguments.
 */
public class PythonResourceProvider extends ResourceProvider {
    private final ResourceDescriptor descriptor;

    protected PythonResourceAdapter pythonResourceAdapter;

    public PythonResourceProvider(String name, ResourceType type, ResourceDescriptor descriptor) {
        super(name, type);
        this.descriptor = descriptor;
    }

    public void setPythonResourceAdapter(PythonResourceAdapter pythonResourceAdapter) {
        this.pythonResourceAdapter = pythonResourceAdapter;
    }

    public ResourceDescriptor getDescriptor() {
        return descriptor;
    }

    @Override
    public Resource provide(BiFunction<String, ResourceType, Resource> getResource)
            throws Exception {
        checkState(pythonResourceAdapter != null, "PythonResourceAdapter is not set");
        Class<?> clazz = Class.forName(descriptor.getClazz());

        HashMap<String, Object> kwargs = new HashMap<>(descriptor.getInitialArguments());
        String pyModule = (String) kwargs.remove("module");
        if (pyModule == null || pyModule.isEmpty()) {
            throw new IllegalArgumentException("module should not be null or empty.");
        }
        String pyClazz = (String) kwargs.remove("clazz");
        if (pyClazz == null || pyClazz.isEmpty()) {
            throw new IllegalArgumentException("clazz should not be null or empty.");
        }
        PyObject pyResource = pythonResourceAdapter.initPythonResource(pyModule, pyClazz, kwargs);
        Constructor<?> constructor =
                clazz.getConstructor(
                        PythonResourceAdapter.class,
                        PyObject.class,
                        ResourceDescriptor.class,
                        BiFunction.class);
        return (Resource)
                constructor.newInstance(pythonResourceAdapter, pyResource, descriptor, getResource);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        PythonResourceProvider that = (PythonResourceProvider) o;
        return Objects.equals(this.getName(), that.getName())
                && Objects.equals(this.getType(), that.getType())
                && Objects.equals(this.getDescriptor(), that.getDescriptor());
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.getName(), this.getType(), this.getDescriptor());
    }
}
