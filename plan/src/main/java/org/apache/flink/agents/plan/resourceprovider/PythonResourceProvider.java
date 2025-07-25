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

import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Python Resource provider that carries resource metadata to create Resource objects at runtime.
 *
 * <p>This provider is used for creating Python-based resources by carrying the necessary module,
 * class, and initialization arguments.
 */
public class PythonResourceProvider extends ResourceProvider {
    private final String module;
    private final String clazz;
    private final Map<String, Object> kwargs;

    public PythonResourceProvider(
            String name,
            ResourceType type,
            String module,
            String clazz,
            Map<String, Object> kwargs) {
        super(name, type);
        this.module = module;
        this.clazz = clazz;
        this.kwargs = kwargs;
    }

    public String getModule() {
        return module;
    }

    public String getClazz() {
        return clazz;
    }

    public Map<String, Object> getKwargs() {
        return kwargs;
    }

    @Override
    public Resource provide(BiFunction<String, ResourceType, Resource> getResource)
            throws Exception {
        // TODO: Implement Python resource creation logic
        // This would typically involve calling into Python runtime to create the
        // resource
        throw new UnsupportedOperationException(
                "Python resource creation not yet implemented in Java runtime");
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
                && Objects.equals(this.module, that.module)
                && Objects.equals(this.clazz, that.clazz)
                && Objects.equals(this.kwargs, that.kwargs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.getName(), this.getType(), module, clazz, kwargs);
    }
}
