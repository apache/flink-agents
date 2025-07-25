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

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.function.BiFunction;

/** Java Resource provider that carries resource instance to be used at runtime. */
public class JavaResourceProvider extends ResourceProvider {
    private final String className;
    private final List<Object> parameters;
    private final List<String> parameterTypes;

    public JavaResourceProvider(
            String name,
            ResourceType type,
            String className,
            List<Object> parameters,
            List<String> parameterTypes) {
        super(name, type);
        this.className = className;
        this.parameters = parameters;
        this.parameterTypes = parameterTypes;
    }

    @Override
    public Resource provide(BiFunction<String, ResourceType, Resource> getResource)
            throws Exception {
        Class<?> clazz = Class.forName(className);
        Class<?>[] types = new Class[parameters.size() + 1];
        Object[] mergeParameters = new Object[parameters.size() + 1];
        mergeParameters[0] = getResource;
        types[0] = BiFunction.class;
        for (int i = 1; i < mergeParameters.length; i++) {
            types[i] = Class.forName(parameterTypes.get(i - 1));
            mergeParameters[i] = parameters.get(i - 1);
        }
        Constructor<?> constructor = clazz.getConstructor(types);
        return (Resource) constructor.newInstance(mergeParameters);
    }

    public List<Object> getParameters() {
        return parameters;
    }

    public List<String> getParameterTypes() {
        return parameterTypes;
    }

    public String getClassName() {
        return className;
    }
}
